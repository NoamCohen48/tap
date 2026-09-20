package com.company.tap.service

import com.company.tap.api.v1.AppAwaitIdleRequest
import com.company.tap.api.v1.AppBool
import com.company.tap.api.v1.AppEmpty
import com.company.tap.api.v1.AppGrantRequest
import com.company.tap.api.v1.AppInstallRequest
import com.company.tap.api.v1.AppLaunchRequest
import com.company.tap.api.v1.AppRequest
import com.company.tap.api.v1.AppServiceGrpc
import com.company.tap.api.v1.AttachRequest
import com.company.tap.api.v1.CloseRunRequest
import com.company.tap.api.v1.CloseRunResponse
import com.company.tap.api.v1.CloseSessionRequest
import com.company.tap.api.v1.CloseSessionResponse
import com.company.tap.api.v1.CommandResult
import com.company.tap.api.v1.DeviceFacts
import com.company.tap.api.v1.DeviceState
import com.company.tap.api.v1.DriverLogRequest
import com.company.tap.api.v1.DriverLogResponse
import com.company.tap.api.v1.ExecuteRequest
import com.company.tap.api.v1.InfoRequest
import com.company.tap.api.v1.InfoResponse
import com.company.tap.api.v1.InventoryRequest
import com.company.tap.api.v1.InventoryResponse
import com.company.tap.api.v1.OpenRunRequest
import com.company.tap.api.v1.OpenRunResponse
import com.company.tap.api.v1.OpenSessionRequest
import com.company.tap.api.v1.OpenSessionResponse
import com.company.tap.api.v1.PoolDevice
import com.company.tap.api.v1.PoolServiceGrpc
import com.company.tap.api.v1.ProcessIdentity
import com.company.tap.api.v1.RunEvent
import com.company.tap.api.v1.RunServiceGrpc
import com.company.tap.api.v1.ScreenshotRequest
import com.company.tap.api.v1.ScreenshotResponse
import com.company.tap.api.v1.SessionServiceGrpc
import com.company.tap.host.AppLifecycle
import com.company.tap.host.AppLifecycleException
import com.company.tap.host.DeviceBusyException
import com.company.tap.host.DriverClient
import com.company.tap.host.HostWaitTimeoutException
import com.company.tap.host.ProcessObservation
import com.company.tap.protocol.ENGINE_VERSION
import com.company.tap.protocol.HOST_BUILD_ID
import com.company.tap.protocol.Operation
import com.google.protobuf.ByteString
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.stub.ServerCallStreamObserver
import io.grpc.stub.StreamObserver
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executor
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

const val SERVICE_VERSION = ENGINE_VERSION

/** Defaults applied when a request leaves its timeout at 0. */
const val DEFAULT_ACTION_TIMEOUT_MS = 10_000L
const val DEFAULT_WAIT_TIMEOUT_MS = 10_000L
const val DEFAULT_LIFECYCLE_TIMEOUT_MS = 30_000L

/** Maps service exceptions to gRPC status codes; everything else is INTERNAL with the message. */
internal fun Throwable.toStatus(): StatusRuntimeException = when (this) {
    is StatusRuntimeException -> this
    is UnknownRunException, is UnknownSessionException -> Status.NOT_FOUND.withDescription(message).asRuntimeException()
    is IllegalArgumentException -> Status.INVALID_ARGUMENT.withDescription(message).asRuntimeException()
    // A busy device is a precondition failure when no wait was asked for, a timeout when it was.
    is DeviceBusyException -> (if (waitedMs > 0) Status.DEADLINE_EXCEEDED else Status.FAILED_PRECONDITION).withDescription(message).asRuntimeException()
    is HostWaitTimeoutException -> Status.DEADLINE_EXCEEDED.withDescription(message).asRuntimeException()
    is AppLifecycleException, is IllegalStateException -> Status.FAILED_PRECONDITION.withDescription(message).asRuntimeException()
    else -> Status.INTERNAL.withDescription("${this::class.simpleName}: $message").withCause(this).asRuntimeException()
}

/** Runs [block] and completes [observer] with its result or a mapped status. */
internal inline fun <T> reply(observer: StreamObserver<T>, block: () -> T) {
    val value = try {
        block()
    } catch (error: Throwable) {
        runCatching { observer.onError(error.toStatus()) }
        return
    }
    // The client may have cancelled while the block ran; delivering then throws and is moot.
    runCatching {
        observer.onNext(value)
        observer.onCompleted()
    }
}

