package io.github.noamcohen48.tap.protocol

import io.github.noamcohen48.tap.api.v1.AwaitToast
import io.github.noamcohen48.tap.api.v1.ChoosePermission
import io.github.noamcohen48.tap.api.v1.ClearText
import io.github.noamcohen48.tap.api.v1.Command
import io.github.noamcohen48.tap.api.v1.Command.OpCase
import io.github.noamcohen48.tap.api.v1.CommandResult
import io.github.noamcohen48.tap.api.v1.Count
import io.github.noamcohen48.tap.api.v1.DeviceInfo
import io.github.noamcohen48.tap.api.v1.DeviceInfoQuery
import io.github.noamcohen48.tap.api.v1.Direction
import io.github.noamcohen48.tap.api.v1.DismissKeyguard
import io.github.noamcohen48.tap.api.v1.DisplayRotation
import io.github.noamcohen48.tap.api.v1.Done
import io.github.noamcohen48.tap.api.v1.DoubleTap
import io.github.noamcohen48.tap.api.v1.Drag
import io.github.noamcohen48.tap.api.v1.DumpHierarchy
import io.github.noamcohen48.tap.api.v1.ElementSnapshot
import io.github.noamcohen48.tap.api.v1.ErrorCode
import io.github.noamcohen48.tap.api.v1.Exists
import io.github.noamcohen48.tap.api.v1.Fling
import io.github.noamcohen48.tap.api.v1.GetClipboard
import io.github.noamcohen48.tap.api.v1.HideKeyboard
import io.github.noamcohen48.tap.api.v1.LongTap
import io.github.noamcohen48.tap.api.v1.MatchMode
import io.github.noamcohen48.tap.api.v1.OpenSystemPanel
import io.github.noamcohen48.tap.api.v1.Orientation
import io.github.noamcohen48.tap.api.v1.LocationAccuracy
import io.github.noamcohen48.tap.api.v1.PermissionChoice
import io.github.noamcohen48.tap.api.v1.PerformAccessibilityAction
import io.github.noamcohen48.tap.api.v1.PerformImeAction
import io.github.noamcohen48.tap.api.v1.SetProgress
import io.github.noamcohen48.tap.api.v1.StandardAction
import io.github.noamcohen48.tap.api.v1.PermissionPrompt
import io.github.noamcohen48.tap.api.v1.Pinch
import io.github.noamcohen48.tap.api.v1.PinchDirection
import io.github.noamcohen48.tap.api.v1.PressKey
import io.github.noamcohen48.tap.api.v1.Scroll
import io.github.noamcohen48.tap.api.v1.Selector
import io.github.noamcohen48.tap.api.v1.SetClipboard
import io.github.noamcohen48.tap.api.v1.SetDisplayRotation
import io.github.noamcohen48.tap.api.v1.SetOrientation
import io.github.noamcohen48.tap.api.v1.SetText
import io.github.noamcohen48.tap.api.v1.Snapshot
import io.github.noamcohen48.tap.api.v1.StabilitySignal
import io.github.noamcohen48.tap.api.v1.Swipe
import io.github.noamcohen48.tap.api.v1.SystemPanel
import io.github.noamcohen48.tap.api.v1.Tap
import io.github.noamcohen48.tap.api.v1.Toast
import io.github.noamcohen48.tap.api.v1.TypeText
import io.github.noamcohen48.tap.api.v1.UnfreezeRotation
import io.github.noamcohen48.tap.api.v1.WaitAppVisible
import io.github.noamcohen48.tap.api.v1.WaitGone
import io.github.noamcohen48.tap.api.v1.WaitPermissionPrompt
import io.github.noamcohen48.tap.api.v1.WaitScreenStable
import io.github.noamcohen48.tap.api.v1.WaitVisible
import io.github.noamcohen48.tap.wire.v1.ArtifactInfo
import io.github.noamcohen48.tap.wire.v1.CaptureScreenshot
import io.github.noamcohen48.tap.wire.v1.Health
import io.github.noamcohen48.tap.wire.v1.Request
import io.github.noamcohen48.tap.wire.v1.Request.BodyCase
import io.github.noamcohen48.tap.wire.v1.Response
import io.github.noamcohen48.tap.wire.v1.SyncBootstrap
import io.github.noamcohen48.tap.wire.v1.SyncPoll
import io.github.noamcohen48.tap.wire.v1.SyncState
import io.github.noamcohen48.tap.api.v1.Error as CommandError

