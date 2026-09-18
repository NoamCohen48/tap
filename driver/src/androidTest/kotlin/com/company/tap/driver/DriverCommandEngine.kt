package com.company.tap.driver

import android.app.Instrumentation
import androidx.test.uiautomator.UiDevice
import com.company.tap.driver.engine.CommandContext
import com.company.tap.protocol.MAX_REQUEST_TIMEOUT_MS
import com.company.tap.protocol.MAX_TEXT_INPUT_CHARS
import com.company.tap.protocol.OPERATION_VERSION
import com.company.tap.protocol.Operation
import com.company.tap.protocol.Request
import com.company.tap.protocol.ErrorCode
import com.company.tap.protocol.ErrorDetail
import com.company.tap.protocol.Response
import com.company.tap.protocol.Selector
import com.company.tap.protocol.SelectorKind
import com.company.tap.protocol.TargetScope
import java.net.Socket

internal class DriverCommandEngine(
    instrumentation: Instrumentation,
    device: UiDevice,
    private val expectedAut: String,
    private val allowedSystemPackages: Set<String>,
    private val faults: FaultController,
    private val sync: SyncProviderClient,
) {
    private val objects = UiObjectAccess(device, expectedAut)
    private val ui = UiAutomationCommands(instrumentation, device, objects, faults)

    /** Runs on the pipeline executor. Deadlines are measured from [CommandContext.acceptedAtMs]. */
    fun execute(
        context: CommandContext,
        socket: Socket,
        request: Request,
        sessionId: String,
        generation: Long,
    ): Response {
        val started = context.acceptedAtMs
        if (request.sessionId != sessionId || request.sessionGeneration != generation) {
            return Response.failure(ErrorCode.SESSION_MISMATCH, durationMs = elapsed(started))
        }
        if (request.operationVersion != OPERATION_VERSION) {
            return Response.failure(ErrorCode.UNSUPPORTED, durationMs = elapsed(started))
        }
        if (request.timeoutMs !in 0..MAX_REQUEST_TIMEOUT_MS) {
            return Response.failure(ErrorCode.INVALID_REQUEST, durationMs = elapsed(started))
        }
        if (isInvalid(request)) {
            return Response.failure(ErrorCode.INVALID_REQUEST, durationMs = elapsed(started))
        }
        if (isScopeDenied(request)) {
            return Response.failure(
                ErrorCode.INVALID_SELECTOR,
                detail = ErrorDetail.SCOPE_DENIED,
                durationMs = elapsed(started),
            )
        }

        context.checkpoint()
        return dispatch(context, socket, request)
    }

    private fun dispatch(context: CommandContext, socket: Socket, request: Request): Response {
        val started = context.acceptedAtMs
        return when (request.operation) {
            Operation.HEALTH -> Response(true, value = true, durationMs = elapsed(started))
            Operation.EXISTS -> Response(
                true,
                value = objects.hasObject(requireNotNull(request.selector)),
                durationMs = elapsed(started),
            )
            Operation.TAP -> ui.tap(context, socket, request)
            Operation.WAIT_VISIBLE -> ui.waitVisible(context, request)
            Operation.DUMP_HIERARCHY -> ui.dumpHierarchy(started)
            Operation.SET_TEXT -> ui.setText(context, request)
            Operation.TYPE_TEXT -> ui.typeText(context, request)
            Operation.SCROLL_UNTIL -> ui.scrollUntil(context, request)
            Operation.SYNC_BOOTSTRAP -> sync.bootstrap(request, started)
            Operation.SYNC_STATE -> sync.state(request, started)
        }
    }

    private fun isInvalid(request: Request): Boolean = when (request.operation) {
        Operation.EXISTS, Operation.TAP, Operation.WAIT_VISIBLE -> request.selector == null
        Operation.SET_TEXT, Operation.TYPE_TEXT ->
            request.selector == null || request.inputText == null ||
                (request.inputText?.length ?: 0) > MAX_TEXT_INPUT_CHARS
        Operation.SCROLL_UNTIL ->
            request.selector == null || request.containerSelector == null ||
                request.maxScrolls !in 1..100
        Operation.HEALTH, Operation.DUMP_HIERARCHY -> false
        Operation.SYNC_BOOTSTRAP ->
            request.observedPid == null || request.observedStartToken.isNullOrBlank() ||
                request.expectedProcessStartUuid != null || request.expectedSessionIdentity != null
        Operation.SYNC_STATE ->
            request.observedPid == null || request.observedStartToken.isNullOrBlank() ||
                request.expectedProcessStartUuid.isNullOrBlank() ||
                request.expectedSessionIdentity.isNullOrBlank()
    }

    private fun isScopeDenied(request: Request): Boolean {
        val denied = listOfNotNull(request.selector, request.containerSelector).any(::isScopeDenied)
        if (denied) return true
        return request.operation == Operation.SCROLL_UNTIL &&
            objects.scopePackage(requireNotNull(request.selector)) !=
            objects.scopePackage(requireNotNull(request.containerSelector))
    }

    private fun isScopeDenied(selector: Selector): Boolean = when (selector.scope) {
        TargetScope.AUT -> selector.kind == SelectorKind.ANDROID_RESOURCE &&
            selector.packageName != expectedAut
        TargetScope.SYSTEM -> selector.scopePackage !in allowedSystemPackages
    }
}

internal fun elapsed(started: Long): Long = android.os.SystemClock.elapsedRealtime() - started
