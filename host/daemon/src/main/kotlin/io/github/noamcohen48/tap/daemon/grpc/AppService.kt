package io.github.noamcohen48.tap.daemon.grpc

import io.github.noamcohen48.tap.api.v1.AppCall
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
import io.github.noamcohen48.tap.api.v1.ForegroundRequest
import io.github.noamcohen48.tap.api.v1.ForegroundResponse
import io.github.noamcohen48.tap.api.v1.GrantPermissionRequest
import io.github.noamcohen48.tap.api.v1.GrantPermissionResponse
import io.github.noamcohen48.tap.api.v1.InstallHeader
import io.github.noamcohen48.tap.api.v1.IntentExtra
import io.github.noamcohen48.tap.api.v1.InstallRequest
import io.github.noamcohen48.tap.api.v1.InstallResponse
import io.github.noamcohen48.tap.api.v1.GetLocalesRequest
import io.github.noamcohen48.tap.api.v1.GetLocalesResponse
import io.github.noamcohen48.tap.api.v1.IsInstalledRequest
import io.github.noamcohen48.tap.api.v1.IsInstalledResponse
import io.github.noamcohen48.tap.api.v1.IsPermissionGrantedRequest
import io.github.noamcohen48.tap.api.v1.IsPermissionGrantedResponse
import io.github.noamcohen48.tap.api.v1.IsRunningRequest
import io.github.noamcohen48.tap.api.v1.IsRunningResponse
import io.github.noamcohen48.tap.api.v1.LaunchRequest
import io.github.noamcohen48.tap.api.v1.LaunchResponse
import io.github.noamcohen48.tap.api.v1.SetLocalesRequest
import io.github.noamcohen48.tap.api.v1.SetLocalesResponse
import io.github.noamcohen48.tap.api.v1.OpenLinkRequest
import io.github.noamcohen48.tap.api.v1.OpenLinkResponse
import io.github.noamcohen48.tap.api.v1.ProcessIdentity
import io.github.noamcohen48.tap.api.v1.ProcessRequest
import io.github.noamcohen48.tap.api.v1.ProcessResponse
import io.github.noamcohen48.tap.api.v1.RevokePermissionRequest
import io.github.noamcohen48.tap.api.v1.RevokePermissionResponse
import io.github.noamcohen48.tap.api.v1.UninstallRequest
import io.github.noamcohen48.tap.api.v1.UninstallResponse
import io.github.noamcohen48.tap.daemon.cli.restrictToOwner
import io.github.noamcohen48.tap.daemon.core.TapDaemon
import io.github.noamcohen48.tap.host.AppLifecycle
import io.github.noamcohen48.tap.host.ProcessObservation
import io.github.noamcohen48.tap.host.canonicalLocales
import io.github.noamcohen48.tap.host.isDriverPackage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.StandardOpenOption

/** Largest APK Install accepts. */
const val MAX_INSTALL_BYTES = 1L shl 30

/** `OpenLinkRequest.uri` bounds: a URI a browser would accept, starting with its scheme. */
const val MAX_URI_LENGTH = 2048

/** `LaunchRequest.extras` / `ColdLaunchRequest.extras` bounds. */
const val MAX_INTENT_EXTRAS = 64
const val MAX_EXTRA_KEY_LENGTH = 256
const val MAX_EXTRA_STRING_LENGTH = 4096
private val URI_SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")

