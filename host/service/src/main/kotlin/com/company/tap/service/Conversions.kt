package com.company.tap.service

import com.company.tap.api.v1.ArtifactInfo as ProtoArtifactInfo
import com.company.tap.api.v1.Bounds as ProtoBounds
import com.company.tap.api.v1.ClearText as ProtoClearText
import com.company.tap.api.v1.Command as ProtoCommand
import com.company.tap.api.v1.CommandResult as ProtoCommandResult
import com.company.tap.api.v1.Count as ProtoCount
import com.company.tap.api.v1.DeviceInfo as ProtoDeviceInfo
import com.company.tap.api.v1.DeviceInfoQuery as ProtoDeviceInfoQuery
import com.company.tap.api.v1.Direction as ProtoDirection
import com.company.tap.api.v1.Done as ProtoDone
import com.company.tap.api.v1.DumpHierarchy as ProtoDumpHierarchy
import com.company.tap.api.v1.ElementSnapshot as ProtoElementSnapshot
import com.company.tap.api.v1.Error as ProtoError
import com.company.tap.api.v1.ErrorCode as ProtoErrorCode
import com.company.tap.api.v1.Exists as ProtoExists
import com.company.tap.api.v1.Health as ProtoHealth
import com.company.tap.api.v1.LongTap as ProtoLongTap
import com.company.tap.api.v1.MatchLimit as ProtoMatchLimit
import com.company.tap.api.v1.MatchMode as ProtoMatchMode
import com.company.tap.api.v1.NodeSelector as ProtoNodeSelector
import com.company.tap.api.v1.PressKey as ProtoPressKey
import com.company.tap.api.v1.ResourceId as ProtoResourceId
import com.company.tap.api.v1.Screenshot as ProtoScreenshot
import com.company.tap.api.v1.Scroll as ProtoScroll
import com.company.tap.api.v1.ScrollUntil as ProtoScrollUntil
import com.company.tap.api.v1.Selector as ProtoSelector
import com.company.tap.api.v1.SetText as ProtoSetText
import com.company.tap.api.v1.Snapshot as ProtoSnapshot
import com.company.tap.api.v1.StabilitySignal as ProtoStabilitySignal
import com.company.tap.api.v1.StringMatch as ProtoStringMatch
import com.company.tap.api.v1.Swipe as ProtoSwipe
import com.company.tap.api.v1.SyncBootstrap as ProtoSyncBootstrap
import com.company.tap.api.v1.SyncPoll as ProtoSyncPoll
import com.company.tap.api.v1.SyncState as ProtoSyncState
import com.company.tap.api.v1.Tap as ProtoTap
import com.company.tap.api.v1.TargetScope as ProtoTargetScope
import com.company.tap.api.v1.TypeText as ProtoTypeText
import com.company.tap.api.v1.WaitAppVisible as ProtoWaitAppVisible
import com.company.tap.api.v1.WaitGone as ProtoWaitGone
import com.company.tap.api.v1.WaitScreenStable as ProtoWaitScreenStable
import com.company.tap.api.v1.WaitVisible as ProtoWaitVisible
import com.company.tap.protocol.ArtifactInfo
import com.company.tap.protocol.ArtifactResult
import com.company.tap.protocol.BoolResult
import com.company.tap.protocol.Bounds
import com.company.tap.protocol.ClearText
import com.company.tap.protocol.Command
import com.company.tap.protocol.CommandResult
import com.company.tap.protocol.Count
import com.company.tap.protocol.CountResult
import com.company.tap.protocol.DEFAULT_GESTURE_PERCENT
import com.company.tap.protocol.DEFAULT_STABLE_FOR_MS
import com.company.tap.protocol.DeviceInfo
import com.company.tap.protocol.DeviceInfoQuery
import com.company.tap.protocol.DeviceInfoResult
import com.company.tap.protocol.Direction
import com.company.tap.protocol.Done
import com.company.tap.protocol.DumpHierarchy
import com.company.tap.protocol.ElementSnapshot
import com.company.tap.protocol.ErrorCode
import com.company.tap.protocol.Exists
import com.company.tap.protocol.Health
import com.company.tap.protocol.LongTap
import com.company.tap.protocol.MatchLimit
import com.company.tap.protocol.MatchMode
import com.company.tap.protocol.Moved
import com.company.tap.protocol.NodeSelector
import com.company.tap.protocol.PressKey
import com.company.tap.protocol.ResourceId
import com.company.tap.protocol.Response
import com.company.tap.protocol.Screenshot
import com.company.tap.protocol.Scroll
import com.company.tap.protocol.ScrollUntil
import com.company.tap.protocol.Selector
import com.company.tap.protocol.SetText
import com.company.tap.protocol.Snapshot
import com.company.tap.protocol.SnapshotResult
import com.company.tap.protocol.StabilitySignal
import com.company.tap.protocol.StringMatch
import com.company.tap.protocol.Swipe
import com.company.tap.protocol.SyncBootstrap
import com.company.tap.protocol.SyncPoll
import com.company.tap.protocol.SyncResult
import com.company.tap.protocol.SyncState
import com.company.tap.protocol.Tap
import com.company.tap.protocol.TargetScope
import com.company.tap.protocol.TextResult
import com.company.tap.protocol.TypeText
import com.company.tap.protocol.WaitAppVisible
import com.company.tap.protocol.WaitGone
import com.company.tap.protocol.WaitScreenStable
import com.company.tap.protocol.WaitVisible

