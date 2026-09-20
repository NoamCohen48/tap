package com.company.tap.service.servicer

import com.company.tap.api.v1.AppAwaitIdleRequest
import com.company.tap.api.v1.AppBool
import com.company.tap.api.v1.AppEmpty
import com.company.tap.api.v1.AppGrantRequest
import com.company.tap.api.v1.AppInstallRequest
import com.company.tap.api.v1.AppLaunchRequest
import com.company.tap.api.v1.AppRequest
import com.company.tap.api.v1.AppServiceGrpcKt
import com.company.tap.api.v1.ProcessIdentity
import com.company.tap.host.AppLifecycle
import com.company.tap.host.ProcessObservation
import com.company.tap.service.TapService
import java.nio.file.Path

/** Delegates to [AppLifecycle] in host core so lifecycle verification rules exist once. */
class AppServicer(
    private val service: TapService,
) : AppServiceGrpcKt.AppServiceCoroutineImplBase() {
    /** The [AppLifecycle] of the requested package on the requested session. */
    private fun app(request: AppRequest): AppLifecycle {
        require(request.packageName.isNotBlank()) { "package_name is required" }
        return service.session(request.sessionId).device.app(request.packageName)
    }

    /** The client's timeout, or [default] when it sent none. */
    private fun AppRequest.timeoutOr(default: Long): Long = if (timeoutMs > 0) timeoutMs else default

    override suspend fun install(request: AppInstallRequest) =
        reply {
            app(request.app).install(Path.of(request.apkPath), request.app.timeoutOr(DEFAULT_LIFECYCLE_TIMEOUT_MS))
            AppEmpty.getDefaultInstance()
        }

    override suspend fun uninstall(request: AppRequest) =
        reply {
            app(request).uninstall()
            AppEmpty.getDefaultInstance()
        }

    override suspend fun isInstalled(request: AppRequest) =
        reply {
            AppBool.newBuilder().setValue(app(request).isInstalled()).build()
        }

    override suspend fun forceStop(request: AppRequest) =
        reply {
            app(request).forceStop(request.timeoutOr(DEFAULT_ACTION_TIMEOUT_MS))
            AppEmpty.getDefaultInstance()
        }

    override suspend fun clearData(request: AppRequest) =
        reply {
            app(request).clearData(request.timeoutOr(DEFAULT_ACTION_TIMEOUT_MS))
            AppEmpty.getDefaultInstance()
        }

    override suspend fun grantPermission(request: AppGrantRequest) =
        reply {
            app(request.app).grantPermission(request.permission)
            AppEmpty.getDefaultInstance()
        }

    override suspend fun launch(request: AppLaunchRequest) =
        reply {
            app(request.app).launch(request.takeIf { it.hasActivity() }?.activity, request.app.timeoutOr(DEFAULT_LIFECYCLE_TIMEOUT_MS))
            AppEmpty.getDefaultInstance()
        }

    override suspend fun coldLaunch(request: AppLaunchRequest) =
        reply {
            app(
                request.app,
            ).coldLaunch(request.takeIf { it.hasActivity() }?.activity, request.app.timeoutOr(DEFAULT_LIFECYCLE_TIMEOUT_MS)).toProto()
        }

    override suspend fun process(request: AppRequest) =
        reply {
            app(request).process(request.timeoutOr(DEFAULT_ACTION_TIMEOUT_MS)).toProto()
        }

    override suspend fun isRunning(request: AppRequest) =
        reply {
            AppBool.newBuilder().setValue(app(request).isRunning()).build()
        }

    override suspend fun awaitIdle(request: AppAwaitIdleRequest) =
        reply {
            val stableFor = if (request.stableForMs > 0) request.stableForMs else 200
            app(request.app).awaitIdle(request.app.timeoutOr(DEFAULT_WAIT_TIMEOUT_MS), stableFor)
            AppEmpty.getDefaultInstance()
        }

    private fun ProcessObservation.toProto(): ProcessIdentity =
        ProcessIdentity
            .newBuilder()
            .setPid(pid)
            .setStartToken(startToken)
            .build()
}