/*
 * A TAP1 operation is a `tap.wire.v1.Request` body: a public `tap.v1.Command` or one of the
 * host-internal operations. The helpers below are the one place that knows which operations
 * exist, which of them mutate, what they target and what they return; every `when` over a case
 * enum is exhaustive, so a new schema case does not compile until it is classified here.
 */

/** The protocol names of every operation this build executes, sorted; advertised in the challenge. */
object Operations {
    /** Public commands: the `tap.v1.Command.op` cases, named like their proto field. */
    val PUBLIC: List<String> =
        OpCase.values().filter { it != OpCase.OP_NOT_SET }.map { it.wireName }.sorted()

    /** Host-internal operations: the other `tap.wire.v1.Request.body` cases. */
    val HOST_INTERNAL: List<String> =
        BodyCase.values().filter { it != BodyCase.COMMAND && it != BodyCase.BODY_NOT_SET }.map { it.wireName }.sorted()

    val ALL: List<String> = (PUBLIC + HOST_INTERNAL).sorted()
}

private val OpCase.wireName: String get() = name.lowercase()
private val BodyCase.wireName: String get() = name.lowercase()

/** Protocol name of this command (`tap`, `set_text`, …); `unset` when no op is set. */
val Command.op: String get() = if (opCase == OpCase.OP_NOT_SET) "unset" else opCase.wireName

/** Protocol name of this request's operation: the command's op or the internal operation's name. */
val Request.op: String
    get() =
        when (bodyCase) {
            BodyCase.COMMAND -> command.op
            BodyCase.HEALTH, BodyCase.SCREENSHOT, BodyCase.SYNC_BOOTSTRAP, BodyCase.SYNC_POLL -> bodyCase.wireName
            BodyCase.BODY_NOT_SET, null -> "unset"
        }

/** A command that may change device state; a lost response leaves it `INDETERMINATE`. */
val Command.isMutation: Boolean
    get() =
        when (opCase) {
            OpCase.PRESS_KEY, OpCase.TAP, OpCase.LONG_TAP, OpCase.SET_TEXT, OpCase.TYPE_TEXT,
            OpCase.CLEAR_TEXT, OpCase.SWIPE, OpCase.SCROLL, OpCase.OPEN_SYSTEM_PANEL,
            OpCase.SET_ORIENTATION, OpCase.SET_DISPLAY_ROTATION, OpCase.UNFREEZE_ROTATION,
            OpCase.DISMISS_KEYGUARD, OpCase.DOUBLE_TAP, OpCase.DRAG, OpCase.PINCH, OpCase.FLING,
            OpCase.CHOOSE_PERMISSION, OpCase.HIDE_KEYBOARD, OpCase.PERFORM_IME_ACTION, OpCase.SET_CLIPBOARD,
            OpCase.PERFORM_ACCESSIBILITY_ACTION, OpCase.SET_PROGRESS,
            -> true

            OpCase.DEVICE_INFO, OpCase.DUMP_HIERARCHY, OpCase.EXISTS, OpCase.COUNT, OpCase.SNAPSHOT,
            OpCase.WAIT_VISIBLE, OpCase.WAIT_GONE, OpCase.WAIT_APP_VISIBLE, OpCase.WAIT_SCREEN_STABLE,
            OpCase.WAIT_PERMISSION_PROMPT, OpCase.GET_CLIPBOARD, OpCase.AWAIT_TOAST, OpCase.OP_NOT_SET, null,
            -> false
        }

/** Host-internal operations never mutate the AUT. */
val Request.isMutation: Boolean get() = bodyCase == BodyCase.COMMAND && command.isMutation

