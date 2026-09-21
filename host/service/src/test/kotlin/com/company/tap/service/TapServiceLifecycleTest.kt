package com.company.tap.service

import com.company.tap.api.v1.AttachRequest
import com.company.tap.api.v1.Command
import com.company.tap.api.v1.ExecuteRequest
import com.company.tap.api.v1.Health
import com.company.tap.api.v1.SessionServiceGrpcKt
import com.company.tap.host.Adb
import com.company.tap.host.AdbReapUncertainException
import com.company.tap.host.AppLifecycle
import com.company.tap.host.DEVICE_PORT
import com.company.tap.host.DRIVER_PACKAGE
import com.company.tap.host.DeviceSession
import com.company.tap.host.DeviceSessionConfig
import com.company.tap.host.DriverClient
import com.company.tap.host.FakeAdb
import com.company.tap.host.FakeDriverServer
import com.company.tap.host.FakeProcess
import com.company.tap.host.ProcessStarter
import com.company.tap.host.ok
import com.company.tap.protocol.Done
import com.company.tap.protocol.FrameType
import com.company.tap.protocol.Response
import com.company.tap.service.servicer.ConnectionServicer
import com.company.tap.service.servicer.SessionServicer
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun testConfig(log: (String) -> Unit = {}): ServiceConfig {
    val dir = Files.createTempDirectory("tap-service-test")
    return ServiceConfig(adb = object : Adb("fake-adb") {}, stateDir = dir, bundledDriver = null, log = log)
}

private fun testOptions() =
    TapService.OpenSessionOptions(
        driverApk = null,
        driverTestApk = null,
        skipDriverInstall = true,
        syncAuthority = null,
        allowedSystemPackages = emptySet(),
        defaultTimeoutMs = 5_000,
        leaseTimeoutMs = 0,
    )

private class FakeDevice(
    override val serial: String,
    override val generation: Long = 1,
    override val autPackage: String = "com.test",
    var poisoned: Throwable? = null,
    var closeGate: CompletableDeferred<Unit>? = null,
    var closeError: Throwable? = null,
    private val closeAction: (suspend () -> Unit)? = null,
    private val realClient: DriverClient? = null,
) : ServiceDevice {
    val closeCalls = AtomicInteger(0)
    val closeEntered = Channel<Unit>(Channel.UNLIMITED)
    val closeCompleted = CompletableDeferred<Unit>()
    val closeTimeouts = java.util.concurrent.CopyOnWriteArrayList<Long>()
    override val client: DriverClient
        get() = realClient ?: error("no client in lifecycle fake")

    override fun app(packageName: String): AppLifecycle = error("no app in lifecycle fake")

    override fun checkUsable() {
        poisoned?.let { throw IllegalStateException("FakeDevice quarantined: ${it.message}", it) }
    }

    override suspend fun close(timeoutMs: Long) {
        closeCalls.incrementAndGet()
        closeTimeouts.add(timeoutMs)
        closeEntered.trySend(Unit)
        try {
            withContext(NonCancellable) {
                if (closeAction != null) closeAction.invoke() else closeGate?.await()
            }
            closeError?.let { throw it }
        } finally {
            closeCompleted.complete(Unit)
        }
    }
}

/** Production-shaped [ServiceDevice]: a live `:host:core` session, so the client's session-owned
 * admission gate is bound exactly as production binds it. Tests poison through [poison]. */
private class RealSessionDevice(
    val delegate: DeviceSession,
) : ServiceDevice {
    override val serial: String get() = delegate.serial
    override val generation: Long get() = delegate.generation
    override val autPackage: String get() = delegate.autPackage
    override val client: DriverClient get() = delegate.client

    override fun app(packageName: String): AppLifecycle = delegate.app(packageName)

    override fun checkUsable() = delegate.checkUsable()

    fun poison(error: AdbReapUncertainException) = delegate.noteReapUncertain(error)

    override suspend fun close(timeoutMs: Long) = delegate.close(timeoutMs)
}

