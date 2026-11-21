package io.github.noamcohen48.tap.daemon.cli

import io.github.noamcohen48.tap.api.v1.ClientConnectionServiceGrpc
import io.github.noamcohen48.tap.api.v1.ConnectionEntry
import io.github.noamcohen48.tap.api.v1.DeviceEntry
import io.github.noamcohen48.tap.api.v1.DeviceServiceGrpc
import io.github.noamcohen48.tap.api.v1.DisconnectRequest
import io.github.noamcohen48.tap.api.v1.InfoRequest
import io.github.noamcohen48.tap.api.v1.InfoResponse
import io.github.noamcohen48.tap.api.v1.ListConnectionsRequest
import io.github.noamcohen48.tap.api.v1.ListDevicesRequest
import io.github.noamcohen48.tap.api.v1.TAP_API_COMPATIBILITY
import io.github.noamcohen48.tap.daemon.grpc.DAEMON_VERSION
import io.github.noamcohen48.tap.daemon.grpc.TokenAuthInterceptor
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.Metadata
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.stub.MetadataUtils
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.TimeUnit

internal data class CliReport(
    val code: Int,
    val data: JsonObject,
    val text: String,
)

/** A short-lived authenticated administration channel. It never opens a client connection. */
internal class Administration(
    val descriptor: DaemonDescriptor,
    private val channel: ManagedChannel = ManagedChannelBuilder.forAddress("127.0.0.1", descriptor.port).usePlaintext().build(),
) : AutoCloseable {
    private val auth =
        MetadataUtils.newAttachHeadersInterceptor(
            Metadata().apply { put(TokenAuthInterceptor.AUTHORIZATION, "Bearer ${descriptor.token}") },
        )
    private val connections = ClientConnectionServiceGrpc.newBlockingStub(channel).withInterceptors(auth)
    private val devices = DeviceServiceGrpc.newBlockingStub(channel).withInterceptors(auth)

    fun info(): InfoResponse =
        connections.withDeadlineAfter(2, TimeUnit.SECONDS).info(InfoRequest.getDefaultInstance()).also {
            check(it.pid == descriptor.pid) { "stale descriptor: daemon PID does not match; run tap status" }
        }

    fun listSessions(): List<ConnectionEntry> =
        connections
            .withDeadlineAfter(10, TimeUnit.SECONDS)
            .listConnections(ListConnectionsRequest.getDefaultInstance())
            .connectionsList
            .sortedBy { it.clientConnectionId }

    fun listDevices(): List<DeviceEntry> =
        devices
            .withDeadlineAfter(30, TimeUnit.SECONDS)
            .listDevices(ListDevicesRequest.getDefaultInstance())
            .devicesList
            .sortedBy { it.serial }

    /** Read the existing attachment only; no session, lease, agent or driver is started. */
    fun driverLog(serial: String): List<String> {
        val matches =
            listSessions().flatMap { session ->
                session.attachedDevicesList.filter { it.serial == serial }.map { session.clientConnectionId to it.attachedDeviceId }
            }
        check(matches.size == 1) { "device '$serial' is not uniquely attached; inspect tap sessions" }
        val (owner, attached) = matches.single()
        return devices
            .withDeadlineAfter(15, TimeUnit.SECONDS)
            .driverLog(
                io.github.noamcohen48.tap.api.v1.DriverLogRequest
                    .newBuilder()
                    .setClientConnectionId(
                        owner,
                    ).setAttachedDeviceId(attached)
                    .build(),
            ).linesList
            .map { redact(it, descriptor.token) }
    }

    fun disconnect(
        id: String,
        reason: String,
    ) = connections
        .withDeadlineAfter(120, TimeUnit.SECONDS)
        .disconnect(
            DisconnectRequest
                .newBuilder()
                .setClientConnectionId(id)
                .setReason(reason)
                .build(),
        )

    fun requireCompatible(): InfoResponse =
        info().also {
            check(it.apiCompatibility == TAP_API_COMPATIBILITY) {
                "incompatible daemon API ${it.apiCompatibility}; CLI requires $TAP_API_COMPATIBILITY. Install matching builds and run tap restart --force"
            }
        }

    override fun close() {
        channel.shutdownNow()
    }
}

internal fun <T> withAdministration(
    stateDir: Path,
    block: (Administration) -> T,
): T {
    val descriptor =
        DaemonDescriptor.read(stateDir)
            ?: error("no daemon (no readable ${stateDir.resolve(DaemonDescriptor.FILE_NAME)}); run tap start")
    return Administration(descriptor).use(block)
}

