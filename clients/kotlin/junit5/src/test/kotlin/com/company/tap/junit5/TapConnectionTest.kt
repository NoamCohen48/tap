package com.company.tap.junit5

import com.company.tap.api.v1.AttachRequest
import com.company.tap.api.v1.CloseConnectionRequest
import com.company.tap.api.v1.CloseConnectionResponse
import com.company.tap.api.v1.ConnectionEvent
import com.company.tap.api.v1.ConnectionServiceGrpcKt
import com.company.tap.api.v1.InfoRequest
import com.company.tap.api.v1.InfoResponse
import com.company.tap.api.v1.OpenConnectionRequest
import com.company.tap.api.v1.OpenConnectionResponse
import com.company.tap.sdk.TapClient
import io.grpc.ManagedChannel
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * `TapConnectionState` generations over in-process fakes: sequential launcher sessions share
 * one client/connection, shut down exactly once (service stopped iff this generation started
 * it), and reopen cleanly afterwards; a shutdown-vs-connection race still closes every
 * created connection exactly once with no half-closed pair observable.
 */
class TapConnectionTest {
    private lateinit var serverName: String
    private lateinit var fakeConnections: FakeConnections
    private lateinit var grpcServer: io.grpc.Server
    private lateinit var channel: ManagedChannel
    private val channels = CopyOnWriteArrayList<ManagedChannel>()
    private val createdClients = AtomicInteger(0)
    private val createdConnections = AtomicInteger(0)
    private val starts = AtomicInteger(0)
    private val stops = AtomicInteger(0)

    @BeforeEach
    fun start() {
        serverName = InProcessServerBuilder.generateName()
        fakeConnections = FakeConnections()
        grpcServer =
            InProcessServerBuilder
                .forName(serverName)
                .directExecutor()
                .addService(fakeConnections)
                .addService(FakeSessions())
                .addService(FakeDevices())
                .build()
                .start()
        channel = InProcessChannelBuilder.forName(serverName).directExecutor().build()
    }

    @AfterEach
    fun stop() {
        channel.shutdownNow()
        channels.forEach { it.shutdownNow() }
        grpcServer.shutdownNow()
    }

    private fun state(manageService: Boolean = true) =
        TapConnectionState(
            manageService = { manageService },
            startService = {
                starts.incrementAndGet()
                true
            },
            stopService = { stops.incrementAndGet() },
            createClient = {
                createdClients.incrementAndGet()
                val ch = InProcessChannelBuilder.forName(serverName).directExecutor().build()
                channels.add(ch)
                TapClient("inprocess:$serverName", ch)
            },
            connectClient = { client ->
                createdConnections.incrementAndGet()
                client.connect("test")
            },
            onFirstClient = { },
        )

    @Test
    fun `sequential launcher sessions share, shut down once and reopen`() =
        runBlocking {
            val connections = state(manageService = true)
            assertSame(connections.client(), connections.client(), "one client per generation")
            val first = connections.connection()
            assertTrue(first.recentEvents.isNotEmpty(), "attach established before first use")
            assertSame(first, connections.connection(), "one connection per generation")
            assertEquals(1, createdClients.get())
            assertEquals(1, createdConnections.get())

            connections.shutdown()
            assertEquals(1, fakeConnections.closes.get(), "exactly one Close RPC")
            assertEquals(1, starts.get(), "service started once")
            assertEquals(1, stops.get(), "service this generation started is stopped")
            withTimeout(5_000) {
                while (fakeConnections.attachCancelled.get() < 1) delay(10)
            }
            assertTrue(channels.all { it.isShutdown }, "client channel shut down")

            connections.shutdown()
            assertEquals(1, fakeConnections.closes.get(), "second shutdown is a no-op")
            assertEquals(1, stops.get())

            // Post-close access explicitly opens a new generation, accounted again.
            val second = connections.connection()
            assertNotSame(first, second, "new generation, not a reused ref")
            assertTrue(second.recentEvents.isNotEmpty(), "reopened connection attaches cleanly")
            assertEquals(2, createdClients.get())
            assertEquals(2, createdConnections.get())
            connections.shutdown()
            assertEquals(2, fakeConnections.closes.get(), "each generation closed exactly once")
            assertEquals(2, stops.get())
        }