private class FakeOpener : DeviceOpener {
    val entered = Channel<Unit>(Channel.UNLIMITED)
    val openCalls = AtomicInteger(0)
    var openGate: CompletableDeferred<ServiceDevice>? = null
    val queue = ArrayDeque<ServiceDevice>()

    override suspend fun open(config: DeviceSessionConfig): ServiceDevice {
        openCalls.incrementAndGet()
        entered.trySend(Unit)
        openGate?.let { return it.await() }
        synchronized(queue) {
            if (queue.isNotEmpty()) return queue.removeFirst()
        }
        return FakeDevice(serial = config.serial, autPackage = config.autPackage)
    }
}

class TapServiceLifecycleTest {
    @Test
    fun `close between attach registration and heartbeat ends the stream with no leak`() =
        runBlocking {
            val opener = FakeOpener()
            val service = TapService(testConfig(), opener)
            val connection = service.openConnection("t1")
            val servicer = ConnectionServicer(service, heartbeatIntervalMs = 50)

            // Attach wins first: first event proves registration completed under the lock.
            val firstEvent = CompletableDeferred<Unit>()
            val events = mutableListOf<String>()
            val job =
                launch {
                    servicer.attach(AttachRequest.newBuilder().setConnectionId(connection.id).build()).collect { event ->
                        events.add(event.message)
                        if (!firstEvent.isCompleted) firstEvent.complete(Unit)
                    }
                }
            withTimeout(2_000) { firstEvent.await() }
            assertTrue(service.attachOwnerPresent(connection.id))
            assertEquals(1, service.onCloseCount(connection.id))

            // Explicit close must cancel the stream promptly (delay is cancellable), not after 15 s.
            service.closeConnection(connection.id, "test close")
            withTimeout(2_000) { job.join() }
            assertTrue(events.isNotEmpty())
            assertFalse(service.connectionExists(connection.id))
            assertEquals(0, service.onCloseCount(connection.id))
            assertFalse(service.attachOwnerPresent(connection.id))

            // Close wins first: attach after close is NOT_FOUND and registers nothing.
            val gone = CompletableDeferred<Unit>()
            val lateJob =
                launch {
                    try {
                        servicer.attach(AttachRequest.newBuilder().setConnectionId(connection.id).build()).collect {}
                    } catch (error: StatusRuntimeException) {
                        assertEquals(Status.Code.NOT_FOUND, error.status.code)
                        gone.complete(Unit)
                    }
                }
            withTimeout(2_000) { gone.await() }
            withTimeout(2_000) { lateJob.join() }
            assertFalse(service.attachOwnerPresent(connection.id))
        }

    @Test
    fun `duplicate attach is FAILED_PRECONDITION and keeps the valid stream`() =
        runBlocking {
            val opener = FakeOpener()
            val service = TapService(testConfig(), opener)
            val connection = service.openConnection("t2")
            val servicer = ConnectionServicer(service, heartbeatIntervalMs = 50)

            val firstEvent = CompletableDeferred<Unit>()
            val firstJob =
                launch {
                    servicer.attach(AttachRequest.newBuilder().setConnectionId(connection.id).build()).collect {
                        if (!firstEvent.isCompleted) firstEvent.complete(Unit)
                    }
                }
            withTimeout(2_000) { firstEvent.await() }

            val duplicateError =
                assertFailsWith<StatusRuntimeException> {
                    servicer.attach(AttachRequest.newBuilder().setConnectionId(connection.id).build()).collect {}
                }
            assertEquals(Status.Code.FAILED_PRECONDITION, duplicateError.status.code)

            // The valid stream survived the duplicate rejection: still registered, still collecting.
            assertTrue(firstJob.isActive)
            assertTrue(service.attachOwnerPresent(connection.id))
            assertEquals(1, service.onCloseCount(connection.id))

            service.closeConnection(connection.id, "test done")
            withTimeout(2_000) { firstJob.join() }
            assertFalse(service.connectionExists(connection.id))
        }

