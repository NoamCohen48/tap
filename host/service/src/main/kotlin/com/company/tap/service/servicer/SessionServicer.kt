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
import com.company.tap.api.v1.SessionServiceGrpc
import com.company.tap.host.DriverClient
import com.company.tap.protocol.Operation
import com.company.tap.service.Conversions
import com.company.tap.service.TapService
import com.google.protobuf.ByteString
import io.grpc.stub.ServerCallStreamObserver
import io.grpc.stub.StreamObserver
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class SessionServicer(
    private val service: TapService,
    private val commandExecutor: Executor,
) : SessionServiceGrpc.SessionServiceImplBase() {
    override fun open(request: OpenSessionRequest, observer: StreamObserver<OpenSessionResponse>) = reply(observer) {
        require(request.serial.isNotBlank()) { "serial is required" }
        require(request.autPackage.isNotBlank()) { "aut_package is required" }
        val connection = service.connection(request.connectionId)
        val session = service.openSession(
            connection, request.serial, request.autPackage,
            TapService.OpenSessionOptions(
                driverApk = request.takeIf { it.hasDriverApk() }?.driverApk?.let(Path::of),
                driverTestApk = request.takeIf { it.hasDriverTestApk() }?.driverTestApk?.let(Path::of),
                skipDriverInstall = request.hasSkipDriverInstall() && request.skipDriverInstall,
                syncAuthority = request.takeIf { it.hasSyncAuthority() }?.syncAuthority,
                allowedSystemPackages = request.allowedSystemPackagesList.toSet(),
                defaultTimeoutMs = if (request.hasDefaultTimeoutMs() && request.defaultTimeoutMs > 0) request.defaultTimeoutMs else 10_000,
                leaseTimeoutMs = request.leaseTimeoutMs,
            ),
        )
        // The session is registered by now; a failed first command must not leave it behind,
        // or the client could never release the device it never received.
        val info = try {
            requireNotNull(session.device.client.executeOrThrow(Operation.DEVICE_INFO, timeoutMs = DEFAULT_ACTION_TIMEOUT_MS).deviceInfo)
        } catch (error: Exception) {
            runCatching { service.closeSession(session.id) }
            throw error
        }
        OpenSessionResponse.newBuilder()
            .setSessionId(session.id)
            .setSerial(session.device.serial)
            .setGeneration(session.device.generation)
            .setDeviceInfo(Conversions.deviceInfo(info))
            .build()
    }

    override fun close(request: CloseSessionRequest, observer: StreamObserver<CloseSessionResponse>) = reply(observer) {
        val detail = service.closeSession(request.sessionId)
        CloseSessionResponse.newBuilder().setClean(detail == null).apply { detail?.let { setDetail(it) } }.build()
    }

    /**
     * Runs the command off the gRPC call thread. Call listener events (including cancellation)
     * are serialised per call, so a handler that blocks in the method body would never see the
     * client's cancel; a worker thread awaits the driver and the cancel handler forwards a
     * protocol `CANCEL` to the in-flight request. The driver decides whether that is honoured
     * (never after the mutation gate), and the awaited response stays the definitive outcome.
     */
    override fun execute(request: ExecuteRequest, observer: StreamObserver<CommandResult>) {
        val call = observer as ServerCallStreamObserver<CommandResult>
        val pendingRef = AtomicReference<DriverClient.PendingCommand?>()
        val cancelled = AtomicBoolean(false)
        call.setOnCancelHandler {
            cancelled.set(true)
            pendingRef.get()?.cancel()
        }
        commandExecutor.execute {
            reply(observer) {
                val session = service.session(request.sessionId)
                val arguments = Conversions.command(request.command, session.defaultTimeoutMs, session.device.config.autPackage)
                val (response, requestId) = service.execute(session, arguments) { pending ->
                    pendingRef.set(pending)
                    if (cancelled.get()) pending.cancel()
                }
                Conversions.result(response, requestId, session.device.generation)
            }
        }
    }

    override fun screenshot(request: ScreenshotRequest, observer: StreamObserver<ScreenshotResponse>) = reply(observer) {
        val session = service.session(request.sessionId)
        val timeout = if (request.timeoutMs > 0) request.timeoutMs else 30_000
        val shot = session.device.client.screenshot(timeout)
        ScreenshotResponse.newBuilder().apply {
            artifact = Conversions.artifact(shot.info)
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

    override fun driverLog(request: DriverLogRequest, observer: StreamObserver<DriverLogResponse>) = reply(observer) {
        DriverLogResponse.newBuilder().addAllLines(service.session(request.sessionId).log.snapshot()).build()
    }
}