    @Test
    fun `shutdown-vs-connection race closes every created connection exactly once`() =
        runBlocking {
            val connections = state(manageService = false)
            repeat(10) {
                coroutineScope {
                    launch { connections.connection() }
                    launch { connections.shutdown() }
                }
            }
            connections.shutdown()
            val created = createdConnections.get()
            assertTrue(created > 1, "the race actually created several generations, saw $created")
            assertEquals(created, fakeConnections.closes.get(), "every created connection got its Close RPC")
            withTimeout(10_000) {
                while (fakeConnections.attachCancelled.get() < created) delay(10)
            }
            assertEquals(created, fakeConnections.attachCancelled.get(), "every attach scope dropped exactly once")
            assertEquals(0, starts.get(), "unmanaged service is never started")
            assertEquals(0, stops.get(), "unmanaged service is never stopped")
            // The state is still usable afterwards: post-close access reopens.
            val reopened = connections.connection()
            assertTrue(reopened.recentEvents.isNotEmpty())
            connections.shutdown()
            assertEquals(created + 1, fakeConnections.closes.get())
        }

    @Test
    fun `concurrent connections share one without extra creations`() =
        runBlocking {
            val connections = state(manageService = false)
            val shared =
                coroutineScope {
                    List(8) { async { connections.connection() } }.map { it.await() }
                }
            assertTrue(shared.all { it === shared.first() }, "all racers share the single connection")
            assertEquals(1, createdClients.get())
            assertEquals(1, createdConnections.get())
            connections.shutdown()
            assertEquals(1, fakeConnections.closes.get())
        }

    @Test
    fun `managed generation cannot start or connect until the prior stop completes`() =
        runBlocking {
            val events = CopyOnWriteArrayList<String>()
            val stopEntered = CompletableDeferred<Unit>()
            val allowStop = CompletableDeferred<Unit>()
            val parkedOnTeardown = CompletableDeferred<Unit>()
            val connects = AtomicInteger(0)
            val connections =
                TapConnectionState(
                    manageService = { true },
                    startService = {
                        val n = starts.incrementAndGet()
                        events.add("start$n")
                        true
                    },
                    stopService = {
                        val n = stops.incrementAndGet()
                        if (n == 1) {
                            events.add("stop1-enter")
                            stopEntered.complete(Unit)
                            allowStop.await()
                            events.add("stop1-done")
                        } else {
                            events.add("stop$n")
                        }
                    },
                    createClient = {
                        createdClients.incrementAndGet()
                        val ch = InProcessChannelBuilder.forName(serverName).directExecutor().build()
                        channels.add(ch)
                        TapClient("inprocess:$serverName", ch)
                    },
                    connectClient = { client ->
                        val n = connects.incrementAndGet()
                        events.add("connect$n-enter")
                        client.connect("test").also { events.add("connect$n-done") }
                    },
                    onFirstClient = { },
                    onTeardownPark = {
                        if (!parkedOnTeardown.isCompleted) parkedOnTeardown.complete(Unit)
                    },
                )
            // Generation 1: client + connection, one owned start.
            connections.client()
            connections.connection()
            assertEquals(1, starts.get())
            // Tear generation 1 down in the background; its owned stop parks on the
            // test gate so generation 2 must queue behind the teardown gate.
            val shutdown =
                async {
                    connections.shutdown()
                }
            withTimeout(10_000) { stopEntered.await() }
            val next =
                async {
                    connections.connection()
                }
            // Explicit barrier, no timing: the hook proves generation 2 observed the
            // teardown gate and is parked before the prior stop is released.
            withTimeout(10_000) { parkedOnTeardown.await() }
            assertEquals(1, starts.get(), "generation 2 must not start until generation 1 stops: $events")
            assertEquals(1, connects.get(), "generation 2 must not connect until generation 1 stops: $events")
            assertFalse(next.isCompleted, "generation 2 stays parked while generation 1 stops")
            allowStop.complete(Unit)
            val second =
                withTimeout(10_000) {
                    shutdown.await()
                    next.await()
                }
            assertTrue(second.recentEvents.isNotEmpty(), "gated generation attaches cleanly")
            val stopDone = events.indexOf("stop1-done")
            val start2 = events.indexOf("start2")
            val connect2 = events.indexOf("connect2-enter")
            assertTrue(stopDone >= 0 && start2 >= 0 && connect2 >= 0, "all generation-2 steps ran: $events")
            assertTrue(stopDone < start2, "generation 2 starts only after the prior stop: $events")
            assertTrue(stopDone < connect2, "generation 2 connects only after the prior stop: $events")
            // Sequential launcher session: the gated generation is a full generation,
            // stopped exactly once when it shuts down.
            connections.shutdown()
            assertEquals(2, starts.get(), "exactly two owned starts: $events")
            assertEquals(2, stops.get(), "exactly two owned stops: $events")
            assertEquals(2, fakeConnections.closes.get(), "each generation closed exactly once")
        }