/** The selector the command acts on. */
val Command.targetSelector: Selector?
    get() =
        when (opCase) {
            OpCase.EXISTS -> exists.selector
            OpCase.COUNT -> count.selector
            OpCase.SNAPSHOT -> snapshot.selector
            OpCase.WAIT_VISIBLE -> waitVisible.selector
            OpCase.WAIT_GONE -> waitGone.selector
            OpCase.TAP -> tap.selector
            OpCase.LONG_TAP -> longTap.selector
            OpCase.SET_TEXT -> setText.selector
            OpCase.CLEAR_TEXT -> clearText.selector
            OpCase.SWIPE -> swipe.selector
            OpCase.SCROLL -> scroll.selector
            OpCase.DOUBLE_TAP -> doubleTap.selector
            OpCase.DRAG -> drag.selector
            OpCase.PINCH -> pinch.selector
            OpCase.FLING -> fling.selector
            OpCase.PERFORM_IME_ACTION -> performImeAction.selector
            OpCase.PERFORM_ACCESSIBILITY_ACTION -> performAccessibilityAction.selector
            OpCase.SET_PROGRESS -> setProgress.selector
            OpCase.DEVICE_INFO, OpCase.PRESS_KEY, OpCase.TYPE_TEXT, OpCase.DUMP_HIERARCHY, OpCase.WAIT_APP_VISIBLE,
            OpCase.WAIT_SCREEN_STABLE, OpCase.OPEN_SYSTEM_PANEL, OpCase.SET_ORIENTATION,
            OpCase.SET_DISPLAY_ROTATION, OpCase.UNFREEZE_ROTATION, OpCase.DISMISS_KEYGUARD,
            OpCase.WAIT_PERMISSION_PROMPT, OpCase.CHOOSE_PERMISSION, OpCase.HIDE_KEYBOARD, OpCase.SET_CLIPBOARD,
            OpCase.GET_CLIPBOARD, OpCase.AWAIT_TOAST, OpCase.OP_NOT_SET, null,
            -> null
        }

val Request.targetSelector: Selector? get() = if (bodyCase == BodyCase.COMMAND) command.targetSelector else null

/** Every selector the command carries: the target, and a drag's destination. */
val Command.selectors: List<Selector>
    get() = if (opCase == OpCase.DRAG) listOf(drag.selector, drag.target) else listOfNotNull(targetSelector)

/** Public commands. Optional arguments left `null` are absent on the wire (the driver's default). */
object Commands {
    fun deviceInfo(): Command = Command.newBuilder().setDeviceInfo(DeviceInfoQuery.getDefaultInstance()).build()

    fun pressKey(keyCode: Int): Command = Command.newBuilder().setPressKey(PressKey.newBuilder().setKeyCode(keyCode)).build()

    fun openSystemPanel(panel: SystemPanel): Command =
        Command.newBuilder().setOpenSystemPanel(OpenSystemPanel.newBuilder().setPanel(panel)).build()

    fun setOrientation(orientation: Orientation): Command =
        Command.newBuilder().setSetOrientation(SetOrientation.newBuilder().setOrientation(orientation)).build()

    fun setDisplayRotation(rotation: DisplayRotation): Command =
        Command.newBuilder().setSetDisplayRotation(SetDisplayRotation.newBuilder().setRotation(rotation)).build()

    fun unfreezeRotation(): Command =
        Command.newBuilder().setUnfreezeRotation(UnfreezeRotation.getDefaultInstance()).build()

    fun dismissKeyguard(): Command = Command.newBuilder().setDismissKeyguard(DismissKeyguard.getDefaultInstance()).build()

    fun waitPermissionPrompt(): Command =
        Command.newBuilder().setWaitPermissionPrompt(WaitPermissionPrompt.getDefaultInstance()).build()

    fun choosePermission(
        choice: PermissionChoice,
        accuracy: LocationAccuracy = LocationAccuracy.LOCATION_ACCURACY_UNSPECIFIED,
    ): Command = Command.newBuilder().setChoosePermission(ChoosePermission.newBuilder().setChoice(choice).setAccuracy(accuracy)).build()

    fun hideKeyboard(): Command = Command.newBuilder().setHideKeyboard(HideKeyboard.getDefaultInstance()).build()

    fun setClipboard(text: String): Command = Command.newBuilder().setSetClipboard(SetClipboard.newBuilder().setText(text)).build()

    fun getClipboard(): Command = Command.newBuilder().setGetClipboard(GetClipboard.getDefaultInstance()).build()

