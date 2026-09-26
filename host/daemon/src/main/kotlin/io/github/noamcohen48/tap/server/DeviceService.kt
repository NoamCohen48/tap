package io.github.noamcohen48.tap.server

import io.github.noamcohen48.tap.api.v1.AttachRequest
import io.github.noamcohen48.tap.api.v1.AttachResponse
import io.github.noamcohen48.tap.api.v1.DetachRequest
import io.github.noamcohen48.tap.api.v1.DetachResponse
import io.github.noamcohen48.tap.api.v1.DeviceServiceGrpcKt
import io.github.noamcohen48.tap.api.v1.DeviceState
import io.github.noamcohen48.tap.api.v1.DriverLogRequest
import io.github.noamcohen48.tap.api.v1.DriverLogResponse
import io.github.noamcohen48.tap.api.v1.ExecuteRequest
import io.github.noamcohen48.tap.api.v1.ExecuteResponse
import io.github.noamcohen48.tap.api.v1.ListDevicesRequest
import io.github.noamcohen48.tap.api.v1.ListDevicesResponse
import io.github.noamcohen48.tap.api.v1.ScreenshotRequest
import io.github.noamcohen48.tap.api.v1.ScreenshotResponse
import io.github.noamcohen48.tap.daemon.DeviceEntry
import io.github.noamcohen48.tap.daemon.DeviceStatus
import io.github.noamcohen48.tap.daemon.TapDaemon
import io.github.noamcohen48.tap.daemon.toCommand
import io.github.noamcohen48.tap.daemon.toProto
import io.github.noamcohen48.tap.host.AdbDeviceState
import io.github.noamcohen48.tap.protocol.DeviceInfoQuery
import com.google.protobuf.ByteString

class DeviceService(
    private val daemon: TapDaemon,
) : DeviceServiceGrpcKt.DeviceServiceCoroutineImplBase() {
    override suspend fun listDevices(request: ListDevicesRequest) =
        reply {
            ListDevicesResponse.newBuilder().addAllDevices(daemon.devices().map(::deviceEntry)).build()
        }

    override suspend fun attach(request: AttachRequest): AttachResponse =
        reply {
            require(request.serial.isNotBlank()) { "serial is required" }
            require(request.autPackage.isNotBlank()) { "aut_package is required" }
            val attachedDevice =
                daemon.attachDevice(
                    request.clientConnectionId,
                    request.serial,
                    request.autPackage,
                    TapDaemon.AttachDeviceOptions(
                        skipDriverInstall = request.hasSkipDriverInstall() && request.skipDriverInstall,
                        syncAuthority = request.takeIf { it.hasSyncAuthority() }?.syncAuthority,
                        allowedSystemPackages = request.allowedSystemPackagesList.toSet(),
                        defaultTimeoutMs = if (request.hasDefaultTimeoutMs()) positive(request.defaultTimeoutMs, "default_timeout_ms") else DEFAULT_ACTION_TIMEOUT_MS,
                        leaseTimeoutMs = if (request.hasLeaseTimeoutMs()) nonNegative(request.leaseTimeoutMs, "lease_timeout_ms") else 0L,
                    ),
                )
            // Attachment is registered by now; a failed first command must not leave it behind.
            val info =
                try {
                    attachedDevice.deviceSession.client
                        .execute(DeviceInfoQuery, timeoutMs = DEFAULT_ACTION_TIMEOUT_MS)
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
                .setDeviceInfo(info.toProto())
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

    /** Cancellation propagates to the pending TAP1 command and requests cooperative cancel. */
    override suspend fun execute(request: ExecuteRequest): ExecuteResponse =
        reply {
            val attachedDevice = daemon.attachedDevice(request.attachedDeviceId, request.clientConnectionId)
            val command = request.command.toCommand(attachedDevice.deviceSession.autPackage)
            val timeoutMs =
                if (request.command.hasTimeoutMs()) positive(request.command.timeoutMs, "command.timeout_ms") else attachedDevice.defaultTimeoutMs
            val pending = attachedDevice.deviceSession.client.submit(command, timeoutMs)
            val result = daemon.await(pending).toProto(pending.requestId, attachedDevice.deviceSession.generation)
            ExecuteResponse.newBuilder().setResult(result).build()
        }

    override suspend fun screenshot(request: ScreenshotRequest): ScreenshotResponse =
        reply {
            val attachedDevice = daemon.attachedDevice(request.attachedDeviceId, request.clientConnectionId)
            val timeout = if (request.hasTimeoutMs()) positive(request.timeoutMs, "timeout_ms") else DEFAULT_LIFECYCLE_TIMEOUT_MS
            val shot = attachedDevice.deviceSession.client.screenshot(timeout)
            ScreenshotResponse
                .newBuilder()
                .setPng(ByteString.copyFrom(shot.png))
                .setSha256(shot.info.sha256)
                .apply {
                    shot.info.width?.let { width = it }
                    shot.info.height?.let { height = it }
                }.build()
        }

    override suspend fun driverLog(request: DriverLogRequest): DriverLogResponse =
        reply {
            DriverLogResponse
                .newBuilder()
                .addAllLines(daemon.attachedDevice(request.attachedDeviceId, request.clientConnectionId).driverLog.snapshot())
                .build()
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
