package com.company.tap.service.servicer

import com.company.tap.api.v1.AttachRequest
import com.company.tap.api.v1.CloseConnectionRequest
import com.company.tap.api.v1.CloseConnectionResponse
import com.company.tap.api.v1.ConnectionEvent
import com.company.tap.api.v1.ConnectionServiceGrpc
import com.company.tap.api.v1.InfoRequest
import com.company.tap.api.v1.InfoResponse
import com.company.tap.api.v1.OpenConnectionRequest
import com.company.tap.api.v1.OpenConnectionResponse
import com.company.tap.protocol.HOST_BUILD_ID
import com.company.tap.service.TapService
import io.grpc.stub.ServerCallStreamObserver
import io.grpc.stub.StreamObserver
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class ConnectionServicer(
    private val service: TapService,
    private val scheduler: ScheduledExecutorService,
) : ConnectionServiceGrpc.ConnectionServiceImplBase() {
    override fun open(request: OpenConnectionRequest, observer: StreamObserver<OpenConnectionResponse>) = reply(observer) {
        OpenConnectionResponse.newBuilder().setConnectionId(service.openConnection(request.name.ifBlank { "unnamed" }).id).build()
    }

    /**
     * Liveness: the stream stays open for the connection's lifetime. When the client goes away
     * gRPC cancels the call and the connection (and every session it owns) is closed. A heartbeat event every 15 s
     * keeps idle proxies from dropping the stream.
     */
    override fun attach(request: AttachRequest, observer: StreamObserver<ConnectionEvent>) {
        val call = observer as ServerCallStreamObserver<ConnectionEvent>
        val connection = try {
            service.connection(request.connectionId)
        } catch (error: Throwable) {
            call.onError(error.toStatus())
            return
        }
        val heartbeat = scheduler.scheduleAtFixedRate({
            try {
                if (!call.isCancelled) call.onNext(event("heartbeat"))
            } catch (_: Throwable) {
            }
        }, 15, 15, TimeUnit.SECONDS)
        call.setOnCancelHandler {
            heartbeat.cancel(false)
            service.closeConnection(connection.id, "client detached")
        }
        connection.onClose += {
            heartbeat.cancel(false)
            runCatching { if (!call.isCancelled) call.onCompleted() }
        }
        call.onNext(event("attached to connection ${connection.id}"))
    }

    override fun close(request: CloseConnectionRequest, observer: StreamObserver<CloseConnectionResponse>) = reply(observer) {
        val sessions = service.closeConnection(request.connectionId, "client request")
        CloseConnectionResponse.newBuilder().setSessionsClosed(sessions).build()
    }

    override fun info(request: InfoRequest, observer: StreamObserver<InfoResponse>) = reply(observer) {
        InfoResponse.newBuilder()
            .setServiceVersion(SERVICE_VERSION)
            .setHostBuildId(HOST_BUILD_ID)
            .setProtocolVersion("1.0")
            .setAdbExecutable(service.config.adb.executable)
            .setStateDir(service.config.stateDir.toString())
            .setBundledDriver(service.config.bundledDriver != null)
            .build()
    }

    private fun event(message: String): ConnectionEvent =
        ConnectionEvent.newBuilder().setAtEpochMs(System.currentTimeMillis()).setMessage(message).build()
}