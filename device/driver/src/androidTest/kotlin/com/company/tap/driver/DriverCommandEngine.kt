package com.company.tap.driver

import android.app.Instrumentation
import androidx.test.uiautomator.UiDevice
import com.company.tap.driver.engine.CommandContext
import com.company.tap.protocol.ErrorCode
import com.company.tap.protocol.ErrorDetail
import com.company.tap.protocol.InvalidSelectorException
import com.company.tap.protocol.MAX_REQUEST_TIMEOUT_MS
import com.company.tap.protocol.MAX_SCROLLS
import com.company.tap.protocol.MAX_TEXT_INPUT_CHARS
import com.company.tap.protocol.OPERATION_VERSION
import com.company.tap.protocol.Operation
import com.company.tap.protocol.Request
import com.company.tap.protocol.Response
import java.net.Socket

internal class DriverCommandEngine(
    instrumentation: Instrumentation,
    device: UiDevice,
    expectedAut: String,
    allowedSystemPackages: Set<String>,
    private val faults: FaultController,
    private val sync: SyncProviderClient,
) {
    private val compiler = SelectorCompiler(expectedAut, allowedSystemPackages)
    private val objects = UiObjectAccess(device, compiler)
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
        try {
            validateSelectors(request)
        } catch (invalid: InvalidSelectorException) {
            return Response.failure(
                ErrorCode.INVALID_SELECTOR,
                detail = invalid.detail,
                message = invalid.message,
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
            Operation.DEVICE_INFO -> ui.deviceInfo(context)
            Operation.PRESS_KEY -> ui.pressKey(context, request)
            Operation.EXISTS -> Response(
                true,
                value = objects.hasObject(requireNotNull(request.selector)),
                durationMs = elapsed(started),
            )
            Operation.COUNT -> Response(
                true,
                count = objects.count(requireNotNull(request.selector)),
                durationMs = elapsed(started),
            )
            Operation.SNAPSHOT -> ui.snapshot(context, request)
            Operation.TAP -> ui.tap(context, socket, request)
            Operation.LONG_TAP -> ui.longTap(context, request)
            Operation.WAIT_VISIBLE -> ui.waitVisible(context, request, expected = true)
            Operation.WAIT_GONE -> ui.waitVisible(context, request, expected = false)
            Operation.WAIT_APP_VISIBLE -> ui.waitAppVisible(context, request)
            Operation.DUMP_HIERARCHY -> ui.dumpHierarchy(started)
            Operation.SET_TEXT -> ui.setText(context, request)
            Operation.TYPE_TEXT -> ui.typeText(context, request)
            Operation.CLEAR_TEXT -> ui.clearText(context, request)
            Operation.SWIPE -> ui.swipe(context, request)
            Operation.SCROLL -> ui.scroll(context, request)
            Operation.SCROLL_UNTIL -> ui.scrollUntil(context, request)
            Operation.SCREENSHOT -> ui.screenshot(context)
            Operation.SYNC_BOOTSTRAP -> sync.bootstrap(request, started)
            Operation.SYNC_STATE -> sync.state(request, started)
        }
    }

    private fun isInvalid(request: Request): Boolean = when (request.operation) {
        Operation.EXISTS, Operation.COUNT, Operation.SNAPSHOT, Operation.TAP, Operation.LONG_TAP,
        Operation.WAIT_VISIBLE, Operation.WAIT_GONE, Operation.CLEAR_TEXT ->
            request.selector == null
        Operation.PRESS_KEY -> (request.keyCode ?: -1) < 0
        Operation.WAIT_APP_VISIBLE -> request.packageName.isNullOrBlank()
        Operation.SWIPE, Operation.SCROLL ->
            request.selector == null || request.direction == null || request.distancePercent !in 1..100
        Operation.SET_TEXT, Operation.TYPE_TEXT ->
            request.selector == null || request.inputText == null ||
                (request.inputText?.length ?: 0) > MAX_TEXT_INPUT_CHARS
        Operation.SCROLL_UNTIL ->
            request.selector == null || request.containerSelector == null ||
                request.maxScrolls !in 1..MAX_SCROLLS || request.distancePercent !in 1..100
        Operation.HEALTH, Operation.DEVICE_INFO, Operation.DUMP_HIERARCHY, Operation.SCREENSHOT -> false
        Operation.SYNC_BOOTSTRAP ->
            request.observedPid == null || request.observedStartToken.isNullOrBlank() ||
                request.expectedProcessStartUuid != null || request.expectedSessionIdentity != null
        Operation.SYNC_STATE ->
            request.observedPid == null || request.observedStartToken.isNullOrBlank() ||
                request.expectedProcessStartUuid.isNullOrBlank() ||
                request.expectedSessionIdentity.isNullOrBlank()
    }

    /** Structural and scope validation before any lookup; malformed selectors never touch UI. */
    private fun validateSelectors(request: Request) {
        val target = request.selector?.let(compiler::compile)
        val container = request.containerSelector?.let(compiler::compile)
        if (target != null && container != null && target.scopePackage != container.scopePackage) {
            throw InvalidSelectorException(
                ErrorDetail.SCOPE_MISMATCH,
                "Target and container selectors must share one scope package",
            )
        }
    }
}

internal fun elapsed(started: Long): Long = android.os.SystemClock.elapsedRealtime() - started