internal fun statusReport(stateDir: Path): CliReport {
    val descriptor = DaemonDescriptor.read(stateDir)
    var info: InfoResponse? = null
    var code = 1
    var state = "stopped"
    val text =
        if (descriptor == null) {
            "no daemon (no readable ${stateDir.resolve(DaemonDescriptor.FILE_NAME)}); run tap start"
        } else {
            try {
                info = Administration(descriptor).use { it.info() }
                val compatible = info.apiCompatibility == TAP_API_COMPATIBILITY
                code = if (compatible) 0 else 1
                state = if (compatible) "running" else "incompatible"
                "$state 127.0.0.1:${descriptor.port} pid=${info.pid} version=${info.daemonVersion} adb=${info.adbExecutable}" +
                    " cli=$DAEMON_VERSION api=${info.apiCompatibility} (required $TAP_API_COMPATIBILITY)" +
                    if (compatible) "" else "; install matching builds and restart the daemon"
            } catch (error: Exception) {
                state =
                    if (error is StatusRuntimeException &&
                        error.status.code in setOf(Status.Code.UNAVAILABLE, Status.Code.DEADLINE_EXCEEDED)
                    ) {
                        "unreachable"
                    } else {
                        "stale"
                    }
                if (state == "unreachable") {
                    "not responding: daemon pid ${descriptor.pid} at 127.0.0.1:${descriptor.port} does not answer; inspect tap logs / tap doctor (may still be starting)"
                } else {
                    "stale descriptor: port ${descriptor.port} rejects its identity; run tap doctor"
                }
            }
        }
    return CliReport(
        code,
        buildJsonObject {
            put("state", state)
            put("healthy", code == 0)
            put("cli_version", DAEMON_VERSION)
            put("required_api_compatibility", TAP_API_COMPATIBILITY)
            put("state_dir", stateDir.toString())
            descriptor?.let { put("endpoint", "127.0.0.1:${it.port}") }
            info?.let { put("daemon", infoJson(it)) }
            put("message", text)
        },
        text,
    )
}

internal fun infoJson(info: InfoResponse): JsonObject =
    buildJsonObject {
        put("daemon_version", info.daemonVersion)
        put("host_build_id", info.hostBuildId)
        put("protocol_version", info.protocolVersion)
        put("api_compatibility", info.apiCompatibility)
        put("pid", info.pid)
        put("adb_executable", info.adbExecutable)
        put("state_dir", info.stateDir)
        put("driver_available", info.driverAvailable)
        put("administrative_disconnect_supported", info.administrativeDisconnectSupported)
    }

internal fun sessionJson(session: ConnectionEntry): JsonObject =
    buildJsonObject {
        put("id", session.clientConnectionId)
        put("name", session.name)
        put("mode", if (session.hasHold()) "held" else "observed")
        put(
            "state",
            if (session.hasHold()) {
                "held"
            } else if (session.observing) {
                "observing"
            } else {
                "awaiting_observer"
            },
        )
        put("created_at_epoch_ms", session.createdAtEpochMs)
        put("last_activity_at_epoch_ms", session.lastActivityAtEpochMs)
        put("idle_ms", session.idleMs)
        put("in_flight", session.inFlight)
        if (session.hasHold()) put("idle_timeout_ms", session.hold.idleTimeoutMs)
        put(
            "devices",
            JsonArray(
                session.attachedDevicesList.map { device ->
                    buildJsonObject {
                        put("serial", device.serial)
                        put("attached_device_id", device.attachedDeviceId)
                        put("generation", device.generation)
                    }
                },
            ),
        )
    }

internal fun deviceJson(device: DeviceEntry): JsonObject =
    buildJsonObject {
        put("serial", device.serial)
        put(
            "state",
            device.state.name
                .removePrefix("DEVICE_")
                .lowercase(),
        )
        if (device.hasClientConnectionId()) put("session_id", device.clientConnectionId)
        if (device.hasQuarantineReason()) put("quarantine_reason", device.quarantineReason)
        put("retry_on_attach", device.retryOnAttach)
        put("under_inspection", device.underInspection)
        if (device.hasProperties()) {
            put(
                "properties",
                buildJsonObject {
                    put("api_level", device.properties.apiLevel)
                    put("manufacturer", device.properties.manufacturer)
                    put("model", device.properties.model)
                    put("emulator", device.properties.emulator)
                },
            )
        }
    }

