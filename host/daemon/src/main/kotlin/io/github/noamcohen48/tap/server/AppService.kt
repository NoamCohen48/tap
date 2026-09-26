package io.github.noamcohen48.tap.server

import io.github.noamcohen48.tap.api.v1.AppServiceGrpcKt
import io.github.noamcohen48.tap.api.v1.AppTarget
import io.github.noamcohen48.tap.api.v1.AwaitIdleRequest
import io.github.noamcohen48.tap.api.v1.AwaitIdleResponse
import io.github.noamcohen48.tap.api.v1.ClearDataRequest
import io.github.noamcohen48.tap.api.v1.ClearDataResponse
import io.github.noamcohen48.tap.api.v1.ColdLaunchRequest
import io.github.noamcohen48.tap.api.v1.ColdLaunchResponse
import io.github.noamcohen48.tap.api.v1.ForceStopRequest
import io.github.noamcohen48.tap.api.v1.ForceStopResponse
import io.github.noamcohen48.tap.api.v1.GrantPermissionRequest
import io.github.noamcohen48.tap.api.v1.GrantPermissionResponse
import io.github.noamcohen48.tap.api.v1.InstallHeader
import io.github.noamcohen48.tap.api.v1.InstallRequest
import io.github.noamcohen48.tap.api.v1.InstallResponse
import io.github.noamcohen48.tap.api.v1.IsInstalledRequest
import io.github.noamcohen48.tap.api.v1.IsInstalledResponse
import io.github.noamcohen48.tap.api.v1.IsRunningRequest
import io.github.noamcohen48.tap.api.v1.IsRunningResponse
import io.github.noamcohen48.tap.api.v1.LaunchRequest
import io.github.noamcohen48.tap.api.v1.LaunchResponse
import io.github.noamcohen48.tap.api.v1.ProcessIdentity
import io.github.noamcohen48.tap.api.v1.ProcessRequest
import io.github.noamcohen48.tap.api.v1.ProcessResponse
import io.github.noamcohen48.tap.api.v1.UninstallRequest
import io.github.noamcohen48.tap.api.v1.UninstallResponse
import io.github.noamcohen48.tap.daemon.TapDaemon
import io.github.noamcohen48.tap.daemon.restrictToOwner
import io.github.noamcohen48.tap.host.AppLifecycle
import io.github.noamcohen48.tap.host.ProcessObservation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.StandardOpenOption

/** Largest APK Install accepts. */
const val MAX_INSTALL_BYTES = 1L shl 30

