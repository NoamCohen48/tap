package com.company.tap.driver

import android.app.Instrumentation
import androidx.test.uiautomator.UiDevice
import com.company.tap.driver.engine.CommandContext
import com.company.tap.protocol.ArtifactResult
import com.company.tap.protocol.BoolResult
import com.company.tap.protocol.ClearText
import com.company.tap.protocol.Command
import com.company.tap.protocol.CommandFailure
import com.company.tap.protocol.CommandHandler
import com.company.tap.protocol.Count
import com.company.tap.protocol.CountResult
import com.company.tap.protocol.DeviceInfoQuery
import com.company.tap.protocol.DeviceInfoResult
import com.company.tap.protocol.Done
import com.company.tap.protocol.DumpHierarchy
import com.company.tap.protocol.ErrorCode
import com.company.tap.protocol.Exists
import com.company.tap.protocol.Health
import com.company.tap.protocol.InvalidSelectorException
import com.company.tap.protocol.LongTap
import com.company.tap.protocol.MAX_REQUEST_TIMEOUT_MS
import com.company.tap.protocol.Moved
import com.company.tap.protocol.PressKey
import com.company.tap.protocol.Request
import com.company.tap.protocol.Response
import com.company.tap.protocol.Screenshot
import com.company.tap.protocol.Scroll
import com.company.tap.protocol.ScrollUntil
import com.company.tap.protocol.SelectorScopeMismatchException
import com.company.tap.protocol.SetText
import com.company.tap.protocol.Snapshot
import com.company.tap.protocol.SnapshotResult
import com.company.tap.protocol.Swipe
import com.company.tap.protocol.SyncBootstrap
import com.company.tap.protocol.SyncPoll
import com.company.tap.protocol.SyncResult
import com.company.tap.protocol.Tap
import com.company.tap.protocol.Targeted
import com.company.tap.protocol.TextResult
import com.company.tap.protocol.TypeText
import com.company.tap.protocol.WaitAppVisible
import com.company.tap.protocol.WaitGone
import com.company.tap.protocol.WaitScreenStable
import com.company.tap.protocol.WaitVisible
import com.company.tap.protocol.dispatch
import java.net.Socket

/**
 * Turns an accepted [Request] into its terminal [Response]: envelope and selector checks first,
 * then the typed [CommandHandler] dispatch. Handlers return their result or throw
 * [CommandFailure]; this is the only place responses are built.
 */
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
        if (request.sessionId != sessionId || request.generation != generation) {
            return Response.failure(ErrorCode.SESSION_MISMATCH, durationMs = context.elapsed())
        }
        if (request.timeoutMs !in 0..MAX_REQUEST_TIMEOUT_MS) {
            return Response.failure(ErrorCode.INVALID_REQUEST, durationMs = context.elapsed())
        }
        try {
            validateSelectors(request.command)
        } catch (invalid: InvalidSelectorException) {
            return Response.failure(
                ErrorCode.INVALID_SELECTOR,
                detail = invalid.detail,
                message = invalid.message,
                durationMs = context.elapsed(),
            )
        }

        context.checkpoint()
        return try {
            Response.ok(request.command.dispatch(Handlers(context, socket, generation)), context.elapsed())
        } catch (failure: CommandFailure) {
            Response.failure(failure.code, detail = failure.detail, message = failure.remoteMessage, durationMs = context.elapsed())
        }
    }

    /** Structural and scope validation before any lookup; malformed selectors never touch UI. */
    private fun validateSelectors(command: Command) {
        when (command) {
            is ScrollUntil -> {
                val target = compiler.compile(command.selector)
                val container = compiler.compile(command.container)
                if (target.scopePackage != container.scopePackage) {
                    throw SelectorScopeMismatchException(
                        "Target and container selectors must share one scope package",
                    )
                }
            }
            is Targeted -> compiler.compile(command.selector)
            else -> Unit
        }
    }

    private inner class Handlers(
        private val context: CommandContext,
        private val socket: Socket,
        private val generation: Long,
    ) : CommandHandler {
        override fun health(command: Health): Done = Done
        override fun deviceInfo(command: DeviceInfoQuery): DeviceInfoResult = ui.deviceInfo()
        override fun pressKey(command: PressKey): Done = ui.pressKey(context, command)
        override fun screenshot(command: Screenshot): ArtifactResult = ui.screenshot(context)
        override fun dumpHierarchy(command: DumpHierarchy): TextResult = ui.dumpHierarchy()
        override fun exists(command: Exists): BoolResult = BoolResult(objects.hasObject(command.selector))
        override fun count(command: Count): CountResult = CountResult(objects.count(command.selector))
        override fun snapshot(command: Snapshot): SnapshotResult = ui.snapshot(command)
        override fun waitVisible(command: WaitVisible): Done = ui.waitVisible(context, command.selector, expected = true)
        override fun waitGone(command: WaitGone): Done = ui.waitVisible(context, command.selector, expected = false)
        override fun waitAppVisible(command: WaitAppVisible): Done = ui.waitAppVisible(context, command)
        override fun waitScreenStable(command: WaitScreenStable): Done = ui.waitScreenStable(context, command)
        override fun tap(command: Tap): Done = ui.tap(context, socket, command, generation)
        override fun longTap(command: LongTap): Done = ui.longTap(context, command)
        override fun setText(command: SetText): Done = ui.setText(context, command)
        override fun typeText(command: TypeText): Done = ui.typeText(context, command)
        override fun clearText(command: ClearText): Done = ui.clearText(context, command)
        override fun swipe(command: Swipe): Moved = ui.swipe(context, command)
        override fun scroll(command: Scroll): Moved = ui.scroll(context, command)
        override fun scrollUntil(command: ScrollUntil): Done = ui.scrollUntil(context, command)
        override fun syncBootstrap(command: SyncBootstrap): SyncResult = sync.bootstrap(context, command)
        override fun syncPoll(command: SyncPoll): SyncResult = sync.poll(context, command)
    }
}
