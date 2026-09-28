package io.github.noamcohen48.tap.sdk

import io.github.noamcohen48.tap.api.v1.AppServiceGrpcKt
import io.github.noamcohen48.tap.api.v1.AttachRequest
import io.github.noamcohen48.tap.api.v1.AttachResponse
import io.github.noamcohen48.tap.api.v1.ClientConnectionServiceGrpcKt
import io.github.noamcohen48.tap.api.v1.Heartbeat
import io.github.noamcohen48.tap.api.v1.Observing
import io.github.noamcohen48.tap.api.v1.CommandResult
import io.github.noamcohen48.tap.api.v1.ConnectRequest
import io.github.noamcohen48.tap.api.v1.ConnectResponse
import io.github.noamcohen48.tap.api.v1.DetachRequest
import io.github.noamcohen48.tap.api.v1.DetachResponse
import io.github.noamcohen48.tap.api.v1.Direction
import io.github.noamcohen48.tap.api.v1.DeviceServiceGrpcKt
import io.github.noamcohen48.tap.api.v1.DisconnectRequest
import io.github.noamcohen48.tap.api.v1.DisconnectResponse
import io.github.noamcohen48.tap.api.v1.Done
import io.github.noamcohen48.tap.api.v1.Command
import io.github.noamcohen48.tap.api.v1.ElementSnapshot
import io.github.noamcohen48.tap.api.v1.ErrorCode
import io.github.noamcohen48.tap.api.v1.ExecuteRequest
import io.github.noamcohen48.tap.api.v1.ExecuteResponse
import io.github.noamcohen48.tap.api.v1.FailureReason
import io.github.noamcohen48.tap.api.v1.InfoRequest
import io.github.noamcohen48.tap.api.v1.InfoResponse
import io.github.noamcohen48.tap.api.v1.ListDevicesRequest
import io.github.noamcohen48.tap.api.v1.ListDevicesResponse
import io.github.noamcohen48.tap.api.v1.ObserveRequest
import io.github.noamcohen48.tap.api.v1.ObserveResponse
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
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * In-process grpc-kotlin fakes for the coroutine client: linearized Observe ownership/lifetime,
 * single-flight closes, device admission vs. close, cancellation propagation and the process /
 * channel teardown seams. No real devices involved.
 */
class TapClientTest {
    private lateinit var serverName: String
    private lateinit var fakeConnections: FakeConnections
    private lateinit var fakeDevices: FakeDevices
    private lateinit var fakeApps: FakeApps
    private lateinit var grpcServer: io.grpc.Server
    private lateinit var channel: ManagedChannel

    @BeforeEach
    fun start() {
        serverName = InProcessServerBuilder.generateName()
        fakeConnections = FakeConnections()
        fakeDevices = FakeDevices()
        fakeApps = FakeApps()
        grpcServer =
            InProcessServerBuilder
                .forName(serverName)
                .directExecutor()
                .addService(fakeConnections)
                .addService(fakeDevices)
                .addService(fakeApps)
                .build()
                .start()
        channel = InProcessChannelBuilder.forName(serverName).directExecutor().build()
    }

    @AfterEach
    fun stop() {
        channel.shutdownNow()
        grpcServer.shutdownNow()
    }

    private fun client() = TapClient("inprocess:$serverName", channel)

    @Test
    fun `connect establishes attach before returning`() {
        runBlocking {
            val connection = client().connect("test")
            try {
                assertTrue(connection.recentEvents.isNotEmpty(), "attach first event before connect returns")
                assertEquals(1, fakeConnections.observes.get(), "exactly one Observe")
            } finally {
                connection.close()
            }
        }
    }

    @Test
    fun `empty attach fails connect and closes the id`() {
        runBlocking {
            fakeConnections.observeMode = FakeConnections.ObserveMode.EMPTY
            val failure =
                assertFailsWith<TapException> {
                    withTimeout(10_000) { client().connect("test") }
                }
            assertTrue(failure.message!!.contains("empty stream"), "unexpected: ${failure.message}")
            assertEquals(1, fakeConnections.observes.get(), "exactly one Observe even when empty")
            withTimeout(5_000) {
                while (fakeConnections.closes.get() < 1) delay(10)
            }
            assertEquals(listOf("conn-1"), fakeConnections.closeIds.toList())
        }
    }

    @Test
    fun `observe error before first event fails connect and disconnects the id`() {
        runBlocking {
            fakeConnections.observeMode = FakeConnections.ObserveMode.ERROR_BEFORE_FIRST
            fakeConnections.observeError = StatusRuntimeException(Status.UNAVAILABLE.withDescription("attach boom"))
            val failure =
                assertFailsWith<TapException> {
                    withTimeout(10_000) { client().connect("test") }
                }
            assertTrue(
                failure is ServerException && failure.status == "UNAVAILABLE",
                "setup failure keeps gRPC mapping, got $failure",
            )
            withTimeout(5_000) {
                while (fakeConnections.closes.get() < 1) delay(10)
            }
            assertEquals(1, fakeConnections.observes.get())
        }
    }

    @Test
    fun `attach normal termination after first event invalidates devices and rejects opens`() {
        runBlocking {
            val connection = client().connect("test")
            val first = tapScope { connection.attachDevice("emulator-5554", "com.test") }
            val second = tapScope { connection.attachDevice("emulator-5555", "com.test") }
            assertEquals(2, connection.liveDeviceCount, "registry retains live handles")
            try {
                // Gate the AttachDevice response so the racing registration is provably in
                // flight when the terminal invalidation lands (deterministic, no sleep). The
                // racing call either fails at the pre-RPC gate or returns only as an
                // invalidated handle; the registry keeps the handle until close either way.
                fakeDevices.openRelease = CompletableDeferred()
                fakeDevices.openEntered = CompletableDeferred()
                val racing =
                    async {
                        runCatching { tapScope { connection.attachDevice("emulator-5559", "com.test") } }
                    }
                withTimeout(5_000) { fakeDevices.openEntered.await() }
                // Server ends the parked stream after establishment: unexpected termination.
                fakeConnections.finishParkedObserve()
                withTimeout(5_000) {
                    while (!connection.isInvalid) delay(10)
                }
                // Release the gated AttachDevice into the terminal state.
                fakeDevices.openRelease.complete(Unit)
                // Both handles observed the post-establishment termination.
                val executeBefore = fakeDevices.executeCalls.get()
                tapScope {
                    assertFailsWith<ServerException> { first.info() }
                    assertFailsWith<ServerException> { second.screenshot() }
                    assertFailsWith<ServerException> { first.driverLog() }
                    assertFailsWith<ServerException> {
                        first.awaitUntil("x", timeout = 100.milliseconds) { true }
                    }
                    assertFailsWith<ServerException> { first.app().isRunning() }
                }
                assertEquals(executeBefore, fakeDevices.executeCalls.get(), "invalidated ops rejected without RPC")
                // The racing registration resolved during the terminal drop: either rejected at
                // the gate or returned only as an invalidated handle.
                val raced = withTimeout(5_000) { racing.await() }
                raced.onSuccess { handle ->
                    tapScope { assertFailsWith<ServerException> { handle.info() } }
                    tapScope { runCatching { handle.detach() } }
                }
                val opensAfterRace = fakeDevices.opens.size
                assertFailsWith<ServerException> {
                    tapScope { connection.attachDevice("emulator-5556", "com.test") }
                }
                assertEquals(opensAfterRace, fakeDevices.opens.size, "no new session opened after invalidation")
                // Closing invalidated handles unregisters them; the registry returns to zero.
                tapScope {
                    runCatching { first.detach() }
                    runCatching { second.detach() }
                }
                withTimeout(5_000) {
                    while (connection.liveDeviceCount != 0) delay(10)
                }
                assertEquals(0, connection.liveDeviceCount, "closed handles leave the registry")
                // Explicit close still sends Disconnect exactly once.
                connection.close()
                assertEquals(1, fakeConnections.closes.get())
            } finally {
                runCatching { connection.close() }
            }
        }
    }

