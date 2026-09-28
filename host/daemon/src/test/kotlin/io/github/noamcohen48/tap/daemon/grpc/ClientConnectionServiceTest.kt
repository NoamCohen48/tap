package io.github.noamcohen48.tap.daemon.grpc

import io.github.noamcohen48.tap.api.v1.ClientConnectionServiceGrpc
import io.github.noamcohen48.tap.api.v1.ConnectRequest
import io.github.noamcohen48.tap.api.v1.DisconnectRequest
import io.github.noamcohen48.tap.api.v1.FailureReason
import io.github.noamcohen48.tap.api.v1.Hold
import io.github.noamcohen48.tap.api.v1.ListConnectionsRequest
import io.github.noamcohen48.tap.api.v1.ObserveRequest
import io.github.noamcohen48.tap.daemon.core.DaemonConfig
import io.github.noamcohen48.tap.daemon.core.TapDaemon
import io.github.noamcohen48.tap.host.Adb
import io.grpc.ManagedChannel
import io.grpc.Server
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The Observe servicer over real gRPC: the liveness contract clients depend on. */
class ClientConnectionServiceTest {
    private val daemon =
        TapDaemon(
            DaemonConfig(
                adb = object : Adb("fake-adb") {},
                stateDir = Files.createTempDirectory("tap-servicer"),
                driver = null,
                log = {},
            ),
        )
    private val name = InProcessServerBuilder.generateName()
    private val server: Server =
        InProcessServerBuilder
            .forName(name)
            .addService(ClientConnectionService(daemon, heartbeatIntervalMs = 50))
            .build()
            .start()
    private val channel: ManagedChannel = InProcessChannelBuilder.forName(name).build()
    private val stub = ClientConnectionServiceGrpc.newBlockingStub(channel).withDeadlineAfter(10, TimeUnit.SECONDS)

    @AfterTest
    fun tearDown() {
        channel.shutdownNow()
        server.shutdownNow()
    }

    private fun connect(): String = stub.connect(ConnectRequest.newBuilder().setName("t").build()).clientConnectionId

    private fun observe(id: String) = stub.observe(ObserveRequest.newBuilder().setClientConnectionId(id).build())

    @Test
    fun `observe acknowledges, heartbeats, and ends with closing on Disconnect`() {
        val id = connect()
        val events = observe(id)
        assertEquals(id, events.next().observing.clientConnectionId)
        assertTrue(events.next().hasHeartbeat())
        stub.disconnect(DisconnectRequest.newBuilder().setClientConnectionId(id).build())
        // Drains to the end without an error status: Disconnect sends `closing`, then completes.
        val rest = generateSequence { if (events.hasNext()) events.next() else null }.toList()
        assertEquals("client request", rest.last().closing.reason)
        assertFalse(daemon.clientConnectionExists(id))
    }

    @Test
    fun `a second observer is rejected and the first keeps the connection`() {
        val id = connect()
        val first = observe(id)
        first.next()
        val error = assertFailsWith<StatusRuntimeException> { observe(id).next() }
        assertEquals(Status.Code.FAILED_PRECONDITION, error.status.code)
        assertTrue(first.next().hasHeartbeat())
        assertTrue(daemon.clientConnectionExists(id))
    }

    @Test
    fun `observing an unknown connection is NOT_FOUND`() {
        val error = assertFailsWith<StatusRuntimeException> { observe("nope").next() }
        assertEquals(Status.Code.NOT_FOUND, error.status.code)
    }

    @Test
    fun `cancelling the observe stream disconnects the client`() {
        val id = connect()
        val context = io.grpc.Context.current().withCancellation()
        val events = context.call { observe(id) }
        events.next()
        context.cancel(null)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (daemon.clientConnectionExists(id) && System.nanoTime() < deadline) Thread.sleep(10)
        assertFalse(daemon.clientConnectionExists(id))
    }

    private fun connectHeld(
        name: String,
        idleMs: Long,
    ): String =
        stub
            .connect(ConnectRequest.newBuilder().setName(name).setHold(Hold.newBuilder().setIdleTimeoutMs(idleMs)).build())
            .clientConnectionId

    private fun rejected(call: () -> Unit): Pair<Status.Code, FailureReason> {
        val error = assertFailsWith<StatusRuntimeException> { call() }
        return error.status.code to checkNotNull(error.trailers?.get(FAILURE_TRAILER)).reason
    }

    @Test
    fun `a held connection is listed by name, refuses Observe and keeps its name unique`() {
        val held = connectHeld("agent", 60_000)
        val observed = connect()
        val rows = stub.listConnections(ListConnectionsRequest.getDefaultInstance()).connectionsList.associateBy { it.clientConnectionId }
        assertEquals("agent", rows.getValue(held).name)
        assertEquals(60_000L, rows.getValue(held).hold.idleTimeoutMs)
        assertFalse(rows.getValue(observed).hasHold())
        assertEquals(Status.Code.FAILED_PRECONDITION to FailureReason.FAILURE_REASON_DAEMON_PRECONDITION, rejected { observe(held).next() })
        assertEquals(Status.Code.FAILED_PRECONDITION to FailureReason.FAILURE_REASON_DAEMON_PRECONDITION, rejected { connectHeld("agent", 60_000) })
        stub.disconnect(DisconnectRequest.newBuilder().setClientConnectionId(held).build())
        connectHeld("agent", 60_000)
    }

    @Test
    fun `a held connection needs a name and an idle timeout within bounds`() {
        val invalid = Status.Code.INVALID_ARGUMENT to FailureReason.FAILURE_REASON_INVALID_ARGUMENT
        assertEquals(invalid, rejected { connectHeld(" ", 60_000) })
        assertEquals(invalid, rejected { connectHeld("a", 999) })
        assertEquals(invalid, rejected { connectHeld("b", 86_400_001) })
        connectHeld("c", 1_000)
        connectHeld("d", 86_400_000)
    }
}