/**
 * Proto ↔ protocol conversion. Every enum maps by name (proto prefix stripped), so a value
 * added to one side without the other fails `EnumMirrorTest` rather than mapping silently;
 * every `Command.op` / `CommandResult.outcome` case maps to exactly one protocol class in an
 * exhaustive `when`, so a case added to one side without the other does not compile.
 * `GoldenRoundTripTest` proves every golden request/response survives a round trip unchanged.
 */
object Conversions {
    private const val ERR = "ERR_"
    private const val DIR = "DIR_"
    private const val MATCH = "MATCH_"
    private const val LIMIT = "LIMIT_"
    private const val SCOPE = "SCOPE_"
    private const val STABILITY = "STABILITY_"

    // ---- enums -------------------------------------------------------------------------------

    fun errorCode(proto: ProtoErrorCode): ErrorCode = ErrorCode.valueOf(named(proto.name, ERR, "errorCode"))
    fun errorCode(value: ErrorCode): ProtoErrorCode = ProtoErrorCode.valueOf(ERR + value.name)

    fun direction(proto: ProtoDirection): Direction = Direction.valueOf(named(proto.name, DIR, "direction"))
    fun direction(value: Direction): ProtoDirection = ProtoDirection.valueOf(DIR + value.name)

    fun stabilitySignal(proto: ProtoStabilitySignal): StabilitySignal =
        if (proto == ProtoStabilitySignal.STABILITY_UNSPECIFIED) StabilitySignal.ALL
        else StabilitySignal.valueOf(named(proto.name, STABILITY, "signal"))
    fun stabilitySignal(value: StabilitySignal): ProtoStabilitySignal = ProtoStabilitySignal.valueOf(STABILITY + value.name)

    private fun matchMode(proto: ProtoMatchMode): MatchMode =
        if (proto == ProtoMatchMode.MATCH_UNSPECIFIED) MatchMode.EXACT else MatchMode.valueOf(named(proto.name, MATCH, "mode"))

    private fun matchMode(value: MatchMode): ProtoMatchMode = ProtoMatchMode.valueOf(MATCH + value.name)

    private fun matchLimit(proto: ProtoMatchLimit): MatchLimit =
        if (proto == ProtoMatchLimit.LIMIT_UNSPECIFIED) MatchLimit.EXACTLY_ONE else MatchLimit.valueOf(named(proto.name, LIMIT, "limit"))

    private fun matchLimit(value: MatchLimit): ProtoMatchLimit = ProtoMatchLimit.valueOf(LIMIT + value.name)