    @Test
    fun `throwing create after an owned start stops exactly once`() =
        runBlocking {
            val failCreate = AtomicBoolean(true)
            val connections =
                TapConnectionState(
                    manageService = { true },
                    startService = {
                        starts.incrementAndGet()
                        true
                    },
                    stopService = { stops.incrementAndGet() },
                    createClient = {
                        if (failCreate.get()) throw IllegalStateException("boom")
                        createdClients.incrementAndGet()
                        val ch = InProcessChannelBuilder.forName(serverName).directExecutor().build()
                        channels.add(ch)
                        TapClient("inprocess:$serverName", ch)
                    },
                    connectClient = { client ->
                        createdConnections.incrementAndGet()
                        client.connect("test")
                    },
                    onFirstClient = { },
                )
            val failure = assertFailsWith<IllegalStateException> { connections.client() }
            assertEquals("boom", failure.message)
            assertEquals(1, starts.get(), "the failed generation started its service")
            assertEquals(1, stops.get(), "the owned start is rolled back exactly once")
            // The state stays usable: the next generation starts and stops cleanly.
            failCreate.set(false)
            connections.client()
            assertEquals(2, starts.get())
            assertEquals(1, stops.get())
            connections.shutdown()
            assertEquals(2, starts.get())
            assertEquals(2, stops.get())
        }

    @Test
    fun `cancelled create rolls back owned start and next client succeeds`() =
        runBlocking {
            val enteredCreate = CompletableDeferred<Unit>()
            val clientCloses = AtomicInteger(0)
            val connections =
                TapConnectionState(
                    manageService = { true },
                    startService = {
                        starts.incrementAndGet()
                        true
                    },
                    stopService = { stops.incrementAndGet() },
                    createClient = {
                        if (createdClients.get() == 0) {
                            createdClients.incrementAndGet()
                            enteredCreate.complete(Unit)
                            awaitCancellation()
                            error("unreachable")
                        } else {
                            createdClients.incrementAndGet()
                            val ch = InProcessChannelBuilder.forName(serverName).directExecutor().build()
                            channels.add(ch)
                            TapClient("inprocess:$serverName", ch)
                        }
                    },
                    connectClient = { client ->
                        createdConnections.incrementAndGet()
                        client.connect("test")
                    },
                    onFirstClient = { },
                    closeClient = { client ->
                        clientCloses.incrementAndGet()
                        client.close()
                    },
                )
            val first = async { connections.client() }
            withTimeout(10_000) { enteredCreate.await() }
            first.cancel()
            assertFailsWith<CancellationException> { first.await() }
            assertEquals(1, starts.get(), "cancelled create had started its service")
            assertEquals(1, stops.get(), "owned start rolled back exactly once under NonCancellable")
            assertEquals(0, clientCloses.get(), "no client existed to close")
            // Flight cleared and gate completed: the next client opens a fresh generation.
            withTimeout(10_000) {
                connections.client()
            }
            assertEquals(2, starts.get())
            assertEquals(1, stops.get())
            assertEquals(2, createdClients.get())
            connections.shutdown()
            assertEquals(2, stops.get(), "second generation stopped exactly once")
            assertEquals(1, clientCloses.get(), "second generation client closed exactly once")
        }