    /** A toast whose text matches [text] under [mode] (any text when null), from [packageName] (any package when null). */
    fun awaitToast(
        text: String? = null,
        mode: MatchMode = MatchMode.MATCH_UNSPECIFIED,
        packageName: String? = null,
    ): Command =
        Command.newBuilder().setAwaitToast(
            AwaitToast.newBuilder().setMode(mode).apply {
                text?.let { setText(it) }
                packageName?.let { setPackageName(it) }
            },
        ).build()

    fun dumpHierarchy(): Command = Command.newBuilder().setDumpHierarchy(DumpHierarchy.getDefaultInstance()).build()

    fun exists(selector: Selector): Command = Command.newBuilder().setExists(Exists.newBuilder().setSelector(selector)).build()

    fun count(selector: Selector): Command = Command.newBuilder().setCount(Count.newBuilder().setSelector(selector)).build()

    fun snapshot(selector: Selector): Command = Command.newBuilder().setSnapshot(Snapshot.newBuilder().setSelector(selector)).build()

    fun waitVisible(selector: Selector): Command =
        Command.newBuilder().setWaitVisible(WaitVisible.newBuilder().setSelector(selector)).build()

    fun waitGone(selector: Selector): Command = Command.newBuilder().setWaitGone(WaitGone.newBuilder().setSelector(selector)).build()

    fun waitAppVisible(packageName: String): Command =
        Command.newBuilder().setWaitAppVisible(WaitAppVisible.newBuilder().setPackageName(packageName)).build()

    fun waitScreenStable(
        packageName: String,
        stableForMs: Long? = null,
        signal: StabilitySignal? = null,
    ): Command =
        Command.newBuilder().setWaitScreenStable(
            WaitScreenStable.newBuilder().setPackageName(packageName).apply {
                stableForMs?.let { setStableForMs(it) }
                signal?.let { setSignal(it) }
            },
        ).build()

    fun tap(selector: Selector): Command = Command.newBuilder().setTap(Tap.newBuilder().setSelector(selector)).build()

    fun longTap(selector: Selector): Command = Command.newBuilder().setLongTap(LongTap.newBuilder().setSelector(selector)).build()

    fun setText(
        selector: Selector,
        text: String,
    ): Command = Command.newBuilder().setSetText(SetText.newBuilder().setSelector(selector).setText(text)).build()

    /** Key events for [text] into whatever has input focus now; tap the field first. */
    fun typeText(text: String): Command = Command.newBuilder().setTypeText(TypeText.newBuilder().setText(text)).build()

    fun clearText(selector: Selector): Command = Command.newBuilder().setClearText(ClearText.newBuilder().setSelector(selector)).build()

    fun swipe(
        selector: Selector,
        direction: Direction,
        distancePercent: Int? = null,
    ): Command =
        Command.newBuilder().setSwipe(
            Swipe.newBuilder().setSelector(selector).setDirection(direction).apply {
                distancePercent?.let { setDistancePercent(it) }
            },
        ).build()

    fun scroll(
        selector: Selector,
        direction: Direction,
        distancePercent: Int? = null,
    ): Command =
        Command.newBuilder().setScroll(
            Scroll.newBuilder().setSelector(selector).setDirection(direction).apply {
                distancePercent?.let { setDistancePercent(it) }
            },
        ).build()

    fun performImeAction(selector: Selector): Command =
        Command.newBuilder().setPerformImeAction(PerformImeAction.newBuilder().setSelector(selector)).build()

    fun performAccessibilityAction(
        selector: Selector,
        action: StandardAction,
    ): Command =
        Command.newBuilder().setPerformAccessibilityAction(PerformAccessibilityAction.newBuilder().setSelector(selector).setStandard(action)).build()

    fun performCustomAction(
        selector: Selector,
        label: String,
    ): Command =
        Command.newBuilder().setPerformAccessibilityAction(PerformAccessibilityAction.newBuilder().setSelector(selector).setCustom(label)).build()

    fun setProgress(
        selector: Selector,
        value: Float,
    ): Command = Command.newBuilder().setSetProgress(SetProgress.newBuilder().setSelector(selector).setValue(value)).build()

