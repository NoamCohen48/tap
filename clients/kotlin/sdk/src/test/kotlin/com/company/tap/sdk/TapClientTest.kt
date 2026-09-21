package com.company.tap.sdk

import com.company.tap.api.v1.AppServiceGrpcKt
import com.company.tap.api.v1.AttachRequest
import com.company.tap.api.v1.CloseConnectionRequest
import com.company.tap.api.v1.CloseConnectionResponse
import com.company.tap.api.v1.CloseSessionRequest
import com.company.tap.api.v1.CloseSessionResponse
import com.company.tap.api.v1.CommandResult
import com.company.tap.api.v1.ConnectionEvent
import com.company.tap.api.v1.ConnectionServiceGrpcKt
import com.company.tap.api.v1.DeviceServiceGrpcKt
import com.company.tap.api.v1.ExecuteRequest
import com.company.tap.api.v1.InfoRequest
import com.company.tap.api.v1.InfoResponse
import com.company.tap.api.v1.ListDevicesRequest
import com.company.tap.api.v1.ListDevicesResponse
import com.company.tap.api.v1.OpenConnectionRequest
import com.company.tap.api.v1.OpenConnectionResponse
import com.company.tap.api.v1.OpenSessionRequest
import com.company.tap.api.v1.OpenSessionResponse
import com.company.tap.api.v1.SessionServiceGrpcKt
import io.grpc.ClientCall
import io.grpc.ManagedChannel
import io.grpc.MethodDescriptor
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * In-process grpc-kotlin fakes for the coroutine client: linearized attach ownership/lifetime,
 * single-flight closes, device admission vs. close, cancellation propagation and the process /
 * channel teardown seams. No real devices involved.
 */
class TapClientTest {
    private lateinit var serverName: String
    private lateinit var fakeConnections: FakeConnections
    private lateinit var fakeSessions: FakeSessions
    private lateinit var fakeDevices: FakeDevices
    private lateinit var fakeApps: FakeApps
    private lateinit var grpcServer: io.grpc.Server
    private lateinit var channel: ManagedChannel

    @BeforeEach
    fun start() {
        serverName = InProcessServerBuilder.generateName()
        fakeConnections = FakeConnections()
        fakeSessions = FakeSessions()
        fakeDevices = FakeDevices()
        fakeApps = FakeApps()
        grpcServer =
            InProcessServerBuilder
                .forName(serverName)
                .directExecutor()
                .addService(fakeConnections)
                .addService(fakeSessions)
                .addService(fakeDevices)
                .addService(fakeApps)
                .build()
                .start()
        channel = InProcessChannelBuilder.forName(serverName).directExecutor().build()
    }

    @AfterEach
    fun stop() {
        TapServiceProcess.processStarter = null
        channel.shutdownNow()
        grpcServer.shutdownNow()
    }

    private fun client() = TapClient("inprocess:$serverName", channel)

    @Test
    fun `connect establishes attach before returning`() =
        runBlocking {
            val connection = client().connect("test")
            try {
                assertTrue(connection.recentEvents.isNotEmpty(), "attach first event before connect returns")
                assertEquals(1, fakeConnections.attaches.get(), "exactly one Attach")
            } finally {
                connection.close()
            }
        }

    @Test
    fun `empty attach fails connect and closes the id`() =
        runBlocking {
            fakeConnections.attachMode = FakeConnections.AttachMode.EMPTY
            val failure =
                assertFailsWith<TapException> {
                    withTimeout(10_000) { client().connect("test") }
                }
            assertTrue(failure.message!!.contains("empty stream"), "unexpected: ${failure.message}")
            assertEquals(1, fakeConnections.attaches.get(), "exactly one Attach even when empty")
            withTimeout(5_000) {
                while (fakeConnections.closes.get() < 1) delay(10)
            }
            assertEquals(listOf("conn-1"), fakeConnections.closeIds.toList())
        }

    @Test
    fun `attach error before first event fails connect and closes the id`() =
        runBlocking {
            fakeConnections.attachMode = FakeConnections.AttachMode.ERROR_BEFORE_FIRST
            fakeConnections.attachError = StatusRuntimeException(Status.UNAVAILABLE.withDescription("attach boom"))
            val failure =
                assertFailsWith<TapException> {
                    withTimeout(10_000) { client().connect("test") }
                }
            assertTrue(
                failure is ServiceException && failure.status == "UNAVAILABLE",
                "setup failure keeps gRPC mapping, got $failure",
            )
            withTimeout(5_000) {
                while (fakeConnections.closes.get() < 1) delay(10)
            }
            assertEquals(1, fakeConnections.attaches.get())
        }

