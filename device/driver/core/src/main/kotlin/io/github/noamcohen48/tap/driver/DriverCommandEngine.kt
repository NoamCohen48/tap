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
import io.github.noamcohen48.tap.api.v1.Selector
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
import java.util.IdentityHashMap

/**
 * Turns an accepted, screened [Request] into its terminal [Response]: selector compilation,
 * then the typed [CommandHandler] dispatch to the command groups.
 * Handlers return their result or throw [CommandFailure]; this is the only place responses are
 * built. Every response is stamped with its duration and the request identity it answers.
 */
internal class DriverCommandEngine(
    instrumentation: Instrumentation,
    device: UiDevice,
    expectedAut: String,
    faults: FaultHooks,
    private val sync: SyncProviderClient,
) {
    private val compiler = SelectorCompiler(expectedAut)
    private val objects = UiObjectAccess(device)
    private val gestures = GestureCommands(device, objects, faults)
    private val textInput = TextInputCommands(instrumentation, objects)
    private val waits = WaitCommands(device, objects)
    private val queries = QueryCommands(device, objects)
    private val screenStability = ScreenStability(instrumentation)
    private val artifacts = ArtifactCommands(instrumentation, device)

    /** Runs on the pipeline executor. Deadlines are measured from [CommandContext.acceptedAtMs]. */
    fun execute(
        context: CommandContext,
        request: Request,
        generation: Long,
    ): Response {
        val response =
            try {
                val selectors = compileSelectors(request)
                context.checkpoint()
                request.dispatch(Handlers(context, selectors))
            } catch (failure: CommandFailure) {
                Responses.failure(failure.code, detail = failure.detail, message = failure.remoteMessage)
            }
        return response.stamped(context.elapsed(), context.requestId, generation)
    }

    /**
     * Compiles every selector the command carries, once for the whole request: this is where
     * the session's AUT is applied (the default scope, AUT-only resources), and the result is
     * what every poll of the command reuses. Session identity,
     * the timeout range and the shared structural validation already ran on the reader lane
     * (`RequestScreening`), so a request that reaches this point is well formed. A denied
     * selector never touches UI.
     */
    private fun compileSelectors(request: Request): CompiledSelectors {
        val compiled = CompiledSelectors(compiler)
        try {
            if (request.bodyCase == Request.BodyCase.COMMAND) request.command.selectors.forEach(compiled::add)
        } catch (invalid: InvalidCommandException) {
            throw invalid.toFailure()
        }
        return compiled
    }

    /**
     * The request's compiled selectors, keyed by the (immutable) wire message instance the
     * command carries. A selector not seen by [add] is compiled on first use.
     */
    private class CompiledSelectors(
        private val compiler: SelectorCompiler,
    ) {
        private val bySelector = IdentityHashMap<Selector, CompiledSelector>()

        fun add(selector: Selector) {
            bySelector[selector] = compiler.compile(selector)
        }

        operator fun get(selector: Selector): CompiledSelector =
            bySelector.getOrPut(selector) {
                try {
                    compiler.compile(selector)
                } catch (invalid: InvalidCommandException) {
                    throw invalid.toFailure()
                }
            }
    }

    private inner class Handlers(
        private val context: CommandContext,
        private val selectors: CompiledSelectors,
    ) : CommandHandler {
        override fun health() = Unit

        override fun screenshot(command: CaptureScreenshot): ArtifactInfo = artifacts.screenshot(context)

        override fun syncBootstrap(command: SyncBootstrap): SyncState = sync.bootstrap(context, command)

        override fun syncPoll(command: SyncPoll): SyncState = sync.poll(context, command)

        override fun deviceInfo(command: DeviceInfoQuery): DeviceInfo = queries.deviceInfo()

        override fun pressKey(command: PressKey) = gestures.pressKey(context, command)

        override fun dumpHierarchy(command: DumpHierarchy): String = artifacts.dumpHierarchy()

        override fun exists(command: Exists): Boolean = queries.exists(context, selectors[command.selector])

        override fun count(command: Count): Int = queries.count(context, selectors[command.selector])

        override fun snapshot(command: Snapshot): ElementSnapshot = queries.snapshot(selectors[command.selector])

        override fun waitVisible(command: WaitVisible) = waits.waitVisible(context, selectors[command.selector], expected = true)

        override fun waitGone(command: WaitGone) = waits.waitVisible(context, selectors[command.selector], expected = false)

        override fun waitAppVisible(command: WaitAppVisible) = waits.waitAppVisible(context, command)

        override fun waitScreenStable(command: WaitScreenStable) = screenStability.waitScreenStable(context, command)

        override fun tap(command: Tap) = gestures.tap(context, command, selectors[command.selector])

        override fun longTap(command: LongTap) = gestures.longTap(context, selectors[command.selector])

        override fun setText(command: SetText) = textInput.setText(context, command, selectors[command.selector])

        override fun typeText(command: TypeText) = textInput.typeText(context, command)

        override fun clearText(command: ClearText) = textInput.clearText(context, selectors[command.selector])

        override fun swipe(command: Swipe) = gestures.swipe(context, command, selectors[command.selector])

        override fun scroll(command: Scroll) = gestures.scroll(context, command, selectors[command.selector])
    }
}