    private fun scope(proto: ProtoTargetScope): TargetScope =
        if (proto == ProtoTargetScope.SCOPE_UNSPECIFIED) TargetScope.AUT else TargetScope.valueOf(named(proto.name, SCOPE, "scope"))

    private fun scope(value: TargetScope): ProtoTargetScope = ProtoTargetScope.valueOf(SCOPE + value.name)

    private fun named(protoName: String, prefix: String, field: String): String {
        require(protoName.startsWith(prefix) && !protoName.endsWith("UNSPECIFIED") && protoName != "UNRECOGNIZED") {
            "$field must be set to a known value (got $protoName)"
        }
        return protoName.removePrefix(prefix)
    }

    // ---- selectors ---------------------------------------------------------------------------

    /**
     * [autPackage] fills in `ResourceId.aut_package` resources; null (no session, as in the golden
     * round trip) rejects them.
     */
    fun selector(proto: ProtoSelector, autPackage: String? = null): Selector = Selector(
        node = node(proto.node, autPackage),
        scope = scope(proto.scope),
        scopePackage = if (proto.hasScopePackage()) proto.scopePackage else null,
        limit = matchLimit(proto.limit),
        index = if (proto.hasIndex()) proto.index else null,
        acceptAccessibilityOrder = proto.acceptAccessibilityOrder,
    )

    fun selector(value: Selector): ProtoSelector = ProtoSelector.newBuilder().apply {
        node = node(value.node)
        scope = scope(value.scope)
        value.scopePackage?.let { scopePackage = it }
        limit = matchLimit(value.limit)
        value.index?.let { index = it }
        acceptAccessibilityOrder = value.acceptAccessibilityOrder
    }.build()

    private fun node(proto: ProtoNodeSelector, autPackage: String?): NodeSelector = NodeSelector(
        text = proto.takeIf { it.hasText() }?.text?.let(::stringMatch),
        contentDescription = proto.takeIf { it.hasContentDescription() }?.contentDescription?.let(::stringMatch),
        hint = proto.takeIf { it.hasHint() }?.hint?.let(::stringMatch),
        className = proto.takeIf { it.hasClassName() }?.className?.let(::stringMatch),
        resource = proto.takeIf { it.hasResource() }?.resource?.let { resource(it, autPackage) },
        enabled = proto.takeIf { it.hasEnabled() }?.enabled,
        checked = proto.takeIf { it.hasChecked() }?.checked,
        checkable = proto.takeIf { it.hasCheckable() }?.checkable,
        clickable = proto.takeIf { it.hasClickable() }?.clickable,
        focused = proto.takeIf { it.hasFocused() }?.focused,
        focusable = proto.takeIf { it.hasFocusable() }?.focusable,
        longClickable = proto.takeIf { it.hasLongClickable() }?.longClickable,
        scrollable = proto.takeIf { it.hasScrollable() }?.scrollable,
        selected = proto.takeIf { it.hasSelected() }?.selected,
        parent = proto.takeIf { it.hasParent() }?.parent?.let { node(it, autPackage) },
        ancestor = proto.takeIf { it.hasAncestor() }?.ancestor?.let { node(it, autPackage) },
        child = proto.takeIf { it.hasChild() }?.child?.let { node(it, autPackage) },
        descendant = proto.takeIf { it.hasDescendant() }?.descendant?.let { node(it, autPackage) },
    )

    private fun node(value: NodeSelector): ProtoNodeSelector = ProtoNodeSelector.newBuilder().apply {
        value.text?.let { text = stringMatch(it) }
        value.contentDescription?.let { contentDescription = stringMatch(it) }
        value.hint?.let { hint = stringMatch(it) }
        value.className?.let { className = stringMatch(it) }
        value.resource?.let { resource = resource(it) }
        value.enabled?.let { enabled = it }
        value.checked?.let { checked = it }
        value.checkable?.let { checkable = it }
        value.clickable?.let { clickable = it }
        value.focused?.let { focused = it }
        value.focusable?.let { focusable = it }
        value.longClickable?.let { longClickable = it }
        value.scrollable?.let { scrollable = it }
        value.selected?.let { selected = it }
        value.parent?.let { parent = node(it) }
        value.ancestor?.let { ancestor = node(it) }
        value.child?.let { child = node(it) }
        value.descendant?.let { descendant = node(it) }
    }.build()

