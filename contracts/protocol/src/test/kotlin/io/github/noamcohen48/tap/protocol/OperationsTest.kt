package io.github.noamcohen48.tap.protocol

import io.github.noamcohen48.tap.api.v1.ClearText
import io.github.noamcohen48.tap.api.v1.Command
import io.github.noamcohen48.tap.api.v1.Command.OpCase
import io.github.noamcohen48.tap.api.v1.CommandResult.OutcomeCase
import io.github.noamcohen48.tap.api.v1.Count
import io.github.noamcohen48.tap.api.v1.DeviceInfo
import io.github.noamcohen48.tap.api.v1.DeviceInfoQuery
import io.github.noamcohen48.tap.api.v1.Direction
import io.github.noamcohen48.tap.api.v1.DumpHierarchy
import io.github.noamcohen48.tap.api.v1.ElementSnapshot
import io.github.noamcohen48.tap.api.v1.ErrorCode
import io.github.noamcohen48.tap.api.v1.Exists
import io.github.noamcohen48.tap.api.v1.LongTap
import io.github.noamcohen48.tap.api.v1.MatchMode
import io.github.noamcohen48.tap.api.v1.OpenSystemPanel
import io.github.noamcohen48.tap.api.v1.PressKey
import io.github.noamcohen48.tap.api.v1.Scroll
import io.github.noamcohen48.tap.api.v1.SetText
import io.github.noamcohen48.tap.api.v1.Snapshot
import io.github.noamcohen48.tap.api.v1.StabilitySignal
import io.github.noamcohen48.tap.api.v1.Swipe
import io.github.noamcohen48.tap.api.v1.SystemPanel
import io.github.noamcohen48.tap.api.v1.Tap
import io.github.noamcohen48.tap.api.v1.TypeText
import io.github.noamcohen48.tap.api.v1.WaitAppVisible
import io.github.noamcohen48.tap.api.v1.WaitGone
import io.github.noamcohen48.tap.api.v1.WaitScreenStable
import io.github.noamcohen48.tap.api.v1.WaitVisible
import io.github.noamcohen48.tap.wire.v1.ArtifactInfo
import io.github.noamcohen48.tap.wire.v1.CaptureScreenshot
import io.github.noamcohen48.tap.wire.v1.Request
import io.github.noamcohen48.tap.wire.v1.Request.BodyCase
import io.github.noamcohen48.tap.wire.v1.Response
import io.github.noamcohen48.tap.wire.v1.SyncBootstrap
import io.github.noamcohen48.tap.wire.v1.SyncPoll
import io.github.noamcohen48.tap.wire.v1.SyncState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OperationsTest {
    @Test
    fun theCatalogueIsTheSchemaCases() {
        val ops = OpCase.entries.filter { it != OpCase.OP_NOT_SET }.map { it.name.lowercase() }
        assertEquals(ops.sorted(), Operations.PUBLIC)
        assertEquals(listOf("health", "screenshot", "sync_bootstrap", "sync_poll"), Operations.HOST_INTERNAL)
        assertEquals((Operations.PUBLIC + Operations.HOST_INTERNAL).sorted(), Operations.ALL)
        assertEquals(Operations.ALL.distinct(), Operations.ALL)
        assertTrue("tap" in Operations.PUBLIC && "scroll" in Operations.PUBLIC && "wait_screen_stable" in Operations.PUBLIC)
        assertFalse("scroll_until" in Operations.PUBLIC)
    }

    @Test
    fun samplesCoverEveryOperation() {
        assertEquals(OpCase.entries.toSet() - OpCase.OP_NOT_SET, sampleCommands.values.map { it.opCase }.toSet())
        assertEquals(sampleCommands.keys, sampleCommands.values.map { it.op }.toSet())
        assertEquals(Operations.HOST_INTERNAL.toSet(), sampleRequests.keys)
        sampleRequests.forEach { (name, request) -> assertEquals(name, request.op) }
        assertEquals("unset", Command.getDefaultInstance().op)
        assertEquals("unset", Request.getDefaultInstance().op)
        assertEquals("tap", Requests.of(Commands.tap(Selectors.text("x"))).op)
    }

    @Test
    fun mutationsAreExactlyTheInputCommands() {
        val mutations = setOf("press_key", "tap", "long_tap", "set_text", "type_text", "clear_text", "swipe", "scroll", "open_system_panel")
        sampleCommands.forEach { (name, command) ->
            assertEquals(name in mutations, command.isMutation, name)
            assertEquals(name in mutations, command.toRequest().isMutation, name)
        }
        sampleRequests.values.forEach { assertFalse(it.isMutation, it.op) }
        assertFalse(Command.getDefaultInstance().isMutation)
    }

    @Test
    fun targetSelectorIsTheActedOnSelector() {
        val target = Selectors.text("Row 40")
        val scroll = Commands.scroll(target, Direction.DIR_DOWN)
        assertEquals(target, scroll.targetSelector)
        assertEquals(listOf(target), scroll.selectors)
        assertNull(Commands.deviceInfo().targetSelector)
        assertEquals(emptyList(), Commands.pressKey(KEYCODE_BACK).selectors)
        assertNull(Requests.health().targetSelector)
        assertEquals(target, Requests.of(Commands.tap(target)).targetSelector)
        val targeted = sampleCommands.filterValues { it.targetSelector != null }.keys
        assertEquals(sampleCommands.keys - setOf("device_info", "press_key", "type_text", "dump_hierarchy", "wait_app_visible", "wait_screen_stable", "open_system_panel"), targeted)
    }

    @Test
    fun optionalArgumentsLeftNullAreAbsentOnTheWire() {
        val swipe = Commands.swipe(Selectors.text("x"), Direction.DIR_UP).swipe
        assertFalse(swipe.hasDistancePercent())
        assertFalse(Commands.scroll(Selectors.text("x"), Direction.DIR_DOWN).scroll.hasDistancePercent())
        val stable = Commands.waitScreenStable(AUT).waitScreenStable
        assertFalse(stable.hasStableForMs())
        assertEquals(StabilitySignal.STABILITY_UNSPECIFIED, stable.signal)
        assertFalse(Commands.tap(Selectors.text("x")).hasTimeoutMs())
    }

    @Test
    fun dispatchCallsTheMatchingHandlerAndWrapsItsResult() {
        val expected =
            mapOf(
                "device_info" to OutcomeCase.DEVICE_INFO,
                "dump_hierarchy" to OutcomeCase.TEXT,
                "exists" to OutcomeCase.BOOL,
                "count" to OutcomeCase.COUNT,
                "snapshot" to OutcomeCase.SNAPSHOT,
            )
        sampleCommands.forEach { (name, command) ->
            val handler = RecordingHandler()
            val response = command.toRequest().dispatch(handler)
            assertEquals(listOf(name), handler.calls, name)
            assertEquals(expected[name] ?: OutcomeCase.DONE, response.result.outcomeCase, name)
            assertEquals(Response.InternalCase.INTERNAL_NOT_SET, response.internalCase, name)
            assertTrue(response.ok, name)
        }
        val handler = RecordingHandler()
        assertEquals(7, Commands.count(Selectors.text("x")).toRequest().dispatch(handler).result.count)
        assertEquals("<hierarchy/>", Commands.dumpHierarchy().toRequest().dispatch(handler).result.text)
        assertEquals(34, Commands.deviceInfo().toRequest().dispatch(handler).result.deviceInfo.apiLevel)
    }

    @Test
    fun dispatchCarriesHostInternalDataBesideDone() {
        val internal =
            mapOf(
                "health" to Response.InternalCase.INTERNAL_NOT_SET,
                "screenshot" to Response.InternalCase.ARTIFACT,
                "sync_bootstrap" to Response.InternalCase.SYNC,
                "sync_poll" to Response.InternalCase.SYNC,
            )
        sampleRequests.forEach { (name, request) ->
            val handler = RecordingHandler()
            val response = request.dispatch(handler)
            assertEquals(listOf(name), handler.calls)
            assertEquals(OutcomeCase.DONE, response.result.outcomeCase, name)
            assertEquals(internal.getValue(name), response.internalCase, name)
        }
        assertEquals("blob", Requests.screenshot().dispatch(RecordingHandler()).artifact.blobId)
    }

    @Test
    fun dispatchOfAnUnsetOperationIsUnsupported() {
        listOf(Request.getDefaultInstance(), Requests.of(Command.getDefaultInstance())).forEach { request ->
            val handler = RecordingHandler()
            val failure = assertFailsWith<CommandFailure> { request.dispatch(handler) }
            assertEquals(ErrorCode.ERR_UNSUPPORTED, failure.code)
            assertEquals(emptyList(), handler.calls)
        }
    }

    @Test
    fun handlerFailuresPropagate() {
        val failing =
            object : CommandHandler by RecordingHandler() {
                override fun tap(command: Tap): Unit = throw CommandFailure(ErrorCode.ERR_AMBIGUOUS)
            }
        val failure = assertFailsWith<CommandFailure> { Commands.tap(Selectors.text("x")).toRequest().dispatch(failing) }
        assertEquals(ErrorCode.ERR_AMBIGUOUS, failure.code)
    }

    @Test
    fun responsesExposeTheirOutcome() {
        val done = Responses.done(12)
        assertTrue(done.ok)
        assertEquals(12, done.result.durationMs)
        assertNull(done.errorCode)
        assertNull(done.detail)
        assertNull(done.message)

        val failure = Responses.failure(ErrorCode.ERR_ACTION_REJECTED, 5, detail = ErrorDetail.PARTIAL_INPUT, message = "why")
        assertFalse(failure.ok)
        assertEquals(ErrorCode.ERR_ACTION_REJECTED, failure.errorCode)
        assertEquals(ErrorDetail.PARTIAL_INPUT, failure.detail)
        assertEquals("why", failure.message)

        val bare = Responses.failure(ErrorCode.ERR_WAIT_TIMEOUT)
        assertFalse(bare.result.error.hasDetail())
        assertFalse(bare.result.error.hasMessage())
        assertNull(bare.detail)

        assertFalse(Response.getDefaultInstance().ok, "a response without a result is not a success")
        val newerCode = Response.newBuilder().setResult(failure.result.toBuilder().setError(failure.result.error.toBuilder().setCodeValue(999))).build()
        assertEquals(ErrorCode.ERR_UNKNOWN, newerCode.errorCode)
    }

    @Test
    fun decodeFallbacksAreNeverSent() {
        listOf(ErrorCode.ERR_UNSPECIFIED, ErrorCode.ERR_UNKNOWN, ErrorCode.UNRECOGNIZED).forEach { code ->
            assertFailsWith<IllegalArgumentException> { Responses.failure(code) }
        }
    }

    @Test
    fun stampingFillsTheDriverOwnedFields() {
        val stamped = Responses.failure(ErrorCode.ERR_OVERLOADED).stamped(durationMs = 31, requestId = 9, generation = 4)
        assertEquals(31, stamped.result.durationMs)
        assertEquals(9, stamped.result.requestId)
        assertEquals(4, stamped.result.sessionGeneration)
        assertEquals(ErrorCode.ERR_OVERLOADED, stamped.errorCode)
    }

    @Test
    fun envelopeWrapsTheBody() {
        val request = Requests.of(Commands.tap(Selectors.text("x"))).withEnvelope("session", 3, 5_000)
        assertEquals("session", request.sessionId)
        assertEquals(3, request.generation)
        assertEquals(5_000, request.timeoutMs)
        assertEquals(BodyCase.COMMAND, request.bodyCase)
    }

    @Test
    fun parseRequestMapsGarbageToInvalidRequest() {
        val request = Requests.health().withEnvelope("s", 1, 100)
        assertEquals(request, parseRequest(request.toByteArray()))
        val error = assertFailsWith<InvalidCommandException> { parseRequest(byteArrayOf(0x0A, 0x05, 0x01)) }
        assertEquals(ErrorCode.ERR_INVALID_REQUEST, error.code)
        assertFailsWith<ProtocolException> { parsePayload("HELLO", byteArrayOf(0x0A, 0x7F), Request::parseFrom) }
    }

    /** Records which method ran and returns a fixed value per result type. */
    private class RecordingHandler : CommandHandler {
        val calls = mutableListOf<String>()

        private fun <T> record(
            name: String,
            value: T,
        ): T = value.also { calls += name }

        override fun health() = record("health", Unit)

        override fun screenshot(command: CaptureScreenshot): ArtifactInfo = record("screenshot", ArtifactInfo.newBuilder().setBlobId("blob").build())

        override fun syncBootstrap(command: SyncBootstrap): SyncState = record("sync_bootstrap", SyncState.newBuilder().setInitialized(true).build())

        override fun syncPoll(command: SyncPoll): SyncState = record("sync_poll", SyncState.newBuilder().setBusyCount(1).build())

        override fun deviceInfo(command: DeviceInfoQuery): DeviceInfo = record("device_info", DeviceInfo.newBuilder().setApiLevel(34).build())

        override fun pressKey(command: PressKey) = record("press_key", Unit)

        override fun dumpHierarchy(command: DumpHierarchy): String = record("dump_hierarchy", "<hierarchy/>")

        override fun exists(command: Exists): Boolean = record("exists", true)

        override fun count(command: Count): Int = record("count", 7)

        override fun snapshot(command: Snapshot): ElementSnapshot = record("snapshot", ElementSnapshot.newBuilder().setText("x").build())

        override fun waitVisible(command: WaitVisible) = record("wait_visible", Unit)

        override fun waitGone(command: WaitGone) = record("wait_gone", Unit)

        override fun waitAppVisible(command: WaitAppVisible) = record("wait_app_visible", Unit)

        override fun waitScreenStable(command: WaitScreenStable) = record("wait_screen_stable", Unit)

        override fun tap(command: Tap) = record("tap", Unit)

        override fun longTap(command: LongTap) = record("long_tap", Unit)

        override fun setText(command: SetText) = record("set_text", Unit)

        override fun typeText(command: TypeText) = record("type_text", Unit)

        override fun clearText(command: ClearText) = record("clear_text", Unit)

        override fun swipe(command: Swipe) = record("swipe", Unit)

        override fun scroll(command: Scroll) = record("scroll", Unit)

        override fun openSystemPanel(command: OpenSystemPanel) = record("open_system_panel", Unit)
    }

    companion object {
        private val button = Selectors.androidResource(AUT, "login")
        private val list = Selectors.androidResource(AUT, "list")

        /** One valid command per public operation, keyed by its protocol name. */
        val sampleCommands: Map<String, Command> =
            mapOf(
                "device_info" to Commands.deviceInfo(),
                "press_key" to Commands.pressKey(KEYCODE_ENTER),
                "dump_hierarchy" to Commands.dumpHierarchy(),
                "exists" to Commands.exists(Selectors.text("Sign in")),
                "count" to Commands.count(Selectors.text("Row", MatchMode.MATCH_STARTS_WITH)),
                "snapshot" to Commands.snapshot(Selectors.of(Nodes.autResource("status"))),
                "wait_visible" to Commands.waitVisible(Selectors.text("Welcome")),
                "wait_gone" to Commands.waitGone(Selectors.rawResource("spinner")),
                "wait_app_visible" to Commands.waitAppVisible(AUT),
                "wait_screen_stable" to Commands.waitScreenStable(AUT, stableForMs = 750, signal = StabilitySignal.STABILITY_PIXELS),
                "tap" to Commands.tap(button),
                "long_tap" to Commands.longTap(Selectors.contentDescription("More")),
                "set_text" to Commands.setText(button, "user@example.com"),
                "type_text" to Commands.typeText("typed"),
                "clear_text" to Commands.clearText(button),
                "swipe" to Commands.swipe(list, Direction.DIR_LEFT, distancePercent = 60),
                "scroll" to Commands.scroll(list, Direction.DIR_DOWN),
                "open_system_panel" to Commands.openSystemPanel(SystemPanel.SYSTEM_PANEL_NOTIFICATIONS),
            )

        /** One valid request per host-internal operation. */
        val sampleRequests: Map<String, Request> =
            mapOf(
                "health" to Requests.health(),
                "screenshot" to Requests.screenshot(),
                "sync_bootstrap" to Requests.syncBootstrap(4242, "1234567"),
                "sync_poll" to Requests.syncPoll(4242, "1234567", "5b4c2f4e-0a8d-4d2a-9d63-7a0c9f5f6f01", "session-identity"),
            )
    }
}