    @Test
    fun `create failure with stop failure suppresses stop into primary exactly once`() =
        runBlocking {
            val connections =
                TapConnectionState(
                    manageService = { true },
                    startService = {
                        starts.incrementAndGet()
                        true
                    },
                    stopService = {
                        stops.incrementAndGet()
                        throw IllegalStateException("stop-boom")
                    },
                    createClient = {
                        createdClients.incrementAndGet()
                        throw IllegalArgumentException("create-boom")
                    },
                    connectClient = { client ->
                        createdConnections.incrementAndGet()
                        client.connect("test")
                    },
                    onFirstClient = { },
                )
            val failure = assertFailsWith<IllegalArgumentException> { connections.client() }
            assertEquals("create-boom", failure.message)
            assertEquals(1, failure.suppressed.size, "stop failure suppressed into create failure")
            assertTrue(failure.suppressed[0] is IllegalStateException)
            assertEquals("stop-boom", failure.suppressed[0].message)
            assertEquals(1, starts.get())
            assertEquals(1, stops.get(), "owned stop attempted exactly once")
            // State stays usable: the next generation starts and stops cleanly.
            val healthy =
                TapConnectionState(
                    manageService = { false },
                    startService = {
                        starts.incrementAndGet()
                        true
                    },
                    stopService = { stops.incrementAndGet() },
                    createClient = {
                        createdClients.incrementAndGet()
                        val ch = InProcessChannelBuilder.forName(serverName).directExecutor().build()
                        channels.add(ch)
                        TapClient("inprocess:$serverName", ch)
                    },
                    connectClient = { client ->
                        createdConnections.incrementAndGet()
                        client.connect("test")
                    },
                    onFirstClient = { },
                )
            healthy.client()
            healthy.shutdown()
        }

    @Test
    fun `cancelled connect rolls back owned client and stop and next connection succeeds`() =
        runBlocking {
            val enteredConnect = CompletableDeferred<Unit>()
            val clientCloses = AtomicInteger(0)
            val connections =
                TapConnectionState(
                    manageService = { true },
                    startService = {
                        starts.incrementAndGet()
                        true
                    },
                    stopService = { stops.incrementAndGet() },
                    createClient = {
                        createdClients.incrementAndGet()
                        val ch = InProcessChannelBuilder.forName(serverName).directExecutor().build()
                        channels.add(ch)
                        TapClient("inprocess:$serverName", ch)
                    },
                    connectClient = { client ->
                        if (createdConnections.get() == 0) {
                            createdConnections.incrementAndGet()
                            enteredConnect.complete(Unit)
                            awaitCancellation()
                            error("unreachable")
                        } else {
                            createdConnections.incrementAndGet()
                            client.connect("test")
                        }
                    },
                    onFirstClient = { },
                    closeClient = { client ->
                        clientCloses.incrementAndGet()
                        client.close()
                    },
                )
            val first = async { connections.connection() }
            withTimeout(10_000) { enteredConnect.await() }
            first.cancel()
            assertFailsWith<CancellationException> { first.await() }
            assertEquals(1, starts.get(), "cancelled connect had started its service")
            assertEquals(1, stops.get(), "owned stop rolled back exactly once under NonCancellable")
            assertEquals(1, clientCloses.get(), "owned client closed exactly once under NonCancellable")
            // The rolled-back generation completed its gate: the next connection opens fresh.
            withTimeout(10_000) {
                connections.connection()
            }
            assertEquals(2, starts.get())
            assertEquals(1, stops.get())
            assertEquals(2, createdClients.get(), "fresh generation recreates its client")
            assertEquals(2, createdConnections.get())
            connections.shutdown()
            assertEquals(2, stops.get())
            assertEquals(2, clientCloses.get())
            // The cancelled attach never published a connection, so only the fresh generation
            // has a Close RPC; both generations closed their clients exactly once above.
            assertEquals(1, fakeConnections.closes.get(), "only the published connection has a Close RPC")
        }