    @Test
    fun `detach closes its session exactly once`() =
        runBlocking {
            val opener = FakeOpener()
            val device = FakeDevice(serial = "detach")
            opener.queue.add(device)
            val service = TapService(testConfig(), opener)
            val connection = service.openConnection("detach-conn")
            service.openSession(connection, "detach", "com.test", testOptions())
            val servicer = ConnectionServicer(service, heartbeatIntervalMs = 50)
            val attached = CompletableDeferred<Unit>()
            val attachJob =
                launch {
                    servicer.attach(AttachRequest.newBuilder().setConnectionId(connection.id).build()).collect {
                        attached.complete(Unit)
                    }
                }
            withTimeout(2_000) { attached.await() }

            attachJob.cancel()
            withTimeout(2_000) { attachJob.join() }
            assertEquals(1, device.closeCalls.get())
            assertTrue(service.sessionIds().isEmpty())
            assertFalse(service.connectionExists(connection.id))
            assertEquals(0, service.closeConnection(connection.id, "already detached"))
            assertEquals(1, device.closeCalls.get())
        }

    @Test
    fun `close winning over a suspended open closes the orphan and registers nothing`() =
        runBlocking {
            val opener = FakeOpener()
            val service = TapService(testConfig(), opener)
            val connection = service.openConnection("t3")

            val orphan = FakeDevice(serial = "serial-orphan")
            opener.openGate = CompletableDeferred()
            // Captured via runCatching in a launch child so the expected orphan failure does not
            // fail the test scope itself through structured concurrency before it is asserted.
            val openResult = CompletableDeferred<Result<Session>>()
            val openJob =
                launch { openResult.complete(runCatching { service.openSession(connection, "serial-orphan", "com.test", testOptions()) }) }
            // Barrier, not a delay race: the opener signals it is suspended inside open.
            withTimeout(2_000) { opener.entered.receive() }

            // Close wins while open is suspended.
            val closed = service.closeConnection(connection.id, "close wins")
            assertEquals(0, closed)

            // Release the suspended open with a real resource: it must be closed, never registered.
            opener.openGate!!.complete(orphan)
            withTimeout(2_000) { openJob.join() }
            val openError = assertFailsWith<UnknownConnectionException> { openResult.await().getOrThrow() }
            assertTrue(openError.message!!.contains(connection.id))
            assertEquals(1, orphan.closeCalls.get())
            assertTrue(service.sessionIds().isEmpty())
            assertEquals(null, service.connectionSessionIds(connection.id))
            assertFalse(service.connectionExists(connection.id))
        }

    @Test
    fun `sessions appear and disappear in both maps atomically with exact-once close`() =
        runBlocking {
            val opener = FakeOpener()
            val service = TapService(testConfig(), opener)
            val connection = service.openConnection("t4")

            val s1 = service.openSession(connection, "s-1", "com.test", testOptions())
            val s2 = service.openSession(connection, "s-2", "com.test", testOptions())
            // One transaction: both maps agree after every transition.
            assertEquals(setOf(s1.id, s2.id), service.sessionIds())
            assertEquals(setOf(s1.id, s2.id), service.connectionSessionIds(connection.id))

            service.closeSession(s1.id)
            assertEquals(setOf(s2.id), service.sessionIds())
            assertEquals(setOf(s2.id), service.connectionSessionIds(connection.id))

            // Exact-once: a second close of the same session is NOT_FOUND, device closed once.
            assertFailsWith<UnknownSessionException> { service.closeSession(s1.id) }
            assertEquals(1, (s1.device as FakeDevice).closeCalls.get())

            val closed = service.closeConnection(connection.id, "teardown")
            assertEquals(1, closed)
            assertTrue(service.sessionIds().isEmpty())
            assertFalse(service.connectionExists(connection.id))
            assertEquals(1, (s2.device as FakeDevice).closeCalls.get())

            // Idempotent teardown: already-closed connection closes zero sessions.
            assertEquals(0, service.closeConnection(connection.id, "again"))
            assertFailsWith<UnknownSessionException> { service.closeSession(s2.id) }
            assertEquals(1, s2.device.closeCalls.get())
        }