    private fun stringMatch(proto: ProtoStringMatch) = StringMatch(proto.value, matchMode(proto.mode))
    private fun stringMatch(value: StringMatch): ProtoStringMatch =
        ProtoStringMatch.newBuilder().setValue(value.value).setMode(matchMode(value.mode)).build()

    private fun resource(proto: ProtoResourceId, autPackage: String?): ResourceId {
        val explicit = if (proto.hasPackageName()) proto.packageName else null
        if (!proto.autPackage) return ResourceId(proto.name, explicit)
        require(explicit == null) { "ResourceId '${proto.name}': package_name and aut_package are mutually exclusive" }
        requireNotNull(autPackage) { "ResourceId '${proto.name}': aut_package needs a session" }
        return ResourceId(proto.name, autPackage)
    }
    private fun resource(value: ResourceId): ProtoResourceId = ProtoResourceId.newBuilder().apply {
        name = value.name
        value.packageName?.let { packageName = it }
    }.build()

    // ---- commands ----------------------------------------------------------------------------

    /** A client's command plus the timeout it asked for (0 = the session default). */
    data class TimedCommand(val command: Command, val timeoutMs: Long)

    /**
     * Proto → protocol. [autPackage] fills in `aut_package` resources; null (no session, as in
     * the golden round trip) rejects them. Range violations surface as [IllegalArgumentException]
     * from the command's own constructor, which the servicer maps to `INVALID_ARGUMENT`.
     */
    fun command(proto: ProtoCommand, defaultTimeoutMs: Long, autPackage: String? = null): TimedCommand {
        fun sel(value: ProtoSelector) = selector(value, autPackage)
        val command: Command = when (proto.opCase) {
            ProtoCommand.OpCase.HEALTH -> Health
            ProtoCommand.OpCase.DEVICE_INFO -> DeviceInfoQuery
            ProtoCommand.OpCase.PRESS_KEY -> PressKey(proto.pressKey.keyCode)
            ProtoCommand.OpCase.SCREENSHOT -> Screenshot
            ProtoCommand.OpCase.DUMP_HIERARCHY -> DumpHierarchy
            ProtoCommand.OpCase.EXISTS -> Exists(sel(proto.exists.selector))
            ProtoCommand.OpCase.COUNT -> Count(sel(proto.count.selector))
            ProtoCommand.OpCase.SNAPSHOT -> Snapshot(sel(proto.snapshot.selector))
            ProtoCommand.OpCase.WAIT_VISIBLE -> WaitVisible(sel(proto.waitVisible.selector))
            ProtoCommand.OpCase.WAIT_GONE -> WaitGone(sel(proto.waitGone.selector))
            ProtoCommand.OpCase.WAIT_APP_VISIBLE -> WaitAppVisible(proto.waitAppVisible.packageName)
            ProtoCommand.OpCase.WAIT_SCREEN_STABLE -> proto.waitScreenStable.let {
                WaitScreenStable(
                    packageName = it.packageName,
                    stableForMs = if (it.hasStableForMs()) it.stableForMs else DEFAULT_STABLE_FOR_MS,
                    signal = stabilitySignal(it.signal),
                )
            }
            ProtoCommand.OpCase.TAP -> Tap(sel(proto.tap.selector))
            ProtoCommand.OpCase.LONG_TAP -> LongTap(sel(proto.longTap.selector))
            ProtoCommand.OpCase.SET_TEXT -> SetText(sel(proto.setText.selector), proto.setText.text)
            ProtoCommand.OpCase.TYPE_TEXT -> TypeText(sel(proto.typeText.selector), proto.typeText.text)
            ProtoCommand.OpCase.CLEAR_TEXT -> ClearText(sel(proto.clearText.selector))
            ProtoCommand.OpCase.SWIPE -> proto.swipe.let {
                Swipe(sel(it.selector), direction(it.direction), if (it.hasDistancePercent()) it.distancePercent else DEFAULT_GESTURE_PERCENT)
            }
            ProtoCommand.OpCase.SCROLL -> proto.scroll.let {
                Scroll(sel(it.selector), direction(it.direction), if (it.hasDistancePercent()) it.distancePercent else DEFAULT_GESTURE_PERCENT)
            }
            ProtoCommand.OpCase.SCROLL_UNTIL -> proto.scrollUntil.let {
                ScrollUntil(
                    selector = sel(it.selector),
                    container = sel(it.container),
                    direction = if (it.direction == ProtoDirection.DIR_UNSPECIFIED) Direction.DOWN else direction(it.direction),
                    distancePercent = if (it.hasDistancePercent()) it.distancePercent else DEFAULT_GESTURE_PERCENT,
                    maxScrolls = if (it.hasMaxScrolls()) it.maxScrolls else 20,
                )
            }
            ProtoCommand.OpCase.SYNC_BOOTSTRAP -> SyncBootstrap(proto.syncBootstrap.observedPid, proto.syncBootstrap.observedStartToken)
            ProtoCommand.OpCase.SYNC_POLL -> proto.syncPoll.let {
                SyncPoll(it.observedPid, it.observedStartToken, it.expectedProcessStartUuid, it.expectedSessionIdentity)
            }
            ProtoCommand.OpCase.OP_NOT_SET, null -> throw IllegalArgumentException("Command.op must be set")
        }
        return TimedCommand(command, if (proto.timeoutMs > 0) proto.timeoutMs else defaultTimeoutMs)
    }