    @Test
    fun `attach normal termination after first event invalidates devices and rejects opens`() =
        runBlocking {
            val connection = client().connect("test")
            val first = tapScope { connection.openDevice("emulator-5554", "com.test") }
            val second = tapScope { connection.openDevice("emulator-5555", "com.test") }
            try {
                // Server ends the parked stream after establishment: unexpected termination.
                fakeConnections.finishParkedAttach()
                withTimeout(5_000) {
                    while (!connection.isInvalid) delay(10)
                }
                // Both handles observed the post-establishment termination.
                val executeBefore = fakeSessions.executeCalls.get()
                tapScope {
                    assertFailsWith<ServiceException> { first.info() }
                    assertFailsWith<ServiceException> { second.screenshot() }
                    assertFailsWith<ServiceException> { first.driverLog() }
                    assertFailsWith<ServiceException> {
                        first.awaitUntil("x", timeout = 100.milliseconds) { true }
                    }
                    assertFailsWith<ServiceException> { first.app().isRunning() }
                }
                assertEquals(executeBefore, fakeSessions.executeCalls.get(), "invalidated ops rejected without RPC")
                assertFailsWith<ServiceException> {
                    tapScope { connection.openDevice("emulator-5556", "com.test") }
                }
                assertEquals(2, fakeSessions.opens.size, "no new session opened after invalidation")
                // Explicit close still sends Close exactly once.
                connection.close()
                assertEquals(1, fakeConnections.closes.get())
            } finally {
                runCatching { connection.close() }
            }
        }

    @Test
    fun `attach error termination after first event invalidates with cause`() =
        runBlocking {
            val connection = client().connect("test")
            val device = tapScope { connection.openDevice("emulator-5554", "com.test") }
            fakeConnections.parkError =
                StatusRuntimeException(Status.UNAVAILABLE.withDescription("stream cut"))
            fakeConnections.finishParkedAttach()
            try {
                withTimeout(5_000) {
                    while (!connection.isInvalid) delay(10)
                }
                val failure =
                    assertFailsWith<ServiceException> {
                        tapScope { device.info() }
                    }
                assertEquals("UNAVAILABLE", failure.status)
                assertTrue(failure.details.contains("liveness"), "unexpected: ${failure.details}")
            } finally {
                runCatching { connection.close() }
            }
        }

    @Test
    fun `close sends Close before dropping attach`() =
        runBlocking {
            val connection = client().connect("test")
            connection.close()
            assertEquals(listOf("close", "attach-cancelled"), fakeConnections.order.toList())
        }

    @Test
    fun `concurrent closes send one Close and share the result`() =
        runBlocking {
            fakeConnections.closeRelease = CompletableDeferred()
            val connection = client().connect("test")
            try {
                val closers = (1..8).map { async(Dispatchers.Default) { connection.close() } }
                withTimeout(5_000) { fakeConnections.closeEntered.await() }
                delay(100)
                assertEquals(1, fakeConnections.closes.get(), "single-flight while the RPC is in flight")
                fakeConnections.closeRelease.complete(Unit)
                withTimeout(5_000) { closers.forEach { it.await() } }
                assertEquals(1, fakeConnections.closes.get())
                // Repeated close reuses the same completion without a new RPC.
                withTimeout(2_000) { connection.close() }
                assertEquals(1, fakeConnections.closes.get())
            } finally {
                fakeConnections.closeRelease.complete(Unit)
                runCatching { connection.close() }
            }
        }