    fun doubleTap(selector: Selector): Command = Command.newBuilder().setDoubleTap(DoubleTap.newBuilder().setSelector(selector)).build()

    fun drag(
        selector: Selector,
        target: Selector,
    ): Command = Command.newBuilder().setDrag(Drag.newBuilder().setSelector(selector).setTarget(target)).build()

    fun pinch(
        selector: Selector,
        direction: PinchDirection,
        percent: Int? = null,
    ): Command =
        Command.newBuilder().setPinch(
            Pinch.newBuilder().setSelector(selector).setDirection(direction).apply {
                percent?.let { setPercent(it) }
            },
        ).build()

    fun fling(
        selector: Selector,
        direction: Direction,
    ): Command = Command.newBuilder().setFling(Fling.newBuilder().setSelector(selector).setDirection(direction)).build()
}

/**
 * Request bodies. The session envelope (`session_id`, `generation`, `timeout_ms`) is left unset:
 * the host transport stamps it with [withEnvelope] when it transmits.
 */
object Requests {
    fun of(command: Command): Request = Request.newBuilder().setCommand(command).build()

    fun health(): Request = Request.newBuilder().setHealth(Health.getDefaultInstance()).build()

    fun screenshot(): Request = Request.newBuilder().setScreenshot(CaptureScreenshot.getDefaultInstance()).build()

    fun syncBootstrap(
        observedPid: Int,
        observedStartToken: String,
        packageName: String,
        authority: String,
    ): Request =
        Request.newBuilder().setSyncBootstrap(
            SyncBootstrap.newBuilder()
                .setObservedPid(observedPid)
                .setObservedStartToken(observedStartToken)
                .setPackageName(packageName)
                .setAuthority(authority),
        ).build()

    fun syncPoll(
        observedPid: Int,
        observedStartToken: String,
        expectedProcessStartUuid: String,
        expectedSessionIdentity: String,
        packageName: String,
        authority: String,
    ): Request =
        Request.newBuilder().setSyncPoll(
            SyncPoll.newBuilder()
                .setObservedPid(observedPid)
                .setObservedStartToken(observedStartToken)
                .setExpectedProcessStartUuid(expectedProcessStartUuid)
                .setExpectedSessionIdentity(expectedSessionIdentity)
                .setPackageName(packageName)
                .setAuthority(authority),
        ).build()
}

fun Command.toRequest(): Request = Requests.of(this)

/** This request body inside the session envelope, ready to encode. */
fun Request.withEnvelope(
    sessionId: String,
    generation: Long,
    timeoutMs: Long,
): Request = toBuilder().setSessionId(sessionId).setGeneration(generation).setTimeoutMs(timeoutMs).build()

// Results -----------------------------------------------------------------------------------------

val CommandResult.ok: Boolean get() = outcomeCase != CommandResult.OutcomeCase.ERROR && outcomeCase != CommandResult.OutcomeCase.OUTCOME_NOT_SET

/** The error, or null for a successful outcome. */
val CommandResult.errorOrNull: CommandError? get() = if (outcomeCase == CommandResult.OutcomeCase.ERROR) error else null

val Response.ok: Boolean get() = hasResult() && result.ok
val Response.errorOrNull: CommandError? get() = result.errorOrNull

/** The error code as this build understands it; null on success. */
val Response.errorCode: ErrorCode? get() = errorOrNull?.code?.normalized()
val Response.detail: String? get() = errorOrNull?.takeIf { it.hasDetail() }?.detail
val Response.message: String? get() = errorOrNull?.takeIf { it.hasMessage() }?.message

object Responses {
    fun of(
        result: CommandResult.Builder,
        durationMs: Long,
    ): Response = Response.newBuilder().setResult(result.setDurationMs(durationMs)).build()

    fun done(durationMs: Long = 0): Response = of(CommandResult.newBuilder().setDone(Done.getDefaultInstance()), durationMs)

    fun failure(
        code: ErrorCode,
        durationMs: Long = 0,
        detail: String? = null,
        message: String? = null,
        matchCount: Int? = null,
    ): Response {
        require(code != ErrorCode.UNRECOGNIZED && code != ErrorCode.ERR_UNSPECIFIED && code != ErrorCode.ERR_UNKNOWN) {
            "$code is a decode fallback and is never sent"
        }
        val error =
            CommandError.newBuilder().setCode(code).apply {
                detail?.let { setDetail(it) }
                message?.let { setMessage(it) }
                matchCount?.let { setMatchCount(it) }
            }
        return of(CommandResult.newBuilder().setError(error), durationMs)
    }
}

