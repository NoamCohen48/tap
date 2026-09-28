package io.github.noamcohen48.tap.junit5

import io.github.noamcohen48.tap.api.v1.ClientConnectionServiceGrpcKt
import io.github.noamcohen48.tap.api.v1.ConnectRequest
import io.github.noamcohen48.tap.api.v1.ConnectResponse
import io.github.noamcohen48.tap.api.v1.DisconnectRequest
import io.github.noamcohen48.tap.api.v1.DisconnectResponse
import io.github.noamcohen48.tap.api.v1.ObserveRequest
import io.github.noamcohen48.tap.api.v1.ObserveResponse
import io.github.noamcohen48.tap.api.v1.Observing
import io.github.noamcohen48.tap.sdk.TapClient
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** [ConnectionMemo] behaviour over an in-process fake server: reuse, re-validation, close. */
class ConnectionMemoTest {
    private lateinit var serverName: String
    private lateinit var fake: FakeConnections
    private lateinit var grpcServer: io.grpc.Server
    private val clients = CopyOnWriteArrayList<TapClient>()
    private val closedClients = AtomicInteger(0)
    private val starts = AtomicInteger(0)
    private val stops = AtomicInteger(0)

    @BeforeEach
    fun start() {
        serverName = InProcessServerBuilder.generateName()
        fake = FakeConnections()
        grpcServer =
            InProcessServerBuilder
                .forName(serverName)
                .directExecutor()
                .addService(fake)
                .build()
                .start()
    }

    @AfterEach
    fun stop() {
        clients.forEach { it.channel.shutdownNow() }
        grpcServer.shutdownNow()
    }

    private fun memo(
        manageDaemon: Boolean = false,
        daemonStarted: Boolean = true,
    ) = ConnectionMemo(
        manageDaemon = { manageDaemon },
        startDaemon = {
            starts.incrementAndGet()
            daemonStarted
        },
        stopDaemon = { stops.incrementAndGet() },
        createClient = {
            TapClient("inprocess:$serverName", InProcessChannelBuilder.forName(serverName).directExecutor().build())
                .also(clients::add)
        },
        connect = { it.connect("test") },
        closeClient = {
            closedClients.incrementAndGet()
            it.close()
        },
    )

    @Test
    fun `a usable connection is reused`() =
        runBlocking {
            val memo = memo()
            val first = memo.connection()
            assertSame(first, memo.connection())
            assertEquals(1, fake.connects.get())
            memo.shutdown()
        }

    @Test
    fun `a connection whose liveness stream ended is replaced with a new client`() =
        runBlocking {
            val memo = memo()
            val first = memo.connection()
            fake.drop(first.id)
            withTimeout(5_000) { while (!first.isInvalid) delay(10) }
            val second = memo.connection()
            assertNotSame(first, second)
            assertTrue(second.isUsable)
            assertEquals(2, fake.connects.get())
            assertEquals(2, clients.size, "a new client re-resolves the daemon")
            assertEquals(1, closedClients.get(), "the stale client is closed")
            memo.shutdown()
        }

    @Test
    fun `a connection closed elsewhere is replaced`() =
        runBlocking {
            val memo = memo()
            val first = memo.connection()
            first.close()
            val second = memo.connection()
            assertNotSame(first, second)
            assertTrue(second.isUsable)
            memo.shutdown()
        }

    @Test
    fun `concurrent first gets share one connection`() =
        runBlocking {
            val memo = memo()
            val all = (1..16).map { async(Dispatchers.Default) { memo.connection() } }.awaitAll()
            assertEquals(1, all.toSet().size)
            assertEquals(1, fake.connects.get())
            memo.shutdown()
        }

    @Test
    fun `a failed connect closes its client and the next get retries`() =
        runBlocking {
            val memo = memo()
            fake.failNextConnect = true
            assertFailsWith<Exception> { memo.connection() }
            assertEquals(1, closedClients.get())
            assertTrue(memo.connection().isUsable)
            memo.shutdown()
        }

    @Test
    fun `shutdown closes everything once and stops only a daemon it started`() =
        runBlocking {
            val memo = memo(manageDaemon = true)
            val connection = memo.connection()
            memo.shutdown()
            assertTrue(connection.isClosed)
            assertEquals(listOf(connection.id), fake.disconnects.toList())
            assertEquals(1, closedClients.get())
            assertEquals(1, stops.get())
            memo.shutdown()
            assertEquals(1, stops.get(), "a second shutdown is a no-op")
            // The next access opens a new pair.
            assertTrue(memo.connection().isUsable)
            memo.shutdown()

            val alreadyRunning = memo(manageDaemon = true, daemonStarted = false)
            alreadyRunning.connection()
            alreadyRunning.shutdown()
            assertEquals(2, stops.get(), "a daemon that was already running is left alone")
        }

    private class FakeConnections : ClientConnectionServiceGrpcKt.ClientConnectionServiceCoroutineImplBase() {
        val connects = AtomicInteger(0)
        val disconnects = CopyOnWriteArrayList<String>()
        private val drops = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

        @Volatile var failNextConnect = false

        fun drop(id: String) {
            drops.computeIfAbsent(id) { CompletableDeferred() }.complete(Unit)
        }

        override suspend fun connect(request: ConnectRequest): ConnectResponse {
            if (failNextConnect) {
                failNextConnect = false
                throw StatusRuntimeException(Status.UNAVAILABLE.withDescription("connect boom"))
            }
            return ConnectResponse.newBuilder().setClientConnectionId("conn-${connects.incrementAndGet()}").build()
        }

        override fun observe(request: ObserveRequest): Flow<ObserveResponse> =
            flow {
                emit(ObserveResponse.newBuilder().setObserving(Observing.newBuilder().setClientConnectionId(request.clientConnectionId)).build())
                drops.computeIfAbsent(request.clientConnectionId) { CompletableDeferred() }.await()
            }

        override suspend fun disconnect(request: DisconnectRequest): DisconnectResponse {
            disconnects.add(request.clientConnectionId)
            return DisconnectResponse.getDefaultInstance()
        }
    }
}
