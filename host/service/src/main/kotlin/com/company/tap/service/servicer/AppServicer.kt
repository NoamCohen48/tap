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
    private fun app(request: AppRequest): Pair<AppLifecycle, Long> {
        require(request.packageName.isNotBlank()) { "package_name is required" }
        val session = service.session(request.sessionId)
        return session.device.app(request.packageName) to (if (request.timeoutMs > 0) request.timeoutMs else 0L)
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