    @Test
    fun `close failure is shared by duplicate callers with mapping preserved`() =
        runBlocking {
            fakeConnections.closeError =
                StatusRuntimeException(Status.DEADLINE_EXCEEDED.withDescription("Timed out waiting for close"))
            val connection = client().connect("test")
            try {
                val first = async(Dispatchers.Default) { runCatching { connection.close() } }
                val second = async(Dispatchers.Default) { runCatching { connection.close() } }
                val results = withTimeout(10_000) { listOf(first.await(), second.await()) }
                results.forEach { result ->
                    assertTrue(result.isFailure, "both callers observe the primary failure")
                    assertIs<WaitTimeoutException>(result.exceptionOrNull())
                }
                assertEquals(1, fakeConnections.closes.get(), "exactly one Close RPC even on failure")
                // A later repeated close rethrows the same primary failure, still one RPC.
                assertFailsWith<WaitTimeoutException> {
                    withTimeout(5_000) { connection.close() }
                }
                assertEquals(1, fakeConnections.closes.get())
            } finally {
                runCatching { connection.close() }
            }
        }

    @Test
    fun `cancelled close still records Close and cleans the scope`() =
        runBlocking {
            fakeConnections.closeRelease = CompletableDeferred()
            val connection = client().connect("test")
            try {
                val job = async { connection.close() }
                withTimeout(5_000) { fakeConnections.closeEntered.await() }
                delay(50)
                job.cancel()
                // Release the server so the NonCancellable RPC can finish despite the cancel.
                fakeConnections.closeRelease.complete(Unit)
                withTimeout(10_000) { job.join() }
                assertTrue(job.isCompleted, "cancelled close still completes teardown")
                assertEquals(1, fakeConnections.closes.get())
                withTimeout(5_000) {
                    while (!fakeConnections.order.contains("attach-cancelled")) delay(10)
                }
            } finally {
                fakeConnections.closeRelease.complete(Unit)
                runCatching { connection.close() }
            }
        }

    @Test
    fun `stubborn attach collector does not hold close past its bound`() =
        runBlocking {
            fakeConnections.attachMode = FakeConnections.AttachMode.STUBBORN
            val connection = client().connect("test")
            try {
                val started = System.nanoTime()
                withTimeout(10_000) { connection.close() }
                val elapsedMs = (System.nanoTime() - started) / 1_000_000
                assertTrue(elapsedMs < 8_000, "bounded teardown despite stubborn collector, took ${elapsedMs}ms")
                assertEquals(1, fakeConnections.closes.get())
            } finally {
                runCatching { withTimeoutOrNull(10_000) { connection.close() } }
            }
        }

    @Test
    fun `dropped attach still lets close run`() =
        runBlocking {
            val connection = client().connect("test")
            // Server ends the stream after the first event; the client marks the connection
            // unusable, but an explicit Close still runs and the scope still cleans up once.
            fakeConnections.finishParkedAttach()
            withTimeout(5_000) {
                while (!connection.isInvalid) delay(10)
            }
            withTimeout(5_000) {
                while (!fakeConnections.order.contains("attach-cancelled")) delay(10)
            }
            connection.close()
            assertTrue(fakeConnections.order.contains("close"))
            assertEquals(1, fakeConnections.order.count { it == "attach-cancelled" })
        }

    @Test
    fun `execute admitted before close finishes before Session Close and later calls rejected`() =
        runBlocking {
            val connection = client().connect("test")
            try {
                tapScope {
                    fakeSessions.hangExecute.complete(Unit)
                    val device = connection.openDevice("emulator-5554", "com.test")
                    try {
                        val running = async { device.info() }
                        withTimeout(5_000) { fakeSessions.enteredExecute.await() }
                        fakeSessions.closeRelease = CompletableDeferred()
                        val closing = async { device.close() }
                        withTimeout(5_000) {
                            while (!device.isClosed) delay(10)
                        }
                        // New operations after close started are rejected locally, no new RPC.
                        val callsBefore = fakeSessions.executeCalls.get()
                        assertFailsWith<TapUsageException> { device.info() }
                        assertFailsWith<TapUsageException> { device.screenshot() }
                        assertEquals(callsBefore, fakeSessions.executeCalls.get())
                        // Let the admitted Execute finish; the session close follows it.
                        fakeSessions.hangExecuteResult.complete(CommandResult.getDefaultInstance())
                        withTimeout(5_000) { running.await() }
                        fakeSessions.closeRelease.complete(Unit)
                        withTimeout(5_000) { closing.await() }
                        assertEquals(
                            listOf("execute-enter", "execute-exit", "session-close"),
                            fakeSessions.order.toList(),
                        )
                        assertEquals(1, fakeSessions.closeCalls.get())
                    } finally {
                        fakeSessions.hangExecuteResult.complete(CommandResult.getDefaultInstance())
                        fakeSessions.closeRelease.complete(Unit)
                        runCatching { device.close() }
                    }
                }
            } finally {
                connection.close()
            }
        }

