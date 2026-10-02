package io.github.noamcohen48.tap.daemon.grpc

import com.google.protobuf.ByteString
import io.github.noamcohen48.tap.api.v1.AddMediaRequest
import io.github.noamcohen48.tap.api.v1.AddMediaResponse
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
import io.github.noamcohen48.tap.api.v1.PullFileRequest
import io.github.noamcohen48.tap.api.v1.PullFileResponse
import io.github.noamcohen48.tap.api.v1.PushFileRequest
import io.github.noamcohen48.tap.api.v1.PushFileResponse
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
import io.github.noamcohen48.tap.api.v1.GetForegroundActivityRequest
import io.github.noamcohen48.tap.api.v1.GetForegroundActivityResponse
import io.github.noamcohen48.tap.api.v1.SetAccessibilityDisplayRequest
import io.github.noamcohen48.tap.api.v1.SetAccessibilityDisplayResponse
import io.github.noamcohen48.tap.api.v1.SetLocationRequest
import io.github.noamcohen48.tap.api.v1.SetLocationResponse
import io.github.noamcohen48.tap.api.v1.SetStayAwakeRequest
import io.github.noamcohen48.tap.api.v1.SetStayAwakeResponse
import io.github.noamcohen48.tap.api.v1.SetSystemLocalesRequest
import io.github.noamcohen48.tap.api.v1.SetSystemLocalesResponse
import io.github.noamcohen48.tap.daemon.cli.restrictToOwner
import io.github.noamcohen48.tap.daemon.core.AttachedDevice
import io.github.noamcohen48.tap.host.DeviceFiles
import io.github.noamcohen48.tap.host.DeviceConditions
import io.github.noamcohen48.tap.host.canonicalLocales
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

/** Diagnostic queries left out of the event log: they read the device, they do not test it. */
/** Size of each chunk PullFile streams. */
private const val PULL_CHUNK_BYTES = 256 * 1024

private val UNLOGGED_OPS = setOf(Command.OpCase.DEVICE_INFO, Command.OpCase.DUMP_HIERARCHY)