class RunServicer(
    private val service: TapService,
    private val scheduler: ScheduledExecutorService,
) : RunServiceGrpc.RunServiceImplBase() {
    override fun open(request: OpenRunRequest, observer: StreamObserver<OpenRunResponse>) = reply(observer) {
        OpenRunResponse.newBuilder().setRunId(service.openRun(request.name.ifBlank { "unnamed" }).id).build()
    }

    /**
     * Liveness: the stream stays open for the run's lifetime. When the client goes away gRPC
     * cancels the call and the run (sessions + leases) is closed. A heartbeat event every 15 s
     * keeps idle proxies from dropping the stream.
     */
    override fun attach(request: AttachRequest, observer: StreamObserver<RunEvent>) {
        val call = observer as ServerCallStreamObserver<RunEvent>
        val run = try {
            service.run(request.runId)
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
            service.closeRun(run.id, "client detached")
        }
        run.onClose += {
            heartbeat.cancel(false)
            runCatching { if (!call.isCancelled) call.onCompleted() }
        }
        call.onNext(event("attached to run ${run.id}"))
    }

    override fun close(request: CloseRunRequest, observer: StreamObserver<CloseRunResponse>) = reply(observer) {
        val sessions = service.closeRun(request.runId, "client request")
        CloseRunResponse.newBuilder().setSessionsClosed(sessions).build()
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

    private fun event(message: String): RunEvent =
        RunEvent.newBuilder().setAtEpochMs(System.currentTimeMillis()).setMessage(message).build()
}

class PoolServicer(private val service: TapService) : PoolServiceGrpc.PoolServiceImplBase() {
    override fun inventory(request: InventoryRequest, observer: StreamObserver<InventoryResponse>) = reply(observer) {
        InventoryResponse.newBuilder().addAllDevices(service.inventory().map(::poolDevice)).build()
    }

    private fun poolDevice(entry: PoolEntry): PoolDevice = PoolDevice.newBuilder().apply {
        facts = facts(entry.facts)
        when (val status = entry.status) {
            DeviceStatus.Free -> state = DeviceState.DEVICE_FREE
            is DeviceStatus.Leased -> {
                state = DeviceState.DEVICE_LEASED
                status.runId?.let { leasedByRun = it }
            }
            is DeviceStatus.Quarantined -> {
                state = DeviceState.DEVICE_QUARANTINED
                quarantineReason = status.reason
            }
        }
    }.build()

    private fun facts(f: Facts): DeviceFacts = DeviceFacts.newBuilder()
        .setSerial(f.serial).setApiLevel(f.apiLevel).setManufacturer(f.manufacturer).setModel(f.model).setEmulator(f.emulator)
        .build()
}

class SessionServicer(
    private val service: TapService,
    private val commandExecutor: Executor,
) : SessionServiceGrpc.SessionServiceImplBase() {
    override fun open(request: OpenSessionRequest, observer: StreamObserver<OpenSessionResponse>) = reply(observer) {
        require(request.serial.isNotBlank()) { "serial is required" }
        require(request.autPackage.isNotBlank()) { "aut_package is required" }
        val run = service.run(request.runId)
        val session = service.openSession(
            run, request.serial, request.autPackage,
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

/** Delegates to [AppLifecycle] in host core so lifecycle verification rules exist once. */
class AppServicer(private val service: TapService) : AppServiceGrpc.AppServiceImplBase() {
    private fun app(request: AppRequest): Pair<AppLifecycle, Long> {
        require(request.packageName.isNotBlank()) { "package_name is required" }
        val session = service.session(request.sessionId)
        return session.app(request.packageName) to (if (request.timeoutMs > 0) request.timeoutMs else 0L)
    }

    private fun timeout(ms: Long, default: Long) = if (ms > 0) ms else default

    override fun install(request: AppInstallRequest, observer: StreamObserver<AppEmpty>) = reply(observer) {
        val (app, ms) = app(request.app)
        app.install(Path.of(request.apkPath), timeout(ms, DEFAULT_LIFECYCLE_TIMEOUT_MS))
        AppEmpty.getDefaultInstance()
    }

    override fun uninstall(request: AppRequest, observer: StreamObserver<AppEmpty>) = reply(observer) {
        app(request).first.uninstall()
        AppEmpty.getDefaultInstance()
    }

    override fun isInstalled(request: AppRequest, observer: StreamObserver<AppBool>) = reply(observer) {
        AppBool.newBuilder().setValue(app(request).first.isInstalled()).build()
    }

    override fun forceStop(request: AppRequest, observer: StreamObserver<AppEmpty>) = reply(observer) {
        val (app, ms) = app(request)
        app.forceStop(timeout(ms, DEFAULT_ACTION_TIMEOUT_MS))
        AppEmpty.getDefaultInstance()
    }

    override fun clearData(request: AppRequest, observer: StreamObserver<AppEmpty>) = reply(observer) {
        val (app, ms) = app(request)
        app.clearData(timeout(ms, DEFAULT_ACTION_TIMEOUT_MS))
        AppEmpty.getDefaultInstance()
    }

    override fun grantPermission(request: AppGrantRequest, observer: StreamObserver<AppEmpty>) = reply(observer) {
        app(request.app).first.grantPermission(request.permission)
        AppEmpty.getDefaultInstance()
    }

    override fun launch(request: AppLaunchRequest, observer: StreamObserver<AppEmpty>) = reply(observer) {
        val (app, ms) = app(request.app)
        app.launch(request.takeIf { it.hasActivity() }?.activity, timeout(ms, DEFAULT_LIFECYCLE_TIMEOUT_MS))
        AppEmpty.getDefaultInstance()
    }

    override fun coldLaunch(request: AppLaunchRequest, observer: StreamObserver<ProcessIdentity>) = reply(observer) {
        val (app, ms) = app(request.app)
        app.coldLaunch(request.takeIf { it.hasActivity() }?.activity, timeout(ms, DEFAULT_LIFECYCLE_TIMEOUT_MS)).toProto()
    }

    override fun process(request: AppRequest, observer: StreamObserver<ProcessIdentity>) = reply(observer) {
        val (app, ms) = app(request)
        app.process(timeout(ms, DEFAULT_ACTION_TIMEOUT_MS)).toProto()
    }

    override fun isRunning(request: AppRequest, observer: StreamObserver<AppBool>) = reply(observer) {
        AppBool.newBuilder().setValue(app(request).first.isRunning()).build()
    }

    override fun awaitIdle(request: AppAwaitIdleRequest, observer: StreamObserver<AppEmpty>) = reply(observer) {
        val (app, ms) = app(request.app)
        app.awaitIdle(timeout(ms, DEFAULT_WAIT_TIMEOUT_MS), if (request.stableForMs > 0) request.stableForMs else 200)
        AppEmpty.getDefaultInstance()
    }

    private fun ProcessObservation.toProto(): ProcessIdentity =
        ProcessIdentity.newBuilder().setPid(pid).setStartToken(startToken).build()
}