    @Test
    fun `duplicate device closes share one Session Close including quarantine detail`() =
        runBlocking {
            fakeSessions.quarantineNextClose = "driver would not die"
            fakeSessions.closeRelease = CompletableDeferred()
            val connection = client().connect("test")
            try {
                tapScope {
                    val device = connection.openDevice("emulator-5554", "com.test")
                    val first = async { device.closeAndReport() }
                    val second = async { device.closeAndReport() }
                    withTimeout(5_000) { fakeSessions.closeEntered.await() }
                    delay(100)
                    assertEquals(1, fakeSessions.closeCalls.get(), "single-flight session close")
                    fakeSessions.closeRelease.complete(Unit)
                    assertEquals("driver would not die", withTimeout(5_000) { first.await() })
                    assertEquals("driver would not die", withTimeout(5_000) { second.await() })
                    // close() on a quarantined session reports the same detail as a failure.
                    assertFailsWith<TapException> { device.close() }
                    assertEquals(1, fakeSessions.closeCalls.get())
                }
            } finally {
                fakeSessions.closeRelease.complete(Unit)
                connection.close()
            }
        }

    @Test
    fun `calls after device close fail locally`() =
        runBlocking {
            val connection = client().connect("test")
            try {
                tapScope {
                    val device = connection.openDevice("emulator-5554", "com.test")
                    device.close()
                    val executeBefore = fakeSessions.executeCalls.get()
                    assertFailsWith<TapUsageException> { device.info() }
                    assertFailsWith<TapUsageException> { device.screenshot() }
                    assertFailsWith<TapUsageException> { device.driverLog() }
                    assertFailsWith<TapUsageException> {
                        device.awaitUntil("x", timeout = 100.milliseconds) { true }
                    }
                    assertFailsWith<TapUsageException> { device.pressBack() }
                    assertFailsWith<TapUsageException> { device.app().isRunning() }
                    assertEquals(executeBefore, fakeSessions.executeCalls.get(), "no RPC after close")
                    assertFailsWith<TapUsageException> { device.execute { } }
                }
            } finally {
                connection.close()
            }
        }

    @Test
    fun `cancelling execute cancels the grpc call`() =
        runBlocking {
            val connection = client().connect("test")
            try {
                fakeSessions.hangExecute.complete(Unit)
                tapScope {
                    val device = connection.openDevice("emulator-5554", "com.test")
                    try {
                        val job = async { device.info() }
                        withTimeout(2_000) { fakeSessions.enteredExecute.await() }
                        job.cancelAndJoin()
                        assertTrue(job.isCancelled, "caller cancel cancels the in-flight Execute")
                        withTimeout(2_000) { fakeSessions.cancelledExecute.await() }
                    } finally {
                        // Release the fake server park, then close inside the same scope.
                        fakeSessions.hangExecuteResult.complete(CommandResult.getDefaultInstance())
                        device.close()
                    }
                }
            } finally {
                connection.close()
            }
        }

    @Test
    fun `grpc sibling failure cancels the other in-flight execute`() =
        runBlocking {
            val connection = client().connect("test")
            try {
                fakeSessions.hangExecute.complete(Unit)
                tapScope {
                    val device = connection.openDevice("emulator-5554", "com.test")
                    try {
                        val failure =
                            assertFailsWith<AssertionError> {
                                kotlinx.coroutines.coroutineScope {
                                    val waiter =
                                        async {
                                            device.info()
                                        }
                                    val boom =
                                        async {
                                            withTimeout(2_000) { fakeSessions.enteredExecute.await() }
                                            delay(50)
                                            throw AssertionError("sibling boom")
                                        }
                                    boom.await()
                                    waiter.await()
                                }
                            }
                        assertEquals("sibling boom", failure.message)
                        // The real gRPC server observed the sibling cancellation, not just a
                        // local awaitCancellation: the in-flight Execute was interrupted.
                        withTimeout(5_000) { fakeSessions.cancelledExecute.await() }
                    } finally {
                        fakeSessions.hangExecuteResult.complete(CommandResult.getDefaultInstance())
                        device.close()
                    }
                }
            } finally {
                connection.close()
            }
        }