    @Test
    fun `racing session and connection close still closes the device exactly once`() =
        runBlocking {
            val opener = FakeOpener()
            val device = FakeDevice(serial = "race", closeGate = CompletableDeferred())
            opener.queue.add(device)
            val service = TapService(testConfig(), opener)
            val connection = service.openConnection("race-conn")
            val session = service.openSession(connection, "race", "com.test", testOptions())

            // Session Close wins the atomic take, then suspends in cleanup.
            val sessionClose = async { service.closeSession(session.id) }
            withTimeout(2_000) { device.closeEntered.receive() }
            assertEquals(0, service.closeConnection(connection.id, "racing connection close"))
            assertFailsWith<UnknownSessionException> { service.closeSession(session.id) }
            device.closeGate!!.complete(Unit)
            withTimeout(2_000) { sessionClose.await() }
            assertEquals(1, device.closeCalls.get())
            assertTrue(service.sessionIds().isEmpty())
            assertFalse(service.connectionExists(connection.id))
        }

    @Test
    fun `a hanging cleanup cannot exceed the shutdown budget and later sessions still close`() =
        runBlocking {
            val opener = FakeOpener()
            val attempts = AtomicInteger(0)
            val firstCloseGate = CompletableDeferred<Unit>()
            val closeAction: suspend () -> Unit = {
                // Whichever session shutdown attempts first is uncooperative. This avoids relying
                // on HashMap/UUID iteration order to prove a later session is still attempted.
                if (attempts.incrementAndGet() == 1) firstCloseGate.await()
            }
            val firstDevice = FakeDevice(serial = "first", closeAction = closeAction)
            val secondDevice = FakeDevice(serial = "second", closeAction = closeAction)
            opener.queue.addAll(listOf(firstDevice, secondDevice))
            // Short configured budget: total 600 ms, 250 ms per session.
            val service = TapService(testConfig(), opener, shutdownTotalMs = 600, shutdownSessionMs = 250)
            val c1 = service.openConnection("hang-conn")
            val c2 = service.openConnection("quick-conn")
            service.openSession(c1, "hang", "com.test", testOptions())
            service.openSession(c2, "quick", "com.test", testOptions())

            // Bounded shutdown: returns despite the hanging close, attempts the later session.
            withTimeout(5_000) { service.close() }
            assertEquals(2, attempts.get())
            assertEquals(1, firstDevice.closeCalls.get())
            assertEquals(1, secondDevice.closeCalls.get())
            assertTrue(service.sessionIds().isEmpty())
            assertFalse(service.connectionExists(c1.id))
            assertFalse(service.connectionExists(c2.id))
            assertFailsWith<ServiceClosingException> { service.openConnection("too-late") }

            // Do not leave the deliberately uncooperative test cleanup running.
            firstCloseGate.complete(Unit)
            withTimeout(2_000) {
                firstDevice.closeCompleted.await()
                secondDevice.closeCompleted.await()
            }
        }

