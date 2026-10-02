package io.github.noamcohen48.tap.daemon.grpc

import com.google.protobuf.ByteString
import io.github.noamcohen48.tap.api.v1.AttachRequest
import io.github.noamcohen48.tap.api.v1.AttachResponse
import io.github.noamcohen48.tap.api.v1.StartRecordingRequest
import io.github.noamcohen48.tap.api.v1.StartRecordingResponse
import io.github.noamcohen48.tap.api.v1.StopRecordingRequest
import io.github.noamcohen48.tap.api.v1.StopRecordingResponse
import io.github.noamcohen48.tap.api.v1.Command
import io.github.noamcohen48.tap.api.v1.CommandResult
import io.github.noamcohen48.tap.api.v1.DetachRequest
import io.github.noamcohen48.tap.api.v1.DetachResponse
import io.github.noamcohen48.tap.api.v1.DeviceCall
import io.github.noamcohen48.tap.api.v1.DeviceServiceGrpcKt
import io.github.noamcohen48.tap.api.v1.DeviceState
import io.github.noamcohen48.tap.api.v1.DriverLogRequest
import io.github.noamcohen48.tap.api.v1.DriverLogResponse
import io.github.noamcohen48.tap.api.v1.Error as CommandError
import io.github.noamcohen48.tap.api.v1.ExecuteRequest
import io.github.noamcohen48.tap.api.v1.ExecuteResponse
import io.github.noamcohen48.tap.api.v1.ListDevicesRequest
import io.github.noamcohen48.tap.api.v1.ListDevicesResponse
import io.github.noamcohen48.tap.api.v1.ResolveRefRequest
import io.github.noamcohen48.tap.api.v1.ResolveRefResponse
import io.github.noamcohen48.tap.api.v1.ScreenSnapshotRequest
import io.github.noamcohen48.tap.api.v1.ScreenSnapshotResponse
import io.github.noamcohen48.tap.api.v1.ScreenshotRequest
import io.github.noamcohen48.tap.api.v1.ScreenshotResponse
import io.github.noamcohen48.tap.api.v1.SetAnimationsRequest
import io.github.noamcohen48.tap.api.v1.SetAnimationsResponse
import io.github.noamcohen48.tap.api.v1.SetDarkModeRequest
import io.github.noamcohen48.tap.api.v1.SetDarkModeResponse
import io.github.noamcohen48.tap.api.v1.SetDensityRequest
import io.github.noamcohen48.tap.api.v1.SetDensityResponse
import io.github.noamcohen48.tap.api.v1.SetFontScaleRequest
import io.github.noamcohen48.tap.api.v1.SetFontScaleResponse
import io.github.noamcohen48.tap.api.v1.SetNetworkRequest
import io.github.noamcohen48.tap.api.v1.SetNetworkResponse
import io.github.noamcohen48.tap.daemon.core.AttachedDevice
import io.github.noamcohen48.tap.host.DeviceConditions
import io.github.noamcohen48.tap.daemon.core.DeviceEntry
import io.github.noamcohen48.tap.daemon.core.DeviceStatus
import io.github.noamcohen48.tap.daemon.core.TapDaemon
import io.github.noamcohen48.tap.daemon.snapshot.ScreenSnapshots
import io.github.noamcohen48.tap.host.AdbDeviceState
import io.github.noamcohen48.tap.host.RecordingException
import io.github.noamcohen48.tap.host.CommandTransportException
import io.github.noamcohen48.tap.host.DriverClient
import io.github.noamcohen48.tap.protocol.CommandValidation
import io.github.noamcohen48.tap.protocol.Commands
import io.github.noamcohen48.tap.protocol.Requests
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/** Diagnostic queries left out of the event log: they read the device, they do not test it. */
private val UNLOGGED_OPS = setOf(Command.OpCase.DEVICE_INFO, Command.OpCase.DUMP_HIERARCHY)