    @Test
    fun `sibling failure cancels the other device poll`() =
        runBlocking {
            val connection = client().connect("test")
            // Fresh flag per test (field is reused across tests in this class).
            val flag = CompletableDeferred<Unit>()
            try {
                val started = System.nanoTime()
                val failure =
                    assertFailsWith<AssertionError> {
                        tapScope {
                            // Deterministic structured-concurrency shape: one child parks in a
                            // cancellable wait (the stand-in for an in-flight device wait),
                            // the other fails fast; the scope must cancel the waiter.
                            kotlinx.coroutines.coroutineScope {
                                val waiter =
                                    async {
                                        try {
                                            awaitCancellation()
                                        } catch (cancelled: CancellationException) {
                                            flag.complete(Unit)
                                            throw cancelled
                                        }
                                    }
                                val boom =
                                    async {
                                        delay(50)
                                        throw AssertionError("sibling boom")
                                    }
                                boom.await()
                                waiter.await()
                            }
                        }
                    }
                assertEquals("sibling boom", failure.message)
                withTimeout(2_000) { flag.await() }
                val elapsedMs = (System.nanoTime() - started) / 1_000_000
                assertTrue(elapsedMs < 10_000, "cancelled promptly after sibling failure, took ${elapsedMs}ms")
            } finally {
                connection.close()
            }
        }

    @Test
    fun `device calls outside scope fail clearly`() =
        runBlocking {
            val connection = client().connect("test")
            try {
                val device = tapScope { connection.openDevice("emulator-5554", "com.test") }
                // No TapContext here: direct call must fail, not hang.
                assertFailsWith<TapUsageException> { device.info() }
                // An independently owned scope does not inherit the Tap scope either.
                val detached = CoroutineScope(Dispatchers.Default)
                try {
                    assertFailsWith<TapUsageException> {
                        withTimeout(2_000) { detached.async { device.info() }.await() }
                    }
                } finally {
                    detached.cancel()
                }
                tapScope { device.close() }
            } finally {
                connection.close()
            }
        }

    @Test
    fun `tapScope nesting fails`() =
        runBlocking {
            assertFailsWith<TapUsageException> {
                tapScope {
                    tapScope { }
                }
            }
        }

    @Test
    fun `awaitUntil is delay based and cancellable`() =
        runBlocking {
            val connection = client().connect("test")
            try {
                val device = tapScope { connection.openDevice("emulator-5554", "com.test") }
                try {
                    val started = System.nanoTime()
                    val job =
                        CoroutineScope(kotlinx.coroutines.currentCoroutineContext()).async {
                            // Must run inside tapScope to pass the bound check; inherit via child.
                            withContext(TapContext("test")) {
                                device.awaitUntil("never", timeout = 30.seconds, pollInterval = 50.milliseconds) { false }
                            }
                        }
                    delay(150)
                    job.cancelAndJoin()
                    assertTrue(job.isCancelled)
                    val elapsedMs = (System.nanoTime() - started) / 1_000_000
                    assertTrue(elapsedMs < 5_000, "poll cancelled promptly, took ${elapsedMs}ms")
                } finally {
                    tapScope { device.close() }
                }
            } finally {
                connection.close()
            }
        }

    @Test
    fun `quarantined close reports detail`() =
        runBlocking {
            fakeSessions.quarantineNextClose = "driver would not die"
            val connection = client().connect("test")
            try {
                val device = tapScope { connection.openDevice("emulator-5554", "com.test") }
                val detail = tapScope { device.closeAndReport() }
                assertEquals("driver would not die", detail)
            } finally {
                connection.close()
            }
        }

    @Test
    fun `session close deadline mapping is preserved`() =
        runBlocking {
            fakeSessions.closeError =
                StatusRuntimeException(Status.DEADLINE_EXCEEDED.withDescription("Timed out waiting for close"))
            val connection = client().connect("test")
            try {
                tapScope {
                    val device = connection.openDevice("emulator-5554", "com.test")
                    assertFailsWith<WaitTimeoutException> { device.closeAndReport() }
                    assertEquals(1, fakeSessions.closeCalls.get())
                }
            } finally {
                connection.close()
            }
        }

