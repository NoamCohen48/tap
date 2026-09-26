package io.github.noamcohen48.tap.driver

import android.app.Instrumentation
import android.content.pm.PackageManager
import android.net.Uri
import io.github.noamcohen48.tap.driver.engine.CommandContext
import io.github.noamcohen48.tap.protocol.CommandFailure
import io.github.noamcohen48.tap.protocol.ErrorCode
import io.github.noamcohen48.tap.protocol.ErrorDetail
import io.github.noamcohen48.tap.protocol.SyncBootstrap
import io.github.noamcohen48.tap.protocol.SyncPoll
import io.github.noamcohen48.tap.protocol.SyncResult
import io.github.noamcohen48.tap.protocol.SyncState
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

internal class SyncProviderClient(
    private val instrumentation: Instrumentation,
    private val expectedAut: String,
    private val syncAuthority: String,
) {
    private var poisoned = false

    fun bootstrap(context: CommandContext, command: SyncBootstrap): SyncResult =
        SyncResult(read(context, command.observedPid))

    fun poll(context: CommandContext, command: SyncPoll): SyncResult {
        val state = read(context, command.observedPid)
        if (
            state.processStartUuid != command.expectedProcessStartUuid ||
            state.sessionIdentity != command.expectedSessionIdentity
        ) {
            throw CommandFailure(ErrorCode.AUT_MISMATCH, detail = ErrorDetail.PROCESS_RESTARTED)
        }
        return SyncResult(state)
    }

    /** Reads a validated state from the AUT's provider; every failure is a [CommandFailure]. */
    private fun read(context: CommandContext, observedPid: Int): SyncState {
        if (poisoned) throw CommandFailure(ErrorCode.SYNC_PROVIDER_UNAVAILABLE, detail = ErrorDetail.PROVIDER_POISONED)
        if (
            instrumentation.targetContext.packageManager.checkSignatures(
                expectedAut,
                instrumentation.targetContext.packageName,
            ) != PackageManager.SIGNATURE_MATCH
        ) {
            throw CommandFailure(ErrorCode.SYNC_PROVIDER_UNAVAILABLE, detail = ErrorDetail.CERTIFICATE_MISMATCH)
        }
        val provider = instrumentation.targetContext.packageManager.resolveContentProvider(syncAuthority, 0)
        if (provider?.packageName != expectedAut) throw CommandFailure(ErrorCode.SYNC_PROVIDER_UNAVAILABLE)

        val remaining = context.remainingMs()
        if (remaining <= 0) throw CommandFailure(ErrorCode.WAIT_TIMEOUT)
        val state = try {
            readState(remaining)
        } catch (error: TimeoutException) {
            poisoned = true
            throw CommandFailure(ErrorCode.SYNC_PROVIDER_UNAVAILABLE, detail = ErrorDetail.PROVIDER_TIMEOUT)
        } catch (error: Throwable) {
            throw CommandFailure(ErrorCode.SYNC_PROVIDER_UNAVAILABLE, message = error.message)
        }
        if (state.processId != observedPid) {
            throw CommandFailure(ErrorCode.AUT_MISMATCH, detail = ErrorDetail.PROCESS_MISMATCH)
        }
        when {
            !state.initialized || state.processStartUuid.isBlank() || state.sessionIdentity.isBlank() ->
                throw CommandFailure(ErrorCode.SYNC_PROVIDER_UNAVAILABLE, detail = ErrorDetail.UNINITIALIZED)
            state.generation < 0 || state.busyCount < 0 || state.lastTransitionElapsedMs < 0 ->
                throw CommandFailure(ErrorCode.SYNC_PROVIDER_UNAVAILABLE, detail = ErrorDetail.MALFORMED_STATE)
            state.error != null ->
                throw CommandFailure(ErrorCode.SYNC_PROVIDER_UNAVAILABLE, detail = ErrorDetail.PROVIDER_ERROR, message = state.error)
            context.isExpired() -> throw CommandFailure(ErrorCode.WAIT_TIMEOUT)
        }
        return state
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
