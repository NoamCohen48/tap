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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
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
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
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
