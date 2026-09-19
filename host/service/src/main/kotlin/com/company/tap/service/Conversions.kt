package com.company.tap.service

import com.company.tap.api.v1.ArtifactInfo as ProtoArtifactInfo
import com.company.tap.api.v1.Bounds as ProtoBounds
import com.company.tap.api.v1.Command
import com.company.tap.api.v1.CommandResult
import com.company.tap.api.v1.DeviceInfo as ProtoDeviceInfo
import com.company.tap.api.v1.Direction as ProtoDirection
import com.company.tap.api.v1.StabilitySignal as ProtoStabilitySignal
import com.company.tap.api.v1.ElementSnapshot as ProtoElementSnapshot
import com.company.tap.api.v1.ErrorCode as ProtoErrorCode
import com.company.tap.api.v1.MatchLimit as ProtoMatchLimit
import com.company.tap.api.v1.MatchMode as ProtoMatchMode
import com.company.tap.api.v1.NodeSelector as ProtoNodeSelector
import com.company.tap.api.v1.Operation as ProtoOperation
import com.company.tap.api.v1.ResourceId as ProtoResourceId
import com.company.tap.api.v1.Selector as ProtoSelector
import com.company.tap.api.v1.StringMatch as ProtoStringMatch
import com.company.tap.api.v1.SyncState as ProtoSyncState
import com.company.tap.api.v1.TargetScope as ProtoTargetScope
import com.company.tap.protocol.ArtifactInfo
import com.company.tap.protocol.Bounds
import com.company.tap.protocol.DEFAULT_GESTURE_PERCENT
import com.company.tap.protocol.DeviceInfo
import com.company.tap.protocol.Direction
import com.company.tap.protocol.StabilitySignal
import com.company.tap.protocol.ElementSnapshot
import com.company.tap.protocol.ErrorCode
import com.company.tap.protocol.MatchLimit
import com.company.tap.protocol.MatchMode
import com.company.tap.protocol.NodeSelector
import com.company.tap.protocol.Operation
import com.company.tap.protocol.Request
import com.company.tap.protocol.ResourceId
import com.company.tap.protocol.Response
import com.company.tap.protocol.Selector
import com.company.tap.protocol.StringMatch
import com.company.tap.protocol.SyncState
import com.company.tap.protocol.TargetScope

/**
 * Proto ↔ protocol conversion. Every enum maps by name (proto prefix stripped), so a value
 * added to one side without the other fails `EnumMirrorTest` rather than mapping silently.
 * `GoldenRoundTripTest` proves every golden request/response survives a round trip unchanged.
 */
object Conversions {
    private const val OP = "OP_"
    private const val ERR = "ERR_"
    private const val DIR = "DIR_"
    private const val MATCH = "MATCH_"
    private const val LIMIT = "LIMIT_"
    private const val SCOPE = "SCOPE_"
    private const val STABILITY = "STABILITY_"

    // ---- enums -------------------------------------------------------------------------------

    fun operation(proto: ProtoOperation): Operation = Operation.valueOf(named(proto.name, OP, "operation"))
    fun operation(value: Operation): ProtoOperation = ProtoOperation.valueOf(OP + value.name)

    fun errorCode(proto: ProtoErrorCode): ErrorCode = ErrorCode.valueOf(named(proto.name, ERR, "errorCode"))
    fun errorCode(value: ErrorCode): ProtoErrorCode = ProtoErrorCode.valueOf(ERR + value.name)

    fun direction(proto: ProtoDirection): Direction = Direction.valueOf(named(proto.name, DIR, "direction"))
    fun direction(value: Direction): ProtoDirection = ProtoDirection.valueOf(DIR + value.name)

    fun stabilitySignal(proto: ProtoStabilitySignal): StabilitySignal =
        StabilitySignal.valueOf(named(proto.name, STABILITY, "stableSignal"))
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

    /** Everything of a protocol [Request] that a client controls; the session supplies the rest. */
    data class CommandArguments(
        val operation: Operation,
        val timeoutMs: Long,
        val selector: Selector?,
        val containerSelector: Selector?,
        val inputText: String?,
        val direction: Direction?,
        val distancePercent: Int,
        val maxScrolls: Int,
        val keyCode: Int?,
        val packageName: String?,
        val stableForMs: Long?,
        val stableSignal: StabilitySignal?,
        val observedPid: Int?,
        val observedStartToken: String?,
        val expectedProcessStartUuid: String?,
        val expectedSessionIdentity: String?,
    )