    @Test
    fun `connection budget exhaustion detaches with full session deadline`() =
        runBlocking {
            val sessionDeadlineMs = 5_000L
            // Distinct from shutdownSessionMs so a perSessionMs leak fails the timeout assertion.
            val perSessionMs = 37L
            val sessionCount = 6
            val logs = java.util.concurrent.CopyOnWriteArrayList<String>()
            val opener = FakeOpener()
            val devices = (0 until sessionCount).map { FakeDevice(serial = "conn-exhaust-$it", closeGate = CompletableDeferred()) }
            opener.queue.addAll(devices)
            val service =
                TapService(
                    testConfig(log = { logs.add(it) }),
                    opener,
                    shutdownTotalMs = 60_000,
                    shutdownSessionMs = sessionDeadlineMs,
                )
            val connection = service.openConnection("conn-exhaust")
            repeat(sessionCount) { service.openSession(connection, "conn-exhaust-$it", "com.test", testOptions()) }
            assertEquals(sessionCount, service.sessionIds().size)

            // Deterministic seam: already-exhausted connection-local deadline, no wall-clock race.
            val closed =
                withTimeout(4_000) {
                    service.closeConnectionWithin(connection.id, "exhausted test", perSessionMs, totalTimeoutMs = 0)
                }
            assertEquals(sessionCount, closed)
            // Connection-specific branch, not the service-level one.
            assertTrue(
                logs.any {
                    it.contains("connection ${connection.id} shutdown budget exhausted") &&
                        it.contains("$sessionCount session(s) detached with cleanup launched")
                },
                "connection exhaustion branch not proven: $logs",
            )
            assertTrue(service.sessionIds().isEmpty())
            assertFalse(service.connectionExists(connection.id))
            // Every detached cleanup launched exactly once: barrier-based, no sleeps.
            devices.forEach { withTimeout(2_000) { it.closeEntered.receive() } }
            assertEquals(sessionCount, devices.sumOf { it.closeCalls.get() })
            devices.forEach {
                assertEquals(
                    listOf(sessionDeadlineMs),
                    it.closeTimeouts.toList(),
                    "detached cleanup must carry full shutdownSessionMs, not perSessionMs",
                )
            }
            // Idempotent: no duplicate cleanups.
            assertEquals(0, service.closeConnection(connection.id, "again"))
            devices.forEach { assertEquals(1, it.closeCalls.get()) }

            devices.forEach { it.closeGate!!.complete(Unit) }
            withTimeout(5_000) { devices.forEach { it.closeCompleted.await() } }
            devices.forEach { assertEquals(1, it.closeCalls.get()) }
        }

