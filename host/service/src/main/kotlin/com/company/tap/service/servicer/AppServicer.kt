package com.company.tap.service.servicer

import com.company.tap.api.v1.AppAwaitIdleRequest
import com.company.tap.api.v1.AppBool
import com.company.tap.api.v1.AppEmpty
import com.company.tap.api.v1.AppGrantRequest
import com.company.tap.api.v1.AppInstallRequest
import com.company.tap.api.v1.AppLaunchRequest
import com.company.tap.api.v1.AppRequest
import com.company.tap.api.v1.AppServiceGrpc
import com.company.tap.api.v1.ProcessIdentity
import com.company.tap.host.AppLifecycle
import com.company.tap.host.ProcessObservation
import com.company.tap.service.TapService
import io.grpc.stub.StreamObserver
import java.nio.file.Path

/** Delegates to [AppLifecycle] in host core so lifecycle verification rules exist once. */
class AppServicer(private val service: TapService) : AppServiceGrpc.AppServiceImplBase() {
    /** The [AppLifecycle] of the requested package on the requested session. */
    private fun app(request: AppRequest): AppLifecycle {
        require(request.packageName.isNotBlank()) { "package_name is required" }
        return service.session(request.sessionId).device.app(request.packageName)
    }

    /** The client's timeout, or [default] when it sent none. */
    private fun AppRequest.timeoutOr(default: Long): Long = if (timeoutMs > 0) timeoutMs else default

    override fun install(request: AppInstallRequest, observer: StreamObserver<AppEmpty>) = reply(observer) {
        app(request.app).install(Path.of(request.apkPath), request.app.timeoutOr(DEFAULT_LIFECYCLE_TIMEOUT_MS))
        AppEmpty.getDefaultInstance()
    }

    override fun uninstall(request: AppRequest, observer: StreamObserver<AppEmpty>) = reply(observer) {
        app(request).uninstall()
        AppEmpty.getDefaultInstance()
    }

    override fun isInstalled(request: AppRequest, observer: StreamObserver<AppBool>) = reply(observer) {
        AppBool.newBuilder().setValue(app(request).isInstalled()).build()
    }

    override fun forceStop(request: AppRequest, observer: StreamObserver<AppEmpty>) = reply(observer) {
        app(request).forceStop(request.timeoutOr(DEFAULT_ACTION_TIMEOUT_MS))
        AppEmpty.getDefaultInstance()
    }

    override fun clearData(request: AppRequest, observer: StreamObserver<AppEmpty>) = reply(observer) {
        app(request).clearData(request.timeoutOr(DEFAULT_ACTION_TIMEOUT_MS))
        AppEmpty.getDefaultInstance()
    }

    override fun grantPermission(request: AppGrantRequest, observer: StreamObserver<AppEmpty>) = reply(observer) {
        app(request.app).grantPermission(request.permission)
        AppEmpty.getDefaultInstance()
    }

    override fun launch(request: AppLaunchRequest, observer: StreamObserver<AppEmpty>) = reply(observer) {
        app(request.app).launch(request.takeIf { it.hasActivity() }?.activity, request.app.timeoutOr(DEFAULT_LIFECYCLE_TIMEOUT_MS))
        AppEmpty.getDefaultInstance()
    }

    override fun coldLaunch(request: AppLaunchRequest, observer: StreamObserver<ProcessIdentity>) = reply(observer) {
        app(request.app).coldLaunch(request.takeIf { it.hasActivity() }?.activity, request.app.timeoutOr(DEFAULT_LIFECYCLE_TIMEOUT_MS)).toProto()
    }

    override fun process(request: AppRequest, observer: StreamObserver<ProcessIdentity>) = reply(observer) {
        app(request).process(request.timeoutOr(DEFAULT_ACTION_TIMEOUT_MS)).toProto()
    }

    override fun isRunning(request: AppRequest, observer: StreamObserver<AppBool>) = reply(observer) {
        AppBool.newBuilder().setValue(app(request).isRunning()).build()
    }

    override fun awaitIdle(request: AppAwaitIdleRequest, observer: StreamObserver<AppEmpty>) = reply(observer) {
        val stableFor = if (request.stableForMs > 0) request.stableForMs else 200
        app(request.app).awaitIdle(request.app.timeoutOr(DEFAULT_WAIT_TIMEOUT_MS), stableFor)
        AppEmpty.getDefaultInstance()
    }

    private fun ProcessObservation.toProto(): ProcessIdentity =
        ProcessIdentity.newBuilder().setPid(pid).setStartToken(startToken).build()
}