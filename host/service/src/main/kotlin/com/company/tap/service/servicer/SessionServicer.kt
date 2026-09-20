package com.company.tap.service.servicer

import com.company.tap.api.v1.CloseSessionRequest
import com.company.tap.api.v1.CloseSessionResponse
import com.company.tap.api.v1.CommandResult
import com.company.tap.api.v1.DriverLogRequest
import com.company.tap.api.v1.DriverLogResponse
import com.company.tap.api.v1.ExecuteRequest
import com.company.tap.api.v1.OpenSessionRequest
import com.company.tap.api.v1.OpenSessionResponse
import com.company.tap.api.v1.ScreenshotRequest
import com.company.tap.api.v1.ScreenshotResponse
import com.company.tap.api.v1.SessionServiceGrpcKt
import com.company.tap.protocol.DeviceInfoQuery
import com.company.tap.service.TapService
import com.company.tap.service.toCommand
import com.company.tap.service.toProto
import com.google.protobuf.ByteString
import java.nio.file.Files
import java.nio.file.Path

class SessionServicer(
    private val service: TapService,
) : SessionServiceGrpcKt.SessionServiceCoroutineImplBase() {
    override suspend fun open(request: OpenSessionRequest): OpenSessionResponse =
        reply {
            require(request.serial.isNotBlank()) { "serial is required" }
            require(request.autPackage.isNotBlank()) { "aut_package is required" }
            val connection = service.connection(request.connectionId)
            val session =
                service.openSession(
                    connection,
                    request.serial,
                    request.autPackage,
                    TapService.OpenSessionOptions(
                        driverApk = request.takeIf { it.hasDriverApk() }?.driverApk?.let(Path::of),
                        driverTestApk = request.takeIf { it.hasDriverTestApk() }?.driverTestApk?.let(Path::of),
                        skipDriverInstall = request.hasSkipDriverInstall() && request.skipDriverInstall,
                        syncAuthority = request.takeIf { it.hasSyncAuthority() }?.syncAuthority,
                        allowedSystemPackages = request.allowedSystemPackagesList.toSet(),
                        defaultTimeoutMs =
                            if (request.hasDefaultTimeoutMs() &&
                                request.defaultTimeoutMs > 0
                            ) {
                                request.defaultTimeoutMs
                            } else {
                                10_000
                            },
                        leaseTimeoutMs = request.leaseTimeoutMs,
                    ),
                )
            // The session is registered by now; a failed first command must not leave it behind,
            // or the client could never release the device it never received.
            val info =
                try {
                    session.device.client
                        .execute(DeviceInfoQuery, timeoutMs = DEFAULT_ACTION_TIMEOUT_MS)
                        .deviceInfo
                } catch (error: Exception) {
                    runCatching { service.closeSession(session.id) }
                    throw error
                }
            OpenSessionResponse
                .newBuilder()
                .setSessionId(session.id)
                .setSerial(session.device.serial)
                .setGeneration(session.device.generation)
                .setDeviceInfo(info.toProto())
                .build()
        }

    override suspend fun close(request: CloseSessionRequest): CloseSessionResponse =
        reply {
            val detail = service.closeSession(request.sessionId)
            CloseSessionResponse
                .newBuilder()
                .setClean(detail == null)
                .apply { detail?.let { setDetail(it) } }
                .build()
        }

    /**
     * Awaits the driver; a gRPC cancel arrives as coroutine cancellation, which
     * `PendingCommand.await()` turns into a cooperative protocol `CANCEL` while still
     * recording the driver's definitive outcome. The driver decides whether the cancel is
     * honoured (never after the mutation gate).
     */
    override suspend fun execute(request: ExecuteRequest): CommandResult =
        reply {
            val session = service.session(request.sessionId)
            val command = request.command.toCommand(session.device.autPackage)
            val timeoutMs = if (request.command.timeoutMs > 0) request.command.timeoutMs else session.defaultTimeoutMs
            val pending = session.device.client.submit(command, timeoutMs)
            service.await(pending).toProto(pending.requestId, session.device.generation)
        }

    override suspend fun screenshot(request: ScreenshotRequest): ScreenshotResponse =
        reply {
            val session = service.session(request.sessionId)
            val timeout = if (request.timeoutMs > 0) request.timeoutMs else 30_000
            val shot = session.device.client.screenshot(timeout)
            ScreenshotResponse
                .newBuilder()
                .apply {
                    artifact = shot.info.toProto()
                    if (request.hasWriteTo()) {
                        val target = Path.of(request.writeTo)
                        target.parent?.let(Files::createDirectories)
                        Files.write(target, shot.png)
                        path = target.toAbsolutePath().toString()
                    } else {
                        png = ByteString.copyFrom(shot.png)
                    }
                }.build()
        }

    override suspend fun driverLog(request: DriverLogRequest): DriverLogResponse =
        reply {
            DriverLogResponse.newBuilder().addAllLines(service.session(request.sessionId).log.snapshot()).build()
        }
}
