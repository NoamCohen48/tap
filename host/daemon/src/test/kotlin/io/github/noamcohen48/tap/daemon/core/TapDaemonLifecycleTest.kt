package io.github.noamcohen48.tap.daemon.core

import io.github.noamcohen48.tap.api.v1.Command
import io.github.noamcohen48.tap.api.v1.DeviceInfoQuery
import io.github.noamcohen48.tap.api.v1.DeviceServiceGrpcKt
import io.github.noamcohen48.tap.api.v1.Direction
import io.github.noamcohen48.tap.api.v1.ErrorCode
import io.github.noamcohen48.tap.api.v1.ExecuteRequest
import io.github.noamcohen48.tap.api.v1.ObserveRequest
import io.github.noamcohen48.tap.api.v1.Selector
import io.github.noamcohen48.tap.daemon.grpc.ClientConnectionService
import io.github.noamcohen48.tap.daemon.grpc.DeviceService
import io.github.noamcohen48.tap.host.Adb
import io.github.noamcohen48.tap.host.AdbCommandException
import io.github.noamcohen48.tap.host.AdbDevice
import io.github.noamcohen48.tap.host.AdbDeviceState
import io.github.noamcohen48.tap.host.AdbReapUncertainException
import io.github.noamcohen48.tap.host.AppLifecycle
import io.github.noamcohen48.tap.host.DEVICE_PORT
import io.github.noamcohen48.tap.host.DRIVER_PACKAGE
import io.github.noamcohen48.tap.host.DRIVER_TEST_PACKAGE
import io.github.noamcohen48.tap.host.DeviceSession
import io.github.noamcohen48.tap.host.DeviceSessionConfig
import io.github.noamcohen48.tap.host.DriverClient
import io.github.noamcohen48.tap.host.FakeAdb
import io.github.noamcohen48.tap.host.FakeDriverServer
import io.github.noamcohen48.tap.host.FakeProcess
import io.github.noamcohen48.tap.host.ProcessStarter
import io.github.noamcohen48.tap.host.ok
import io.github.noamcohen48.tap.protocol.Commands
import io.github.noamcohen48.tap.protocol.FrameType
import io.github.noamcohen48.tap.protocol.Nodes
import io.github.noamcohen48.tap.protocol.Responses
import io.github.noamcohen48.tap.protocol.stamped
import io.github.noamcohen48.tap.protocol.toSelector
import io.github.noamcohen48.tap.wire.v1.Request
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
import java.lang.ref.WeakReference
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun testConfig(log: (String) -> Unit = {}): DaemonConfig {
    val dir = Files.createTempDirectory("tap-daemon-test")
    return DaemonConfig(adb = object : Adb("fake-adb") {}, stateDir = dir, driver = null, log = log)
}

private fun testOptions() =
    TapDaemon.AttachDeviceOptions(
        skipDriverInstall = true,
        defaultTimeoutMs = 5_000,
        leaseTimeoutMs = 0,
    )

