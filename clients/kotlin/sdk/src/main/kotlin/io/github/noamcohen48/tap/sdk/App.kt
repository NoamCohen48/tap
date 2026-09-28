package io.github.noamcohen48.tap.sdk

import io.github.noamcohen48.tap.api.v1.AppServiceGrpcKt
import io.github.noamcohen48.tap.api.v1.AppTarget
import io.github.noamcohen48.tap.api.v1.AwaitIdleRequest
import io.github.noamcohen48.tap.api.v1.ClearDataRequest
import io.github.noamcohen48.tap.api.v1.ColdLaunchRequest
import io.github.noamcohen48.tap.api.v1.ForceStopRequest
import io.github.noamcohen48.tap.api.v1.GrantPermissionRequest
import io.github.noamcohen48.tap.api.v1.InstallHeader
import io.github.noamcohen48.tap.api.v1.InstallRequest
import io.github.noamcohen48.tap.api.v1.IsInstalledRequest
import io.github.noamcohen48.tap.api.v1.IsRunningRequest
import io.github.noamcohen48.tap.api.v1.LaunchRequest
import io.github.noamcohen48.tap.api.v1.ProcessRequest
import io.github.noamcohen48.tap.api.v1.UninstallRequest
import com.google.protobuf.ByteString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.fileSize
import kotlin.io.path.isRegularFile
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Size of each `InstallRequest.chunk` streamed by [App.install]. */
internal const val INSTALL_CHUNK_BYTES = 1 shl 20

/**
 * Lifecycle of one package on one device, executed by the host server (ADB plus verified
 * postconditions). The client only names the package and the timeouts; [install] uploads the
 * APK from this machine.
 *
 * All I/O methods are `suspend` and require an owning scope (`tapScope`/`tapTest`); the
 * constructor ([Device.app]) only binds values, so it stays non-suspend. Per-call
 * `withDeadlineAfter` is applied server-side; caller cancellation promptly cancels the RPC.
 * Every call is admitted through the device's command gate, so a started [Device.detach]
 * or an invalidated connection rejects it locally and an in-flight call delays the session
 * close.
 */
