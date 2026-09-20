package com.company.tap.service

import com.company.tap.api.v1.AttachRequest
import com.company.tap.api.v1.Command
import com.company.tap.api.v1.ExecuteRequest
import com.company.tap.api.v1.Health
import com.company.tap.host.Adb
import com.company.tap.host.AppLifecycle
import com.company.tap.host.DeviceSessionConfig
import com.company.tap.host.DriverClient
import com.company.tap.host.FakeDriverServer
import com.company.tap.protocol.Done
import com.company.tap.protocol.FrameType
import com.company.tap.protocol.Response
import com.company.tap.service.servicer.ConnectionServicer
import com.company.tap.service.servicer.SessionServicer
import io.grpc.Status
import io.grpc.StatusRuntimeException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
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
    var closeGate: CompletableDeferred<Unit>? = null,
    var closeError: Throwable? = null,
    private val realClient: DriverClient? = null,
) : ServiceDevice {
    val closeCalls = AtomicInteger(0)
    override val client: DriverClient
        get() = realClient ?: error("no client in lifecycle fake")

    override fun app(packageName: String): AppLifecycle = error("no app in lifecycle fake")

    override suspend fun close(timeoutMs: Long) {
        closeCalls.incrementAndGet()
        // Ignores timeoutMs on purpose: proves the service outer bound, not fake cooperation.
        closeGate?.await()
        closeError?.let { throw it }
    }
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
            val openJob = launch { openResult.complete(runCatching { service.openSession(connection, "serial-orphan", "com.test", testOptions()) }) }
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
            assertEquals(1, (s2.device as FakeDevice).closeCalls.get())
        }

    @Test
    fun `a hanging cleanup cannot exceed the shutdown budget and later sessions still close`() =
        runBlocking {
            val opener = FakeOpener()
            val hanging = FakeDevice(serial = "hang", closeGate = CompletableDeferred())
            val quick = FakeDevice(serial = "quick")
            opener.queue.addAll(listOf(hanging, quick))
            // Short configured budget: total 600 ms, 250 ms per session.
            val service = TapService(testConfig(), opener, shutdownTotalMs = 600, shutdownSessionMs = 250)
            val c1 = service.openConnection("hang-conn")
            val c2 = service.openConnection("quick-conn")
            service.openSession(c1, "hang", "com.test", testOptions())
            service.openSession(c2, "quick", "com.test", testOptions())

            // Bounded shutdown: returns despite the hanging close, attempts the later session.
            withTimeout(5_000) { service.close() }
            assertEquals(1, hanging.closeCalls.get())
            assertEquals(1, quick.closeCalls.get())
            assertTrue(service.sessionIds().isEmpty())
            assertFalse(service.connectionExists(c1.id))
            assertFalse(service.connectionExists(c2.id))
        }

    @Test
    fun `cancelled execute propagates cancellation with CANCEL and no transport-loss response`() =
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
                    val request =
                        ExecuteRequest
                            .newBuilder()
                            .setSessionId(session.id)
                            .setCommand(Command.newBuilder().setHealth(Health.getDefaultInstance()).setTimeoutMs(10_000))
                            .build()

                    // The execute is in flight on the driver; the REQUEST frame is the barrier.
                    val executeJob = async { servicer.execute(request) }
                    val submitted = withTimeout(2_000) { server.nextFrame() }
                    assertEquals(FrameType.REQUEST, submitted.type)

                    // Caller cancellation propagates as cancellation, never as a result value.
                    executeJob.cancel()
                    assertFailsWith<CancellationException> {
                        withTimeout(2_000) { executeJob.await() }
                    }

                    // Core forwarded the cooperative CANCEL while keeping the entry registered.
                    val cancelFrame = withTimeout(2_000) { server.nextFrame() }
                    assertEquals(FrameType.CANCEL, cancelFrame.type)
                    assertEquals(submitted.requestId, cancelFrame.requestId)

                    // The driver's late terminal response is still consumed, not an unknown id:
                    // the transport stays usable for the next command.
                    server.respond(submitted.requestId, Response.ok(Done, durationMs = 1))
                    val second =
                        ExecuteRequest
                            .newBuilder()
                            .setSessionId(session.id)
                            .setCommand(Command.newBuilder().setHealth(Health.getDefaultInstance()).setTimeoutMs(10_000))
                            .build()
                    val secondJob = async { servicer.execute(second) }
                    val secondFrame = withTimeout(2_000) { server.nextFrame() }
                    assertEquals(FrameType.REQUEST, secondFrame.type)
                    server.respond(secondFrame.requestId, Response.ok(Done, durationMs = 1))
                    withTimeout(2_000) { secondJob.await() }
                } finally {
                    runCatching { client.close() }
                }
            }
        }
}