/** Delegates to [AppLifecycle] in host core so lifecycle verification rules exist once. */
class AppService(
    private val daemon: TapDaemon,
) : AppServiceGrpcKt.AppServiceCoroutineImplBase() {
    /** The [AppLifecycle] of the target package, on a device the calling connection owns. */
    private fun app(target: AppTarget): AppLifecycle {
        require(target.packageName.isNotBlank()) { "package_name is required" }
        return daemon.attachedDevice(target.attachedDeviceId, target.clientConnectionId).deviceSession.app(target.packageName)
    }

    /**
     * Spools the upload (header first, then chunks) to an owner-only file under the state dir,
     * checks its size against the header, installs it and deletes it.
     */
    override suspend fun install(requests: Flow<InstallRequest>): InstallResponse =
        reply {
            val uploads = daemon.config.stateDir.resolve("uploads")
            withContext(Dispatchers.IO) { Files.createDirectories(uploads) }
            val apk = withContext(Dispatchers.IO) { Files.createTempFile(uploads, "install", ".apk") }
            try {
                restrictToOwner(apk)
                var header: InstallHeader? = null
                var received = 0L
                withContext(Dispatchers.IO) {
                    Files.newOutputStream(apk, StandardOpenOption.TRUNCATE_EXISTING).use { out ->
                        requests.collect { part ->
                            when (part.partCase) {
                                InstallRequest.PartCase.HEADER -> {
                                    require(header == null) { "InstallHeader must be sent exactly once, first" }
                                    val h = part.header
                                    require(h.sizeBytes in 1..MAX_INSTALL_BYTES) { "size_bytes must be in 1..$MAX_INSTALL_BYTES" }
                                    header = h
                                }

                                InstallRequest.PartCase.CHUNK -> {
                                    val size = requireNotNull(header) { "InstallHeader must come before any chunk" }.sizeBytes
                                    received += part.chunk.size()
                                    require(received <= size) { "upload exceeds size_bytes $size" }
                                    part.chunk.writeTo(out)
                                }

                                InstallRequest.PartCase.PART_NOT_SET, null -> {
                                    throw IllegalArgumentException("InstallRequest.part must be set")
                                }
                            }
                        }
                    }
                }
                val h = requireNotNull(header) { "InstallHeader is required" }
                require(received == h.sizeBytes) { "upload ended after $received of ${h.sizeBytes} bytes" }
                val timeout = if (h.hasTimeoutMs()) positive(h.timeoutMs, "timeout_ms") else DEFAULT_LIFECYCLE_TIMEOUT_MS
                app(h.app).install(apk, timeout)
                InstallResponse.getDefaultInstance()
            } finally {
                withContext(Dispatchers.IO) { Files.deleteIfExists(apk) }
            }
        }

    override suspend fun uninstall(request: UninstallRequest) =
        reply {
            app(request.app).uninstall()
            UninstallResponse.getDefaultInstance()
        }

    override suspend fun isInstalled(request: IsInstalledRequest) =
        reply {
            IsInstalledResponse.newBuilder().setInstalled(app(request.app).isInstalled()).build()
        }

    override suspend fun forceStop(request: ForceStopRequest) =
        reply {
            app(request.app).forceStop(timeout(request.hasTimeoutMs(), request.timeoutMs, DEFAULT_ACTION_TIMEOUT_MS))
            ForceStopResponse.getDefaultInstance()
        }

    override suspend fun clearData(request: ClearDataRequest) =
        reply {
            app(request.app).clearData(timeout(request.hasTimeoutMs(), request.timeoutMs, DEFAULT_ACTION_TIMEOUT_MS))
            ClearDataResponse.getDefaultInstance()
        }

    override suspend fun grantPermission(request: GrantPermissionRequest) =
        reply {
            require(request.permission.isNotBlank()) { "permission is required" }
            app(request.app).grantPermission(request.permission)
            GrantPermissionResponse.getDefaultInstance()
        }

    override suspend fun launch(request: LaunchRequest) =
        reply {
            app(request.app).launch(
                request.takeIf { it.hasActivity() }?.activity,
                timeout(request.hasTimeoutMs(), request.timeoutMs, DEFAULT_LIFECYCLE_TIMEOUT_MS),
            )
            LaunchResponse.getDefaultInstance()
        }

    override suspend fun coldLaunch(request: ColdLaunchRequest) =
        reply {
            val process =
                app(request.app).coldLaunch(
                    request.takeIf { it.hasActivity() }?.activity,
                    timeout(request.hasTimeoutMs(), request.timeoutMs, DEFAULT_LIFECYCLE_TIMEOUT_MS),
                )
            ColdLaunchResponse.newBuilder().setProcess(process.toProto()).build()
        }

    override suspend fun process(request: ProcessRequest) =
        reply {
            val process = app(request.app).process(timeout(request.hasTimeoutMs(), request.timeoutMs, DEFAULT_ACTION_TIMEOUT_MS))
            ProcessResponse.newBuilder().setProcess(process.toProto()).build()
        }

    override suspend fun isRunning(request: IsRunningRequest) =
        reply {
            IsRunningResponse.newBuilder().setRunning(app(request.app).isRunning()).build()
        }

    override suspend fun awaitIdle(request: AwaitIdleRequest) =
        reply {
            val stableFor = if (request.hasStableForMs()) positive(request.stableForMs, "stable_for_ms") else DEFAULT_IDLE_STABLE_MS
            app(request.app).awaitIdle(timeout(request.hasTimeoutMs(), request.timeoutMs, DEFAULT_WAIT_TIMEOUT_MS), stableFor)
            AwaitIdleResponse.getDefaultInstance()
        }

    private fun timeout(
        present: Boolean,
        value: Long,
        default: Long,
    ): Long = if (present) positive(value, "timeout_ms") else default

    private fun ProcessObservation.toProto(): ProcessIdentity =
        ProcessIdentity
            .newBuilder()
            .setPid(pid)
            .setStartToken(startToken)
            .build()
}
