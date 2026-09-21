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
import java.nio.file.Files
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
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
    fun `connect establishes attach before returning`() {
        runBlocking {
            val connection = client().connect("test")
            try {
                assertTrue(connection.recentEvents.isNotEmpty(), "attach first event before connect returns")
                assertEquals(1, fakeConnections.attaches.get(), "exactly one Attach")
            } finally {
                connection.close()
            }
        }
    }

    @Test
    fun `empty attach fails connect and closes the id`() {
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
    }

    @Test
    fun `attach error before first event fails connect and closes the id`() {
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
    }

    @Test
    fun `attach normal termination after first event invalidates devices and rejects opens`() {
        runBlocking {
            val connection = client().connect("test")
            val first = tapScope { connection.openDevice("emulator-5554", "com.test") }
            val second = tapScope { connection.openDevice("emulator-5555", "com.test") }
            assertEquals(2, connection.liveDeviceCount, "registry retains live handles")
            try {
                // Gate the OpenSession response so the racing registration is provably in
                // flight when the terminal invalidation lands (deterministic, no sleep). The
                // racing call either fails at the pre-RPC gate or returns only as an
                // invalidated handle; the registry keeps the handle until close either way.
                fakeSessions.openRelease = CompletableDeferred()
                fakeSessions.openEntered = CompletableDeferred()
                val racing =
                    async {
                        runCatching { tapScope { connection.openDevice("emulator-5559", "com.test") } }
                    }
                withTimeout(5_000) { fakeSessions.openEntered.await() }
                // Server ends the parked stream after establishment: unexpected termination.
                fakeConnections.finishParkedAttach()
                withTimeout(5_000) {
                    while (!connection.isInvalid) delay(10)
                }
                // Release the gated OpenSession into the terminal state.
                fakeSessions.openRelease.complete(Unit)
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
                // The racing registration resolved during the terminal drop: either rejected at
                // the gate or returned only as an invalidated handle.
                val raced = withTimeout(5_000) { racing.await() }
                raced.onSuccess { handle ->
                    tapScope { assertFailsWith<ServiceException> { handle.info() } }
                    tapScope { runCatching { handle.close() } }
                }
                val opensAfterRace = fakeSessions.opens.size
                assertFailsWith<ServiceException> {
                    tapScope { connection.openDevice("emulator-5556", "com.test") }
                }
                assertEquals(opensAfterRace, fakeSessions.opens.size, "no new session opened after invalidation")
                // Closing invalidated handles unregisters them; the registry returns to zero.
                tapScope {
                    runCatching { first.close() }
                    runCatching { second.close() }
                }
                withTimeout(5_000) {
                    while (connection.liveDeviceCount != 0) delay(10)
                }
                assertEquals(0, connection.liveDeviceCount, "closed handles leave the registry")
                // Explicit close still sends Close exactly once.
                connection.close()
                assertEquals(1, fakeConnections.closes.get())
            } finally {
                runCatching { connection.close() }
            }
        }
    }

    @Test
    fun `attach error termination after first event invalidates with cause`() {
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
    }

    @Test
    fun `close sends Close before dropping attach`() {
        runBlocking {
            val connection = client().connect("test")
            connection.close()
            assertEquals(listOf("close", "attach-cancelled"), fakeConnections.order.toList())
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
                // while the single Close RPC is parked.
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
                    while (!fakeConnections.order.contains("attach-cancelled")) delay(10)
                }
            } finally {
                fakeConnections.closeRelease.complete(Unit)
                runCatching { connection.close() }
            }
        }
    }

    @Test
    fun `stubborn attach collector does not hold close past its bound`() {
        runBlocking {
            fakeConnections.attachMode = FakeConnections.AttachMode.STUBBORN
            // Isolated per-instance bound (no shared mutation): safe under parallel tests.
            val connection = Connection(client(), "conn-stubborn", ConnectionBounds(teardownMs = 300))
            connection.attach()
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
    fun `dropped attach still lets close run`() {
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
    }

    @Test
    fun `execute admitted before close finishes before Session Close and later calls rejected`() {
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
    }

    @Test
    fun `device drain timeout fails closed with one Connection Close`() {
        runBlocking {
            val connection = Connection(client(), "conn-failclosed", ConnectionBounds(), DeviceBounds(drainMs = 300))
            connection.attach()
            try {
                tapScope {
                    val first = connection.openDevice("emulator-5554", "com.test")
                    val second = connection.openDevice("emulator-5555", "com.test")
                    assertEquals(2, connection.liveDeviceCount)
                    val firstPoll = CompletableDeferred<Unit>()
                    // Permanently parked admitted operation: never released to unblock close.
                    // Fail-closed must tear the connection down without waiting for this drain.
                    val parked =
                        async {
                            first.awaitUntil("parked", timeout = 30.seconds) {
                                if (!firstPoll.isCompleted) firstPoll.complete(Unit)
                                false
                            }
                        }
                    withTimeout(5_000) { firstPoll.await() }
                    assertTrue(connection.liveDeviceCount == 2)
                    // Duplicate closes share the same drain wait and the same terminal failure.
                    val closer1 = async { runCatching { first.closeAndReport() } }
                    val closer2 = async { runCatching { first.closeAndReport() } }
                    val firstResult = withTimeout(10_000) { closer1.await() }
                    val secondResult = withTimeout(10_000) { closer2.await() }
                    assertTrue(firstResult.isFailure, "drain timeout fails the first close")
                    assertTrue(secondResult.isFailure, "duplicate shares the terminal failure")
                    val firstFailure = firstResult.exceptionOrNull()
                    val secondFailure = secondResult.exceptionOrNull()
                    assertIs<ServiceException>(firstFailure)
                    assertEquals("DEADLINE_EXCEEDED", firstFailure.status)
                    assertTrue(firstFailure.details.contains("drain timed out"), "unexpected: ${firstFailure.details}")
                    assertTrue(firstFailure.details.contains("fail-closed"), "unexpected: ${firstFailure.details}")
                    // Same terminal failure instance for owner and duplicate.
                    assertSame(firstFailure, secondFailure, "duplicate Device closes share the same timeout instance")
                    // Exactly one bounded Connection Close, zero Session Close.
                    withTimeout(5_000) {
                        while (fakeConnections.closes.get() < 1) delay(10)
                    }
                    assertEquals(1, fakeConnections.closes.get(), "exactly one Connection Close")
                    delay(200)
                    assertEquals(0, fakeSessions.closeCalls.get(), "no Session Close on the fail-closed path")
                    assertEquals(1, fakeConnections.closes.get(), "no duplicate Connection Close")
                    // Connection unusable, every handle invalid, registry cleared before the
                    // shared terminal completion.
                    withTimeout(5_000) {
                        while (!connection.isInvalid) delay(10)
                    }
                    withTimeout(5_000) {
                        while (connection.liveDeviceCount != 0) delay(10)
                    }
                    assertEquals(0, connection.liveDeviceCount, "registry cleared before terminal completion")
                    // The closed handle rejects locally; the sibling handle is connection-invalid.
                    assertFailsWith<TapUsageException> { first.info() }
                    val siblingFailure = assertFailsWith<ServiceException> { second.info() }
                    assertEquals("UNAVAILABLE", siblingFailure.status)
                    assertFailsWith<TapUsageException> { connection.openDevice("emulator-5556", "com.test") }
                    // Owned scope/job terminated after the bounded Connection Close.
                    assertTrue(first.failClosedStarted, "fail-closed job launched on the owned scope")
                    withTimeout(5_000) {
                        while (first.failClosedHandle?.isCompleted != true) delay(10)
                    }
                    withTimeout(5_000) {
                        while (!first.cleanupTerminated) delay(10)
                    }
                    // A later duplicate still shares the same terminal failure, no new RPC.
                    val late = runCatching { withTimeout(5_000) { first.closeAndReport() } }.exceptionOrNull()
                    assertSame(firstFailure, late, "late duplicate shares the same terminal failure")
                    assertEquals(1, fakeConnections.closes.get())
                    assertEquals(0, fakeSessions.closeCalls.get())
                    parked.cancelAndJoin()
                }
            } finally {
                runCatching { connection.close() }
            }
        }
    }

    @Test
    fun `fail-closed Connection Close failure is suppressed without stranding`() {
        runBlocking {
            fakeConnections.closeError =
                StatusRuntimeException(Status.UNAVAILABLE.withDescription("connection boom"))
            val connection = Connection(client(), "conn-failclosed-err", ConnectionBounds(), DeviceBounds(drainMs = 300))
            connection.attach()
            try {
                tapScope {
                    val device = connection.openDevice("emulator-5554", "com.test")
                    val firstPoll = CompletableDeferred<Unit>()
                    val parked =
                        async {
                            device.awaitUntil("parked", timeout = 30.seconds) {
                                if (!firstPoll.isCompleted) firstPoll.complete(Unit)
                                false
                            }
                        }
                    withTimeout(5_000) { firstPoll.await() }
                    val failure = assertFailsWith<ServiceException> { device.closeAndReport() }
                    assertEquals("DEADLINE_EXCEEDED", failure.status)
                    assertTrue(failure.details.contains("fail-closed"), "unexpected: ${failure.details}")
                    withTimeout(5_000) {
                        while (fakeConnections.closes.get() < 1) delay(10)
                    }
                    // The Connection Close failure is observable via suppressed, never stranded.
                    withTimeout(5_000) {
                        while (failure.suppressed.isEmpty()) delay(10)
                    }
                    assertTrue(
                        failure.suppressed.any { it.message?.contains("connection boom") == true },
                        "suppressed carries the Connection Close failure, got: ${failure.suppressed.toList()}",
                    )
                    assertEquals(1, fakeConnections.closes.get(), "exactly one Connection Close even on failure")
                    assertEquals(0, fakeSessions.closeCalls.get(), "no Session Close on the fail-closed path")
                    withTimeout(5_000) {
                        while (!connection.isInvalid) delay(10)
                    }
                    withTimeout(5_000) {
                        while (connection.liveDeviceCount != 0) delay(10)
                    }
                    assertEquals(0, connection.liveDeviceCount, "registry cleared even when Connection Close fails")
                    withTimeout(5_000) {
                        while (device.failClosedHandle?.isCompleted != true) delay(10)
                    }
                    withTimeout(5_000) {
                        while (!device.cleanupTerminated) delay(10)
                    }
                    val duplicate = assertFailsWith<ServiceException> { withTimeout(5_000) { device.closeAndReport() } }
                    assertSame(failure, duplicate, "duplicate shares the same terminal failure with suppressed cause")
                    parked.cancelAndJoin()
                }
            } finally {
                fakeConnections.closeError = null
                runCatching { connection.close() }
            }
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
                ServiceDiscovery.DiscoveryDeps(
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
            Files.writeString(dir.resolve("service.json"), "{\"port\":1}")
            // The outer withTimeout owns the probe call directly, so its
            // TimeoutCancellationException is the cancellation identity under test.
            val probing = async { withTimeout(200.milliseconds) { ServiceDiscovery.running(dir, hanging) } }
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
                ServiceDiscovery.DiscoveryDeps(
                    channelFactory = { deadChannel },
                    infoProbe = { throw StatusRuntimeException(Status.UNAVAILABLE.withDescription("dead")) },
                )
            val deadDir = Files.createTempDirectory("tap-probe-dead")
            Files.writeString(deadDir.resolve("service.json"), "{\"port\":2}")
            assertNull(withTimeout(10_000) { ServiceDiscovery.running(deadDir, dead) }, "probe failure maps to dead")
            assertEquals(1, deadChannel.shutdownNows.get(), "failed probe channel still shut down")
            assertEquals(1, deadChannel.awaits.get(), "failed probe channel awaited after shutdownNow")
            // Own timeout maps to dead (null): a hanging probe with a short injected bound
            // returns null without an outer timeout involved.
            val timeoutChannel = TestChannel()
            val ownTimeout =
                ServiceDiscovery.DiscoveryDeps(
                    channelFactory = { timeoutChannel },
                    infoProbe = { awaitCancellation() },
                    probeTimeoutMs = 200L,
                )
            val timeoutDir = Files.createTempDirectory("tap-probe-timeout")
            Files.writeString(timeoutDir.resolve("service.json"), "{\"port\":3}")
            assertNull(withTimeout(10_000) { ServiceDiscovery.running(timeoutDir, ownTimeout) }, "own timeout maps to dead")
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
                ServiceDiscovery.DiscoveryDeps(
                    channelFactory = { probeChannel },
                    infoProbe = {
                        if (!entered.isCompleted) entered.complete(Unit)
                        awaitCancellation()
                    },
                    probeTimeoutMs = 10_000L,
                )
            val dir = Files.createTempDirectory("tap-probe-known")
            Files.writeString(dir.resolve("service.json"), "{\"port\":1}")
            val known = CancellationException("known-discovery-cancel")
            val probing = async { ServiceDiscovery.running(dir, hanging) }
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
    fun `duplicate device closes share one Session Close including quarantine detail`() {
        runBlocking {
            fakeSessions.quarantineNextClose = "driver would not die"
            fakeSessions.closeRelease = CompletableDeferred()
            val connection = client().connect("test")
            try {
                tapScope {
                    val device = connection.openDevice("emulator-5554", "com.test")
                    val firstJoined = CompletableDeferred<Unit>()
                    val secondJoined = CompletableDeferred<Unit>()
                    val first = async { firstJoined.complete(Unit); device.closeAndReport() }
                    val second = async { secondJoined.complete(Unit); device.closeAndReport() }
                    withTimeout(5_000) {
                        firstJoined.await()
                        secondJoined.await()
                        fakeSessions.closeEntered.await()
                    }
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
    }

    @Test
    fun `calls after device close fail locally`() {
        runBlocking {
            val connection = client().connect("test")
            try {
                tapScope {
                    val device = connection.openDevice("emulator-5554", "com.test")
                    assertEquals(1, connection.liveDeviceCount, "open registers a live handle")
                    device.close()
                    assertEquals(0, connection.liveDeviceCount, "close unregisters the handle")
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
                    // Many open/close cycles return the live registry to zero.
                    repeat(10) { index ->
                        val cycled = connection.openDevice("emulator-55${50 + index}", "com.test")
                        cycled.close()
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
    }

    @Test
    fun `grpc sibling failure cancels the other in-flight execute`() {
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
                val device = tapScope { connection.openDevice("emulator-5554", "com.test") }
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
                                    device.closeAndReport()
                                    true
                                }
                            }
                        assertTrue(recursive.message!!.contains("own admitted operation"), "unexpected: ${recursive.message}")
                    }
                } finally {
                    tapScope { device.close() }
                }
            } finally {
                connection.close()
            }
        }
    }

    @Test
    fun `quarantined close reports detail`() {
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
    }

    @Test
    fun `session close deadline mapping is preserved`() {
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
    fun `service process cancellation destroys a long-lived executable promptly`() {
        runBlocking {
            val fake = FakeProcess("started 127.0.0.1:9999\n", alive = true)
            val starterEntered = CompletableDeferred<Unit>()
            TapServiceProcess.processStarter = {
                starterEntered.complete(Unit)
                fake
            }
            try {
                val started = System.nanoTime()
                val job = async(Dispatchers.IO) { TapServiceProcess.start(binary = "fake", timeout = 30.seconds) }
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
                TapServiceProcess.processStarter = {
                    starter2Entered.complete(Unit)
                    fake2
                }
                val outer =
                    assertFailsWith<TimeoutCancellationException> {
                        withTimeout(200.milliseconds) {
                            TapServiceProcess.start(binary = "fake", timeout = 30.seconds)
                        }
                    }
                assertTrue(fake2.destroyed.get(), "outer-timed-out process destroyed and reaped")
            } finally {
                TapServiceProcess.processStarter = null
            }
        }
    }

    @Test
    fun `service process death returns parsed output`() {
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
    }

    @Test
    fun `service process timeout destroys and reports boundedly`() {
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
        // OpenSession gate for the invalidation/open race test: the test parks the response
        // to prove the OpenSession RPC was admitted before the terminal invalidation lands.
        var openEntered = CompletableDeferred<Unit>()
        var openRelease: CompletableDeferred<Unit> = CompletableDeferred<Unit>().also { it.complete(Unit) }

        override suspend fun open(request: OpenSessionRequest): OpenSessionResponse {
            opens.add(request.serial)
            if (!openEntered.isCompleted) openEntered.complete(Unit)
            withTimeout(30_000) { openRelease.await() }
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
            com.company.tap.api.v1.AppBool
                .getDefaultInstance()
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