    /** Protocol → proto (the golden round-trip test and clients). */
    fun command(value: Command, timeoutMs: Long): ProtoCommand = ProtoCommand.newBuilder().apply {
        this.timeoutMs = timeoutMs
        when (value) {
            is Health -> health = ProtoHealth.getDefaultInstance()
            is DeviceInfoQuery -> deviceInfo = ProtoDeviceInfoQuery.getDefaultInstance()
            is PressKey -> pressKey = ProtoPressKey.newBuilder().setKeyCode(value.keyCode).build()
            is Screenshot -> screenshot = ProtoScreenshot.getDefaultInstance()
            is DumpHierarchy -> dumpHierarchy = ProtoDumpHierarchy.getDefaultInstance()
            is Exists -> exists = ProtoExists.newBuilder().setSelector(selector(value.selector)).build()
            is Count -> count = ProtoCount.newBuilder().setSelector(selector(value.selector)).build()
            is Snapshot -> snapshot = ProtoSnapshot.newBuilder().setSelector(selector(value.selector)).build()
            is WaitVisible -> waitVisible = ProtoWaitVisible.newBuilder().setSelector(selector(value.selector)).build()
            is WaitGone -> waitGone = ProtoWaitGone.newBuilder().setSelector(selector(value.selector)).build()
            is WaitAppVisible -> waitAppVisible = ProtoWaitAppVisible.newBuilder().setPackageName(value.packageName).build()
            is WaitScreenStable -> waitScreenStable = ProtoWaitScreenStable.newBuilder()
                .setPackageName(value.packageName)
                .setStableForMs(value.stableForMs)
                .setSignal(stabilitySignal(value.signal))
                .build()
            is Tap -> tap = ProtoTap.newBuilder().setSelector(selector(value.selector)).build()
            is LongTap -> longTap = ProtoLongTap.newBuilder().setSelector(selector(value.selector)).build()
            is SetText -> setText = ProtoSetText.newBuilder().setSelector(selector(value.selector)).setText(value.text).build()
            is TypeText -> typeText = ProtoTypeText.newBuilder().setSelector(selector(value.selector)).setText(value.text).build()
            is ClearText -> clearText = ProtoClearText.newBuilder().setSelector(selector(value.selector)).build()
            is Swipe -> swipe = ProtoSwipe.newBuilder()
                .setSelector(selector(value.selector)).setDirection(direction(value.direction)).setDistancePercent(value.distancePercent).build()
            is Scroll -> scroll = ProtoScroll.newBuilder()
                .setSelector(selector(value.selector)).setDirection(direction(value.direction)).setDistancePercent(value.distancePercent).build()
            is ScrollUntil -> scrollUntil = ProtoScrollUntil.newBuilder()
                .setSelector(selector(value.selector))
                .setContainer(selector(value.container))
                .setDirection(direction(value.direction))
                .setDistancePercent(value.distancePercent)
                .setMaxScrolls(value.maxScrolls)
                .build()
            is SyncBootstrap -> syncBootstrap = ProtoSyncBootstrap.newBuilder()
                .setObservedPid(value.observedPid).setObservedStartToken(value.observedStartToken).build()
            is SyncPoll -> syncPoll = ProtoSyncPoll.newBuilder()
                .setObservedPid(value.observedPid)
                .setObservedStartToken(value.observedStartToken)
                .setExpectedProcessStartUuid(value.expectedProcessStartUuid)
                .setExpectedSessionIdentity(value.expectedSessionIdentity)
                .build()
        }
    }.build()