    @Test
    fun `tap client close forces termination with a second bounded await`() =
        runBlocking {
            val probe = TestChannel(firstAwaitResult = false, secondAwaitResult = true)
            val probeClient = TapClient("test", probe)
            withTimeout(10_000) { probeClient.close() }
            assertEquals(1, probe.shutdowns.get(), "shutdown called once")
            assertEquals(1, probe.shutdownNows.get(), "forced shutdownNow after the first await timed out")
            assertEquals(2, probe.awaits.get(), "second bounded await after shutdownNow")
        }

    @Test
    fun `tap client close completes even when the caller is cancelled`() =
        runBlocking {
            val probe = TestChannel(firstAwaitResult = true)
            val probeClient = TapClient("test", probe)
            // Slow the first await so there is a window to cancel the caller; the NonCancellable
            // close must still finish the shutdown sequence.
            probe.blockFirstAwaitMs = 300
            val job = async { probeClient.close() }
            delay(50)
            job.cancel()
            withTimeout(10_000) { job.join() }
            assertTrue(job.isCompleted)
            assertEquals(1, probe.shutdowns.get())
        }

    @Test
    fun `service process cancellation destroys a long-lived executable promptly`() =
        runBlocking {
            val fake = FakeProcess("started 127.0.0.1:9999\n", alive = true)
            TapServiceProcess.processStarter = { fake }
            try {
                val started = System.nanoTime()
                val job = async(Dispatchers.IO) { TapServiceProcess.start(binary = "fake", timeout = 30.seconds) }
                delay(200)
                job.cancelAndJoin()
                assertTrue(job.isCancelled, "cancelling start cancels the process wait promptly")
                val elapsedMs = (System.nanoTime() - started) / 1_000_000
                assertTrue(elapsedMs < 5_000, "prompt cancellation, took ${elapsedMs}ms")
                assertTrue(fake.destroyed.get(), "long-lived process destroyed on cancellation")
            } finally {
                TapServiceProcess.processStarter = null
            }
        }

    @Test
    fun `service process death returns parsed output`() =
        runBlocking {
            TapServiceProcess.processStarter = { FakeProcess("started 127.0.0.1:1234\n", alive = false) }
            try {
                val result =
                    withTimeout(10_000) {
                        TapServiceProcess.start(binary = "fake", timeout = 5.seconds)
                    }
                assertEquals("127.0.0.1:1234", result.address)
                assertTrue(result.started)
            } finally {
                TapServiceProcess.processStarter = null
            }
        }

    @Test
    fun `service process timeout destroys and reports boundedly`() =
        runBlocking {
            val fake = FakeProcess("", alive = true)
            TapServiceProcess.processStarter = { fake }
            try {
                val failure =
                    assertFailsWith<TapException> {
                        withTimeout(10_000) {
                            TapServiceProcess.start(binary = "fake", timeout = 200.milliseconds)
                        }
                    }
                assertTrue(failure.message!!.contains("did not finish"), "unexpected: ${failure.message}")
                assertTrue(fake.destroyed.get(), "timed-out process destroyed and reaped")
            } finally {
                TapServiceProcess.processStarter = null
            }
        }

    // --- Fakes ----------------------------------------------------------------------------------

    private class FakeConnections : ConnectionServiceGrpcKt.ConnectionServiceCoroutineImplBase() {
        enum class AttachMode {
            HELLO_THEN_PARK,
            EMPTY,
            ERROR_BEFORE_FIRST,
            HELLO_THEN_ERROR,
            HELLO_THEN_COMPLETE,
            STUBBORN,
        }

        val attaches = AtomicInteger(0)
        val closes = AtomicInteger(0)
        val closeIds = CopyOnWriteArrayList<String>()
        val order = CopyOnWriteArrayList<String>()
        var attachMode: AttachMode = AttachMode.HELLO_THEN_PARK
        var attachError: Throwable = StatusRuntimeException(Status.UNAVAILABLE.withDescription("attach boom"))
        var closeError: Throwable? = null
        var closeEntered = CompletableDeferred<Unit>()
        var closeRelease: CompletableDeferred<Unit> = CompletableDeferred<Unit>().also { it.complete(Unit) }
        val parkCompletions = CompletableDeferred<Unit>()
        @Volatile var parkError: Throwable? = null

        fun finishParkedAttach() {
            parkCompletions.complete(Unit)
        }