    @Test
    fun `exhausted shutdown detaches everything launches every cleanup and admits nothing new`() =
        runBlocking {
            val innerBudgetMs = 25L
            val sessionDeadlineMs = 5_000L
            val opener = FakeOpener()
            val sessionCount = 120
            val devices = (0 until sessionCount).map { FakeDevice(serial = "exhaust-$it", closeGate = CompletableDeferred()) }
            opener.queue.addAll(devices)
            val service =
                TapService(
                    testConfig(),
                    opener,
                    shutdownTotalMs = innerBudgetMs,
                    shutdownSessionMs = sessionDeadlineMs,
                )
            val connection = service.openConnection("exhaust-conn")
            repeat(sessionCount) { service.openSession(connection, "exhaust-$it", "com.test", testOptions()) }
            assertEquals(sessionCount, service.sessionIds().size)

            // Elapsed-bound integration coverage only: which exhaustion branch fires here is a
            // wall-clock race, so the exact branch is proven by the deterministic
            // `connection budget exhaustion` test above, not by timing or log matching here.
            val startedNanos = System.nanoTime()
            withTimeout(innerBudgetMs + 4_000) { service.close() }
            val elapsedMs = (System.nanoTime() - startedNanos) / 1_000_000L
            assertTrue(elapsedMs < innerBudgetMs + 2_000L, "shutdown took ${elapsedMs}ms for a ${innerBudgetMs}ms budget")
            assertTrue(service.sessionIds().isEmpty())
            assertFalse(service.connectionExists(connection.id))
            devices.forEach { withTimeout(2_000) { it.closeEntered.receive() } }
            assertEquals(sessionCount, devices.sumOf { it.closeCalls.get() })
            assertFailsWith<ServiceClosingException> { service.openConnection("during-shutdown") }

            val outerLogs = java.util.concurrent.CopyOnWriteArrayList<String>()
            val outerOpener = FakeOpener()
            val outerDevices = (0 until 12).map { FakeDevice(serial = "outer-$it", closeGate = CompletableDeferred()) }
            outerOpener.queue.addAll(outerDevices)
            val outerService =
                TapService(testConfig(log = { outerLogs.add(it) }), outerOpener, shutdownTotalMs = 0, shutdownSessionMs = sessionDeadlineMs)
            val outerConnections = (0 until 3).map { outerService.openConnection("outer-conn-$it") }
            outerConnections.forEachIndexed { ci, outerConnection ->
                repeat(4) { si -> outerService.openSession(outerConnection, "outer-${ci * 4 + si}", "com.test", testOptions()) }
            }
            withTimeout(4_000) { outerService.close() }
            assertTrue(outerLogs.any { it.contains("service shutdown budget 0ms exhausted") }, "outer detach branch not proven: $outerLogs")
            assertTrue(outerService.sessionIds().isEmpty())
            outerConnections.forEach { assertFalse(outerService.connectionExists(it.id)) }
            outerDevices.forEach { withTimeout(2_000) { it.closeEntered.receive() } }
            assertEquals(12, outerDevices.sumOf { it.closeCalls.get() })
            outerDevices.forEach { assertEquals(sessionDeadlineMs, it.closeTimeouts.single()) }

            val lateOpener = FakeOpener()
            lateOpener.openGate = CompletableDeferred()
            val lateService = TapService(testConfig(), lateOpener, shutdownTotalMs = innerBudgetMs, shutdownSessionMs = sessionDeadlineMs)
            val lateConnection = lateService.openConnection("late-conn")
            val lateOpenResult = CompletableDeferred<Result<Session>>()
            val lateOpenJob =
                launch {
                    lateOpenResult.complete(runCatching { lateService.openSession(lateConnection, "late", "com.test", testOptions()) })
                }
            withTimeout(2_000) { lateOpener.entered.receive() }
            val lateClose = async { lateService.close(200) }
            withTimeout(2_000) { lateClose.await() }
            val lateOrphan = FakeDevice(serial = "late-orphan")
            lateOpener.openGate!!.complete(lateOrphan)
            withTimeout(2_000) { lateOpenJob.join() }
            assertFailsWith<UnknownConnectionException> { lateOpenResult.await().getOrThrow() }
            assertEquals(1, lateOrphan.closeCalls.get())
            assertTrue(lateService.sessionIds().isEmpty())
            assertFalse(lateService.connectionExists(lateConnection.id))

            devices.forEach { it.closeGate!!.complete(Unit) }
            outerDevices.forEach { it.closeGate!!.complete(Unit) }
            withTimeout(5_000) {
                devices.forEach { it.closeCompleted.await() }
                outerDevices.forEach { it.closeCompleted.await() }
            }
            devices.forEach { assertEquals(1, it.closeCalls.get()) }
            outerDevices.forEach { assertEquals(1, it.closeCalls.get()) }
        }

