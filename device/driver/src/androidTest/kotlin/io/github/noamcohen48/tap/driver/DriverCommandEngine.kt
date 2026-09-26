package io.github.noamcohen48.tap.driver

import android.app.Instrumentation
import androidx.test.uiautomator.UiDevice
import io.github.noamcohen48.tap.api.v1.ClearText
import io.github.noamcohen48.tap.api.v1.Count
import io.github.noamcohen48.tap.api.v1.DeviceInfo
import io.github.noamcohen48.tap.api.v1.DeviceInfoQuery
import io.github.noamcohen48.tap.api.v1.DumpHierarchy
import io.github.noamcohen48.tap.api.v1.ElementSnapshot
import io.github.noamcohen48.tap.api.v1.Exists
import io.github.noamcohen48.tap.api.v1.LongTap
import io.github.noamcohen48.tap.api.v1.PressKey
import io.github.noamcohen48.tap.api.v1.Scroll
import io.github.noamcohen48.tap.api.v1.ScrollUntil
import io.github.noamcohen48.tap.api.v1.SetText
import io.github.noamcohen48.tap.api.v1.Snapshot
import io.github.noamcohen48.tap.api.v1.Swipe
import io.github.noamcohen48.tap.api.v1.Tap
import io.github.noamcohen48.tap.api.v1.TypeText
import io.github.noamcohen48.tap.api.v1.WaitAppVisible
import io.github.noamcohen48.tap.api.v1.WaitGone
import io.github.noamcohen48.tap.api.v1.WaitScreenStable
import io.github.noamcohen48.tap.api.v1.WaitVisible
import io.github.noamcohen48.tap.driver.engine.CommandContext
import io.github.noamcohen48.tap.protocol.CommandFailure
import io.github.noamcohen48.tap.protocol.CommandHandler
import io.github.noamcohen48.tap.protocol.InvalidCommandException
import io.github.noamcohen48.tap.protocol.Responses
import io.github.noamcohen48.tap.protocol.dispatch
import io.github.noamcohen48.tap.protocol.selectors
import io.github.noamcohen48.tap.protocol.stamped
import io.github.noamcohen48.tap.wire.v1.ArtifactInfo
import io.github.noamcohen48.tap.wire.v1.CaptureScreenshot
import io.github.noamcohen48.tap.wire.v1.Request
import io.github.noamcohen48.tap.wire.v1.Response
import io.github.noamcohen48.tap.wire.v1.SyncBootstrap
import io.github.noamcohen48.tap.wire.v1.SyncPoll
import io.github.noamcohen48.tap.wire.v1.SyncState
import java.net.Socket

/**
 * Turns an accepted, screened [Request] into its terminal [Response]: the driver's scope policy,
 * then the typed [CommandHandler] dispatch.
 * Handlers return their result or throw [CommandFailure]; this is the only place responses are
 * built. Every response is stamped with its duration and the request identity it answers.
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
        generation: Long,
    ): Response {
        val response =
            try {
                checkScopePolicy(request)
                context.checkpoint()
                request.dispatch(Handlers(context, socket, generation))
            } catch (failure: CommandFailure) {
                Responses.failure(failure.code, detail = failure.detail, message = failure.remoteMessage)
            }
        return response.stamped(context.elapsed(), context.requestId, generation)
    }

    /**
     * Scope policy that needs session config (the system-package allowlist, AUT-only resources),
     * for every selector the command carries. Session identity, the timeout range and the shared
     * structural validation already ran on the reader lane (`RequestScreening`), so a request that
     * reaches this point is well formed. A denied selector never touches UI.
     */
    private fun checkScopePolicy(request: Request) {
        try {
            if (request.bodyCase == Request.BodyCase.COMMAND) request.command.selectors.forEach(compiler::compile)
        } catch (invalid: InvalidCommandException) {
            throw invalid.toFailure()
        }
    }

    private inner class Handlers(
        private val context: CommandContext,
        private val socket: Socket,
        private val generation: Long,
    ) : CommandHandler {
        override fun health() = Unit

        override fun screenshot(command: CaptureScreenshot): ArtifactInfo = ui.screenshot(context)

        override fun syncBootstrap(command: SyncBootstrap): SyncState = sync.bootstrap(context, command)

        override fun syncPoll(command: SyncPoll): SyncState = sync.poll(context, command)

        override fun deviceInfo(command: DeviceInfoQuery): DeviceInfo = ui.deviceInfo()

        override fun pressKey(command: PressKey) = ui.pressKey(context, command)

        override fun dumpHierarchy(command: DumpHierarchy): String = ui.dumpHierarchy()

        override fun exists(command: Exists): Boolean = objects.hasObject(command.selector)

        override fun count(command: Count): Int = objects.count(command.selector)

        override fun snapshot(command: Snapshot): ElementSnapshot = ui.snapshot(command)

        override fun waitVisible(command: WaitVisible) = ui.waitVisible(context, command.selector, expected = true)

        override fun waitGone(command: WaitGone) = ui.waitVisible(context, command.selector, expected = false)

        override fun waitAppVisible(command: WaitAppVisible) = ui.waitAppVisible(context, command)

        override fun waitScreenStable(command: WaitScreenStable) = ui.waitScreenStable(context, command)

        override fun tap(command: Tap) = ui.tap(context, socket, command, generation)

        override fun longTap(command: LongTap) = ui.longTap(context, command)

        override fun setText(command: SetText) = ui.setText(context, command)

        override fun typeText(command: TypeText) = ui.typeText(context, command)

        override fun clearText(command: ClearText) = ui.clearText(context, command)

        override fun swipe(command: Swipe): Boolean = ui.swipe(context, command)

        override fun scroll(command: Scroll): Boolean = ui.scroll(context, command)

        override fun scrollUntil(command: ScrollUntil) = ui.scrollUntil(context, command)
    }
}