    @Test
    fun `connect failure with close and stop failures suppresses both in order exactly once`() =
        runBlocking {
            val clientCloses = AtomicInteger(0)
            val closeOrder = CopyOnWriteArrayList<String>()
            val connections =
                TapConnectionState(
                    manageService = { true },
                    startService = {
                        starts.incrementAndGet()
                        true
                    },
                    stopService = {
                        stops.incrementAndGet()
                        closeOrder.add("stop")
                        throw IllegalStateException("stop-boom")
                    },
                    createClient = {
                        createdClients.incrementAndGet()
                        val ch = InProcessChannelBuilder.forName(serverName).directExecutor().build()
                        channels.add(ch)
                        TapClient("inprocess:$serverName", ch)
                    },
                    connectClient = {
                        createdConnections.incrementAndGet()
                        throw IllegalArgumentException("connect-boom")
                    },
                    onFirstClient = { },
                    closeClient = { client ->
                        clientCloses.incrementAndGet()
                        closeOrder.add("close")
                        // Close the real channel so nothing leaks, then fail to prove suppression.
                        client.close()
                        throw IllegalStateException("close-boom")
                    },
                )
            val failure = assertFailsWith<IllegalArgumentException> { connections.connection() }
            assertEquals("connect-boom", failure.message)
            assertEquals(2, failure.suppressed.size, "close and stop failures suppressed into connect failure")
            assertTrue(failure.suppressed[0] is IllegalStateException)
            assertEquals("close-boom", failure.suppressed[0].message)
            assertTrue(failure.suppressed[1] is IllegalStateException)
            assertEquals("stop-boom", failure.suppressed[1].message)
            assertEquals(listOf("close", "stop"), closeOrder.toList(), "close runs before stop, both attempted")
            assertEquals(1, starts.get())
            assertEquals(1, stops.get(), "owned stop attempted exactly once")
            assertEquals(1, clientCloses.get(), "owned client close attempted exactly once")
            // The failed generation completed its gate: the next connection reopens cleanly.
            val healthy =
                TapConnectionState(
                    manageService = { false },
                    startService = {
                        starts.incrementAndGet()
                        true
                    },
                    stopService = { stops.incrementAndGet() },
                    createClient = {
                        createdClients.incrementAndGet()
                        val ch = InProcessChannelBuilder.forName(serverName).directExecutor().build()
                        channels.add(ch)
                        TapClient("inprocess:$serverName", ch)
                    },
                    connectClient = { client ->
                        createdConnections.incrementAndGet()
                        client.connect("test")
                    },
                    onFirstClient = { },
                )
            healthy.connection()
            healthy.shutdown()
        }

