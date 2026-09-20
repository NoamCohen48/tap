package com.company.tap.service.servicer

import com.company.tap.api.v1.AttachRequest
import com.company.tap.api.v1.CloseConnectionRequest
import com.company.tap.api.v1.CloseConnectionResponse
import com.company.tap.api.v1.ConnectionEvent
import com.company.tap.api.v1.ConnectionServiceGrpcKt
import com.company.tap.api.v1.InfoRequest
import com.company.tap.api.v1.InfoResponse
import com.company.tap.api.v1.OpenConnectionRequest
import com.company.tap.api.v1.OpenConnectionResponse
import com.company.tap.protocol.HOST_BUILD_ID
import com.company.tap.protocol.SUPPORTED_PROTOCOL_VERSIONS
import com.company.tap.service.TapService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive

class ConnectionServicer(
    private val service: TapService,
) : ConnectionServiceGrpcKt.ConnectionServiceCoroutineImplBase() {
    override suspend fun open(request: OpenConnectionRequest): OpenConnectionResponse =
        reply {
            OpenConnectionResponse.newBuilder().setConnectionId(service.openConnection(request.name.ifBlank { "unnamed" }).id).build()
        }

    /**
     * Liveness: the stream stays open for the connection's lifetime. When the client goes away
     * gRPC cancels collection and the `finally` closes the connection (and every session it
     * owns). When the connection closes another way the `onClose` hook cancels this
     * collection, so the stream ends instead of heartbeating a dead connection. A heartbeat
     * event every 15 s keeps idle proxies from dropping the stream.
     */
    override fun attach(request: AttachRequest): Flow<ConnectionEvent> =
        flow {
            val connection =
                try {
                    service.connection(request.connectionId)
                } catch (error: Throwable) {
                    throw error.toStatus()
                }
            val self = currentCoroutineContext()[Job]
            val closer: () -> Unit = { self?.cancel(CancellationException("connection closed")) }
            connection.onClose += closer
            try {
                emit(event("attached to connection ${connection.id}"))
                while (currentCoroutineContext().isActive) {
                    delay(15_000)
                    emit(event("heartbeat"))
                }
            } finally {
                connection.onClose -= closer
                service.closeConnection(connection.id, "client detached")
            }
        }

    override suspend fun close(request: CloseConnectionRequest): CloseConnectionResponse =
        reply {
            val sessions = service.closeConnection(request.connectionId, "client request")
            CloseConnectionResponse.newBuilder().setSessionsClosed(sessions).build()
        }

    override suspend fun info(request: InfoRequest): InfoResponse =
        reply {
            InfoResponse
                .newBuilder()
                .setServiceVersion(SERVICE_VERSION)
                .setHostBuildId(HOST_BUILD_ID)
                .setProtocolVersion(SUPPORTED_PROTOCOL_VERSIONS.max().let { "${it.major}.${it.minor}" })
                .setAdbExecutable(service.config.adb.executable)
                .setStateDir(service.config.stateDir.toString())
                .setBundledDriver(service.config.bundledDriver != null)
                .build()
        }

    private fun event(message: String): ConnectionEvent =
        ConnectionEvent
            .newBuilder()
            .setAtEpochMs(System.currentTimeMillis())
            .setMessage(message)
            .build()
}