/** This response stamped with the driver's timing and the request identity it answers. */
fun Response.stamped(
    durationMs: Long,
    requestId: Long,
    generation: Long,
): Response =
    toBuilder().setResult(
        result.toBuilder().setDurationMs(durationMs).setRequestId(requestId).setSessionGeneration(generation),
    ).build()

// Dispatch ----------------------------------------------------------------------------------------

/**
 * One method per operation with that operation's own result type. The driver implements it;
 * [dispatch] is exhaustive over the schema's cases, so adding an operation without a handler
 * does not compile. A handler ends a command with an error by throwing [CommandFailure].
 */
interface CommandHandler {
    fun health()

    fun screenshot(command: CaptureScreenshot): ArtifactInfo

    fun syncBootstrap(command: SyncBootstrap): SyncState

    fun syncPoll(command: SyncPoll): SyncState

    fun deviceInfo(command: DeviceInfoQuery): DeviceInfo

    fun pressKey(command: PressKey)

    fun dumpHierarchy(command: DumpHierarchy): String

    fun exists(command: Exists): Boolean

    fun count(command: Count): Int

    fun snapshot(command: Snapshot): ElementSnapshot

    fun waitVisible(command: WaitVisible)

    fun waitGone(command: WaitGone)

    fun waitAppVisible(command: WaitAppVisible)

    fun waitScreenStable(command: WaitScreenStable)

    fun tap(command: Tap)

    fun longTap(command: LongTap)

    fun setText(command: SetText)

    fun typeText(command: TypeText)

    fun clearText(command: ClearText)

    fun swipe(command: Swipe)

    fun scroll(command: Scroll)

    fun openSystemPanel(command: OpenSystemPanel)

    fun doubleTap(command: DoubleTap)

    fun drag(command: Drag)

    fun pinch(command: Pinch)

    fun fling(command: Fling)

    fun dismissKeyguard(command: DismissKeyguard)

    fun waitPermissionPrompt(command: WaitPermissionPrompt): PermissionPrompt

    fun choosePermission(command: ChoosePermission)

    fun hideKeyboard(command: HideKeyboard)

    fun performImeAction(command: PerformImeAction)

    fun performAccessibilityAction(command: PerformAccessibilityAction)

    fun setProgress(command: SetProgress)

    fun setClipboard(command: SetClipboard)

    fun getClipboard(command: GetClipboard): String

    fun awaitToast(command: AwaitToast): Toast

    fun setOrientation(command: SetOrientation)

    fun setDisplayRotation(command: SetDisplayRotation)

    fun unfreezeRotation(command: UnfreezeRotation)
}

/**
 * Runs this request's operation on [handler] and wraps what it returned. Duration, request id
 * and generation are left for [stamped].
 *
 * @throws CommandFailure from the handler, or `UNSUPPORTED` when no operation this build knows is set.
 */
fun Request.dispatch(handler: CommandHandler): Response {
    val done = CommandResult.newBuilder().setDone(Done.getDefaultInstance())
    val response = Response.newBuilder()
    when (bodyCase) {
        BodyCase.HEALTH -> {
            handler.health()
            response.setResult(done)
        }

        BodyCase.SCREENSHOT -> {
            response.setResult(done).setArtifact(handler.screenshot(screenshot))
        }

        BodyCase.SYNC_BOOTSTRAP -> {
            response.setResult(done).setSync(handler.syncBootstrap(syncBootstrap))
        }

        BodyCase.SYNC_POLL -> {
            response.setResult(done).setSync(handler.syncPoll(syncPoll))
        }

        BodyCase.COMMAND -> {
            response.setResult(command.dispatch(handler))
        }

        BodyCase.BODY_NOT_SET, null -> {
            throw CommandFailure(ErrorCode.ERR_UNSUPPORTED, message = "No operation this driver knows is set")
        }
    }
    return response.build()
}