    @Test
    fun `cancelled shutdown while owning teardown still completes gate and next shutdown succeeds`() =
        runBlocking {
            val stopEntered = CompletableDeferred<Unit>()
            val allowStop = CompletableDeferred<Unit>()
            val parkedOnTeardown = CompletableDeferred<Unit>()
            val clientCloses = AtomicInteger(0)
            val connections =
                TapConnectionState(
                    manageService = { true },
                    startService = {
                        starts.incrementAndGet()
                        true
                    },
                    stopService = {
                        val n = stops.incrementAndGet()
                        if (n == 1) {
                            stopEntered.complete(Unit)
                            allowStop.await()
                        }
                    },
                    createClient = {
                        createdClients.incrementAndGet()
                        val ch = InProcessChannelBuilder.forName(serverName).directExecutor().build()
                        channels.add(ch)
                        TapClient("inprocess:$serverName", ch)
                    },
                    connectClient = { client ->
                        createdConnections.incrementAndGet()
                        client.connect("test")
                    },
                    onFirstClient = { },
                    closeClient = { client ->
                        clientCloses.incrementAndGet()
                        client.close()
                    },
                    onTeardownPark = {
                        if (!parkedOnTeardown.isCompleted) parkedOnTeardown.complete(Unit)
                    },
                )
            // Generation 1 fully published with one owned start.
            connections.client()
            connections.connection()
            assertEquals(1, starts.get())
            val shutdown = async { connections.shutdown() }
            withTimeout(10_000) { stopEntered.await() }
            // A second-generation accessor parks on the teardown gate (hook proves it).
            val waiter = async { connections.connection() }
            withTimeout(10_000) { parkedOnTeardown.await() }
            assertEquals(1, starts.get(), "parked waiter must not start before the owned stop")
            // Cancel the shutdown while it owns the teardown gate; NonCancellable teardown
            // must still run to completion before the original cancellation propagates.
            shutdown.cancel()
            assertFalse(shutdown.isCompleted, "owning shutdown stays in NonCancellable teardown after cancel")
            allowStop.complete(Unit)
            assertFailsWith<CancellationException> {
                withTimeout(10_000) { shutdown.await() }
            }
            assertEquals(1, stops.get(), "owned stop completed exactly once despite cancellation")
            assertEquals(1, clientCloses.get(), "owned client closed exactly once despite cancellation")
            assertEquals(1, fakeConnections.closes.get(), "owned connection closed exactly once")
            // Gate completed: the parked waiter proceeds to a fresh generation.
            withTimeout(10_000) { waiter.await() }
            assertEquals(2, starts.get(), "waiter opens generation 2 only after the prior stop")
            connections.shutdown()
            assertEquals(2, stops.get(), "second generation stopped exactly once")
            assertEquals(2, clientCloses.get())
            assertEquals(2, fakeConnections.closes.get())
        }

    @Test
    fun `cancelled shutdown while awaiting creator still completes gate and shares next generation`() =
        runBlocking {
            val enteredCreate = CompletableDeferred<Unit>()
            val allowCreate = CompletableDeferred<Unit>()
            val parkedOnTeardown = CompletableDeferred<Unit>()
            val clientCloses = AtomicInteger(0)
            val connections =
                TapConnectionState(
                    manageService = { true },
                    startService = {
                        starts.incrementAndGet()
                        true
                    },
                    stopService = { stops.incrementAndGet() },
                    createClient = {
                        if (createdClients.get() == 0) {
                            createdClients.incrementAndGet()
                            enteredCreate.complete(Unit)
                            allowCreate.await()
                            val ch = InProcessChannelBuilder.forName(serverName).directExecutor().build()
                            channels.add(ch)
                            TapClient("inprocess:$serverName", ch)
                        } else {
                            createdClients.incrementAndGet()
                            val ch = InProcessChannelBuilder.forName(serverName).directExecutor().build()
                            channels.add(ch)
                            TapClient("inprocess:$serverName", ch)
                        }
                    },
                    connectClient = { client ->
                        createdConnections.incrementAndGet()
                        client.connect("test")
                    },
                    onFirstClient = { },
                    closeClient = { client ->
                        clientCloses.incrementAndGet()
                        client.close()
                    },
                    onTeardownPark = {
                        if (!parkedOnTeardown.isCompleted) parkedOnTeardown.complete(Unit)
                    },
                )
            // Creator 1 owns the client flight and parks inside create after its owned start.
            val creator1 = async { connections.client() }
            withTimeout(10_000) { enteredCreate.await() }
            // Shutdown installs its teardown gate and awaits creator 1; creator 2 proves the
            // gate exists by parking on it (no timing assumptions).
            val shutdown = async { connections.shutdown() }
            val creator2 = async { connections.client() }
            withTimeout(10_000) { parkedOnTeardown.await() }
            // Cancel the shutdown while it awaits the creator flight: teardown must still
            // complete under NonCancellable before the original cancellation propagates.
            shutdown.cancel()
            allowCreate.complete(Unit)
            assertFailsWith<CancellationException> {
                withTimeout(10_000) { shutdown.await() }
            }
            // Both creators share the single fresh generation (single-flight holds across the
            // discard-and-retry); nothing leaks and the gate never stalls.
            val firstClient = withTimeout(10_000) { creator1.await() }
            val secondClient = withTimeout(10_000) { creator2.await() }
            assertSame(firstClient, secondClient, "discard-and-retry shares one next generation")
            assertEquals(2, starts.get(), "first start rolled back, second start published")
            assertEquals(1, stops.get(), "stale start stopped exactly once")
            assertEquals(1, clientCloses.get(), "stale client closed exactly once")
            connections.shutdown()
            assertEquals(2, stops.get())
            assertEquals(2, clientCloses.get())
        }