    fun command(proto: Command, defaultTimeoutMs: Long, autPackage: String? = null): CommandArguments = CommandArguments(
        operation = operation(proto.operation),
        timeoutMs = if (proto.timeoutMs > 0) proto.timeoutMs else defaultTimeoutMs,
        selector = proto.takeIf { it.hasSelector() }?.selector?.let { selector(it, autPackage) },
        containerSelector = proto.takeIf { it.hasContainerSelector() }?.containerSelector?.let { selector(it, autPackage) },
        inputText = proto.takeIf { it.hasInputText() }?.inputText,
        direction = proto.takeIf { it.hasDirection() }?.direction?.let(::direction),
        distancePercent = if (proto.hasDistancePercent()) proto.distancePercent else DEFAULT_GESTURE_PERCENT,
        maxScrolls = if (proto.hasMaxScrolls()) proto.maxScrolls else 20,
        keyCode = proto.takeIf { it.hasKeyCode() }?.keyCode,
        packageName = proto.takeIf { it.hasPackageName() }?.packageName,
        stableForMs = proto.takeIf { it.hasStableForMs() }?.stableForMs,
        stableSignal = proto.takeIf { it.hasStableSignal() && it.stableSignal != ProtoStabilitySignal.STABILITY_UNSPECIFIED }
            ?.stableSignal?.let(::stabilitySignal),
        observedPid = proto.takeIf { it.hasObservedPid() }?.observedPid,
        observedStartToken = proto.takeIf { it.hasObservedStartToken() }?.observedStartToken,
        expectedProcessStartUuid = proto.takeIf { it.hasExpectedProcessStartUuid() }?.expectedProcessStartUuid,
        expectedSessionIdentity = proto.takeIf { it.hasExpectedSessionIdentity() }?.expectedSessionIdentity,
    )

    /** The proto shape of a protocol request (used by the golden round-trip test and clients). */
    fun command(request: Request): Command = Command.newBuilder().apply {
        operation = operation(request.operation)
        timeoutMs = request.timeoutMs
        request.selector?.let { selector = selector(it) }
        request.containerSelector?.let { containerSelector = selector(it) }
        request.inputText?.let { inputText = it }
        request.direction?.let { direction = direction(it) }
        distancePercent = request.distancePercent
        maxScrolls = request.maxScrolls
        request.keyCode?.let { keyCode = it }
        request.packageName?.let { packageName = it }
        request.stableForMs?.let { stableForMs = it }
        request.stableSignal?.let { stableSignal = stabilitySignal(it) }
        request.observedPid?.let { observedPid = it }
        request.observedStartToken?.let { observedStartToken = it }
        request.expectedProcessStartUuid?.let { expectedProcessStartUuid = it }
        request.expectedSessionIdentity?.let { expectedSessionIdentity = it }
    }.build()

    fun request(arguments: CommandArguments, sessionId: String, generation: Long): Request = Request(
        sessionId = sessionId,
        sessionGeneration = generation,
        operation = arguments.operation,
        timeoutMs = arguments.timeoutMs,
        selector = arguments.selector,
        containerSelector = arguments.containerSelector,
        inputText = arguments.inputText,
        direction = arguments.direction,
        distancePercent = arguments.distancePercent,
        maxScrolls = arguments.maxScrolls,
        keyCode = arguments.keyCode,
        packageName = arguments.packageName,
        stableForMs = arguments.stableForMs,
        stableSignal = arguments.stableSignal,
        observedPid = arguments.observedPid,
        observedStartToken = arguments.observedStartToken,
        expectedProcessStartUuid = arguments.expectedProcessStartUuid,
        expectedSessionIdentity = arguments.expectedSessionIdentity,
    )

    // ---- responses ---------------------------------------------------------------------------

    fun result(response: Response, requestId: Long, generation: Long): CommandResult = CommandResult.newBuilder().apply {
        ok = response.ok
        response.value?.let { value = it }
        response.text?.let { text = it }
        response.errorCode?.let { errorCode = errorCode(it) }
        response.detail?.let { detail = it }
        response.message?.let { message = it }
        durationMs = response.durationMs
        response.syncState?.let { syncState = syncState(it) }
        response.artifact?.let { artifact = artifact(it) }
        response.count?.let { count = it }
        response.snapshot?.let { snapshot = snapshot(it) }
        response.deviceInfo?.let { deviceInfo = deviceInfo(it) }
        this.requestId = requestId
        sessionGeneration = generation
    }.build()

    fun response(proto: CommandResult): Response = Response(
        ok = proto.ok,
        value = proto.takeIf { it.hasValue() }?.value,
        text = proto.takeIf { it.hasText() }?.text,
        errorCode = proto.takeIf { it.hasErrorCode() }?.errorCode?.let(::errorCode),
        detail = proto.takeIf { it.hasDetail() }?.detail,
        message = proto.takeIf { it.hasMessage() }?.message,
        durationMs = proto.durationMs,
        syncState = proto.takeIf { it.hasSyncState() }?.syncState?.let(::syncState),
        artifact = proto.takeIf { it.hasArtifact() }?.artifact?.let(::artifact),
        count = proto.takeIf { it.hasCount() }?.count,
        snapshot = proto.takeIf { it.hasSnapshot() }?.snapshot?.let(::snapshot),
        deviceInfo = proto.takeIf { it.hasDeviceInfo() }?.deviceInfo?.let(::deviceInfo),
    )

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
