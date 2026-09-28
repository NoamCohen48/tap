package io.github.noamcohen48.tap.daemon.grpc

import io.github.noamcohen48.tap.api.v1.AttachedDeviceEntry
import io.github.noamcohen48.tap.api.v1.ClientConnectionServiceGrpcKt
import io.github.noamcohen48.tap.api.v1.Closing
import io.github.noamcohen48.tap.api.v1.ConnectionEntry
import io.github.noamcohen48.tap.api.v1.ConnectRequest
import io.github.noamcohen48.tap.api.v1.ConnectResponse
import io.github.noamcohen48.tap.api.v1.DisconnectRequest
import io.github.noamcohen48.tap.api.v1.DisconnectResponse
import io.github.noamcohen48.tap.api.v1.EventsRequest
import io.github.noamcohen48.tap.api.v1.EventsResponse
import io.github.noamcohen48.tap.api.v1.Heartbeat
import io.github.noamcohen48.tap.api.v1.Hold
import io.github.noamcohen48.tap.api.v1.InfoRequest
import io.github.noamcohen48.tap.api.v1.InfoResponse
import io.github.noamcohen48.tap.api.v1.ListConnectionsRequest
import io.github.noamcohen48.tap.api.v1.ListConnectionsResponse
import io.github.noamcohen48.tap.api.v1.ObserveRequest
import io.github.noamcohen48.tap.api.v1.ObserveResponse
import io.github.noamcohen48.tap.api.v1.Observing
import io.github.noamcohen48.tap.daemon.core.TapDaemon
import io.github.noamcohen48.tap.protocol.HOST_BUILD_ID
import io.github.noamcohen48.tap.protocol.PROTOCOL_VERSION_ORDER
import io.github.noamcohen48.tap.protocol.SUPPORTED_PROTOCOL_VERSIONS
import io.github.noamcohen48.tap.protocol.render
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds

class ClientConnectionService(
    private val daemon: TapDaemon,
    private val heartbeatIntervalMs: Long = OBSERVE_HEARTBEAT_MS,
) : ClientConnectionServiceGrpcKt.ClientConnectionServiceCoroutineImplBase() {
    override suspend fun connect(request: ConnectRequest): ConnectResponse =
        reply {
            val holdIdleMs =
                if (request.hasHold()) {
                    argument(request.name.isNotBlank()) { "a held connection needs a name" }
                    request.hold.idleTimeoutMs.also {
                        argument(it in MIN_HOLD_IDLE_MS..MAX_HOLD_IDLE_MS) {
                            "hold.idle_timeout_ms must be in $MIN_HOLD_IDLE_MS..$MAX_HOLD_IDLE_MS, got $it"
                        }
                    }
                } else {
                    null
                }
            ConnectResponse
                .newBuilder()
                .setClientConnectionId(daemon.connectClient(request.name.ifBlank { "unnamed" }, holdIdleMs).id)
                .build()
        }

    override suspend fun listConnections(request: ListConnectionsRequest): ListConnectionsResponse =
        reply {
            ListConnectionsResponse
                .newBuilder()
                .addAllConnections(
                    daemon.connections().sortedBy { it.name }.map { connection ->
                        ConnectionEntry
                            .newBuilder()
                            .setClientConnectionId(connection.id)
                            .setName(connection.name)
                            .setIdleMs(connection.idleMs)
                            .apply { connection.holdIdleMs?.let { hold = Hold.newBuilder().setIdleTimeoutMs(it).build() } }
                            .addAllAttachedDevices(
                                connection.attachedDevices.map { device ->
                                    AttachedDeviceEntry
                                        .newBuilder()
                                        .setAttachedDeviceId(device.id)
                                        .setSerial(device.deviceSession.serial)
                                        .setAutPackage(device.deviceSession.autPackage)
                                        .setGeneration(device.deviceSession.generation)
                                        .build()
                                },
                            ).build()
                    },
                ).build()
        }

    override suspend fun events(request: EventsRequest): EventsResponse =
        reply {
            argument(request.afterSeq >= 0) { "after_seq must be >= 0" }
            val (events, dropped) = daemon.eventLog(request.clientConnectionId).after(request.afterSeq)
            EventsResponse.newBuilder().addAllEvents(events).setDropped(dropped).build()
        }

    /**
     * The single Observe stream is the connection's liveness signal. Registration and close
     * callbacks are linearized by the daemon lifecycle lock. A stale stream token cannot release
     * a newer observer, and stream termination disconnects the client and detaches its devices.
     * A daemon-side disconnect sends `closing` and completes the stream normally.
     */
    override fun observe(request: ObserveRequest): Flow<ObserveResponse> =
        flow {
            val token = Any()
            val disconnected = CompletableDeferred<String>()
            val closer: (String) -> Unit = { reason -> disconnected.complete(reason) }
            val connection =
                try {
                    daemon.observeAcquire(request.clientConnectionId, token, closer)
                } catch (error: Throwable) {
                    throw error.toStatus()
                }
            try {
                emit(event { observing = Observing.newBuilder().setClientConnectionId(connection.id).build() })
                while (true) {
                    val reason = withTimeoutOrNull(heartbeatIntervalMs.milliseconds) { disconnected.await() }
                    if (reason != null) {
                        emit(event { closing = Closing.newBuilder().setReason(reason).build() })
                        break
                    }
                    emit(event { heartbeat = Heartbeat.getDefaultInstance() })
                }
            } finally {
                daemon.disconnectObservedClient(connection.id, token, closer, "client observation ended")
            }
        }

    override suspend fun disconnect(request: DisconnectRequest): DisconnectResponse =
        reply {
            val detached = daemon.disconnectClient(request.clientConnectionId, "client request")
            DisconnectResponse.newBuilder().setAttachedDevicesDetached(detached).build()
        }

    override suspend fun info(request: InfoRequest): InfoResponse =
        reply {
            InfoResponse
                .newBuilder()
                .setDaemonVersion(DAEMON_VERSION)
                .setHostBuildId(HOST_BUILD_ID)
                .setProtocolVersion(SUPPORTED_PROTOCOL_VERSIONS.maxWith(PROTOCOL_VERSION_ORDER).render())
                .setAdbExecutable(daemon.config.adb.executable)
                .setStateDir(daemon.config.stateDir.toString())
                .setDriverAvailable(daemon.config.driver != null)
                .setPid(ProcessHandle.current().pid())
                .setDefaults(Defaults.message)
                .build()
        }

    companion object {
        /** Outbound heartbeat cadence; not a death detector, only idle-proxy traffic. */
        const val OBSERVE_HEARTBEAT_MS = 15_000L

        /** Bounds for `ConnectRequest.hold.idle_timeout_ms`. */
        const val MIN_HOLD_IDLE_MS = 1_000L
        const val MAX_HOLD_IDLE_MS = 86_400_000L
    }

    private inline fun event(fill: ObserveResponse.Builder.() -> Unit): ObserveResponse =
        ObserveResponse
            .newBuilder()
            .setAtEpochMs(System.currentTimeMillis())
            .apply(fill)
            .build()
}