/** Exact IDs only: client names are neither unique across modes nor safe administrative keys. */
internal fun discoveryReport(
    line: CommandLine,
    admin: Administration,
): CliReport {
    val info = admin.requireCompatible()
    if (line.command == "sessions close") {
        check(info.administrativeDisconnectSupported) {
            "daemon predates administrative cleanup reporting; upgrade it before using sessions close"
        }
    }
    return when (line.command) {
        "logs driver" -> {
            val serial = line.options.getValue("--serial")
            val lines = admin.driverLog(serial)
            CliReport(
                0,
                buildJsonObject {
                    put("serial", serial)
                    put("lines", JsonArray(lines.map(::JsonPrimitive)))
                    put("scope", "current_attachment_driver_buffer")
                },
                lines.joinToString("\n").ifEmpty { "no driver diagnostics retained for $serial" },
            )
        }

        "devices", "devices show" -> {
            val devices = admin.listDevices()
            if (line.command == "devices show") {
                val device =
                    devices.find { it.serial == line.arguments.single() }
                        ?: error("device '${line.arguments.single()}' not found; run tap devices")
                val data = deviceJson(device)
                CliReport(0, data, describe(data))
            } else {
                val data = buildJsonObject { put("devices", JsonArray(devices.map(::deviceJson))) }
                val text =
                    if (devices.isEmpty()) {
                        "no devices; connect a device with USB debugging enabled, then run tap doctor"
                    } else {
                        "SERIAL  STATE  SESSION  MODEL\n" +
                            devices.joinToString("\n") {
                                "${it.serial}  ${it.state.name.removePrefix(
                                    "DEVICE_",
                                ).lowercase()}  ${it.clientConnectionId.ifEmpty { "-" }}  ${it.properties.model}" +
                                    if (it.hasQuarantineReason()) "  quarantine=${it.quarantineReason}" else ""
                            }
                    }
                CliReport(0, data, text)
            }
        }

        "sessions", "sessions show" -> {
            val sessions = admin.listSessions()
            if (line.command == "sessions show") {
                val session =
                    sessions.find { it.clientConnectionId == line.arguments.single() }
                        ?: error("session '${line.arguments.single()}' not found; run tap sessions")
                val data = sessionJson(session)
                CliReport(0, data, describe(data))
            } else {
                val data = buildJsonObject { put("sessions", JsonArray(sessions.map(::sessionJson))) }
                val text =
                    if (sessions.isEmpty()) {
                        "no active sessions; connect using a Kotlin/Python SDK, tap-agent or Tap Studio"
                    } else {
                        "ID  CLIENT NAME  MODE  IDLE_MS  DEVICES\n" +
                            sessions.joinToString("\n") {
                                "${it.clientConnectionId}  ${it.name}  ${if (it.hasHold()) "held" else "observed"}  ${it.idleMs}  ${it.attachedDevicesList.joinToString(
                                    ",",
                                ) { d -> d.serial }.ifEmpty { "-" }}"
                            }
                    }
                CliReport(0, data, text)
            }
        }

        "sessions close" -> {
            val id = line.arguments.single()
            check(admin.listSessions().any { it.clientConnectionId == id }) { "session '$id' not found; run tap sessions" }
            val reason = "administrator: " + (line.options["--reason"] ?: "forced session close")
            val response = admin.disconnect(id, reason)
            val issues = response.cleanupIssuesMap.toSortedMap()
            val data =
                buildJsonObject {
                    put("id", id)
                    put("closed", true)
                    put("reason", reason)
                    put("attached_devices_detached", response.attachedDevicesDetached)
                    put("cleanup_issues", JsonObject(issues.mapValues { JsonPrimitive(it.value) }))
                }
            CliReport(
                if (issues.isEmpty()) 0 else 1,
                data,
                "closed session $id; interrupted its client; detached ${response.attachedDevicesDetached} device(s)" +
                    issues.entries.joinToString(
                        "",
                    ) { "\n${it.key}: cleanup not proven: ${it.value}; inspect tap doctor (quarantine is unchanged)" },
            )
        }

        else -> {
            error("not a discovery command: ${line.command}")
        }
    }
}

/** Plain text details without leaking JSON quoting, while preserving nested field names. */
internal fun describe(
    value: JsonElement,
    prefix: String = "",
): String =
    when (value) {
        is JsonObject -> {
            value.entries.joinToString("\n") { (key, child) -> describe(child, if (prefix.isEmpty()) key else "$prefix.$key") }
        }

        is JsonArray -> {
            if (value.isEmpty()) {
                "$prefix: []"
            } else {
                value
                    .mapIndexed {
                        index,
                        child,
                        ->
                        describe(child, "$prefix[$index]")
                    }.joinToString("\n")
            }
        }

        else -> {
            "$prefix: ${value.jsonPrimitive.content}" +
                if (prefix.endsWith("_at_epoch_ms") &&
                    value.jsonPrimitive.content
                        .toLongOrNull()
                        ?.let { it > 0 } == true
                ) {
                    " (${Instant.ofEpochMilli(value.jsonPrimitive.content.toLong())})"
                } else {
                    ""
                }
        }
    }

/** Escape terminal controls in server/client-provided names and errors. JSON already escapes them. */
internal fun terminalSafe(text: String): String =
    buildString {
        text.forEach { c ->
            if ((c.isISOControl() && c != '\n' && c != '\t') ||
                c == '\u007f'
            ) {
                append("\\u%04x".format(c.code))
            } else {
                append(c)
            }
        }
    }