    // --- Fakes ----------------------------------------------------------------------------------

    private class FakeConnections : ConnectionServiceGrpcKt.ConnectionServiceCoroutineImplBase() {
        val attaches = AtomicInteger(0)
        val closes = AtomicInteger(0)
        val attachCancelled = AtomicInteger(0)

        override suspend fun open(request: OpenConnectionRequest): OpenConnectionResponse =
            OpenConnectionResponse.newBuilder().setConnectionId("conn-${attaches.get() + closes.get()}").build()

        override fun attach(request: AttachRequest): Flow<ConnectionEvent> =
            flow {
                attaches.incrementAndGet()
                emit(ConnectionEvent.newBuilder().setMessage("hello").build())
                try {
                    awaitCancellation()
                } finally {
                    attachCancelled.incrementAndGet()
                }
            }

        override suspend fun close(request: CloseConnectionRequest): CloseConnectionResponse {
            closes.incrementAndGet()
            return CloseConnectionResponse.getDefaultInstance()
        }

        override suspend fun info(request: InfoRequest): InfoResponse = InfoResponse.getDefaultInstance()
    }

    private class FakeSessions : com.company.tap.api.v1.SessionServiceGrpcKt.SessionServiceCoroutineImplBase() {
        override suspend fun open(request: com.company.tap.api.v1.OpenSessionRequest): com.company.tap.api.v1.OpenSessionResponse =
            com.company.tap.api.v1.OpenSessionResponse
                .newBuilder()
                .setSessionId("sess-${request.serial}")
                .setSerial(request.serial)
                .setGeneration(1)
                .build()

        override suspend fun execute(request: com.company.tap.api.v1.ExecuteRequest): com.company.tap.api.v1.CommandResult =
            com.company.tap.api.v1.CommandResult
                .getDefaultInstance()

        override suspend fun close(request: com.company.tap.api.v1.CloseSessionRequest): com.company.tap.api.v1.CloseSessionResponse =
            com.company.tap.api.v1.CloseSessionResponse
                .getDefaultInstance()

        override suspend fun screenshot(request: com.company.tap.api.v1.ScreenshotRequest): com.company.tap.api.v1.ScreenshotResponse =
            com.company.tap.api.v1.ScreenshotResponse
                .getDefaultInstance()

        override suspend fun driverLog(request: com.company.tap.api.v1.DriverLogRequest): com.company.tap.api.v1.DriverLogResponse =
            com.company.tap.api.v1.DriverLogResponse
                .getDefaultInstance()
    }

    private class FakeDevices : com.company.tap.api.v1.DeviceServiceGrpcKt.DeviceServiceCoroutineImplBase() {
        override suspend fun listDevices(request: com.company.tap.api.v1.ListDevicesRequest): com.company.tap.api.v1.ListDevicesResponse =
            com.company.tap.api.v1.ListDevicesResponse
                .getDefaultInstance()
    }
}
