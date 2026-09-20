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
    private val heartbeatIntervalMs: Long = ATTACH_HEARTBEAT_MS,
) : ConnectionServiceGrpcKt.ConnectionServiceCoroutineImplBase() {
    override suspend fun open(request: OpenConnectionRequest): OpenConnectionResponse =
        reply {
            OpenConnectionResponse.newBuilder().setConnectionId(service.openConnection(request.name.ifBlank { "unnamed" }).id).build()
        }

    /**
     * Liveness: the stream stays open for the connection's lifetime. Exactly one Attach stream
     * owns a connection: [TapService.attachAcquire] atomically claims the owner and registers
     * the cancel hook, so a duplicate fails FAILED_PRECONDITION without touching the valid
     * stream, and a close concurrent with registration either rejects the newcomer (NOT_FOUND)
     * or cancels it promptly with no callback leak. When the client goes away gRPC cancels
     * collection and the `finally` closes the connection (and every session it owns). When the
     * connection closes another way the hook cancels this collection, so the stream ends
     * instead of heartbeating a dead connection. A heartbeat event every 15 s keeps idle
     * proxies from dropping the stream; it never detects death, cancellation does.
     */
    override fun attach(request: AttachRequest): Flow<ConnectionEvent> =
        flow {
            val token = Any()
            val self = currentCoroutineContext()[Job]
            val closer: () -> Unit = { self?.cancel(CancellationException("connection closed")) }
            val connection =
                try {
                    service.attachAcquire(request.connectionId, token, closer)
                } catch (error: Throwable) {
                    throw error.toStatus()
                }
            try {
                emit(event("attached to connection ${connection.id}"))
                while (currentCoroutineContext().isActive) {
                    delay(heartbeatIntervalMs)
                    emit(event("heartbeat"))
                }
            } finally {
                service.attachRelease(connection, token, closer)
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

    companion object {
        /** Outbound heartbeat cadence; not a death detector, only idle-proxy traffic. */
        const val ATTACH_HEARTBEAT_MS = 15_000L
    }

    private fun event(message: String): ConnectionEvent =
        ConnectionEvent
            .newBuilder()
            .setAtEpochMs(System.currentTimeMillis())
            .setMessage(message)
            .build()
}