/** Commands that read the driver's notification listener: the server gives it access first. */
private val NOTIFICATION_OPS =
    setOf(
        Command.OpCase.AWAIT_NOTIFICATION,
        Command.OpCase.LIST_NOTIFICATIONS,
        Command.OpCase.OPEN_NOTIFICATION,
        Command.OpCase.DISMISS_NOTIFICATION,
    )

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
                        if (command.opCase in NOTIFICATION_OPS) attachedDevice.deviceSession.conditions.allowNotificationListener()
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

    override suspend fun setSystemLocales(request: SetSystemLocalesRequest): SetSystemLocalesResponse =
        reply {
            argument(request.localesCount > 0) { "set at least one locale" }
            val locales =
                try {
                    canonicalLocales(request.localesList)
                } catch (bad: IllegalArgumentException) {
                    throw InvalidArgumentException(bad.message ?: "invalid locales")
                }
            condition(request.clientConnectionId, request.attachedDeviceId, "set_system_locales", { addAllLocales(request.localesList) }) {
                it.setSystemLocales(locales)
            }
            SetSystemLocalesResponse.getDefaultInstance()
        }

    override suspend fun setLocation(request: SetLocationRequest): SetLocationResponse =
        reply {
            val accuracy = if (request.hasAccuracyM()) request.accuracyM else null
            val altitude = if (request.hasAltitudeM()) request.altitudeM else null
            argument(request.latitude in -90.0..90.0) { "latitude must be -90 to 90, not ${request.latitude}" }
            argument(request.longitude in -180.0..180.0) { "longitude must be -180 to 180, not ${request.longitude}" }
            argument(accuracy == null || (accuracy.isFinite() && accuracy > 0f)) { "accuracy_m must be a positive number of meters, not $accuracy" }
            argument(altitude == null || altitude.isFinite()) { "altitude_m must be finite, not $altitude" }
            val logged: DeviceCall.Builder.() -> Unit = {
                latitude = request.latitude
                longitude = request.longitude
                accuracy?.let { accuracyM = it }
                altitude?.let { altitudeM = it }
            }
            condition(request.clientConnectionId, request.attachedDeviceId, "set_location", logged) {
                it.setLocation(request.latitude, request.longitude, accuracy, altitude)
            }
            SetLocationResponse.getDefaultInstance()
        }

    override suspend fun setStayAwake(request: SetStayAwakeRequest): SetStayAwakeResponse =
        reply {
            condition(request.clientConnectionId, request.attachedDeviceId, "set_stay_awake", { enabled = request.enabled }) {
                it.setStayAwake(request.enabled)
            }
            SetStayAwakeResponse.getDefaultInstance()
        }

    override suspend fun setAccessibilityDisplay(request: SetAccessibilityDisplayRequest): SetAccessibilityDisplayResponse =
        reply {
            val contrast = if (request.hasHighContrastText()) request.highContrastText else null
            val inversion = if (request.hasColorInversion()) request.colorInversion else null
            val bold = if (request.hasBoldText()) request.boldText else null
            argument(contrast != null || inversion != null || bold != null) { "set at least one of high_contrast_text, color_inversion or bold_text" }
            val logged: DeviceCall.Builder.() -> Unit = {
                contrast?.let { highContrastText = it }
                inversion?.let { colorInversion = it }
                bold?.let { boldText = it }
            }
            condition(request.clientConnectionId, request.attachedDeviceId, "set_accessibility_display", logged) {
                it.setAccessibilityDisplay(contrast, inversion, bold)
            }
            SetAccessibilityDisplayResponse.getDefaultInstance()
        }

    override suspend fun getForegroundActivity(request: GetForegroundActivityRequest): GetForegroundActivityResponse =
        reply {
            val attachedDevice = daemon.attachedDevice(request.attachedDeviceId, request.clientConnectionId)
            val top = attachedDevice.deviceSession.foregroundActivity()
            GetForegroundActivityResponse
                .newBuilder()
                .apply {
                    top?.let { (pkg, activity) ->
                        packageName = pkg
                        this.activity = activity
                    }
                }.build()
        }

    override suspend fun pushFile(requests: Flow<PushFileRequest>): PushFileResponse =
        reply {
            spooled(
                requests,
                header = { if (it.partCase == PushFileRequest.PartCase.HEADER) it.header else null },
                chunk = { if (it.partCase == PushFileRequest.PartCase.CHUNK) it.chunk else null },
                size = { it.sizeBytes },
            ) { h, upload ->
                argumentDevicePath(h.devicePath)
                file(h.clientConnectionId, h.attachedDeviceId, "push_file", h.devicePath, h.sizeBytes) { it.push(upload, h.devicePath) }
            }
            PushFileResponse.getDefaultInstance()
        }

    /**
     * Pulls into an owner-only file under the state dir, then streams it: the size in the first
     * message, [PULL_CHUNK_BYTES] chunks after. Failures before the first message are statuses.
     */
    override fun pullFile(request: PullFileRequest): Flow<PullFileResponse> =
        flow {
            val target = reply { withContext(Dispatchers.IO) { uploadFile("pull") } }
            try {
                val size =
                    reply {
                        argumentDevicePath(request.devicePath)
                        file(request.clientConnectionId, request.attachedDeviceId, "pull_file", request.devicePath, null) {
                            it.pull(request.devicePath, target)
                        }
                        withContext(Dispatchers.IO) { Files.size(target) }
                    }
                emit(PullFileResponse.newBuilder().setSizeBytes(size).build())
                val buffer = ByteArray(PULL_CHUNK_BYTES)
                withContext(Dispatchers.IO) { Files.newInputStream(target) }.use { input ->
                    while (true) {
                        val read = withContext(Dispatchers.IO) { input.read(buffer) }
                        if (read < 0) break
                        if (read > 0) emit(PullFileResponse.newBuilder().setChunk(ByteString.copyFrom(buffer, 0, read)).build())
                    }
                }
            } finally {
                withContext(Dispatchers.IO) { Files.deleteIfExists(target) }
            }
        }

    override suspend fun addMedia(requests: Flow<AddMediaRequest>): AddMediaResponse =
        reply {
            spooled(
                requests,
                header = { if (it.partCase == AddMediaRequest.PartCase.HEADER) it.header else null },
                chunk = { if (it.partCase == AddMediaRequest.PartCase.CHUNK) it.chunk else null },
                size = { it.sizeBytes },
            ) { h, upload ->
                try {
                    DeviceFiles.mediaFolder(h.fileName)
                } catch (invalid: IllegalArgumentException) {
                    throw InvalidArgumentException(invalid.message ?: "file_name is invalid")
                }
                var path = ""
                file(h.clientConnectionId, h.attachedDeviceId, "add_media", h.fileName, h.sizeBytes) { path = it.addMedia(upload, h.fileName) }
                AddMediaResponse.newBuilder().setDevicePath(path).build()
            }
        }

    private fun argumentDevicePath(path: String) {
        try {
            DeviceFiles.checkDevicePath(path)
        } catch (invalid: IllegalArgumentException) {
            throw InvalidArgumentException(invalid.message ?: "device_path is invalid")
        }
    }

    private fun uploadFile(prefix: String): Path {
        val uploads = daemon.config.stateDir.resolve("uploads")
        Files.createDirectories(uploads)
        return Files.createTempFile(uploads, prefix, ".bin").also { restrictToOwner(it) }
    }

    /**
     * Spools a client upload (one header first, then chunks) to an owner-only file under the
     * state dir, checks it against the header's size, runs [block] on it and deletes it.
     */
    private suspend fun <R, H : Any, T> spooled(
        requests: Flow<R>,
        header: (R) -> H?,
        chunk: (R) -> ByteString?,
        size: (H) -> Long,
        block: suspend (H, Path) -> T,
    ): T {
        val upload = withContext(Dispatchers.IO) { uploadFile("upload") }
        try {
            var first: H? = null
            var received = 0L
            withContext(Dispatchers.IO) {
                Files.newOutputStream(upload, StandardOpenOption.TRUNCATE_EXISTING).use { out ->
                    requests.collect { part ->
                        val h = header(part)
                        val bytes = chunk(part)
                        when {
                            h != null -> {
                                argument(first == null) { "the header must be sent exactly once, first" }
                                argument(size(h) in 0..DeviceFiles.MAX_FILE_BYTES) { "size_bytes must be in 0..${DeviceFiles.MAX_FILE_BYTES}" }
                                first = h
                            }

                            bytes != null -> {
                                val expected = size(argumentNotNull(first) { "the header must come before any chunk" })
                                received += bytes.size()
                                argument(received <= expected) { "upload exceeds size_bytes $expected" }
                                bytes.writeTo(out)
                            }

                            else -> throw InvalidArgumentException("part must be set")
                        }
                    }
                }
            }
            val h = argumentNotNull(first) { "the header is required" }
            argument(received == size(h)) { "upload ended after $received of ${size(h)} bytes" }
            return block(h, upload)
        } finally {
            withContext(Dispatchers.IO) { Files.deleteIfExists(upload) }
        }
    }

    /** Runs a file operation on the attached device and logs it as [operation]. */
    private suspend fun file(
        clientConnectionId: String,
        attachedDeviceId: String,
        operation: String,
        devicePath: String,
        sizeBytes: Long?,
        block: suspend (DeviceFiles) -> Unit,
    ) {
        val attachedDevice: AttachedDevice = daemon.attachedDevice(attachedDeviceId, clientConnectionId)
        val logged =
            DeviceCall
                .newBuilder()
                .setOperation(operation)
                .setDevicePath(devicePath)
                .apply { if (sizeBytes != null) setSizeBytes(sizeBytes) }
                .build()
        attachedDevice.recorded({ setDevice(logged) }) { block(attachedDevice.deviceSession.files) }
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