private fun Command.dispatch(handler: CommandHandler): CommandResult.Builder {
    val result = CommandResult.newBuilder()
    val done = Done.getDefaultInstance()
    when (opCase) {
        OpCase.DEVICE_INFO -> result.setDeviceInfo(handler.deviceInfo(deviceInfo))
        OpCase.PRESS_KEY -> result.setDone(done).also { handler.pressKey(pressKey) }
        OpCase.DUMP_HIERARCHY -> result.setText(handler.dumpHierarchy(dumpHierarchy))
        OpCase.EXISTS -> result.setBool(handler.exists(exists))
        OpCase.COUNT -> result.setCount(handler.count(count))
        OpCase.SNAPSHOT -> result.setSnapshot(handler.snapshot(snapshot))
        OpCase.WAIT_VISIBLE -> result.setDone(done).also { handler.waitVisible(waitVisible) }
        OpCase.WAIT_GONE -> result.setDone(done).also { handler.waitGone(waitGone) }
        OpCase.WAIT_APP_VISIBLE -> result.setDone(done).also { handler.waitAppVisible(waitAppVisible) }
        OpCase.WAIT_SCREEN_STABLE -> result.setDone(done).also { handler.waitScreenStable(waitScreenStable) }
        OpCase.TAP -> result.setDone(done).also { handler.tap(tap) }
        OpCase.LONG_TAP -> result.setDone(done).also { handler.longTap(longTap) }
        OpCase.SET_TEXT -> result.setDone(done).also { handler.setText(setText) }
        OpCase.TYPE_TEXT -> result.setDone(done).also { handler.typeText(typeText) }
        OpCase.CLEAR_TEXT -> result.setDone(done).also { handler.clearText(clearText) }
        OpCase.SWIPE -> result.setDone(done).also { handler.swipe(swipe) }
        OpCase.SCROLL -> result.setDone(done).also { handler.scroll(scroll) }
        OpCase.OPEN_SYSTEM_PANEL -> result.setDone(done).also { handler.openSystemPanel(openSystemPanel) }
        OpCase.SET_ORIENTATION -> result.setDone(done).also { handler.setOrientation(setOrientation) }
        OpCase.SET_DISPLAY_ROTATION -> result.setDone(done).also { handler.setDisplayRotation(setDisplayRotation) }
        OpCase.UNFREEZE_ROTATION -> result.setDone(done).also { handler.unfreezeRotation(unfreezeRotation) }
        OpCase.DISMISS_KEYGUARD -> result.setDone(done).also { handler.dismissKeyguard(dismissKeyguard) }
        OpCase.DOUBLE_TAP -> result.setDone(done).also { handler.doubleTap(doubleTap) }
        OpCase.DRAG -> result.setDone(done).also { handler.drag(drag) }
        OpCase.PINCH -> result.setDone(done).also { handler.pinch(pinch) }
        OpCase.FLING -> result.setDone(done).also { handler.fling(fling) }
        OpCase.WAIT_PERMISSION_PROMPT -> result.setPermissionPrompt(handler.waitPermissionPrompt(waitPermissionPrompt))
        OpCase.CHOOSE_PERMISSION -> result.setDone(done).also { handler.choosePermission(choosePermission) }
        OpCase.HIDE_KEYBOARD -> result.setDone(done).also { handler.hideKeyboard(hideKeyboard) }
        OpCase.PERFORM_IME_ACTION -> result.setDone(done).also { handler.performImeAction(performImeAction) }
        OpCase.PERFORM_ACCESSIBILITY_ACTION -> result.setDone(done).also { handler.performAccessibilityAction(performAccessibilityAction) }
        OpCase.SET_PROGRESS -> result.setDone(done).also { handler.setProgress(setProgress) }
        OpCase.SET_CLIPBOARD -> result.setDone(done).also { handler.setClipboard(setClipboard) }
        OpCase.GET_CLIPBOARD -> result.setText(handler.getClipboard(getClipboard))
        OpCase.AWAIT_TOAST -> result.setToast(handler.awaitToast(awaitToast))
        OpCase.OP_NOT_SET, null -> throw CommandFailure(ErrorCode.ERR_UNSUPPORTED, message = "No command op this driver knows is set")
    }
    return result
}