        /** Backwards-compatible alias for the pre-repair test shape (parked attach). */
        val failAttachCompletions: CompletableDeferred<Unit> get() = parkCompletions

        override suspend fun open(request: OpenConnectionRequest): OpenConnectionResponse =
            OpenConnectionResponse.newBuilder().setConnectionId("conn-1").build()

        override fun attach(request: AttachRequest): Flow<ConnectionEvent> =
            flow {
                attaches.incrementAndGet()
                when (attachMode) {
                    AttachMode.EMPTY -> {
                        // Complete without emitting: connect must fail and close the id.
                    }

                    AttachMode.ERROR_BEFORE_FIRST -> {
                        throw attachError
                    }

                    AttachMode.HELLO_THEN_PARK -> {
                        emit(ConnectionEvent.newBuilder().setMessage("hello").build())
                        try {
                            while (!parkCompletions.isCompleted) {
                                delay(10)
                            }
                            parkError?.let { throw it }
                            emit(ConnectionEvent.newBuilder().setMessage("bye").build())
                        } finally {
                            order.add("attach-cancelled")
                        }
                    }

                    AttachMode.HELLO_THEN_ERROR -> {
                        emit(ConnectionEvent.newBuilder().setMessage("hello").build())
                        try {
                            delay(20)
                            throw attachError
                        } finally {
                            order.add("attach-cancelled")
                        }
                    }

                    AttachMode.HELLO_THEN_COMPLETE -> {
                        emit(ConnectionEvent.newBuilder().setMessage("hello").build())
                        try {
                            delay(20)
                        } finally {
                            order.add("attach-cancelled")
                        }
                    }

                    AttachMode.STUBBORN -> {
                        emit(ConnectionEvent.newBuilder().setMessage("hello").build())
                        try {
                            awaitCancellation()
                        } finally {
                            // Ignore cancellation for 10 s: the client's bounded teardown must win.
                            withContext(NonCancellable) { delay(10_000) }
                            order.add("attach-cancelled")
                        }
                    }
                }
            }

        override suspend fun close(request: CloseConnectionRequest): CloseConnectionResponse {
            closes.incrementAndGet()
            closeIds.add(request.connectionId)
            if (!closeEntered.isCompleted) closeEntered.complete(Unit)
            withTimeout(30_000) { closeRelease.await() }
            closeError?.let { throw it }
            order.add("close")
            return CloseConnectionResponse.getDefaultInstance()
        }

        override suspend fun info(request: InfoRequest): InfoResponse = InfoResponse.getDefaultInstance()
    }

    private class FakeSessions : SessionServiceGrpcKt.SessionServiceCoroutineImplBase() {
        val enteredExecute = CompletableDeferred<Unit>()
        val cancelledExecute = CompletableDeferred<Unit>()
        val hangExecute = CompletableDeferred<Unit>()
        val hangExecuteResult = CompletableDeferred<CommandResult>()
        var quarantineNextClose: String? = null
        var closeError: Throwable? = null
        var closeEntered = CompletableDeferred<Unit>()
        var closeRelease: CompletableDeferred<Unit> = CompletableDeferred<Unit>().also { it.complete(Unit) }
        val opens = CopyOnWriteArrayList<String>()
        val closes = CopyOnWriteArrayList<String>()
        val order = CopyOnWriteArrayList<String>()
        val executeCalls = AtomicInteger(0)
        val closeCalls = AtomicInteger(0)

        override suspend fun open(request: OpenSessionRequest): OpenSessionResponse {
            opens.add(request.serial)
            return OpenSessionResponse
                .newBuilder()
                .setSessionId("sess-${request.serial}")
                .setSerial(request.serial)
                .setGeneration(1)
                .build()
        }

        override suspend fun execute(request: ExecuteRequest): CommandResult {
            executeCalls.incrementAndGet()
            if (hangExecute.isCompleted) {
                if (!enteredExecute.isCompleted) enteredExecute.complete(Unit)
                order.add("execute-enter")
                try {
                    // Park until the test releases; client cancel must interrupt this.
                    withTimeout(30_000) { hangExecuteResult.await() }
                    order.add("execute-exit")
                    return hangExecuteResult.await()
                } catch (cancelled: CancellationException) {
                    if (!cancelledExecute.isCompleted) cancelledExecute.complete(Unit)
                    order.add("execute-cancelled")
                    throw cancelled
                }
            }
            return CommandResult.getDefaultInstance()
        }