class App internal constructor(
    val device: Device,
    val packageName: String,
) {
    private val apps get() = device.ownerConnection.client.apps

    private val target: AppTarget
        get() =
            AppTarget
                .newBuilder()
                .setClientConnectionId(device.ownerConnection.id)
                .setAttachedDeviceId(device.attachedDeviceId)
                .setPackageName(packageName)
                .build()

    private suspend fun <T> call(
        timeout: Duration?,
        block: suspend (AppServiceGrpcKt.AppServiceCoroutineStub) -> T,
    ): T {
        ensureTapBound("App.$packageName")
        return device.admitted("App.$packageName") {
            mapped(device.serial) {
                block(
                    apps.withDeadlineAfter(
                        ((timeout ?: device.timeouts.lifecycle) + RPC_DEADLINE_SLACK_MS.milliseconds).inWholeMilliseconds,
                        TimeUnit.MILLISECONDS,
                    ),
                )
            }
        }
    }

    /** Whether the package is installed. */
    suspend fun isInstalled(): Boolean =
        call(null) { it.isInstalled(IsInstalledRequest.newBuilder().setApp(target).build()) }.installed

    /**
     * `adb install -r -t` of [apk], verified. [apk] is a file on *this* machine: its bytes are
     * streamed to the server in 1 MiB chunks, so the server may run elsewhere.
     */
    suspend fun install(
        apk: Path,
        timeout: Duration = device.timeouts.lifecycle,
    ) {
        require(apk.isRegularFile()) { "APK $apk is not a readable file" }
        val size = apk.fileSize()
        call(timeout + 120.seconds) { it.install(installParts(apk, size, timeout)) }
    }

    private fun installParts(
        apk: Path,
        size: Long,
        timeout: Duration,
    ): Flow<InstallRequest> =
        flow {
            val header =
                InstallHeader
                    .newBuilder()
                    .setApp(target)
                    .setTimeoutMs(timeout.inWholeMilliseconds)
                    .setSizeBytes(size)
            emit(InstallRequest.newBuilder().setHeader(header).build())
            Files.newInputStream(apk).use { input ->
                val buffer = ByteArray(INSTALL_CHUNK_BYTES)
                while (true) {
                    val read = input.readNBytes(buffer, 0, buffer.size)
                    if (read <= 0) break
                    emit(InstallRequest.newBuilder().setChunk(ByteString.copyFrom(buffer, 0, read)).build())
                }
            }
        }.flowOn(Dispatchers.IO)

    /** `pm uninstall`, verified. */
    suspend fun uninstall() {
        call(120.seconds) { it.uninstall(UninstallRequest.newBuilder().setApp(target).build()) }
    }

    /** `am force-stop` plus proof that no process of the package remains. */
    suspend fun forceStop(timeout: Duration = device.timeouts.action) {
        call(timeout) {
            it.forceStop(ForceStopRequest.newBuilder().setApp(target).setTimeoutMs(timeout.inWholeMilliseconds).build())
        }
    }

    /** `pm clear`: data, cache, and runtime permissions are gone; the app is left stopped. */
    suspend fun clearData(timeout: Duration = device.timeouts.action) {
        call(timeout) {
            it.clearData(ClearDataRequest.newBuilder().setApp(target).setTimeoutMs(timeout.inWholeMilliseconds).build())
        }
    }

    /** `pm grant` a runtime permission, e.g. `android.permission.CAMERA`. */
    suspend fun grantPermission(permission: String) {
        call(null) {
            it.grantPermission(GrantPermissionRequest.newBuilder().setApp(target).setPermission(permission).build())
        }
    }

    /**
     * Starts [activity] (or the launcher activity) with `am start -W` and returns when Android
     * reports the launch complete. Nothing about the UI is assumed: wait for what the test needs
     * ([Device.awaitAppVisible], [Device.awaitScreenStable], an element wait).
     */
    suspend fun launch(
        activity: String? = null,
        timeout: Duration = device.timeouts.lifecycle,
    ) {
        call(timeout) {
            it.launch(
                LaunchRequest
                    .newBuilder()
                    .setApp(target)
                    .setTimeoutMs(timeout.inWholeMilliseconds)
                    .apply { activity?.let { name -> setActivity(name) } }
                    .build(),
            )
        }
    }

    /** Verified force-stop, [launch], then the *new* process identity. */
    suspend fun coldLaunch(
        activity: String? = null,
        timeout: Duration = device.timeouts.lifecycle,
    ): AppProcess =
        call(timeout) {
            it.coldLaunch(
                ColdLaunchRequest
                    .newBuilder()
                    .setApp(target)
                    .setTimeoutMs(timeout.inWholeMilliseconds)
                    .apply { activity?.let { name -> setActivity(name) } }
                    .build(),
            )
        }.process.toModel()

    /** Current single process identity (PID + start token); waits briefly for it to exist. */
    suspend fun process(timeout: Duration = device.timeouts.action): AppProcess =
        call(timeout) {
            it.process(ProcessRequest.newBuilder().setApp(target).setTimeoutMs(timeout.inWholeMilliseconds).build())
        }.process.toModel()

    /** Whether any process of the package is alive. */
    suspend fun isRunning(): Boolean = call(null) { it.isRunning(IsRunningRequest.newBuilder().setApp(target).build()) }.running

    /**
     * Waits until the app's `TapSynchronization` reports no busy work for [stableFor]. Requires
     * the app's E2E build to ship `sync-sdk` and be signed like the driver.
     */
    suspend fun awaitIdle(
        timeout: Duration = device.timeouts.wait,
        stableFor: Duration = Timeouts.IDLE_STABLE_FOR,
    ) {
        call(timeout) {
            it.awaitIdle(
                AwaitIdleRequest
                    .newBuilder()
                    .setApp(target)
                    .setTimeoutMs(timeout.inWholeMilliseconds)
                    .setStableForMs(stableFor.inWholeMilliseconds)
                    .build(),
            )
        }
    }

    override fun toString(): String = "App($packageName on ${device.serial})"
}