/** Delegates to [AppLifecycle] in host core so lifecycle verification rules exist once. */
class AppService(
    private val daemon: TapDaemon,
) : AppServiceGrpcKt.AppServiceCoroutineImplBase() {
    /** The [AppLifecycle] of the target package, on a device the calling connection owns. */
    private suspend fun app(target: AppTarget): AppLifecycle {
        checkTarget(target)
        return daemon.attachedDevice(target.attachedDeviceId, target.clientConnectionId).deviceSession.app(target.packageName)
    }

    /**
     * Runs a call that changes the device on the target package and records it in the owning
     * connection's event log as [operation]; [call] adds the call's arguments.
     */
    private suspend fun <T> logged(
        target: AppTarget,
        operation: String,
        call: AppCall.Builder.() -> Unit = {},
        block: suspend (AppLifecycle) -> T,
    ): T {
        checkTarget(target)
        val device = daemon.attachedDevice(target.attachedDeviceId, target.clientConnectionId)
        val app = device.deviceSession.app(target.packageName)
        val logged = AppCall.newBuilder().setOperation(operation).setPackageName(target.packageName).apply(call).build()
        return device.recorded({ setApp(logged) }) { block(app) }
    }

    private fun checkTarget(target: AppTarget) {
        argument(target.packageName.isNotBlank()) { "package_name is required" }
        argument(!isDriverPackage(target.packageName)) {
            "${target.packageName} is Tap's driver: stopping, clearing or changing it would end the device session"
        }
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
                                    argument(header == null) { "InstallHeader must be sent exactly once, first" }
                                    val h = part.header
                                    argument(h.sizeBytes in 1..MAX_INSTALL_BYTES) { "size_bytes must be in 1..$MAX_INSTALL_BYTES" }
                                    header = h
                                }

                                InstallRequest.PartCase.CHUNK -> {
                                    val size = argumentNotNull(header) { "InstallHeader must come before any chunk" }.sizeBytes
                                    received += part.chunk.size()
                                    argument(received <= size) { "upload exceeds size_bytes $size" }
                                    part.chunk.writeTo(out)
                                }

                                InstallRequest.PartCase.PART_NOT_SET, null -> {
                                    throw InvalidArgumentException("InstallRequest.part must be set")
                                }
                            }
                        }
                    }
                }
                val h = argumentNotNull(header) { "InstallHeader is required" }
                argument(received == h.sizeBytes) { "upload ended after $received of ${h.sizeBytes} bytes" }
                val timeout = if (h.hasTimeoutMs()) positive(h.timeoutMs, "timeout_ms") else Defaults.LIFECYCLE_TIMEOUT_MS
                logged(h.app, "install", { if (h.hasTimeoutMs()) timeoutMs = timeout }) { it.install(apk, timeout) }
                InstallResponse.getDefaultInstance()
            } finally {
                withContext(Dispatchers.IO) { Files.deleteIfExists(apk) }
            }
        }

    override suspend fun uninstall(request: UninstallRequest) =
        reply {
            logged(request.app, "uninstall") { it.uninstall() }
            UninstallResponse.getDefaultInstance()
        }

    override suspend fun isInstalled(request: IsInstalledRequest) =
        reply {
            IsInstalledResponse.newBuilder().setInstalled(app(request.app).isInstalled()).build()
        }

    override suspend fun forceStop(request: ForceStopRequest) =
        reply {
            val timeout = timeout(request.hasTimeoutMs(), request.timeoutMs, Defaults.ACTION_TIMEOUT_MS)
            logged(request.app, "force_stop", { if (request.hasTimeoutMs()) timeoutMs = timeout }) { it.forceStop(timeout) }
            ForceStopResponse.getDefaultInstance()
        }

    override suspend fun clearData(request: ClearDataRequest) =
        reply {
            val timeout = timeout(request.hasTimeoutMs(), request.timeoutMs, Defaults.ACTION_TIMEOUT_MS)
            logged(request.app, "clear_data", { if (request.hasTimeoutMs()) timeoutMs = timeout }) { it.clearData(timeout) }
            ClearDataResponse.getDefaultInstance()
        }

    override suspend fun grantPermission(request: GrantPermissionRequest) =
        reply {
            argument(request.permission.isNotBlank()) { "permission is required" }
            logged(request.app, "grant_permission", { permission = request.permission }) { it.grantPermission(request.permission) }
            GrantPermissionResponse.getDefaultInstance()
        }

    override suspend fun revokePermission(request: RevokePermissionRequest) =
        reply {
            argument(request.permission.isNotBlank()) { "permission is required" }
            logged(request.app, "revoke_permission", { permission = request.permission }) { it.revokePermission(request.permission) }
            RevokePermissionResponse.getDefaultInstance()
        }

    override suspend fun isPermissionGranted(request: IsPermissionGrantedRequest) =
        reply {
            argument(request.permission.isNotBlank()) { "permission is required" }
            IsPermissionGrantedResponse.newBuilder().setGranted(app(request.app).isPermissionGranted(request.permission)).build()
        }

    override suspend fun setLocales(request: SetLocalesRequest) =
        reply {
            val locales =
                try {
                    canonicalLocales(request.localesList)
                } catch (bad: IllegalArgumentException) {
                    throw InvalidArgumentException(bad.message ?: "invalid locales")
                }
            logged(request.app, "set_locales", { addAllLocales(request.localesList) }) { it.setLocales(locales) }
            SetLocalesResponse.getDefaultInstance()
        }

    override suspend fun getLocales(request: GetLocalesRequest) =
        reply {
            GetLocalesResponse.newBuilder().addAllLocales(app(request.app).locales()).build()
        }

    override suspend fun launch(request: LaunchRequest) =
        reply {
            val activity = request.takeIf { it.hasActivity() }?.activity
            val timeout = timeout(request.hasTimeoutMs(), request.timeoutMs, Defaults.LIFECYCLE_TIMEOUT_MS)
            checkExtras(request.extrasList)
            logged(request.app, "launch", { launchArguments(activity, request.hasTimeoutMs(), timeout, request.extrasList) }) {
                it.launch(activity, timeout, request.extrasList)
            }
            LaunchResponse.getDefaultInstance()
        }

    override suspend fun coldLaunch(request: ColdLaunchRequest) =
        reply {
            val activity = request.takeIf { it.hasActivity() }?.activity
            val timeout = timeout(request.hasTimeoutMs(), request.timeoutMs, Defaults.LIFECYCLE_TIMEOUT_MS)
            checkExtras(request.extrasList)
            val process =
                logged(request.app, "cold_launch", { launchArguments(activity, request.hasTimeoutMs(), timeout, request.extrasList) }) {
                    it.coldLaunch(activity, timeout, extras = request.extrasList)
                }
            ColdLaunchResponse.newBuilder().setProcess(process.toProto()).build()
        }

    override suspend fun foreground(request: ForegroundRequest) =
        reply {
            val timeout = timeout(request.hasTimeoutMs(), request.timeoutMs, Defaults.LIFECYCLE_TIMEOUT_MS)
            logged(request.app, "foreground", { if (request.hasTimeoutMs()) timeoutMs = timeout }) { it.foreground(timeout) }
            ForegroundResponse.getDefaultInstance()
        }

    override suspend fun openLink(request: OpenLinkRequest) =
        reply {
            val uri = request.uri
            argument(uri.length in 1..MAX_URI_LENGTH) { "uri must be 1..$MAX_URI_LENGTH characters" }
            argument(URI_SCHEME.containsMatchIn(uri)) { "uri must be absolute (start with a scheme such as https: or myapp:)" }
            argument(uri.none { it.isWhitespace() || it.isISOControl() }) { "uri must not contain whitespace or control characters; percent-encode them" }
            val timeout = timeout(request.hasTimeoutMs(), request.timeoutMs, Defaults.LIFECYCLE_TIMEOUT_MS)
            val activity =
                logged(request.app, "open_link", {
                    this.uri = uri
                    if (request.anyApp) anyApp = true
                    if (request.hasTimeoutMs()) timeoutMs = timeout
                }) { it.openLink(uri, request.anyApp, timeout) }
            OpenLinkResponse.newBuilder().apply { activity?.let { this.activity = it } }.build()
        }

    override suspend fun process(request: ProcessRequest) =
        reply {
            val process = app(request.app).process(timeout(request.hasTimeoutMs(), request.timeoutMs, Defaults.ACTION_TIMEOUT_MS))
            ProcessResponse.newBuilder().setProcess(process.toProto()).build()
        }

    override suspend fun isRunning(request: IsRunningRequest) =
        reply {
            IsRunningResponse.newBuilder().setRunning(app(request.app).isRunning()).build()
        }

    override suspend fun awaitIdle(request: AwaitIdleRequest) =
        reply {
            val stableFor = if (request.hasStableForMs()) positive(request.stableForMs, "stable_for_ms") else Defaults.IDLE_STABLE_MS
            app(request.app).awaitIdle(timeout(request.hasTimeoutMs(), request.timeoutMs, Defaults.WAIT_TIMEOUT_MS), stableFor)
            AwaitIdleResponse.getDefaultInstance()
        }

    private fun timeout(
        present: Boolean,
        value: Long,
        default: Long,
    ): Long = if (present) positive(value, "timeout_ms") else default

    private fun AppCall.Builder.launchArguments(
        activity: String?,
        explicitTimeout: Boolean,
        timeout: Long,
        extras: List<IntentExtra>,
    ) {
        activity?.let { this.activity = it }
        if (explicitTimeout) timeoutMs = timeout
        addAllExtras(extras)
    }

    /**
     * `am start` extras: at most [MAX_INTENT_EXTRAS], distinct keys a shell word can hold, a
     * value set, strings bounded and floats finite.
     */
    private fun checkExtras(extras: List<IntentExtra>) {
        argument(extras.size <= MAX_INTENT_EXTRAS) { "at most $MAX_INTENT_EXTRAS extras" }
        val keys = HashSet<String>()
        extras.forEach { extra ->
            val key = extra.key
            argument(key.length in 1..MAX_EXTRA_KEY_LENGTH) { "extra keys must be 1..$MAX_EXTRA_KEY_LENGTH characters" }
            argument(key.none { it.isWhitespace() || it.isISOControl() }) { "extra key '$key' must not contain whitespace or control characters" }
            argument(keys.add(key)) { "extra key '$key' is given twice" }
            when (extra.valueCase) {
                IntentExtra.ValueCase.STRING_VALUE -> {
                    argument(extra.stringValue.length <= MAX_EXTRA_STRING_LENGTH) { "extra '$key' is longer than $MAX_EXTRA_STRING_LENGTH characters" }
                    argument(extra.stringValue.none { it == '\u0000' }) { "extra '$key' must not contain NUL" }
                }
                IntentExtra.ValueCase.FLOAT_VALUE -> argument(extra.floatValue.isFinite()) { "extra '$key' must be a finite float" }
                IntentExtra.ValueCase.VALUE_NOT_SET, null -> throw InvalidArgumentException("extra '$key' needs a value")
                else -> Unit
            }
        }
    }

    private fun ProcessObservation.toProto(): ProcessIdentity =
        ProcessIdentity
            .newBuilder()
            .setPid(pid)
            .setStartToken(startToken)
            .build()
}