    // ---- responses ---------------------------------------------------------------------------

    fun result(response: Response, requestId: Long, generation: Long): ProtoCommandResult = ProtoCommandResult.newBuilder().apply {
        durationMs = response.durationMs
        this.requestId = requestId
        sessionGeneration = generation
        when (response) {
            is Response.Error -> error = ProtoError.newBuilder().apply {
                code = errorCode(response.code)
                response.detail?.let { detail = it }
                response.message?.let { message = it }
            }.build()
            is Response.Ok -> when (val result = response.result) {
                is Done -> done = ProtoDone.getDefaultInstance()
                is BoolResult -> bool = result.value
                is Moved -> moved = result.moved
                is CountResult -> count = result.count
                is TextResult -> text = result.text
                is SnapshotResult -> snapshot = snapshot(result.snapshot)
                is DeviceInfoResult -> deviceInfo = deviceInfo(result.deviceInfo)
                is ArtifactResult -> artifact = artifact(result.artifact)
                is SyncResult -> sync = syncState(result.state)
            }
        }
    }.build()

    fun response(proto: ProtoCommandResult): Response {
        val result: CommandResult = when (proto.outcomeCase) {
            ProtoCommandResult.OutcomeCase.DONE -> Done
            ProtoCommandResult.OutcomeCase.BOOL -> BoolResult(proto.bool)
            ProtoCommandResult.OutcomeCase.MOVED -> Moved(proto.moved)
            ProtoCommandResult.OutcomeCase.COUNT -> CountResult(proto.count)
            ProtoCommandResult.OutcomeCase.TEXT -> TextResult(proto.text)
            ProtoCommandResult.OutcomeCase.SNAPSHOT -> SnapshotResult(snapshot(proto.snapshot))
            ProtoCommandResult.OutcomeCase.DEVICE_INFO -> DeviceInfoResult(deviceInfo(proto.deviceInfo))
            ProtoCommandResult.OutcomeCase.ARTIFACT -> ArtifactResult(artifact(proto.artifact))
            ProtoCommandResult.OutcomeCase.SYNC -> SyncResult(syncState(proto.sync))
            ProtoCommandResult.OutcomeCase.ERROR -> return Response.failure(
                errorCode(proto.error.code),
                detail = if (proto.error.hasDetail()) proto.error.detail else null,
                message = if (proto.error.hasMessage()) proto.error.message else null,
                durationMs = proto.durationMs,
            )
            ProtoCommandResult.OutcomeCase.OUTCOME_NOT_SET, null ->
                throw IllegalArgumentException("CommandResult.outcome must be set")
        }
        return Response.ok(result, proto.durationMs)
    }

