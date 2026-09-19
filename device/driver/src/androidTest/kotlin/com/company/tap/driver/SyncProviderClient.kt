package com.company.tap.driver

import android.app.Instrumentation
import android.content.pm.PackageManager
import android.net.Uri
import com.company.tap.protocol.Request
import com.company.tap.protocol.ErrorCode
import com.company.tap.protocol.ErrorDetail
import com.company.tap.protocol.Response
import com.company.tap.protocol.SyncState
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

internal class SyncProviderClient(
    private val instrumentation: Instrumentation,
    private val expectedAut: String,
    private val syncAuthority: String,
) {
    private var poisoned = false

    fun bootstrap(request: Request, started: Long): Response = call(request, started) { state ->
        Response(true, value = state.busyCount == 0, durationMs = elapsed(started), syncState = state)
    }

    fun state(request: Request, started: Long): Response = call(request, started) { state ->
        if (
            state.processStartUuid != request.expectedProcessStartUuid ||
            state.sessionIdentity != request.expectedSessionIdentity
        ) {
            Response.failure(
                ErrorCode.AUT_MISMATCH,
                detail = ErrorDetail.PROCESS_RESTARTED,
                durationMs = elapsed(started),
            )
        } else {
            Response(true, value = state.busyCount == 0, durationMs = elapsed(started), syncState = state)
        }
    }

    private inline fun call(
        request: Request,
        started: Long,
        block: (SyncState) -> Response,
    ): Response {
        if (poisoned) {
            return Response.failure(
                ErrorCode.SYNC_PROVIDER_UNAVAILABLE,
                detail = ErrorDetail.PROVIDER_POISONED,
                durationMs = elapsed(started),
            )
        }
        if (
            instrumentation.targetContext.packageManager.checkSignatures(
                expectedAut,
                instrumentation.targetContext.packageName,
            ) != PackageManager.SIGNATURE_MATCH
        ) {
            return Response.failure(
                ErrorCode.SYNC_PROVIDER_UNAVAILABLE,
                detail = ErrorDetail.CERTIFICATE_MISMATCH,
                durationMs = elapsed(started),
            )
        }
        val provider = instrumentation.targetContext.packageManager.resolveContentProvider(syncAuthority, 0)
        if (provider?.packageName != expectedAut) {
            return Response.failure(
                ErrorCode.SYNC_PROVIDER_UNAVAILABLE,
                durationMs = elapsed(started),
            )
        }

        return try {
            val remaining = request.timeoutMs - elapsed(started)
            if (remaining <= 0) {
                return Response.failure(ErrorCode.WAIT_TIMEOUT, durationMs = elapsed(started))
            }
            val state = readState(remaining)
            if (state.processId != request.observedPid) {
                return Response.failure(
                    ErrorCode.AUT_MISMATCH,
                    detail = ErrorDetail.PROCESS_MISMATCH,
                    durationMs = elapsed(started),
                )
            }
            when {
                !state.initialized || state.processStartUuid.isBlank() || state.sessionIdentity.isBlank() ->
                    Response.failure(
                        ErrorCode.SYNC_PROVIDER_UNAVAILABLE,
                        detail = ErrorDetail.UNINITIALIZED,
                        durationMs = elapsed(started),
                    )
                state.generation < 0 || state.busyCount < 0 || state.lastTransitionElapsedMs < 0 ->
                    Response.failure(
                        ErrorCode.SYNC_PROVIDER_UNAVAILABLE,
                        detail = ErrorDetail.MALFORMED_STATE,
                        durationMs = elapsed(started),
                    )
                state.error != null -> Response.failure(
                    ErrorCode.SYNC_PROVIDER_UNAVAILABLE,
                    detail = ErrorDetail.PROVIDER_ERROR,
                    message = state.error,
                    durationMs = elapsed(started),
                )
                elapsed(started) >= request.timeoutMs ->
                    Response.failure(ErrorCode.WAIT_TIMEOUT, durationMs = elapsed(started))
                else -> block(state)
            }
        } catch (error: TimeoutException) {
            poisoned = true
            Response.failure(
                ErrorCode.SYNC_PROVIDER_UNAVAILABLE,
                detail = ErrorDetail.PROVIDER_TIMEOUT,
                durationMs = elapsed(started),
            )
        } catch (error: Throwable) {
            Response.failure(ErrorCode.SYNC_PROVIDER_UNAVAILABLE, message = error.message,
                durationMs = elapsed(started),
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun readState(timeoutMs: Long): SyncState {
        val task = FutureTask {
            val bundle = requireNotNull(
                instrumentation.targetContext.contentResolver.call(
                    Uri.parse("content://$syncAuthority"),
                    "state",
                    null,
                    null,
                )
            )
            val requiredKeys = setOf(
                "initialized",
                "processId",
                "processStartUuid",
                "sessionIdentity",
                "generation",
                "busyCount",
                "lastTransitionElapsedMs",
                "error",
            )
            require(bundle.keySet().containsAll(requiredKeys)) { "Incomplete synchronization state" }
            require(bundle["initialized"] is Boolean)
            require(bundle["processId"] is Int)
            require(bundle["processStartUuid"] is String)
            require(bundle["sessionIdentity"] is String)
            require(bundle["generation"] is Long)
            require(bundle["busyCount"] is Int)
            require(bundle["lastTransitionElapsedMs"] is Long)
            require(bundle["error"] == null || bundle["error"] is String)
            SyncState(
                initialized = bundle.getBoolean("initialized"),
                processId = bundle.getInt("processId"),
                processStartUuid = requireNotNull(bundle.getString("processStartUuid")),
                sessionIdentity = requireNotNull(bundle.getString("sessionIdentity")),
                generation = bundle.getLong("generation"),
                busyCount = bundle.getInt("busyCount"),
                lastTransitionElapsedMs = bundle.getLong("lastTransitionElapsedMs"),
                error = bundle.getString("error"),
            )
        }
        Thread(task, "tap-sync-provider-call").apply { isDaemon = true }.start()
        return task.get(timeoutMs, TimeUnit.MILLISECONDS)
    }
}