    @Test
    fun `grpc caller cancellation propagates CANCEL and retains the terminal response`(): Unit =
        runBlocking {
            val sessionId = "cancel-session"
            val generation = 7L
            val secret = ByteArray(32).also(SecureRandom()::nextBytes)
            FakeDriverServer(sessionId, generation, secret).use { server ->
                val client =
                    DriverClient.connect(
                        hostPort = server.port,
                        sessionId = sessionId,
                        generation = generation,
                        secret = secret,
                        serial = "cancel-serial",
                        heartbeatIntervalMs = 0,
                    )
                try {
                    val device =
                        FakeDevice(
                            serial = "cancel-serial",
                            generation = generation,
                            autPackage = "com.test",
                            realClient = client,
                        )
                    val opener = FakeOpener().apply { queue.add(device) }
                    val service = TapService(testConfig(), opener)
                    val connection = service.openConnection("cancel-conn")
                    val session = service.openSession(connection, "cancel-serial", "com.test", testOptions())
                    val servicer = SessionServicer(service)
                    val serverName = InProcessServerBuilder.generateName()
                    val grpcServer =
                        InProcessServerBuilder
                            .forName(serverName)
                            .directExecutor()
                            .addService(servicer)
                            .build()
                            .start()
                    val channel = InProcessChannelBuilder.forName(serverName).directExecutor().build()
                    val stub = SessionServiceGrpcKt.SessionServiceCoroutineStub(channel)
                    val request =
                        ExecuteRequest
                            .newBuilder()
                            .setSessionId(session.id)
                            .setCommand(Command.newBuilder().setHealth(Health.getDefaultInstance()).setTimeoutMs(10_000))
                            .build()

                    try {
                        // The Execute RPC is in flight on the driver; REQUEST is the barrier.
                        val executeJob = async { stub.execute(request) }
                        val submitted = withTimeout(2_000) { withContext(Dispatchers.IO) { server.nextFrame() } }
                        assertEquals(FrameType.REQUEST, submitted.type)

                        // Cancel the grpc-kotlin client call, not a direct servicer invocation.
                        executeJob.cancel()
                        assertFailsWith<CancellationException> {
                            withTimeout(2_000) { executeJob.await() }
                        }

                        // Core forwarded cooperative CANCEL while retaining the pending entry.
                        val cancelFrame = withTimeout(2_000) { withContext(Dispatchers.IO) { server.nextFrame() } }
                        assertEquals(FrameType.CANCEL, cancelFrame.type)
                        assertEquals(submitted.requestId, cancelFrame.requestId)

                        // A late terminal response is consumed rather than poisoning the transport.
                        server.respond(submitted.requestId, Response.ok(Done, durationMs = 1))
                        val secondJob = async { stub.execute(request) }
                        val secondFrame = withTimeout(2_000) { withContext(Dispatchers.IO) { server.nextFrame() } }
                        assertEquals(FrameType.REQUEST, secondFrame.type)
                        server.respond(secondFrame.requestId, Response.ok(Done, durationMs = 1))
                        withTimeout(2_000) { secondJob.await() }
                    } finally {
                        channel.shutdownNow()
                        grpcServer.shutdownNow()
                        channel.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)
                        grpcServer.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)
                    }
                } finally {
                    runCatching { client.close() }
                }
            }
        }

    @Test
    fun `poisoned session rejects direct command paths but still closes`() =
        runBlocking {
            val opener = FakeOpener()
            val service = TapService(testConfig(), opener)
            val connection = service.openConnection("poison-conn")
            val device = FakeDevice(serial = "poison-serial")
            opener.queue.add(device)
            val session = service.openSession(connection, "poison-serial", "com.test", testOptions())
            device.poisoned =
                com.company.tap.host.AdbReapUncertainException(
                    "ADB process or output drain survived bounded reap",
                    listOf("adb", "-s", "poison-serial", "shell", "pidof", "com.test"),
                    "poison-serial",
                )
            assertFailsWith<IllegalStateException> { service.session(session.id) }
            // Cleanup paths do not go through the poison check: the lease still releases.
            service.closeSession(session.id)
            assertEquals(1, device.closeCalls.get())
            assertTrue(service.sessionIds().isEmpty())
        }

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `service lookup then poison before submit rejects through the session gate`() =
        runBlocking {
            val serial = "lookup-poison-serial"
            val secret = ByteArray(32).also(SecureRandom()::nextBytes)
            val fake = FakeDriverServer("lookup-poison", 1, secret, acceptAnySession = true)
            try {
                lateinit var adb: FakeAdb
                adb =
                    FakeAdb(
                        mapOf(
                            "shell cat /proc/sys/kernel/random/boot_id" to ok("boot-1"),
                            "shell am force-stop $DRIVER_PACKAGE" to ok(""),
                            "shell input keyevent KEYCODE_WAKEUP" to ok(""),
                            "shell wm dismiss-keyguard" to ok(""),
                            "forward tcp:0 tcp:$DEVICE_PORT" to ok(fake.port.toString()),
                            "shell cat /proc/4242/stat" to
                                ok("4242 (app_process) S 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 99999 20 21"),
                            "forward --remove tcp:${fake.port}" to ok(""),
                        ),
                    )
                adb.responder = { _, command ->
                    when (command) {
                        "shell pidof $DRIVER_PACKAGE" -> {
                            val forwarded = adb.calls.any { it.contains("forward tcp:0") }
                            val forceStops = adb.calls.count { it.contains("am force-stop") }
                            if (forwarded && forceStops < 2) ok("4242") else Adb.Result(1, "")
                        }
                        else -> null
                    }
                }
                val processes = mutableListOf<FakeProcess>()
                val sessionConfig =
                    DeviceSessionConfig(
                        serial = serial,
                        autPackage = "com.test",
                        journalRoot = tempDir.resolve("sessions"),
                        adb = adb,
                        processStarter =
                            ProcessStarter { command ->
                                val args = command.drop(3)
                                fun option(name: String): String {
                                    val index = args.indexOf(name)
                                    return args[index + 1]
                                }
                                fake.secret = java.util.Base64.getUrlDecoder().decode(option("tapSecret"))
                                FakeProcess(
                                    stdout =
                                        "INSTRUMENTATION_RESULT: ok\n" +
                                            "TAP_READY session=${option("tapSession")} " +
                                            "generation=${option("tapGeneration")} " +
                                            "port=${option("tapPort")} instance=test-instance\n",
                                    exitDelayMs = FakeProcess.NEVER,
                                ).also(processes::add)
                            },
                    )
                val opener =
                    object : DeviceOpener {
                        override suspend fun open(config: DeviceSessionConfig): ServiceDevice =
                            RealSessionDevice(DeviceSession.open(sessionConfig))
                    }
                val service = TapService(testConfig(), opener)
                val connection = service.openConnection("lookup-poison-conn")
                val opening = async(Dispatchers.IO) { service.openSession(connection, serial, "com.test", testOptions()) }
                fake.respond(withTimeout(5_000) { fake.nextFrame() }.requestId, Response.ok(Done, durationMs = 1))
                val session = withTimeout(5_000) { opening.await() }
                // Barrier, not a delay race: the lookup completes first, then the poison lands
                // before submission — the submit must still reject through the bound gate.
                val captured = service.session(session.id).device.client
                (session.device as RealSessionDevice).poison(
                    AdbReapUncertainException(
                        "ADB process or output drain survived bounded reap",
                        listOf("adb", "-s", serial, "shell", "pidof", "com.test"),
                        serial,
                    ),
                )
                assertFailsWith<IllegalStateException> {
                    captured.submit(com.company.tap.protocol.Health)
                }
                // Nothing reached the driver: the next frame poll times out.
                val noFrame =
                    try {
                        withTimeout(300) { fake.nextFrame() }
                        false
                    } catch (_: Exception) {
                        true
                    }
                assertTrue(noFrame, "poisoned submit emitted a frame")
                assertFailsWith<IllegalStateException> { service.session(session.id) }
                // Poison alone never throws from close: cleanup runs clean, the journal records
                // the quarantine, and the lease releases.
                assertEquals(null, service.closeSession(session.id))
                val record = com.company.tap.host.SessionJournalStore(tempDir.resolve("sessions"), serial).read()
                assertEquals(com.company.tap.host.JournalState.QUARANTINED, record?.state)
                assertTrue(service.sessionIds().isEmpty())
            } finally {
                fake.close()
            }
        }
}