    fun deviceInfo(value: DeviceInfo): ProtoDeviceInfo = ProtoDeviceInfo.newBuilder().apply {
        apiLevel = value.apiLevel
        manufacturer = value.manufacturer
        model = value.model
        product = value.product
        displayWidth = value.displayWidth
        displayHeight = value.displayHeight
        displayRotation = value.displayRotation
        value.currentPackage?.let { currentPackage = it }
    }.build()

    private fun deviceInfo(proto: ProtoDeviceInfo) = DeviceInfo(
        proto.apiLevel, proto.manufacturer, proto.model, proto.product,
        proto.displayWidth, proto.displayHeight, proto.displayRotation,
        if (proto.hasCurrentPackage()) proto.currentPackage else null,
    )

    fun artifact(value: ArtifactInfo): ProtoArtifactInfo = ProtoArtifactInfo.newBuilder().apply {
        blobId = value.blobId
        mediaType = value.mediaType
        byteCount = value.byteCount
        sha256 = value.sha256
        value.width?.let { width = it }
        value.height?.let { height = it }
    }.build()

    private fun artifact(proto: ProtoArtifactInfo) = ArtifactInfo(
        proto.blobId, proto.mediaType, proto.byteCount, proto.sha256,
        if (proto.hasWidth()) proto.width else null,
        if (proto.hasHeight()) proto.height else null,
    )

    private fun syncState(value: SyncState): ProtoSyncState = ProtoSyncState.newBuilder().apply {
        initialized = value.initialized
        processId = value.processId
        processStartUuid = value.processStartUuid
        sessionIdentity = value.sessionIdentity
        generation = value.generation
        busyCount = value.busyCount
        lastTransitionElapsedMs = value.lastTransitionElapsedMs
        value.error?.let { error = it }
    }.build()

    private fun syncState(proto: ProtoSyncState) = SyncState(
        proto.initialized, proto.processId, proto.processStartUuid, proto.sessionIdentity,
        proto.generation, proto.busyCount, proto.lastTransitionElapsedMs,
        if (proto.hasError()) proto.error else null,
    )

    private fun snapshot(value: ElementSnapshot): ProtoElementSnapshot = ProtoElementSnapshot.newBuilder().apply {
        value.className?.let { className = it }
        value.packageName?.let { packageName = it }
        value.resourceName?.let { resourceName = it }
        value.text?.let { text = it }
        value.contentDescription?.let { contentDescription = it }
        value.hint?.let { hint = it }
        bounds = ProtoBounds.newBuilder()
            .setLeft(value.bounds.left).setTop(value.bounds.top)
            .setRight(value.bounds.right).setBottom(value.bounds.bottom).build()
        checkable = value.checkable
        checked = value.checked
        clickable = value.clickable
        enabled = value.enabled
        focusable = value.focusable
        focused = value.focused
        longClickable = value.longClickable
        scrollable = value.scrollable
        selected = value.selected
        childCount = value.childCount
    }.build()

    private fun snapshot(proto: ProtoElementSnapshot) = ElementSnapshot(
        className = if (proto.hasClassName()) proto.className else null,
        packageName = if (proto.hasPackageName()) proto.packageName else null,
        resourceName = if (proto.hasResourceName()) proto.resourceName else null,
        text = if (proto.hasText()) proto.text else null,
        contentDescription = if (proto.hasContentDescription()) proto.contentDescription else null,
        hint = if (proto.hasHint()) proto.hint else null,
        bounds = Bounds(proto.bounds.left, proto.bounds.top, proto.bounds.right, proto.bounds.bottom),
        checkable = proto.checkable,
        checked = proto.checked,
        clickable = proto.clickable,
        enabled = proto.enabled,
        focusable = proto.focusable,
        focused = proto.focused,
        longClickable = proto.longClickable,
        scrollable = proto.scrollable,
        selected = proto.selected,
        childCount = proto.childCount,
    )
}