        override suspend fun close(request: CloseSessionRequest): CloseSessionResponse {
            closeCalls.incrementAndGet()
            closes.add(request.sessionId)
            if (!closeEntered.isCompleted) closeEntered.complete(Unit)
            withTimeout(30_000) { closeRelease.await() }
            closeError?.let { throw it }
            order.add("session-close")
            val detail = quarantineNextClose?.also { quarantineNextClose = null }
            return CloseSessionResponse
                .newBuilder()
                .setClean(detail == null)
                .apply { detail?.let { setDetail(it) } }
                .build()
        }

        override suspend fun screenshot(request: com.company.tap.api.v1.ScreenshotRequest): com.company.tap.api.v1.ScreenshotResponse =
            com.company.tap.api.v1.ScreenshotResponse
                .getDefaultInstance()

        override suspend fun driverLog(request: com.company.tap.api.v1.DriverLogRequest): com.company.tap.api.v1.DriverLogResponse =
            com.company.tap.api.v1.DriverLogResponse
                .getDefaultInstance()
    }

    private class FakeDevices : DeviceServiceGrpcKt.DeviceServiceCoroutineImplBase() {
        override suspend fun listDevices(request: ListDevicesRequest): ListDevicesResponse = ListDevicesResponse.getDefaultInstance()
    }

    private class FakeApps : AppServiceGrpcKt.AppServiceCoroutineImplBase() {
        override suspend fun isRunning(request: com.company.tap.api.v1.AppRequest): com.company.tap.api.v1.AppBool =
            com.company.tap.api.v1.AppBool.getDefaultInstance()
    }

    private class TestChannel(
        val firstAwaitResult: Boolean = false,
        val secondAwaitResult: Boolean = true,
    ) : ManagedChannel() {
        val shutdowns = AtomicInteger(0)
        val shutdownNows = AtomicInteger(0)
        val awaits = AtomicInteger(0)
        var blockFirstAwaitMs: Long = 0

        override fun shutdown(): ManagedChannel {
            shutdowns.incrementAndGet()
            return this
        }

        override fun shutdownNow(): ManagedChannel {
            shutdownNows.incrementAndGet()
            return this
        }

        override fun isShutdown(): Boolean = shutdowns.get() > 0

        override fun isTerminated(): Boolean = false

        override fun awaitTermination(
            timeout: Long,
            unit: TimeUnit,
        ): Boolean {
            val call = awaits.incrementAndGet()
            if (call == 1 && blockFirstAwaitMs > 0) Thread.sleep(blockFirstAwaitMs)
            return if (call == 1) firstAwaitResult else secondAwaitResult
        }

        override fun <RequestT : Any, ResponseT : Any> newCall(
            methodDescriptor: MethodDescriptor<RequestT, ResponseT>,
            callOptions: io.grpc.CallOptions,
        ): ClientCall<RequestT, ResponseT> = throw UnsupportedOperationException("test channel")

        override fun authority(): String = "test"
    }

    private class FakeProcess(
        output: String,
        @Volatile var alive: Boolean,
        private val exitCode: Int = 0,
    ) : Process() {
        val destroyed =
            java.util.concurrent.atomic
                .AtomicBoolean(false)
        private val inputBytes = output.toByteArray()
        private val outputSink = java.io.ByteArrayOutputStream()

        override fun getOutputStream(): java.io.OutputStream = outputSink

        override fun getInputStream(): java.io.InputStream = java.io.ByteArrayInputStream(inputBytes)

        override fun getErrorStream(): java.io.InputStream = java.io.ByteArrayInputStream(ByteArray(0))

        override fun waitFor(): Int = throw UnsupportedOperationException("fake")

        override fun waitFor(
            timeout: Long,
            unit: TimeUnit,
        ): Boolean = throw UnsupportedOperationException("fake")

        override fun exitValue(): Int {
            if (alive) throw IllegalThreadStateException("process has not exited")
            return exitCode
        }

        override fun destroy() {
            destroyed.set(true)
            alive = false
        }

        override fun destroyForcibly(): Process {
            destroyed.set(true)
            alive = false
            return this
        }
    }
}