private class FakeDevice(
    override val serial: String,
    override val generation: Long = 1,
    var poisoned: Throwable? = null,
    var closeGate: CompletableDeferred<Unit>? = null,
    var closeError: Throwable? = null,
    private val closeAction: (suspend () -> Unit)? = null,
    private val realClient: DriverClient? = null,
) : DaemonDeviceSession {
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

/** Production-shaped [DaemonDeviceSession]: a live `:host:core` session, so the client's session-owned
 * admission gate is bound exactly as production binds it. Tests poison through [poison]. */
private class RealSessionDevice(
    val delegate: DeviceSession,
) : DaemonDeviceSession {
    override val serial: String get() = delegate.serial
    override val generation: Long get() = delegate.generation
    override val client: DriverClient get() = delegate.client

    override fun app(packageName: String): AppLifecycle = delegate.app(packageName)

    override fun checkUsable() = delegate.checkUsable()

    fun poison(error: AdbReapUncertainException) = delegate.noteReapUncertain(error)

    override suspend fun close(timeoutMs: Long) = delegate.close(timeoutMs)
}

private class FakeOpener : DeviceSessionOpener {
    val entered = Channel<Unit>(Channel.UNLIMITED)
    val openCalls = AtomicInteger(0)
    var openGate: CompletableDeferred<DaemonDeviceSession>? = null
    val queue = ArrayDeque<DaemonDeviceSession>()

    override suspend fun open(config: DeviceSessionConfig): DaemonDeviceSession {
        openCalls.incrementAndGet()
        entered.trySend(Unit)
        openGate?.let { return it.await() }
        synchronized(queue) {
            if (queue.isNotEmpty()) return queue.removeFirst()
        }
        return FakeDevice(serial = config.serial)
    }
}

/** A scrcpy child that takes [exitAfterMs] to exit once asked to stop. */
private class SlowExitProcess(
    private val exitAfterMs: Long,
) : Process() {
    @Volatile private var stopRequestedAt = 0L

    override fun getOutputStream() = java.io.ByteArrayOutputStream()

    override fun getInputStream() = java.io.ByteArrayInputStream(ByteArray(0))

    override fun getErrorStream() = java.io.ByteArrayInputStream(ByteArray(0))

    override fun destroy() {
        if (stopRequestedAt == 0L) stopRequestedAt = System.nanoTime()
    }

    override fun isAlive(): Boolean = stopRequestedAt == 0L || System.nanoTime() - stopRequestedAt < exitAfterMs * 1_000_000L

    override fun waitFor(): Int {
        while (isAlive) Thread.sleep(10)
        return 0
    }

    override fun waitFor(
        timeout: Long,
        unit: TimeUnit,
    ): Boolean {
        val end = System.nanoTime() + unit.toNanos(timeout)
        while (isAlive && System.nanoTime() < end) Thread.sleep(10)
        return !isAlive
    }

    override fun exitValue(): Int = if (isAlive) throw IllegalThreadStateException() else 0
}

class TapDaemonLifecycleTest {
    @Test
    fun `only the owning connection can use or detach an attached device`() =
        runBlocking {
            val opener = FakeOpener()
            val daemon = TapDaemon(testConfig(), opener)
            val owner = daemon.connectClient("owner")
            val intruder = daemon.connectClient("intruder")
            val attached = daemon.attachDevice(owner.id, "owned-serial", testOptions())
            assertFailsWith<NotOwnerException> { daemon.attachedDevice(attached.id, intruder.id) }
            assertFailsWith<NotOwnerException> { daemon.detachDevice(attached.id, intruder.id) }
            assertEquals(setOf(attached.id), daemon.attachedDeviceIds())
            assertEquals(attached, daemon.attachedDevice(attached.id, owner.id))
            daemon.detachDevice(attached.id, owner.id)
            assertTrue(daemon.attachedDeviceIds().isEmpty())
        }

    @Test
    fun `a driver whose installed APKs differ from the daemon's is reinstalled within the attach`() =
        runBlocking {
            val dir = Files.createTempDirectory("tap-daemon-driver")
            val apk = Files.write(dir.resolve("driver.apk"), byteArrayOf(1, 2, 3))
            val testApk = Files.write(dir.resolve("driver-test.apk"), byteArrayOf(4, 5))
            val driver = DriverApks.override(apk, testApk)
            val (driverSha256, testSha256) = driver.sha256()
            var installed: Map<String, List<String>> = emptyMap()
            var unreadable = false
            val adb =
                object : Adb("fake-adb") {
                    override suspend fun installedApkSha256(
                        serial: String,
                        packageName: String,
                        timeoutMs: Long,
                    ): List<String> {
                        if (unreadable) throw AdbCommandException(serial, listOf("shell", "sha256sum"), 127, "sha256sum: not found")
                        return installed[packageName].orEmpty()
                    }
                }
            val installs = mutableListOf<Boolean>()
            val opener =
                object : DeviceSessionOpener {
                    override suspend fun open(config: DeviceSessionConfig): DaemonDeviceSession {
                        installs += config.installDriver()
                        if (installs.last()) installed = mapOf(DRIVER_PACKAGE to listOf(driverSha256), DRIVER_TEST_PACKAGE to listOf(testSha256))
                        return FakeDevice(config.serial)
                    }
                }
            val daemon = TapDaemon(DaemonConfig(adb = adb, stateDir = dir, driver = driver, log = {}), opener)
            val owner = daemon.connectClient("owner")
            val options = testOptions().copy(skipDriverInstall = false)
            suspend fun attachAndDetach() = daemon.detachDevice(daemon.attachDevice(owner.id, "serial-1", options).id, owner.id)

            attachAndDetach() // nothing installed
            attachAndDetach() // the daemon's own build: cached
            installed = installed + (DRIVER_PACKAGE to listOf("0".repeat(64))) // a snapshot restored an older build
            attachAndDetach()
            installed = installed + (DRIVER_TEST_PACKAGE to listOf(testSha256, "1".repeat(64))) // a split install
            attachAndDetach()
            unreadable = true
            attachAndDetach()
            assertEquals(listOf(true, false, true, true, true), installs)
        }

    @Test
    fun `a slow scrcpy stop on detach leaves the driver close its full budget`() =
        runBlocking {
            val device = FakeDevice(serial = "recording-serial")
            val opener = FakeOpener().apply { queue.add(device) }
            val scrcpy = SlowExitProcess(exitAfterMs = 700)
            val daemon = TapDaemon(testConfig(), DaemonDeps(opener, scrcpyLaunch = { _, _ -> scrcpy }))
            val owner = daemon.connectClient("owner")
            val attached = daemon.attachDevice(owner.id, "recording-serial", testOptions())
            attached.recording.start(video = true, audioSource = null, maxSeconds = 5)
            // The scrcpy stop alone outlasts the 500 ms detach budget; the session is still clean
            // and its close gets the whole budget (less the core margin), not what scrcpy left.
            assertNull(daemon.detachDevice(attached.id, owner.id, timeoutMs = 500))
            assertFalse(scrcpy.isAlive)
            assertEquals(listOf(450L), device.closeTimeouts.toList())
        }

    @Test
    fun `unreadable journals are quarantined and offline or unauthorized devices are listed as unavailable`() =
        runBlocking {
            val dir = Files.createTempDirectory("tap-daemon-journal")
            val adb =
                object : Adb("fake-adb") {
                    override suspend fun deviceStates(timeoutMs: Long): List<AdbDevice> =
                        listOf(
                            AdbDevice("corrupt1", AdbDeviceState.ONLINE, "device"),
                            AdbDevice("off1", AdbDeviceState.OFFLINE, "offline"),
                            AdbDevice("auth1", AdbDeviceState.UNAUTHORIZED, "unauthorized"),
                        )
                }
            val daemon = TapDaemon(DaemonConfig(adb = adb, stateDir = dir, driver = null, log = {}), FakeOpener())
            Files.createDirectories(dir.resolve("sessions"))
            val encoded = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("corrupt1".toByteArray())
            Files.writeString(dir.resolve("sessions").resolve("$encoded.json"), "{not json")
            val (corrupt, offline, unauthorized) = daemon.devices().map { it.status }
            assertTrue(corrupt is DeviceStatus.Quarantined && corrupt.reason.startsWith("journal unreadable"), "got $corrupt")
            assertEquals(DeviceStatus.Unavailable(AdbDeviceState.OFFLINE, "offline"), offline)
            assertEquals(DeviceStatus.Unavailable(AdbDeviceState.UNAUTHORIZED, "unauthorized"), unauthorized)
        }

    @Test
    fun `a connection that never opens Observe is reaped with its devices`() =
        runBlocking {
            val daemon = TapDaemon(testConfig(), FakeOpener(), observeGraceMs = 100)
            val silent = daemon.connectClient("silent")
            val device = daemon.attachDevice(silent.id, "serial-a", testOptions()).deviceSession as FakeDevice
            val observed = daemon.connectClient("observed")
            daemon.observeAcquire(observed.id, Any()) {}
            withTimeout(5_000) { device.closeCompleted.await() }
            withTimeout(5_000) { while (daemon.clientConnectionExists(silent.id)) delay(10) }
            delay(200)
            assertTrue(daemon.clientConnectionExists(observed.id))
            assertEquals(1, device.closeCalls.get())
        }

    @Test
    fun `a disconnected connection and its attached devices are not retained`() {
        val daemon = TapDaemon(testConfig(), FakeOpener())
        val (connection, attached) =
            runBlocking {
                val connection = daemon.connectClient("collectable")
                val attached = daemon.attachDevice(connection.id, "serial-gc", testOptions())
                assertEquals(1, daemon.disconnectClient(connection.id, "test"))
                WeakReference(connection) to WeakReference(attached)
            }
        val deadline = System.nanoTime() + 5_000_000_000L
        while ((connection.get() != null || attached.get() != null) && System.nanoTime() < deadline) {
            System.gc()
            Thread.sleep(10)
        }
        assertNull(connection.get(), "the daemon still references the disconnected ConnectedClient")
        assertNull(attached.get(), "the daemon still references the detached AttachedDevice")
    }

    @Test
    fun `connection disconnect scans globally owned attached devices`() =
        runBlocking {
            val device = FakeDevice("index-mismatch")
            val opener = FakeOpener().also { it.queue.add(device) }
            val daemon = TapDaemon(testConfig(), opener)
            val connection = daemon.connectClient("index-mismatch")
            val session = daemon.attachDevice(connection.id, device.serial, testOptions())

            assertEquals(1, daemon.disconnectClient(connection.id, "test invariant mismatch"))
            assertTrue(daemon.attachedDeviceIds().isEmpty())
            assertEquals(1, device.closeCalls.get())
        }

    @Test
    fun `disconnect between observe registration and heartbeat ends the stream with no leak`() =
        runBlocking {
            val opener = FakeOpener()
            val daemon = TapDaemon(testConfig(), opener)
            val connection = daemon.connectClient("t1")
            val servicer = ClientConnectionService(daemon, heartbeatIntervalMs = 50)

            // Observe wins first: first event proves registration completed under the lock.
            val firstEvent = CompletableDeferred<Unit>()
            val events = mutableListOf<String>()
            val job =
                launch {
                    servicer.observe(ObserveRequest.newBuilder().setClientConnectionId(connection.id).build()).collect { event ->
                        events.add(event.eventCase.name)
                        if (!firstEvent.isCompleted) firstEvent.complete(Unit)
                    }
                }
            withTimeout(2_000) { firstEvent.await() }
            assertTrue(daemon.observeOwnerPresent(connection.id))
            assertEquals(1, daemon.onDisconnectCount(connection.id))

            // Explicit disconnect must cancel the stream promptly (delay is cancellable), not after 15 s.
            daemon.disconnectClient(connection.id, "test close")
            withTimeout(2_000) { job.join() }
            assertTrue(events.isNotEmpty())
            assertFalse(daemon.clientConnectionExists(connection.id))
            assertEquals(0, daemon.onDisconnectCount(connection.id))
            assertFalse(daemon.observeOwnerPresent(connection.id))

            // Disconnect wins first: observe after disconnect is NOT_FOUND and registers nothing.
            val gone = CompletableDeferred<Unit>()
            val lateJob =
                launch {
                    try {
                        servicer.observe(ObserveRequest.newBuilder().setClientConnectionId(connection.id).build()).collect {}
                    } catch (error: StatusRuntimeException) {
                        assertEquals(Status.Code.NOT_FOUND, error.status.code)
                        gone.complete(Unit)
                    }
                }
            withTimeout(2_000) { gone.await() }
            withTimeout(2_000) { lateJob.join() }
            assertFalse(daemon.observeOwnerPresent(connection.id))
        }

    @Test
    fun `duplicate observe is FAILED_PRECONDITION and keeps the valid stream`() =
        runBlocking {
            val opener = FakeOpener()
            val daemon = TapDaemon(testConfig(), opener)
            val connection = daemon.connectClient("t2")
            val servicer = ClientConnectionService(daemon, heartbeatIntervalMs = 50)

            val firstEvent = CompletableDeferred<Unit>()
            val firstJob =
                launch {
                    servicer.observe(ObserveRequest.newBuilder().setClientConnectionId(connection.id).build()).collect {
                        if (!firstEvent.isCompleted) firstEvent.complete(Unit)
                    }
                }
            withTimeout(2_000) { firstEvent.await() }

            val duplicateError =
                assertFailsWith<StatusRuntimeException> {
                    servicer.observe(ObserveRequest.newBuilder().setClientConnectionId(connection.id).build()).collect {}
                }
            assertEquals(Status.Code.FAILED_PRECONDITION, duplicateError.status.code)

            // The valid stream survived the duplicate rejection: still registered, still collecting.
            assertTrue(firstJob.isActive)
            assertTrue(daemon.observeOwnerPresent(connection.id))
            assertEquals(1, daemon.onDisconnectCount(connection.id))

            daemon.disconnectClient(connection.id, "test done")
            withTimeout(2_000) { firstJob.join() }
            assertFalse(daemon.clientConnectionExists(connection.id))
        }

    @Test
    fun `observe termination detaches its device exactly once`() =
        runBlocking {
            val opener = FakeOpener()
            val device = FakeDevice(serial = "detach")
            opener.queue.add(device)
            val daemon = TapDaemon(testConfig(), opener)
            val connection = daemon.connectClient("detach-conn")
            daemon.attachDevice(connection.id, "detach", testOptions())
            val servicer = ClientConnectionService(daemon, heartbeatIntervalMs = 50)
            val observed = CompletableDeferred<Unit>()
            val observeJob =
                launch {
                    servicer.observe(ObserveRequest.newBuilder().setClientConnectionId(connection.id).build()).collect {
                        observed.complete(Unit)
                    }
                }
            withTimeout(2_000) { observed.await() }

            observeJob.cancel()
            withTimeout(2_000) { observeJob.join() }
            assertEquals(1, device.closeCalls.get())
            assertTrue(daemon.attachedDeviceIds().isEmpty())
            assertFalse(daemon.clientConnectionExists(connection.id))
            assertEquals(0, daemon.disconnectClient(connection.id, "already detached"))
            assertEquals(1, device.closeCalls.get())
        }

    @Test
    fun `disconnect winning over a suspended attach closes the orphan and registers nothing`() =
        runBlocking {
            val opener = FakeOpener()
            val daemon = TapDaemon(testConfig(), opener)
            val connection = daemon.connectClient("t3")

            val orphan = FakeDevice(serial = "serial-orphan")
            opener.openGate = CompletableDeferred()
            // Captured via runCatching in a launch child so the expected orphan failure does not
            // fail the test scope itself through structured concurrency before it is asserted.
            val openResult = CompletableDeferred<Result<AttachedDevice>>()
            val openJob =
                launch {
                    openResult.complete(
                        runCatching { daemon.attachDevice(connection.id, "serial-orphan", testOptions()) },
                    )
                }
            // Barrier, not a delay race: the opener signals it is suspended inside open.
            withTimeout(2_000) { opener.entered.receive() }

            // Disconnect wins while attachment is suspended.
            val closed = daemon.disconnectClient(connection.id, "close wins")
            assertEquals(0, closed)

            // Release the suspended attachment with a real resource: it must be closed, never registered.
            opener.openGate!!.complete(orphan)
            withTimeout(2_000) { openJob.join() }
            val openError = assertFailsWith<UnknownClientConnectionException> { openResult.await().getOrThrow() }
            assertTrue(openError.message!!.contains(connection.id))
            assertEquals(1, orphan.closeCalls.get())
            assertTrue(daemon.attachedDeviceIds().isEmpty())
            assertEquals(null, daemon.attachedDeviceIdsForConnection(connection.id))
            assertFalse(daemon.clientConnectionExists(connection.id))
        }

    @Test
    fun `attached devices appear and disappear atomically with exact-once cleanup`() =
        runBlocking {
            val opener = FakeOpener()
            val daemon = TapDaemon(testConfig(), opener)
            val connection = daemon.connectClient("t4")

            val s1 = daemon.attachDevice(connection.id, "s-1", testOptions())
            val s2 = daemon.attachDevice(connection.id, "s-2", testOptions())
            // One transaction: both maps agree after every transition.
            assertEquals(setOf(s1.id, s2.id), daemon.attachedDeviceIds())
            assertEquals(setOf(s1.id, s2.id), daemon.attachedDeviceIdsForConnection(connection.id))

            daemon.detachDevice(s1.id, connection.id)
            assertEquals(setOf(s2.id), daemon.attachedDeviceIds())
            assertEquals(setOf(s2.id), daemon.attachedDeviceIdsForConnection(connection.id))

            // Exact-once: a second detach of the same attached device is NOT_FOUND, device closed once.
            assertFailsWith<UnknownAttachedDeviceException> { daemon.detachDevice(s1.id, connection.id) }
            assertEquals(1, (s1.deviceSession as FakeDevice).closeCalls.get())

            val closed = daemon.disconnectClient(connection.id, "teardown")
            assertEquals(1, closed)
            assertTrue(daemon.attachedDeviceIds().isEmpty())
            assertFalse(daemon.clientConnectionExists(connection.id))
            assertEquals(1, (s2.deviceSession as FakeDevice).closeCalls.get())

            // Idempotent teardown: already-disconnected connection detaches zero devices.
            assertEquals(0, daemon.disconnectClient(connection.id, "again"))
            assertFailsWith<UnknownAttachedDeviceException> { daemon.detachDevice(s2.id, connection.id) }
            assertEquals(1, s2.deviceSession.closeCalls.get())
        }

    @Test
    fun `racing device detach and client disconnect clean up exactly once`() =
        runBlocking {
            val opener = FakeOpener()
            val device = FakeDevice(serial = "race", closeGate = CompletableDeferred())
            opener.queue.add(device)
            val daemon = TapDaemon(testConfig(), opener)
            val connection = daemon.connectClient("race-conn")
            val session = daemon.attachDevice(connection.id, "race", testOptions())

            // Detach wins the atomic take, then suspends in cleanup.
            val sessionDisconnect = async { daemon.detachDevice(session.id, connection.id) }
            withTimeout(2_000) { device.closeEntered.receive() }
            assertEquals(0, daemon.disconnectClient(connection.id, "racing connection close"))
            assertFailsWith<UnknownAttachedDeviceException> { daemon.detachDevice(session.id, connection.id) }
            device.closeGate!!.complete(Unit)
            withTimeout(2_000) { sessionDisconnect.await() }
            assertEquals(1, device.closeCalls.get())
            assertTrue(daemon.attachedDeviceIds().isEmpty())
            assertFalse(daemon.clientConnectionExists(connection.id))
        }

    @Test
    fun `a hanging cleanup cannot exceed the shutdown budget and later attached devices are still cleaned up`() =
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
            val daemon = TapDaemon(testConfig(), opener, shutdownTotalMs = 600, shutdownAttachedDeviceMs = 250)
            val c1 = daemon.connectClient("hang-conn")
            val c2 = daemon.connectClient("quick-conn")
            daemon.attachDevice(c1.id, "hang", testOptions())
            daemon.attachDevice(c2.id, "quick", testOptions())

            // Bounded shutdown: returns despite the hanging close, attempts the later session.
            withTimeout(5_000) { daemon.close() }
            assertEquals(2, attempts.get())
            assertEquals(1, firstDevice.closeCalls.get())
            assertEquals(1, secondDevice.closeCalls.get())
            assertTrue(daemon.attachedDeviceIds().isEmpty())
            assertFalse(daemon.clientConnectionExists(c1.id))
            assertFalse(daemon.clientConnectionExists(c2.id))
            assertFailsWith<DaemonClosingException> { daemon.connectClient("too-late") }

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
            // Distinct from shutdownAttachedDeviceMs so a perSessionMs leak fails the timeout assertion.
            val perSessionMs = 37L
            val sessionCount = 6
            val logs = java.util.concurrent.CopyOnWriteArrayList<String>()
            val opener = FakeOpener()
            val devices = (0 until sessionCount).map { FakeDevice(serial = "conn-exhaust-$it", closeGate = CompletableDeferred()) }
            opener.queue.addAll(devices)
            val daemon =
                TapDaemon(
                    testConfig(log = { logs.add(it) }),
                    opener,
                    shutdownTotalMs = 60_000,
                    shutdownAttachedDeviceMs = sessionDeadlineMs,
                )
            val connection = daemon.connectClient("conn-exhaust")
            repeat(sessionCount) { daemon.attachDevice(connection.id, "conn-exhaust-$it", testOptions()) }
            assertEquals(sessionCount, daemon.attachedDeviceIds().size)

            // Deterministic seam: already-exhausted connection-local deadline, no wall-clock race.
            val closed =
                withTimeout(4_000) {
                    daemon.disconnectClientWithin(connection.id, "exhausted test", perSessionMs, totalTimeoutMs = 0)
                }
            assertEquals(sessionCount, closed)
            // ConnectedClient-specific branch, not the daemon-level one.
            assertTrue(
                logs.any {
                    it.contains("connection ${connection.id} shutdown budget exhausted") &&
                        it.contains("$sessionCount attached device(s) detached with cleanup launched")
                },
                "connection exhaustion branch not proven: $logs",
            )
            assertTrue(daemon.attachedDeviceIds().isEmpty())
            assertFalse(daemon.clientConnectionExists(connection.id))
            // Every detached cleanup launched exactly once: barrier-based, no sleeps.
            devices.forEach { withTimeout(2_000) { it.closeEntered.receive() } }
            assertEquals(sessionCount, devices.sumOf { it.closeCalls.get() })
            devices.forEach {
                assertEquals(
                    listOf(sessionDeadlineMs),
                    it.closeTimeouts.toList(),
                    "detached cleanup must carry full shutdownAttachedDeviceMs, not perSessionMs",
                )
            }
            // Idempotent: no duplicate cleanups.
            assertEquals(0, daemon.disconnectClient(connection.id, "again"))
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
            val daemon =
                TapDaemon(
                    testConfig(),
                    opener,
                    shutdownTotalMs = innerBudgetMs,
                    shutdownAttachedDeviceMs = sessionDeadlineMs,
                )
            val connection = daemon.connectClient("exhaust-conn")
            repeat(sessionCount) { daemon.attachDevice(connection.id, "exhaust-$it", testOptions()) }
            assertEquals(sessionCount, daemon.attachedDeviceIds().size)

            // Elapsed-bound integration coverage only: which exhaustion branch fires here is a
            // wall-clock race, so the exact branch is proven by the deterministic
            // `connection budget exhaustion` test above, not by timing or log matching here.
            val startedNanos = System.nanoTime()
            withTimeout(innerBudgetMs + 4_000) { daemon.close() }
            val elapsedMs = (System.nanoTime() - startedNanos) / 1_000_000L
            assertTrue(elapsedMs < innerBudgetMs + 2_000L, "shutdown took ${elapsedMs}ms for a ${innerBudgetMs}ms budget")
            assertTrue(daemon.attachedDeviceIds().isEmpty())
            assertFalse(daemon.clientConnectionExists(connection.id))
            devices.forEach { withTimeout(2_000) { it.closeEntered.receive() } }
            assertEquals(sessionCount, devices.sumOf { it.closeCalls.get() })
            assertFailsWith<DaemonClosingException> { daemon.connectClient("during-shutdown") }

            val outerLogs = java.util.concurrent.CopyOnWriteArrayList<String>()
            val outerOpener = FakeOpener()
            val outerDevices = (0 until 12).map { FakeDevice(serial = "outer-$it", closeGate = CompletableDeferred()) }
            outerOpener.queue.addAll(outerDevices)
            val outerService =
                TapDaemon(
                    testConfig(log = { outerLogs.add(it) }),
                    outerOpener,
                    shutdownTotalMs = 0,
                    shutdownAttachedDeviceMs = sessionDeadlineMs,
                )
            val outerConnections = (0 until 3).map { outerService.connectClient("outer-conn-$it") }
            outerConnections.forEachIndexed { ci, outerConnection ->
                repeat(4) { si -> outerService.attachDevice(outerConnection.id, "outer-${ci * 4 + si}", testOptions()) }
            }
            withTimeout(4_000) { outerService.close() }
            assertTrue(outerLogs.any { it.contains("daemon shutdown budget 0ms exhausted") }, "outer detach branch not proven: $outerLogs")
            assertTrue(outerService.attachedDeviceIds().isEmpty())
            outerConnections.forEach { assertFalse(outerService.clientConnectionExists(it.id)) }
            outerDevices.forEach { withTimeout(2_000) { it.closeEntered.receive() } }
            assertEquals(12, outerDevices.sumOf { it.closeCalls.get() })
            outerDevices.forEach { assertEquals(sessionDeadlineMs, it.closeTimeouts.single()) }

            val lateOpener = FakeOpener()
            lateOpener.openGate = CompletableDeferred()
            val lateService =
                TapDaemon(testConfig(), lateOpener, shutdownTotalMs = innerBudgetMs, shutdownAttachedDeviceMs = sessionDeadlineMs)
            val lateConnection = lateService.connectClient("late-conn")
            val lateOpenResult = CompletableDeferred<Result<AttachedDevice>>()
            val lateOpenJob =
                launch {
                    lateOpenResult.complete(
                        runCatching { lateService.attachDevice(lateConnection.id, "late", testOptions()) },
                    )
                }
            withTimeout(2_000) { lateOpener.entered.receive() }
            val lateClose = async { lateService.close(200) }
            withTimeout(2_000) { lateClose.await() }
            val lateOrphan = FakeDevice(serial = "late-orphan")
            lateOpener.openGate!!.complete(lateOrphan)
            withTimeout(2_000) { lateOpenJob.join() }
            assertFailsWith<UnknownClientConnectionException> { lateOpenResult.await().getOrThrow() }
            assertEquals(1, lateOrphan.closeCalls.get())
            assertTrue(lateService.attachedDeviceIds().isEmpty())
            assertFalse(lateService.clientConnectionExists(lateConnection.id))

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
                            realClient = client,
                        )
                    val opener = FakeOpener().apply { queue.add(device) }
                    val daemon = TapDaemon(testConfig(), opener)
                    val connection = daemon.connectClient("cancel-conn")
                    val session = daemon.attachDevice(connection.id, "cancel-serial", testOptions())
                    val servicer = DeviceService(daemon)
                    val serverName = InProcessServerBuilder.generateName()
                    val grpcServer =
                        InProcessServerBuilder
                            .forName(serverName)
                            .directExecutor()
                            .addService(servicer)
                            .build()
                            .start()
                    val channel = InProcessChannelBuilder.forName(serverName).directExecutor().build()
                    val stub = DeviceServiceGrpcKt.DeviceServiceCoroutineStub(channel)
                    val request =
                        ExecuteRequest
                            .newBuilder()
                            .setClientConnectionId(connection.id)
                            .setAttachedDeviceId(session.id)
                            .setCommand(Command.newBuilder().setDeviceInfo(DeviceInfoQuery.getDefaultInstance()).setTimeoutMs(10_000))
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
                        server.respond(submitted.requestId, Responses.done(1))
                        val secondJob = async { stub.execute(request) }
                        val secondFrame = withTimeout(2_000) { withContext(Dispatchers.IO) { server.nextFrame() } }
                        assertEquals(FrameType.REQUEST, secondFrame.type)
                        server.respond(secondFrame.requestId, Responses.done(1))
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
    fun `execute validates, forwards the command unchanged and returns the driver result unchanged`(): Unit =
        runBlocking {
            val sessionId = "forward-session"
            val generation = 9L
            val secret = ByteArray(32).also(SecureRandom()::nextBytes)
            FakeDriverServer(sessionId, generation, secret).use { server ->
                val client =
                    DriverClient.connect(server.port, sessionId, generation, secret, serial = "forward-serial", heartbeatIntervalMs = 0)
                try {
                    val device = FakeDevice(serial = "forward-serial", generation = generation, realClient = client)
                    val opener = FakeOpener().apply { queue.add(device) }
                    val daemon = TapDaemon(testConfig(), opener)
                    val connection = daemon.connectClient("forward-conn")
                    val session = daemon.attachDevice(connection.id, "forward-serial", testOptions())
                    val serverName = InProcessServerBuilder.generateName()
                    val grpcServer =
                        InProcessServerBuilder.forName(serverName).directExecutor().addService(DeviceService(daemon)).build().start()
                    val channel = InProcessChannelBuilder.forName(serverName).directExecutor().build()
                    val stub = DeviceServiceGrpcKt.DeviceServiceCoroutineStub(channel)
                    fun execute(command: Command) =
                        ExecuteRequest
                            .newBuilder()
                            .setClientConnectionId(connection.id)
                            .setAttachedDeviceId(session.id)
                            .setCommand(command)
                            .build()
                    try {
                        // The selector and the absent optional fields reach the driver as sent.
                        val command =
                            Commands
                                .scroll(Nodes.resource("list").toSelector(), Direction.DIR_DOWN)
                                .toBuilder()
                                .setTimeoutMs(4_000)
                                .build()
                        val call = async { stub.execute(execute(command)) }
                        val frame = withTimeout(2_000) { withContext(Dispatchers.IO) { server.nextFrame() } }
                        val request = Request.parseFrom(frame.payload)
                        assertEquals(command, request.command)
                        assertEquals(4_000L, request.timeoutMs)
                        assertFalse(request.command.scroll.selector.node.resource.hasPackageName())
                        assertFalse(request.command.scroll.hasDistancePercent())

                        val driverResult =
                            Responses
                                .failure(ErrorCode.ERR_NOT_FOUND, detail = "SOME_DETAIL", message = "no row")
                                .stamped(durationMs = 17, requestId = frame.requestId, generation = generation)
                        server.respond(frame.requestId, driverResult)
                        assertEquals(driverResult.result, withTimeout(2_000) { call.await() }.result)

                        // Pre-flight: a malformed command is INVALID_ARGUMENT and never reaches the driver.
                        val invalid =
                            listOf(
                                Commands.tap(Selector.getDefaultInstance()),
                                Command.getDefaultInstance(),
                                Commands.swipe(Nodes.resource("row").toSelector(), Direction.DIR_UNSPECIFIED),
                            )
                        invalid.forEach { bad ->
                            val status = assertFailsWith<io.grpc.StatusException> { stub.execute(execute(bad)) }
                            assertEquals(Status.Code.INVALID_ARGUMENT, status.status.code, bad.toString())
                        }
                        val idAfter = client.submit(Commands.deviceInfo())
                        assertEquals(frame.requestId + 1, idAfter.requestId, "rejected commands must not consume a request ID")
                        val next = withTimeout(2_000) { withContext(Dispatchers.IO) { server.nextFrame() } }
                        assertEquals(idAfter.requestId, next.requestId)
                        server.respond(next.requestId, Responses.done(1))
                        assertTrue(idAfter.await().result.hasDone())
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
    fun `poisoned attached device rejects direct command paths but still detaches`() =
        runBlocking {
            val opener = FakeOpener()
            val daemon = TapDaemon(testConfig(), opener)
            val connection = daemon.connectClient("poison-conn")
            val device = FakeDevice(serial = "poison-serial")
            opener.queue.add(device)
            val session = daemon.attachDevice(connection.id, "poison-serial", testOptions())
            device.poisoned =
                io.github.noamcohen48.tap.host.AdbReapUncertainException(
                    "ADB process or output drain survived bounded reap",
                    listOf("adb", "-s", "poison-serial", "shell", "pidof", "com.test"),
                    "poison-serial",
                )
            assertFailsWith<IllegalStateException> { daemon.attachedDevice(session.id, connection.id) }
            // Cleanup paths do not go through the poison check: the lease still releases, but the
            // detach reports the quarantine instead of clean.
            val detail = daemon.detachDevice(session.id, connection.id)
            assertTrue(detail != null && "bounded reap" in detail, "poisoned detach reported clean: $detail")
            assertEquals(1, device.closeCalls.get())
            assertTrue(daemon.attachedDeviceIds().isEmpty())
        }

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `daemon lookup then poison before submit rejects through the DeviceSession gate`() =
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

                        else -> {
                            null
                        }
                    }
                }
                val processes = mutableListOf<FakeProcess>()
                val sessionConfig =
                    DeviceSessionConfig(
                        serial = serial,
                        journalRoot = tempDir.resolve("sessions"),
                        adb = adb,
                        processStarter =
                            ProcessStarter { command ->
                                val args = command.drop(3)

                                fun option(name: String): String {
                                    val index = args.indexOf(name)
                                    return args[index + 1]
                                }
                                fake.secret =
                                    java.util.Base64
                                        .getUrlDecoder()
                                        .decode(option("tapSecret"))
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
                    object : DeviceSessionOpener {
                        override suspend fun open(config: DeviceSessionConfig): DaemonDeviceSession =
                            RealSessionDevice(DeviceSession.open(sessionConfig))
                    }
                val daemon = TapDaemon(testConfig(), opener)
                val connection = daemon.connectClient("lookup-poison-conn")
                val opening = async(Dispatchers.IO) { daemon.attachDevice(connection.id, serial, testOptions()) }
                fake.respond(withTimeout(5_000) { fake.nextFrame() }.requestId, Responses.done(1))
                val session = withTimeout(5_000) { opening.await() }
                // Barrier, not a delay race: the lookup completes first, then the poison lands
                // before submission — the submit must still reject through the bound gate.
                val captured = daemon.attachedDevice(session.id, connection.id).deviceSession.client
                (session.deviceSession as RealSessionDevice).poison(
                    AdbReapUncertainException(
                        "ADB process or output drain survived bounded reap",
                        listOf("adb", "-s", serial, "shell", "pidof", "com.test"),
                        serial,
                    ),
                )
                assertFailsWith<io.github.noamcohen48.tap.host.DeviceQuarantinedException> {
                    captured.submit(io.github.noamcohen48.tap.protocol.Requests.health())
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
                assertFailsWith<io.github.noamcohen48.tap.host.DeviceQuarantinedException> { daemon.attachedDevice(session.id, connection.id) }
                // Poison never throws from close: cleanup runs, the journal records the quarantine
                // and the lease releases, but the detach reports the quarantine instead of clean.
                val detail = daemon.detachDevice(session.id, connection.id)
                assertTrue(detail != null && "quarantined" in detail, "poisoned detach reported clean: $detail")
                val record =
                    io.github.noamcohen48.tap.host
                        .SessionJournalStore(tempDir.resolve("sessions"), serial)
                        .read()
                assertEquals(io.github.noamcohen48.tap.host.JournalState.QUARANTINED, record?.state)
                assertTrue(daemon.attachedDeviceIds().isEmpty())
            } finally {
                fake.close()
            }
        }
}