    @Test
    fun `observe error termination after first event invalidates with cause`() {
        runBlocking {
            val connection = client().connect("test")
            val device = tapScope { connection.attachDevice("emulator-5554", "com.test") }
            fakeConnections.parkError =
                StatusRuntimeException(Status.UNAVAILABLE.withDescription("stream cut"))
            fakeConnections.finishParkedObserve()
            try {
                withTimeout(5_000) {
                    while (!connection.isInvalid) delay(10)
                }
                val failure =
                    assertFailsWith<ServerException> {
                        tapScope { device.info() }
                    }
                assertEquals("UNAVAILABLE", failure.status)
                assertTrue(failure.details.contains("liveness"), "unexpected: ${failure.details}")
            } finally {
                runCatching { connection.close() }
            }
        }
    }

    @Test
    fun `close sends Disconnect before dropping observe`() {
        runBlocking {
            val connection = client().connect("test")
            connection.close()
            assertEquals(listOf("disconnect", "observe-cancelled"), fakeConnections.order.toList())
        }
    }

    @Test
    fun `concurrent closes send one Close and share the result`() {
        runBlocking {
            fakeConnections.closeRelease = CompletableDeferred()
            val connection = client().connect("test")
            try {
                val joined = (1..8).map { CompletableDeferred<Unit>() }
                val closers =
                    (1..8).mapIndexed { index, _ ->
                        async(Dispatchers.Default) {
                            joined[index].complete(Unit)
                            connection.close()
                        }
                    }
                withTimeout(5_000) { joined.forEach { it.await() } }
                withTimeout(5_000) { fakeConnections.closeEntered.await() }
                // Deterministic shared-flight proof via the read-only duplicate seam: every
                // duplicate observed the shared closeDeferred before the RPC is released.
                withTimeout(5_000) {
                    while (connection.duplicateCloseCount < 7) delay(10)
                }
                assertEquals(1, fakeConnections.closes.get(), "single-flight while the RPC is in flight")
                // Every duplicate caller joined before the release and none returns early
                // while the single Disconnect RPC is parked.
                assertTrue(closers.none { it.isCompleted }, "duplicate closers joined the shared flight; none returns early")
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
    }

    @Test
    fun `close failure is shared by duplicate callers with mapping preserved`() {
        runBlocking {
            fakeConnections.closeError =
                daemonFailure(Status.DEADLINE_EXCEEDED, FailureReason.FAILURE_REASON_HOST_WAIT_TIMEOUT, "Timed out waiting for close")
            val connection = client().connect("test")
            try {
                val first = async(Dispatchers.Default) { runCatching { connection.close() } }
                val second = async(Dispatchers.Default) { runCatching { connection.close() } }
                val results = withTimeout(10_000) { listOf(first.await(), second.await()) }
                results.forEach { result ->
                    assertTrue(result.isFailure, "both callers observe the primary failure")
                    assertIs<WaitTimeoutException>(result.exceptionOrNull())
                }
                assertEquals(1, fakeConnections.closes.get(), "exactly one Disconnect RPC even on failure")
                // A later repeated close rethrows the same primary failure, still one RPC.
                assertFailsWith<WaitTimeoutException> {
                    withTimeout(5_000) { connection.close() }
                }
                assertEquals(1, fakeConnections.closes.get())
            } finally {
                runCatching { connection.close() }
            }
        }
    }

    @Test
    fun `cancelled close still records Close and cleans the scope`() {
        runBlocking {
            fakeConnections.closeRelease = CompletableDeferred()
            val connection = client().connect("test")
            try {
                val job = async { connection.close() }
                withTimeout(5_000) { fakeConnections.closeEntered.await() }
                job.cancel()
                // Release the server so the NonCancellable RPC can finish despite the cancel.
                fakeConnections.closeRelease.complete(Unit)
                withTimeout(10_000) { job.join() }
                assertTrue(job.isCompleted, "cancelled close still completes teardown")
                assertEquals(1, fakeConnections.closes.get())
                withTimeout(5_000) {
                    while (!fakeConnections.order.contains("observe-cancelled")) delay(10)
                }
            } finally {
                fakeConnections.closeRelease.complete(Unit)
                runCatching { connection.close() }
            }
        }
    }

    @Test
    fun `stubborn observe collector does not hold close past its bound`() {
        runBlocking {
            fakeConnections.observeMode = FakeConnections.ObserveMode.STUBBORN
            // Isolated per-instance bound (no shared mutation): safe under parallel tests.
            val connection = ClientConnection(client(), "conn-stubborn", ClientConnectionBounds(teardownMs = 300))
            connection.observe()
            try {
                val started = System.nanoTime()
                withTimeout(10_000) { connection.close() }
                val elapsedMs = (System.nanoTime() - started) / 1_000_000
                assertTrue(elapsedMs < 5_000, "bounded teardown despite stubborn collector, took ${elapsedMs}ms")
                assertEquals(1, fakeConnections.closes.get())
            } finally {
                runCatching { withTimeoutOrNull(10_000) { connection.close() } }
            }
        }
    }

    @Test
    fun `dropped observation still lets close run`() {
        runBlocking {
            val connection = client().connect("test")
            // Server ends the stream after the first event; the client marks the connection
            // unusable, but an explicit Close still runs and the scope still cleans up once.
            fakeConnections.finishParkedObserve()
            withTimeout(5_000) {
                while (!connection.isInvalid) delay(10)
            }
            withTimeout(5_000) {
                while (!fakeConnections.order.contains("observe-cancelled")) delay(10)
            }
            connection.close()
            assertTrue(fakeConnections.order.contains("disconnect"))
            assertEquals(1, fakeConnections.order.count { it == "observe-cancelled" })
        }
    }

    @Test
    fun `execute admitted before detach finishes before DetachDevice and later calls rejected`() {
        runBlocking {
            val connection = client().connect("test")
            try {
                tapScope {
                    fakeDevices.hangExecute.complete(Unit)
                    val device = connection.attachDevice("emulator-5554", "com.test")
                    try {
                        val running = async { device.info() }
                        withTimeout(5_000) { fakeDevices.enteredExecute.await() }
                        fakeDevices.closeRelease = CompletableDeferred()
                        val closing = async { device.detach() }
                        withTimeout(5_000) {
                            while (!device.isDetached) delay(10)
                        }
                        // New operations after close started are rejected locally, no new RPC.
                        val callsBefore = fakeDevices.executeCalls.get()
                        assertFailsWith<TapUsageException> { device.info() }
                        assertFailsWith<TapUsageException> { device.screenshot() }
                        assertEquals(callsBefore, fakeDevices.executeCalls.get())
                        // Let the admitted Execute finish; the device detach follows it.
                        fakeDevices.hangExecuteResult.complete(CommandResult.getDefaultInstance())
                        withTimeout(5_000) { running.await() }
                        fakeDevices.closeRelease.complete(Unit)
                        withTimeout(5_000) { closing.await() }
                        assertEquals(
                            listOf("execute-enter", "execute-exit", "device-detach"),
                            fakeDevices.order.toList(),
                        )
                        assertEquals(1, fakeDevices.closeCalls.get())
                    } finally {
                        fakeDevices.hangExecuteResult.complete(CommandResult.getDefaultInstance())
                        fakeDevices.closeRelease.complete(Unit)
                        runCatching { device.detach() }
                    }
                }
            } finally {
                connection.close()
            }
        }
    }

    @Test
    fun `device drain timeout detaches only that device and keeps the connection`() {
        runBlocking {
            val connection = ClientConnection(client(), "conn-failclosed", ClientConnectionBounds(), DeviceBounds(drainMs = 300))
            connection.observe()
            try {
                tapScope {
                    val first = connection.attachDevice("emulator-5554", "com.test")
                    val second = connection.attachDevice("emulator-5555", "com.test")
                    assertEquals(2, connection.liveDeviceCount)
                    val firstPoll = CompletableDeferred<Unit>()
                    // Permanently parked admitted operation: never released to unblock close.
                    val parked =
                        async {
                            first.awaitUntil("parked", timeout = 30.seconds) {
                                if (!firstPoll.isCompleted) firstPoll.complete(Unit)
                                false
                            }
                        }
                    withTimeout(5_000) { firstPoll.await() }
                    // Duplicate closes share the same drain wait and the same terminal failure.
                    val closer1 = async { runCatching { first.detachAndReport() } }
                    val closer2 = async { runCatching { first.detachAndReport() } }
                    val firstFailure = withTimeout(10_000) { closer1.await() }.exceptionOrNull()
                    val secondFailure = withTimeout(10_000) { closer2.await() }.exceptionOrNull()
                    assertIs<ServerException>(firstFailure)
                    assertEquals("DEADLINE_EXCEEDED", firstFailure.status)
                    assertTrue(firstFailure.details.contains("drain timed out"), "unexpected: ${firstFailure.details}")
                    assertTrue(firstFailure.details.contains("fail-closed"), "unexpected: ${firstFailure.details}")
                    assertSame(firstFailure, secondFailure, "duplicate Device closes share the same timeout instance")
                    // Exactly one best-effort DetachDevice, for this device only; no Disconnect.
                    withTimeout(5_000) {
                        while (first.failClosedHandle?.isCompleted != true) delay(10)
                    }
                    assertEquals(listOf("attached-emulator-5554"), fakeDevices.closes.toList())
                    assertEquals(0, fakeConnections.closes.get(), "the shared connection is not disconnected")
                    assertFalse(connection.isInvalid, "the shared connection stays usable")
                    assertEquals(1, connection.liveDeviceCount, "only the poisoned handle is unregistered")
                    withTimeout(5_000) {
                        while (!first.cleanupTerminated) delay(10)
                    }
                    // The poisoned handle rejects locally; the sibling and new attaches still work.
                    assertFailsWith<TapUsageException> { first.info() }
                    second.info()
                    val third = connection.attachDevice("emulator-5556", "com.test")
                    // A later duplicate still shares the same terminal failure, no new RPC.
                    val late = runCatching { withTimeout(5_000) { first.detachAndReport() } }.exceptionOrNull()
                    assertSame(firstFailure, late, "late duplicate shares the same terminal failure")
                    assertEquals(1, fakeDevices.closeCalls.get())
                    parked.cancelAndJoin()
                    second.detach()
                    third.detach()
                }
            } finally {
                runCatching { connection.close() }
            }
        }
    }

    @Test
    fun `fail-closed DetachDevice failure is suppressed without stranding`() {
        runBlocking {
            fakeDevices.closeError = StatusRuntimeException(Status.UNAVAILABLE.withDescription("detach boom"))
            val connection = ClientConnection(client(), "conn-failclosed-err", ClientConnectionBounds(), DeviceBounds(drainMs = 300))
            connection.observe()
            try {
                tapScope {
                    val device = connection.attachDevice("emulator-5554", "com.test")
                    val firstPoll = CompletableDeferred<Unit>()
                    val parked =
                        async {
                            device.awaitUntil("parked", timeout = 30.seconds) {
                                if (!firstPoll.isCompleted) firstPoll.complete(Unit)
                                false
                            }
                        }
                    withTimeout(5_000) { firstPoll.await() }
                    val failure = assertFailsWith<ServerException> { device.detachAndReport() }
                    assertEquals("DEADLINE_EXCEEDED", failure.status)
                    withTimeout(5_000) {
                        while (device.failClosedHandle?.isCompleted != true) delay(10)
                    }
                    assertTrue(
                        failure.suppressed.any { it.message?.contains("detach boom") == true },
                        "suppressed carries the DetachDevice failure, got: ${failure.suppressed.toList()}",
                    )
                    assertEquals(1, fakeDevices.closeCalls.get(), "exactly one DetachDevice even on failure")
                    assertEquals(0, fakeConnections.closes.get(), "no Disconnect on the fail-closed path")
                    assertEquals(0, connection.liveDeviceCount, "registry cleared even when DetachDevice fails")
                    withTimeout(5_000) {
                        while (!device.cleanupTerminated) delay(10)
                    }
                    val duplicate = assertFailsWith<ServerException> { withTimeout(5_000) { device.detachAndReport() } }
                    assertSame(failure, duplicate, "duplicate shares the same terminal failure with suppressed cause")
                    parked.cancelAndJoin()
                }
            } finally {
                fakeDevices.closeError = null
                runCatching { connection.close() }
            }
        }
    }

    @Test
    fun `awaitAppVisible maps only WAIT_TIMEOUT to WaitTimeoutException`() {
        waitMapping(ErrorCode.ERR_WAIT_TIMEOUT) { it.awaitAppVisible() }.let { assertIs<WaitTimeoutException>(it) }
        waitMapping(ErrorCode.ERR_DRIVER_UNHEALTHY) { it.awaitAppVisible() }.let {
            assertIs<CommandException>(it)
            assertEquals(ErrorCode.ERR_DRIVER_UNHEALTHY, it.code)
        }
    }

    @Test
    fun `awaitScreenStable maps only WAIT_TIMEOUT to WaitTimeoutException`() {
        waitMapping(ErrorCode.ERR_WAIT_TIMEOUT) { it.awaitScreenStable() }.let { assertIs<WaitTimeoutException>(it) }
        waitMapping(ErrorCode.ERR_TRANSPORT_LOST) { it.awaitScreenStable() }.let {
            assertIs<CommandException>(it)
            assertEquals(ErrorCode.ERR_TRANSPORT_LOST, it.code)
        }
    }

    @Test
    fun `scrollUntil propagates a failing step unchanged`() {
        val scroll: suspend (Device) -> Unit = { it.element(rawRes("list")).scrollUntil(text("row 40")) }
        waitMapping(ErrorCode.ERR_NOT_FOUND, scroll).let {
            assertIs<CommandException>(it)
            assertEquals(ErrorCode.ERR_NOT_FOUND, it.code)
        }
    }

    @Test
    fun `element waits map only WAIT_TIMEOUT to WaitTimeoutException`() {
        waitMapping(ErrorCode.ERR_WAIT_TIMEOUT) { it.await(text("x")).visible() }.let { assertIs<WaitTimeoutException>(it) }
        waitMapping(ErrorCode.ERR_DRIVER_UNHEALTHY) { it.await(text("x")).gone() }.let { assertIs<CommandException>(it) }
    }

    @Test
    fun `every command carries the timeout and one deadline policy`() {
        runBlocking {
            val connection = client().connect("test")
            try {
                tapScope {
                    val device = connection.attachDevice("emulator-5554", "com.test", Timeouts(action = 3.seconds, wait = 7.seconds))
                    device.info()
                    device.awaitAppVisible()
                    device.execute(timeout = 2.seconds) { deviceInfo = io.github.noamcohen48.tap.api.v1.DeviceInfoQuery.getDefaultInstance() }
                    assertEquals(listOf(3_000L, 7_000L, 2_000L), fakeDevices.executeRequests.map { it.command.timeoutMs })
                    device.detach()
                }
            } finally {
                connection.close()
            }
        }
    }

    /** Runs [call] against a fake whose every Execute fails with [code]; returns what it threw. */
    @Test
    fun `scrollUntil scrolls until the target exists inside the container`() {
        runBlocking {
            var scrolls = 0
            fakeDevices.executeResponder = { request ->
                when {
                    request.command.hasScroll() -> CommandResult.newBuilder().setDone(Done.getDefaultInstance()).build().also { scrolls++ }
                    request.command.hasExists() -> CommandResult.newBuilder().setBool(scrolls == 3).build()
                    else -> null
                }
            }
            val connection = client().connect("test")
            try {
                tapScope {
                    val device = connection.attachDevice("emulator-5554", "com.test")
                    try {
                        val list = device.element(rawRes("list"))
                        val inList = rawRes("list").descendant(text("row 40"))
                        assertEquals(inList, list.scrollUntil(text("row 40")).selector)
                        val ops = fakeDevices.executeRequests.map { it.command }.filter { it.hasExists() || it.hasScroll() }
                        assertEquals(List(3) { listOf("exists", "scroll") }.flatten() + "exists", ops.map { if (it.hasExists()) "exists" else "scroll" })
                        assertTrue(ops.filter { it.hasExists() }.all { it.exists.selector == inList.proto })
                        assertTrue(ops.filter { it.hasScroll() }.all { it.scroll.selector == rawRes("list").proto && it.scroll.direction == Direction.DIR_DOWN })
                        // A picked container cannot be carried into a relation: the bare target is used.
                        assertEquals(text("row 40"), list.first().scrollUntil(text("row 40")).selector)
                    } finally {
                        device.detach()
                    }
                }
            } finally {
                fakeDevices.executeResponder = null
                connection.close()
            }
        }
    }

    @Test
    fun `typeText taps the field, waits for its focus, then types into the focus`() {
        runBlocking {
            var snapshots = 0
            fakeDevices.executeResponder = { request ->
                when {
                    request.command.hasSnapshot() ->
                        CommandResult.newBuilder().setSnapshot(ElementSnapshot.newBuilder().setFocused(++snapshots == 2)).build()
                    request.command.hasTap() || request.command.hasTypeText() -> CommandResult.newBuilder().setDone(Done.getDefaultInstance()).build()
                    else -> null
                }
            }
            val connection = client().connect("test")
            try {
                tapScope {
                    val device = connection.attachDevice("emulator-5554", "com.test")
                    try {
                        device.element(res("email")).typeText("abc")
                        val ops = fakeDevices.executeRequests.map { it.command }.filter { it.hasTap() || it.hasSnapshot() || it.hasTypeText() }
                        assertEquals(listOf(Command.OpCase.TAP, Command.OpCase.SNAPSHOT, Command.OpCase.SNAPSHOT, Command.OpCase.TYPE_TEXT), ops.map { it.opCase })
                        assertEquals("abc", ops.last().typeText.text)

                        fakeDevices.executeRequests.clear()
                        device.element(res("email")).typeText("d", awaitFocus = false)
                        val unwaited = fakeDevices.executeRequests.map { it.command.opCase }.filter { it != Command.OpCase.DEVICE_INFO }
                        assertEquals(listOf(Command.OpCase.TAP, Command.OpCase.TYPE_TEXT), unwaited)
                    } finally {
                        device.detach()
                    }
                }
            } finally {
                fakeDevices.executeResponder = null
                connection.close()
            }
        }
    }

    @Test
    fun `scrollUntil gives up after maxScrolls with WaitTimeoutException`() {
        runBlocking {
            val connection = client().connect("test")
            try {
                tapScope {
                    val device = connection.attachDevice("emulator-5554", "com.test")
                    try {
                        val timeout = assertFailsWith<WaitTimeoutException> { device.element(rawRes("list")).scrollUntil(text("row 40"), maxScrolls = 2) }
                        assertEquals(2, timeout.polls)
                        val ops = fakeDevices.executeRequests.map { it.command }.filter { it.hasExists() || it.hasScroll() }
                        assertEquals(listOf(true, false, true, false, true), ops.map { it.hasExists() })
                    } finally {
                        device.detach()
                    }
                }
            } finally {
                connection.close()
            }
        }
    }

    private fun waitMapping(
        code: ErrorCode,
        call: suspend (Device) -> Unit,
    ): Throwable =
        runBlocking {
            fakeDevices.executeResponder = { request ->
                if (request.command.hasDeviceInfo()) {
                    null
                } else {
                    CommandResult
                        .newBuilder()
                        .setError(io.github.noamcohen48.tap.api.v1.Error.newBuilder().setCode(code))
                        .build()
                }
            }
            val connection = client().connect("test")
            try {
                tapScope {
                    val device = connection.attachDevice("emulator-5554", "com.test")
                    try {
                        assertFailsWith<TapException> { call(device) }
                    } finally {
                        device.detach()
                    }
                }
            } finally {
                fakeDevices.executeResponder = null
                connection.close()
            }
        }

    @Test
    fun `hanging info probe preserves outer cancellation identity`() {
        runBlocking {
            val entered = CompletableDeferred<Unit>()
            val probeChannel = TestChannel()
            var seenAddress: String? = null
            // Deterministic fake for this invocation only: gates `entered` synchronously
            // before parking in the cancellable region, with no socket involved. The large
            // own timeout keeps ownership with the outer 200 ms timeout deterministically.
            val hanging =
                DaemonDiscovery.DiscoveryDeps(
                    channelFactory = { address ->
                        seenAddress = address
                        probeChannel
                    },
                    infoProbe = {
                        if (!entered.isCompleted) entered.complete(Unit)
                        awaitCancellation()
                    },
                    probeTimeoutMs = 10_000L,
                )
            val dir = Files.createTempDirectory("tap-probe")
            Files.writeString(dir.resolve("daemon.json"), "{\"port\":1}")
            // The outer withTimeout owns the probe call directly, so its
            // TimeoutCancellationException is the cancellation identity under test.
            val probing = async { withTimeout(200.milliseconds) { DaemonDiscovery.running(dir, hanging) } }
            withTimeout(10_000) { entered.await() }
            val outer = assertFailsWith<TimeoutCancellationException> { probing.await() }
            assertTrue(
                outer.message!!.contains("200ms") || outer.message!!.contains("Timed out"),
                "outer timeout identity, got: ${outer.message}",
            )
            assertTrue(probing.isCancelled, "hanging probe cancelled, never returned as dead")
            withTimeout(5_000) { probing.join() }
            assertEquals("127.0.0.1:1", seenAddress, "probe dialled the descriptor address")
            assertEquals(1, probeChannel.shutdownNows.get(), "probe channel shut down in NonCancellable")
            assertEquals(1, probeChannel.awaits.get(), "probe channel awaited after shutdownNow")
            // Ordinary probe failure still maps to dead (null), never throws, with cleanup.
            val deadChannel = TestChannel()
            val dead =
                DaemonDiscovery.DiscoveryDeps(
                    channelFactory = { deadChannel },
                    infoProbe = { throw StatusRuntimeException(Status.UNAVAILABLE.withDescription("dead")) },
                )
            val deadDir = Files.createTempDirectory("tap-probe-dead")
            Files.writeString(deadDir.resolve("daemon.json"), "{\"port\":2}")
            assertNull(withTimeout(10_000) { DaemonDiscovery.running(deadDir, dead) }, "probe failure maps to dead")
            assertEquals(1, deadChannel.shutdownNows.get(), "failed probe channel still shut down")
            assertEquals(1, deadChannel.awaits.get(), "failed probe channel awaited after shutdownNow")
            // Own timeout maps to dead (null): a hanging probe with a short injected bound
            // returns null without an outer timeout involved.
            val timeoutChannel = TestChannel()
            val ownTimeout =
                DaemonDiscovery.DiscoveryDeps(
                    channelFactory = { timeoutChannel },
                    infoProbe = { awaitCancellation() },
                    probeTimeoutMs = 200L,
                )
            val timeoutDir = Files.createTempDirectory("tap-probe-timeout")
            Files.writeString(timeoutDir.resolve("daemon.json"), "{\"port\":3}")
            assertNull(withTimeout(10_000) { DaemonDiscovery.running(timeoutDir, ownTimeout) }, "own timeout maps to dead")
            assertEquals(1, timeoutChannel.shutdownNows.get(), "timed-out probe channel still shut down")
            assertEquals(1, timeoutChannel.awaits.get(), "timed-out probe channel awaited after shutdownNow")
        }
    }

    @Test
    fun `discovery preserves known cancellation identity`() {
        runBlocking {
            val entered = CompletableDeferred<Unit>()
            val probeChannel = TestChannel()
            // Deterministic fake for this invocation only: gates `entered` synchronously
            // before parking in the cancellable region, with no socket involved.
            val hanging =
                DaemonDiscovery.DiscoveryDeps(
                    channelFactory = { probeChannel },
                    infoProbe = {
                        if (!entered.isCompleted) entered.complete(Unit)
                        awaitCancellation()
                    },
                    probeTimeoutMs = 10_000L,
                )
            val dir = Files.createTempDirectory("tap-probe-known")
            Files.writeString(dir.resolve("daemon.json"), "{\"port\":1}")
            val known = CancellationException("known-discovery-cancel")
            val probing = async { DaemonDiscovery.running(dir, hanging) }
            withTimeout(10_000) { entered.await() }
            probing.cancel(known)
            val thrown = assertFailsWith<CancellationException> { probing.await() }

            // Coroutine cancellation may wrap the cause; the known instance must still be
            // observable by identity somewhere in the chain (itself or its cause chain).
            fun chainContainsKnown(error: Throwable?): Boolean {
                var current = error
                while (current != null) {
                    if (current === known) return true
                    current = current.cause
                }
                return false
            }
            assertTrue(chainContainsKnown(thrown), "known cancellation preserved in the chain, got: $thrown")
            // Where the machinery propagates without wrapping, assert the identity directly.
            if (thrown === known) {
                assertSame(known, thrown, "known outer cancellation propagates with identity")
            } else {
                var current: Throwable? = thrown
                while (current != null && current !== known) current = current.cause
                assertSame(known, current, "known cancellation preserved as the cause identity")
            }
            withTimeout(5_000) { probing.join() }
            assertEquals(1, probeChannel.shutdownNows.get(), "cancelled probe channel still shut down")
            assertEquals(1, probeChannel.awaits.get(), "cancelled probe channel awaited after shutdownNow")
        }
    }

    @Test
    fun `duplicate device detaches share one DetachDevice including quarantine detail`() {
        runBlocking {
            fakeDevices.quarantineNextClose = "driver would not die"
            fakeDevices.closeRelease = CompletableDeferred()
            val connection = client().connect("test")
            try {
                tapScope {
                    val device = connection.attachDevice("emulator-5554", "com.test")
                    val firstJoined = CompletableDeferred<Unit>()
                    val secondJoined = CompletableDeferred<Unit>()
                    val first =
                        async {
                            firstJoined.complete(Unit)
                            device.detachAndReport()
                        }
                    val second =
                        async {
                            secondJoined.complete(Unit)
                            device.detachAndReport()
                        }
                    withTimeout(5_000) {
                        firstJoined.await()
                        secondJoined.await()
                        fakeDevices.closeEntered.await()
                    }
                    assertEquals(1, fakeDevices.closeCalls.get(), "single-flight session close")
                    fakeDevices.closeRelease.complete(Unit)
                    assertEquals("driver would not die", withTimeout(5_000) { first.await() })
                    assertEquals("driver would not die", withTimeout(5_000) { second.await() })
                    // close() on a quarantined session reports the same detail as a failure.
                    assertFailsWith<TapException> { device.detach() }
                    assertEquals(1, fakeDevices.closeCalls.get())
                }
            } finally {
                fakeDevices.closeRelease.complete(Unit)
                connection.close()
            }
        }
    }

    @Test
    fun `calls after device close fail locally`() {
        runBlocking {
            val connection = client().connect("test")
            try {
                tapScope {
                    val device = connection.attachDevice("emulator-5554", "com.test")
                    assertEquals(1, connection.liveDeviceCount, "open registers a live handle")
                    device.detach()
                    assertEquals(0, connection.liveDeviceCount, "close unregisters the handle")
                    val executeBefore = fakeDevices.executeCalls.get()
                    assertFailsWith<TapUsageException> { device.info() }
                    assertFailsWith<TapUsageException> { device.screenshot() }
                    assertFailsWith<TapUsageException> { device.driverLog() }
                    assertFailsWith<TapUsageException> {
                        device.awaitUntil("x", timeout = 100.milliseconds) { true }
                    }
                    assertFailsWith<TapUsageException> { device.pressBack() }
                    assertFailsWith<TapUsageException> { device.app().isRunning() }
                    assertEquals(executeBefore, fakeDevices.executeCalls.get(), "no RPC after close")
                    assertFailsWith<TapUsageException> { device.execute { } }
                    // Many open/close cycles return the live registry to zero.
                    repeat(10) { index ->
                        val cycled = connection.attachDevice("emulator-55${50 + index}", "com.test")
                        cycled.detach()
                    }
                    assertEquals(0, connection.liveDeviceCount, "registry returns to zero after many cycles")
                }
            } finally {
                connection.close()
            }
        }
    }

    @Test
    fun `cancelling execute cancels the grpc call`() {
        runBlocking {
            val connection = client().connect("test")
            try {
                fakeDevices.hangExecute.complete(Unit)
                tapScope {
                    val device = connection.attachDevice("emulator-5554", "com.test")
                    try {
                        val job = async { device.info() }
                        withTimeout(2_000) { fakeDevices.enteredExecute.await() }
                        job.cancelAndJoin()
                        assertTrue(job.isCancelled, "caller cancel cancels the in-flight Execute")
                        withTimeout(2_000) { fakeDevices.cancelledExecute.await() }
                    } finally {
                        // Release the fake server park, then close inside the same scope.
                        fakeDevices.hangExecuteResult.complete(CommandResult.getDefaultInstance())
                        device.detach()
                    }
                }
            } finally {
                connection.close()
            }
        }
    }

    @Test
    fun `grpc sibling failure cancels the other in-flight execute`() {
        runBlocking {
            val connection = client().connect("test")
            try {
                fakeDevices.hangExecute.complete(Unit)
                tapScope {
                    val device = connection.attachDevice("emulator-5554", "com.test")
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
                                            withTimeout(2_000) { fakeDevices.enteredExecute.await() }
                                            throw AssertionError("sibling boom")
                                        }
                                    boom.await()
                                    waiter.await()
                                }
                            }
                        assertEquals("sibling boom", failure.message)
                        // The real gRPC server observed the sibling cancellation, not just a
                        // local awaitCancellation: the in-flight Execute was interrupted.
                        withTimeout(5_000) { fakeDevices.cancelledExecute.await() }
                    } finally {
                        fakeDevices.hangExecuteResult.complete(CommandResult.getDefaultInstance())
                        device.detach()
                    }
                }
            } finally {
                connection.close()
            }
        }
    }

    @Test
    fun `sibling failure cancels the other device poll`() {
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
                                val waiterStarted = CompletableDeferred<Unit>()
                                val waiter =
                                    async {
                                        waiterStarted.complete(Unit)
                                        try {
                                            awaitCancellation()
                                        } catch (cancelled: CancellationException) {
                                            flag.complete(Unit)
                                            throw cancelled
                                        }
                                    }
                                val boom =
                                    async {
                                        withTimeout(2_000) { waiterStarted.await() }
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
    }

    @Test
    fun `device calls outside scope fail clearly`() {
        runBlocking {
            val connection = client().connect("test")
            try {
                val device = tapScope { connection.attachDevice("emulator-5554", "com.test") }
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
                tapScope { device.detach() }
            } finally {
                connection.close()
            }
        }
    }

    @Test
    fun `tapScope nesting fails`() {
        runBlocking {
            assertFailsWith<TapUsageException> {
                tapScope {
                    tapScope { }
                }
            }
        }
    }

    @Test
    fun `awaitUntil is delay based and cancellable`() {
        runBlocking {
            val connection = client().connect("test")
            try {
                val device = tapScope { connection.attachDevice("emulator-5554", "com.test") }
                try {
                    val started = System.nanoTime()
                    val firstPoll = CompletableDeferred<Unit>()
                    var polls = 0
                    val job =
                        CoroutineScope(kotlinx.coroutines.currentCoroutineContext()).async {
                            // Must run inside tapScope to pass the bound check; inherit via child.
                            withContext(TapContext("test")) {
                                device.awaitUntil("never", timeout = 30.seconds, pollInterval = 50.milliseconds) {
                                    polls++
                                    if (!firstPoll.isCompleted) firstPoll.complete(Unit)
                                    false
                                }
                            }
                        }
                    withTimeout(5_000) { firstPoll.await() }
                    job.cancelAndJoin()
                    assertTrue(job.isCancelled)
                    assertTrue(polls >= 1, "poll ran before cancellation")
                    val elapsedMs = (System.nanoTime() - started) / 1_000_000
                    assertTrue(elapsedMs < 5_000, "poll cancelled promptly, took ${elapsedMs}ms")
                    // A close invoked from the same admitted operation fails immediately with
                    // a usage error instead of waiting for itself.
                    tapScope {
                        val recursive =
                            assertFailsWith<TapUsageException> {
                                device.awaitUntil("recursive", timeout = 5.seconds) {
                                    device.detachAndReport()
                                    true
                                }
                            }
                        assertTrue(recursive.message!!.contains("own admitted operation"), "unexpected: ${recursive.message}")
                    }
                } finally {
                    tapScope { device.detach() }
                }
            } finally {
                connection.close()
            }
        }
    }

    @Test
    fun `detaching a device from inside another device's operation nested in its own refuses`() {
        runBlocking {
            val connection = client().connect("test")
            try {
                val first = tapScope { connection.attachDevice("emulator-5554", "com.test") }
                val second = tapScope { connection.attachDevice("emulator-5555", "com.test") }
                try {
                    // first's operation encloses second's: first stays admitted, so detaching it
                    // fails fast instead of waiting on its own drain.
                    val nested =
                        withTimeout(10_000) {
                            tapScope {
                                assertFailsWith<TapUsageException> {
                                    first.awaitUntil("outer", timeout = 5.seconds) {
                                        second.awaitUntil("inner", timeout = 5.seconds) {
                                            first.detachAndReport()
                                            true
                                        }
                                        true
                                    }
                                }
                            }
                        }
                    assertTrue(nested.message!!.contains("own admitted operation"), "unexpected: ${nested.message}")
                } finally {
                    tapScope {
                        first.detach()
                        second.detach()
                    }
                }
            } finally {
                connection.close()
            }
        }
    }

    @Test
    fun `app calls name their target, pass timeouts and stream the apk in chunks`() {
        runBlocking {
            val connection = client().connect("test")
            try {
                val device = tapScope { connection.attachDevice("emulator-5554", "com.test") }
                try {
                    val app = device.app()
                    val apk = Files.createTempFile("tap-app-test", ".apk")
                    val bytes = ByteArray(2 * 1024 * 1024 + 17) { it.toByte() }
                    Files.write(apk, bytes)
                    tapScope {
                        assertTrue(app.isRunning())
                        app.install(apk, timeout = 90.seconds)
                        app.grantPermission("android.permission.CAMERA")
                        app.launch(".Main", timeout = 7.seconds)
                        app.launch()
                        assertEquals(ProcessIdentity(4242, "token"), app.coldLaunch(timeout = 9.seconds))
                        val refused = assertFailsWith<ServerException> { app.grantPermission("android.permission.NOPE") }
                        assertEquals("FAILED_PRECONDITION", refused.status)
                    }
                    assertEquals(listOf(1024 * 1024, 1024 * 1024, 17), fakeApps.installChunks)
                    assertContentEquals(bytes, fakeApps.installed.toByteArray())

                    val requests = fakeApps.requests
                    val header = requests.filterIsInstance<io.github.noamcohen48.tap.api.v1.InstallHeader>().single()
                    assertEquals(bytes.size.toLong(), header.sizeBytes)
                    assertEquals(90_000, header.timeoutMs)
                    val launches = requests.filterIsInstance<io.github.noamcohen48.tap.api.v1.LaunchRequest>()
                    assertEquals(listOf(".Main" to 7_000L, "" to device.timeouts.lifecycle.inWholeMilliseconds), launches.map { it.activity to it.timeoutMs })
                    assertFalse(launches[1].hasActivity())
                    assertEquals(9_000, requests.filterIsInstance<io.github.noamcohen48.tap.api.v1.ColdLaunchRequest>().single().timeoutMs)
                    val targets =
                        requests.map { request ->
                            when (request) {
                                is io.github.noamcohen48.tap.api.v1.IsRunningRequest -> request.app
                                is io.github.noamcohen48.tap.api.v1.InstallHeader -> request.app
                                is io.github.noamcohen48.tap.api.v1.GrantPermissionRequest -> request.app
                                is io.github.noamcohen48.tap.api.v1.LaunchRequest -> request.app
                                is io.github.noamcohen48.tap.api.v1.ColdLaunchRequest -> request.app
                                else -> error("unexpected request $request")
                            }
                        }
                    assertEquals(7, targets.size)
                    for (target in targets) {
                        assertEquals("com.test", target.packageName)
                        assertEquals(connection.id, target.clientConnectionId)
                        assertEquals(device.attachedDeviceId, target.attachedDeviceId)
                    }
                } finally {
                    tapScope { device.detach() }
                }
            } finally {
                connection.close()
            }
        }
    }

    @Test
    fun `quarantined close reports detail`() {
        runBlocking {
            fakeDevices.quarantineNextClose = "driver would not die"
            val connection = client().connect("test")
            try {
                val device = tapScope { connection.attachDevice("emulator-5554", "com.test") }
                val detail = tapScope { device.detachAndReport() }
                assertEquals("driver would not die", detail)
            } finally {
                connection.close()
            }
        }
    }

    @Test
    fun `session close deadline mapping is preserved`() {
        runBlocking {
            fakeDevices.closeError =
                daemonFailure(Status.DEADLINE_EXCEEDED, FailureReason.FAILURE_REASON_HOST_WAIT_TIMEOUT, "Timed out waiting for close")
            val connection = client().connect("test")
            try {
                tapScope {
                    val device = connection.attachDevice("emulator-5554", "com.test")
                    assertFailsWith<WaitTimeoutException> { device.detachAndReport() }
                    assertEquals(1, fakeDevices.closeCalls.get())
                }
            } finally {
                connection.close()
            }
        }
    }

    @Test
    fun `tap client close forces termination with a second bounded await`() {
        runBlocking {
            val probe = TestChannel(firstAwaitResult = false, secondAwaitResult = true)
            val probeClient = TapClient("test", probe)
            withTimeout(10_000) { probeClient.close() }
            assertEquals(1, probe.shutdowns.get(), "shutdown called once")
            assertEquals(1, probe.shutdownNows.get(), "forced shutdownNow after the first await timed out")
            assertEquals(2, probe.awaits.get(), "second bounded await after shutdownNow")
        }
    }

    @Test
    fun `tap client close completes even when the caller is cancelled`() {
        runBlocking {
            val probe = TestChannel(firstAwaitResult = true)
            val probeClient = TapClient("test", probe)
            // Slow the first await so there is a window to cancel the caller; the NonCancellable
            // close must still finish the shutdown sequence.
            probe.blockFirstAwaitMs = 300
            val job = async { probeClient.close() }
            withTimeout(5_000) {
                while (probe.awaits.get() < 1) delay(10)
            }
            job.cancel()
            withTimeout(10_000) { job.join() }
            assertTrue(job.isCompleted)
            assertEquals(1, probe.shutdowns.get())
        }
    }

    @Test
    fun `daemon process cancellation destroys a long-lived executable promptly`() {
        runBlocking {
            val fake = FakeProcess("started 127.0.0.1:9999\n", alive = true)
            val starterEntered = CompletableDeferred<Unit>()
            val starter: (List<String>) -> Process = {
                starterEntered.complete(Unit)
                fake
            }
            run {
                val started = System.nanoTime()
                val job = async(Dispatchers.IO) { TapDaemonProcess.start(binary = "fake", timeout = 30.seconds, starter = starter) }
                // Handshake: the executable really started before cancellation is requested.
                withTimeout(5_000) { starterEntered.await() }
                job.cancelAndJoin()
                assertTrue(job.isCancelled, "cancelling start cancels the process wait promptly")
                val elapsedMs = (System.nanoTime() - started) / 1_000_000
                assertTrue(elapsedMs < 5_000, "prompt cancellation, took ${elapsedMs}ms")
                assertTrue(fake.destroyed.get(), "long-lived process destroyed on cancellation")
                // Outer timeout identity: an outer withTimeout is preserved as cancellation,
                // never reported as the executable's own bounded-wait timeout.
                val fake2 = FakeProcess("", alive = true)
                val starter2Entered = CompletableDeferred<Unit>()
                val starter2: (List<String>) -> Process = {
                    starter2Entered.complete(Unit)
                    fake2
                }
                val outer =
                    assertFailsWith<TimeoutCancellationException> {
                        withTimeout(200.milliseconds) {
                            TapDaemonProcess.start(binary = "fake", timeout = 30.seconds, starter = starter2)
                        }
                    }
                assertTrue(fake2.destroyed.get(), "outer-timed-out process destroyed and reaped")
            }
        }
    }

    @Test
    fun `daemon process death returns parsed output`() {
        runBlocking {
            val result =
                withTimeout(10_000) {
                    TapDaemonProcess.start(binary = "fake", timeout = 5.seconds) { FakeProcess("started 127.0.0.1:1234\n", alive = false) }
                }
            assertEquals("127.0.0.1:1234", result.address)
            assertTrue(result.started)
        }
    }

    @Test
    fun `daemon process timeout destroys and reports boundedly`() {
        runBlocking {
            val fake = FakeProcess("", alive = true)
            val failure =
                assertFailsWith<TapException> {
                    withTimeout(10_000) {
                        TapDaemonProcess.start(binary = "fake", timeout = 200.milliseconds) { fake }
                    }
                }
            assertTrue(failure.message!!.contains("did not finish"), "unexpected: ${failure.message}")
            assertTrue(fake.destroyed.get(), "timed-out process destroyed and reaped")
        }
    }

    // --- Fakes ----------------------------------------------------------------------------------

    private class FakeConnections : ClientConnectionServiceGrpcKt.ClientConnectionServiceCoroutineImplBase() {
        enum class ObserveMode {
            HELLO_THEN_PARK,
            EMPTY,
            ERROR_BEFORE_FIRST,
            HELLO_THEN_ERROR,
            HELLO_THEN_COMPLETE,
            STUBBORN,
        }

        val observes = AtomicInteger(0)
        val closes = AtomicInteger(0)
        val closeIds = CopyOnWriteArrayList<String>()
        val order = CopyOnWriteArrayList<String>()
        var observeMode: ObserveMode = ObserveMode.HELLO_THEN_PARK
        var observeError: Throwable = StatusRuntimeException(Status.UNAVAILABLE.withDescription("attach boom"))
        var closeError: Throwable? = null
        var closeEntered = CompletableDeferred<Unit>()
        var closeRelease: CompletableDeferred<Unit> = CompletableDeferred<Unit>().also { it.complete(Unit) }
        val parkCompletions = CompletableDeferred<Unit>()

        @Volatile var parkError: Throwable? = null

        fun finishParkedObserve() {
            parkCompletions.complete(Unit)
        }

        override suspend fun connect(request: ConnectRequest): ConnectResponse =
            ConnectResponse.newBuilder().setClientConnectionId("conn-1").build()

        override fun observe(request: ObserveRequest): Flow<ObserveResponse> =
            flow {
                observes.incrementAndGet()
                when (observeMode) {
                    ObserveMode.EMPTY -> {
                        // Complete without emitting: connect must fail and close the id.
                    }

                    ObserveMode.ERROR_BEFORE_FIRST -> {
                        throw observeError
                    }

                    ObserveMode.HELLO_THEN_PARK -> {
                        emit(ObserveResponse.newBuilder().setObserving(Observing.newBuilder().setClientConnectionId(request.clientConnectionId)).build())
                        try {
                            while (!parkCompletions.isCompleted) {
                                delay(10)
                            }
                            parkError?.let { throw it }
                            emit(ObserveResponse.newBuilder().setHeartbeat(Heartbeat.getDefaultInstance()).build())
                        } finally {
                            order.add("observe-cancelled")
                        }
                    }

                    ObserveMode.HELLO_THEN_ERROR -> {
                        emit(ObserveResponse.newBuilder().setObserving(Observing.newBuilder().setClientConnectionId(request.clientConnectionId)).build())
                        try {
                            delay(20)
                            throw observeError
                        } finally {
                            order.add("observe-cancelled")
                        }
                    }

                    ObserveMode.HELLO_THEN_COMPLETE -> {
                        emit(ObserveResponse.newBuilder().setObserving(Observing.newBuilder().setClientConnectionId(request.clientConnectionId)).build())
                        try {
                            delay(20)
                        } finally {
                            order.add("observe-cancelled")
                        }
                    }

                    ObserveMode.STUBBORN -> {
                        emit(ObserveResponse.newBuilder().setObserving(Observing.newBuilder().setClientConnectionId(request.clientConnectionId)).build())
                        try {
                            awaitCancellation()
                        } finally {
                            // Ignore cancellation for 10 s: the client's bounded teardown must win.
                            withContext(NonCancellable) { delay(10_000) }
                            order.add("observe-cancelled")
                        }
                    }
                }
            }

        override suspend fun disconnect(request: DisconnectRequest): DisconnectResponse {
            closes.incrementAndGet()
            closeIds.add(request.clientConnectionId)
            if (!closeEntered.isCompleted) closeEntered.complete(Unit)
            withTimeout(30_000) { closeRelease.await() }
            closeError?.let { throw it }
            order.add("disconnect")
            return DisconnectResponse.getDefaultInstance()
        }

        override suspend fun info(request: InfoRequest): InfoResponse = InfoResponse.getDefaultInstance()
    }

    private class FakeDevices : DeviceServiceGrpcKt.DeviceServiceCoroutineImplBase() {
        override suspend fun listDevices(request: ListDevicesRequest): ListDevicesResponse = ListDevicesResponse.getDefaultInstance()

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

        // AttachDevice gate for the invalidation/open race test: the test parks the response
        // to prove the AttachDevice RPC was admitted before the terminal invalidation lands.
        var openEntered = CompletableDeferred<Unit>()
        var openRelease: CompletableDeferred<Unit> = CompletableDeferred<Unit>().also { it.complete(Unit) }

        override suspend fun attach(request: AttachRequest): AttachResponse {
            opens.add(request.serial)
            if (!openEntered.isCompleted) openEntered.complete(Unit)
            withTimeout(30_000) { openRelease.await() }
            return AttachResponse
                .newBuilder()
                .setAttachedDeviceId("attached-${request.serial}")
                .setSerial(request.serial)
                .setGeneration(1)
                .build()
        }

        /** When set, answers Execute with its result (null falls through to the default). */
        @Volatile var executeResponder: ((ExecuteRequest) -> CommandResult?)? = null
        val executeRequests = CopyOnWriteArrayList<ExecuteRequest>()

        override suspend fun execute(request: ExecuteRequest): ExecuteResponse = ExecuteResponse.newBuilder().setResult(result(request)).build()

        private suspend fun result(request: ExecuteRequest): CommandResult {
            executeCalls.incrementAndGet()
            executeRequests.add(request)
            executeResponder?.invoke(request)?.let { return it }
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

        override suspend fun detach(request: DetachRequest): DetachResponse {
            closeCalls.incrementAndGet()
            closes.add(request.attachedDeviceId)
            if (!closeEntered.isCompleted) closeEntered.complete(Unit)
            withTimeout(30_000) { closeRelease.await() }
            closeError?.let { throw it }
            order.add("device-detach")
            val detail = quarantineNextClose?.also { quarantineNextClose = null }
            return DetachResponse
                .newBuilder()
                .setClean(detail == null)
                .apply { detail?.let { setDetail(it) } }
                .build()
        }

        override suspend fun screenshot(request: io.github.noamcohen48.tap.api.v1.ScreenshotRequest): io.github.noamcohen48.tap.api.v1.ScreenshotResponse =
            io.github.noamcohen48.tap.api.v1.ScreenshotResponse
                .getDefaultInstance()

        override suspend fun driverLog(request: io.github.noamcohen48.tap.api.v1.DriverLogRequest): io.github.noamcohen48.tap.api.v1.DriverLogResponse =
            io.github.noamcohen48.tap.api.v1.DriverLogResponse
                .getDefaultInstance()
    }

    private class FakeApps : AppServiceGrpcKt.AppServiceCoroutineImplBase() {
        val requests = java.util.concurrent.CopyOnWriteArrayList<Any>()
        val installChunks = java.util.concurrent.CopyOnWriteArrayList<Int>()
        val installed = java.io.ByteArrayOutputStream()

        override suspend fun isRunning(request: io.github.noamcohen48.tap.api.v1.IsRunningRequest): io.github.noamcohen48.tap.api.v1.IsRunningResponse =
            io.github.noamcohen48.tap.api.v1.IsRunningResponse
                .newBuilder()
                .setRunning(true)
                .build()
                .also { requests += request }

        override suspend fun install(
            requests: kotlinx.coroutines.flow.Flow<io.github.noamcohen48.tap.api.v1.InstallRequest>,
        ): io.github.noamcohen48.tap.api.v1.InstallResponse {
            requests.collect { part ->
                if (part.hasHeader()) {
                    this.requests += part.header
                } else {
                    installChunks += part.chunk.size()
                    synchronized(installed) { part.chunk.writeTo(installed) }
                }
            }
            return io.github.noamcohen48.tap.api.v1.InstallResponse
                .getDefaultInstance()
        }

        override suspend fun grantPermission(
            request: io.github.noamcohen48.tap.api.v1.GrantPermissionRequest,
        ): io.github.noamcohen48.tap.api.v1.GrantPermissionResponse {
            requests += request
            if (request.permission == "android.permission.NOPE") {
                throw io.grpc.StatusException(io.grpc.Status.FAILED_PRECONDITION.withDescription("not granted after pm grant"))
            }
            return io.github.noamcohen48.tap.api.v1.GrantPermissionResponse
                .getDefaultInstance()
        }

        override suspend fun launch(request: io.github.noamcohen48.tap.api.v1.LaunchRequest): io.github.noamcohen48.tap.api.v1.LaunchResponse {
            requests += request
            return io.github.noamcohen48.tap.api.v1.LaunchResponse
                .getDefaultInstance()
        }

        override suspend fun coldLaunch(request: io.github.noamcohen48.tap.api.v1.ColdLaunchRequest): io.github.noamcohen48.tap.api.v1.ColdLaunchResponse {
            requests += request
            return io.github.noamcohen48.tap.api.v1.ColdLaunchResponse
                .newBuilder()
                .setProcess(
                    io.github.noamcohen48.tap.api.v1.ProcessIdentity
                        .newBuilder()
                        .setPid(4242)
                        .setStartToken("token"),
                ).build()
        }
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
        private val exit = java.util.concurrent.CompletableFuture<Process>().also { if (!alive) it.complete(this) }

        override fun onExit(): java.util.concurrent.CompletableFuture<Process> = exit.thenApply { it }

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
            exit.complete(this)
        }

        override fun destroyForcibly(): Process {
            destroy()
            return this
        }
    }
}