class DeviceService(
    private val daemon: TapDaemon,
) : DeviceServiceGrpcKt.DeviceServiceCoroutineImplBase() {
    override suspend fun listDevices(request: ListDevicesRequest) =
        reply {
            ListDevicesResponse.newBuilder().addAllDevices(daemon.devices().map(::deviceEntry)).build()
        }

    override suspend fun attach(request: AttachRequest): AttachResponse =
        reply {
            argument(request.serial.isNotBlank()) { "serial is required" }
            val attachedDevice =
                daemon.attachDevice(
                    request.clientConnectionId,
                    request.serial,
                    TapDaemon.AttachDeviceOptions(
                        skipDriverInstall = request.hasSkipDriverInstall() && request.skipDriverInstall,
                        defaultTimeoutMs = if (request.hasDefaultTimeoutMs()) positive(request.defaultTimeoutMs, "default_timeout_ms") else Defaults.ACTION_TIMEOUT_MS,
                        leaseTimeoutMs = if (request.hasLeaseTimeoutMs()) nonNegative(request.leaseTimeoutMs, "lease_timeout_ms") else 0L,
                    ),
                )
            // Attachment is registered by now; a failed first command must not leave it behind.
            val info =
                try {
                    attachedDevice.deviceSession.client
                        .execute(Commands.deviceInfo(), timeoutMs = Defaults.ACTION_TIMEOUT_MS)
                        .deviceInfo
                } catch (error: Exception) {
                    runCatching { daemon.detachDevice(attachedDevice.id, request.clientConnectionId) }
                    throw error
                }
            AttachResponse
                .newBuilder()
                .setAttachedDeviceId(attachedDevice.id)
                .setSerial(attachedDevice.deviceSession.serial)
                .setGeneration(attachedDevice.deviceSession.generation)
                .setDeviceInfo(info)
                .build()
        }

    override suspend fun detach(request: DetachRequest): DetachResponse =
        reply {
            val detail = daemon.detachDevice(request.attachedDeviceId, request.clientConnectionId)
            DetachResponse
                .newBuilder()
                .setClean(detail == null)
                .apply { detail?.let { setDetail(it) } }
                .build()
        }

    /**
     * Forwards the client's command unchanged: package ownership is already represented by an
     * ordinary selector predicate, and the result comes back as it was sent. The shared validation
     * runs first as a pre-flight, so a malformed command is `INVALID_ARGUMENT` before any device
     * work. Cancellation propagates to the pending TAP1 command and requests cooperative cancel.
     */
    override suspend fun execute(request: ExecuteRequest): ExecuteResponse =
        reply {
            val attachedDevice = daemon.attachedDevice(request.attachedDeviceId, request.clientConnectionId)
            val command = request.command
            CommandValidation.validate(command)
            val timeoutMs =
                if (command.hasTimeoutMs()) positive(command.timeoutMs, "command.timeout_ms") else attachedDevice.defaultTimeoutMs
            val result =
                if (command.opCase in UNLOGGED_OPS) {
                    resultOf(attachedDevice.deviceSession.client.submit(Requests.of(command), timeoutMs))
                } else {
                    attachedDevice.recorded({ setCommand(command) }, { it.takeIf { it.hasError() }?.error }) {
                        resultOf(attachedDevice.deviceSession.client.submit(Requests.of(command), timeoutMs))
                    }
                }
            ExecuteResponse.newBuilder().setResult(result).build()
        }

    /**
     * Awaits a submitted command and returns the driver's result as it arrived. Transport loss
     * is reported as an error result (with the transmission state as its detail and the request
     * identity the daemon issued), not thrown, so the client sees `INDETERMINATE` /
     * `TRANSPORT_LOST` through the normal result path. Caller cancellation propagates as
     * cancellation: it is never mapped to a transport-loss result.
     */
    private suspend fun resultOf(pending: DriverClient.PendingCommand): CommandResult =
        try {
            pending.await().result
        } catch (loss: CommandTransportException) {
            CommandResult
                .newBuilder()
                .setRequestId(loss.requestId)
                .setSessionGeneration(loss.sessionGeneration)
                .setError(
                    CommandError
                        .newBuilder()
                        .setCode(loss.code)
                        .setDetail(loss.transmissionState.name)
                        .apply { loss.message?.let { message = it } },
                ).build()
        }

    override suspend fun screenshot(request: ScreenshotRequest): ScreenshotResponse =
        reply {
            val attachedDevice = daemon.attachedDevice(request.attachedDeviceId, request.clientConnectionId)
            val timeout = if (request.hasTimeoutMs()) positive(request.timeoutMs, "timeout_ms") else Defaults.LIFECYCLE_TIMEOUT_MS
            val shot = attachedDevice.deviceSession.client.screenshot(timeout)
            ScreenshotResponse
                .newBuilder()
                .setPng(ByteString.copyFrom(shot.png))
                .setSha256(shot.info.sha256)
                .apply {
                    if (shot.info.hasWidth()) width = shot.info.width
                    if (shot.info.hasHeight()) height = shot.info.height
                }.build()
        }

    /**
     * Runs the diagnostic hierarchy dump and turns it into ref-addressed nodes with synthesised
     * selectors, aligned with the device's previous snapshot. Nothing on the action path reads
     * it: a ref only names a selector, which the driver resolves afresh (`.docs/agent-surface.md`).
     */
    override suspend fun screenSnapshot(request: ScreenSnapshotRequest): ScreenSnapshotResponse =
        reply {
            val attachedDevice = daemon.attachedDevice(request.attachedDeviceId, request.clientConnectionId)
            val timeoutMs = if (request.hasTimeoutMs()) positive(request.timeoutMs, "timeout_ms") else attachedDevice.defaultTimeoutMs
            val xml = attachedDevice.deviceSession.client.execute(Commands.dumpHierarchy(), timeoutMs).text
            val screen = withContext(Dispatchers.Default) { ScreenSnapshots.screen(xml, request.selectorCandidates) }
            attachedDevice.screen.record(screen)
        }

    override suspend fun resolveRef(request: ResolveRefRequest): ResolveRefResponse =
        reply {
            argument(request.ref.removePrefix("@").isNotBlank()) { "ref is required" }
            daemon.attachedDevice(request.attachedDeviceId, request.clientConnectionId).screen.resolve(request.ref)
        }

    override suspend fun startRecording(request: StartRecordingRequest): StartRecordingResponse =
        reply {
            val source = request.audioSource.takeIf { it.isNotBlank() }
            argument(request.video || source != null) { "video or audio_source must be set" }
            argument(source == null || source in setOf("output", "playback", "mic")) { "audio_source must be output, playback, or mic" }
            val max = if (request.video) 30 else 60
            val seconds = request.maxSeconds.takeIf { it != 0 } ?: max
            argument(seconds in 1..max) { "max_seconds must be in 1..$max" }
            val attached = daemon.attachedDevice(request.attachedDeviceId, request.clientConnectionId)
            if (source != null) {
                val api = attached.deviceSession.client.execute(Commands.deviceInfo(), Defaults.ACTION_TIMEOUT_MS).deviceInfo.apiLevel
                if (api < 30 || (source == "playback" && api < 33)) {
                    throw RecordingException("$source audio capture is unsupported on Android API $api")
                }
            }
            attached.recording.start(request.video, source, seconds)
            StartRecordingResponse.getDefaultInstance()
        }

    override suspend fun stopRecording(request: StopRecordingRequest): StopRecordingResponse =
        reply {
            val media = daemon.attachedDevice(request.attachedDeviceId, request.clientConnectionId, requireDriver = false).recording.stop()
            val hash = MessageDigest.getInstance("SHA-256").digest(media.bytes).joinToString("") { "%02x".format(it) }
            StopRecordingResponse.newBuilder().setData(ByteString.copyFrom(media.bytes)).setFormat(media.format).setSha256(hash).build()
        }

    override suspend fun driverLog(request: DriverLogRequest): DriverLogResponse =
        reply {
            DriverLogResponse
                .newBuilder()
                .addAllLines(daemon.attachedDevice(request.attachedDeviceId, request.clientConnectionId).driverLog.snapshot())
                .build()
        }

    override suspend fun setAnimations(request: SetAnimationsRequest): SetAnimationsResponse =
        reply {
            condition(request.clientConnectionId, request.attachedDeviceId, "set_animations", { enabled = request.enabled }) {
                it.setAnimations(request.enabled)
            }
            SetAnimationsResponse.getDefaultInstance()
        }

    override suspend fun setDarkMode(request: SetDarkModeRequest): SetDarkModeResponse =
        reply {
            condition(request.clientConnectionId, request.attachedDeviceId, "set_dark_mode", { enabled = request.enabled }) {
                it.setDarkMode(request.enabled)
            }
            SetDarkModeResponse.getDefaultInstance()
        }

    override suspend fun setFontScale(request: SetFontScaleRequest): SetFontScaleResponse =
        reply {
            val scale = request.scale
            argument(scale.isFinite() && scale in DeviceConditions.MIN_FONT_SCALE..DeviceConditions.MAX_FONT_SCALE) {
                "scale must be ${DeviceConditions.MIN_FONT_SCALE} to ${DeviceConditions.MAX_FONT_SCALE}, not $scale"
            }
            condition(request.clientConnectionId, request.attachedDeviceId, "set_font_scale", { fontScale = scale }) {
                it.setFontScale(scale)
            }
            SetFontScaleResponse.getDefaultInstance()
        }

    override suspend fun setDensity(request: SetDensityRequest): SetDensityResponse =
        reply {
            val dpi = if (request.hasDpi()) request.dpi else null
            argument(dpi == null || dpi in DeviceConditions.MIN_DENSITY..DeviceConditions.MAX_DENSITY) {
                "dpi must be ${DeviceConditions.MIN_DENSITY} to ${DeviceConditions.MAX_DENSITY}, not $dpi"
            }
            condition(request.clientConnectionId, request.attachedDeviceId, "set_density", { dpi?.let { densityDpi = it } }) {
                it.setDensity(dpi)
            }
            SetDensityResponse.getDefaultInstance()
        }

    override suspend fun setNetwork(request: SetNetworkRequest): SetNetworkResponse =
        reply {
            val airplane = if (request.hasAirplaneMode()) request.airplaneMode else null
            val wifi = if (request.hasWifi()) request.wifi else null
            val data = if (request.hasMobileData()) request.mobileData else null
            argument(airplane != null || wifi != null || data != null) { "set at least one of airplane_mode, wifi or mobile_data" }
            val logged: DeviceCall.Builder.() -> Unit = {
                airplane?.let { airplaneMode = it }
                wifi?.let { this.wifi = it }
                data?.let { mobileData = it }
            }
            condition(request.clientConnectionId, request.attachedDeviceId, "set_network", logged) {
                it.setNetwork(airplane, wifi, data)
            }
            SetNetworkResponse.getDefaultInstance()
        }

    /** Runs a device-condition change on the attached device and logs it as [operation]. */
    private suspend fun condition(
        clientConnectionId: String,
        attachedDeviceId: String,
        operation: String,
        call: DeviceCall.Builder.() -> Unit,
        block: suspend (DeviceConditions) -> Unit,
    ) {
        val attachedDevice: AttachedDevice = daemon.attachedDevice(attachedDeviceId, clientConnectionId)
        val logged = DeviceCall.newBuilder().setOperation(operation).apply(call).build()
        attachedDevice.recorded({ setDevice(logged) }) { block(attachedDevice.deviceSession.conditions) }
    }

    private fun deviceEntry(entry: DeviceEntry): io.github.noamcohen48.tap.api.v1.DeviceEntry =
        io.github.noamcohen48.tap.api.v1.DeviceEntry
            .newBuilder()
            .apply {
                serial = entry.serial
                when (val status = entry.status) {
                    DeviceStatus.Free -> {
                        state = DeviceState.DEVICE_FREE
                    }

                    is DeviceStatus.Held -> {
                        state = DeviceState.DEVICE_LEASED
                        status.ownerConnectionId?.let { clientConnectionId = it }
                    }

                    is DeviceStatus.Quarantined -> {
                        state = DeviceState.DEVICE_QUARANTINED
                        quarantineReason = status.reason
                    }

                    // OTHER (e.g. `no permissions`, `recovery`) is reported as offline: present, not usable.
                    is DeviceStatus.Unavailable -> {
                        state = if (status.state == AdbDeviceState.UNAUTHORIZED) DeviceState.DEVICE_UNAUTHORIZED else DeviceState.DEVICE_OFFLINE
                    }
                }
            }.build()
}
