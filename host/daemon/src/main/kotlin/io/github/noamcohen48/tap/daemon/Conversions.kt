package io.github.noamcohen48.tap.daemon

import io.github.noamcohen48.tap.protocol.ArtifactResult
import io.github.noamcohen48.tap.protocol.BoolResult
import io.github.noamcohen48.tap.protocol.Bounds
import io.github.noamcohen48.tap.protocol.ClearText
import io.github.noamcohen48.tap.protocol.Command
import io.github.noamcohen48.tap.protocol.CommandResult
import io.github.noamcohen48.tap.protocol.Count
import io.github.noamcohen48.tap.protocol.CountResult
import io.github.noamcohen48.tap.protocol.DEFAULT_GESTURE_PERCENT
import io.github.noamcohen48.tap.protocol.DEFAULT_STABLE_FOR_MS
import io.github.noamcohen48.tap.protocol.DeviceInfo
import io.github.noamcohen48.tap.protocol.DeviceInfoQuery
import io.github.noamcohen48.tap.protocol.DeviceInfoResult
import io.github.noamcohen48.tap.protocol.Direction
import io.github.noamcohen48.tap.protocol.Done
import io.github.noamcohen48.tap.protocol.DumpHierarchy
import io.github.noamcohen48.tap.protocol.ElementSnapshot
import io.github.noamcohen48.tap.protocol.ErrorCode
import io.github.noamcohen48.tap.protocol.Exists
import io.github.noamcohen48.tap.protocol.Health
import io.github.noamcohen48.tap.protocol.LongTap
import io.github.noamcohen48.tap.protocol.MatchMode
import io.github.noamcohen48.tap.protocol.Moved
import io.github.noamcohen48.tap.protocol.Node
import io.github.noamcohen48.tap.protocol.NodeFlag
import io.github.noamcohen48.tap.protocol.Pick
import io.github.noamcohen48.tap.protocol.PressKey
import io.github.noamcohen48.tap.protocol.Relation
import io.github.noamcohen48.tap.protocol.Response
import io.github.noamcohen48.tap.protocol.Scope
import io.github.noamcohen48.tap.protocol.Screenshot
import io.github.noamcohen48.tap.protocol.Scroll
import io.github.noamcohen48.tap.protocol.ScrollUntil
import io.github.noamcohen48.tap.protocol.Selector
import io.github.noamcohen48.tap.protocol.SetText
import io.github.noamcohen48.tap.protocol.Snapshot
import io.github.noamcohen48.tap.protocol.SnapshotResult
import io.github.noamcohen48.tap.protocol.StabilitySignal
import io.github.noamcohen48.tap.protocol.Swipe
import io.github.noamcohen48.tap.protocol.SyncBootstrap
import io.github.noamcohen48.tap.protocol.SyncPoll
import io.github.noamcohen48.tap.protocol.SyncResult
import io.github.noamcohen48.tap.protocol.Tap
import io.github.noamcohen48.tap.protocol.TextProperty
import io.github.noamcohen48.tap.protocol.TextResult
import io.github.noamcohen48.tap.protocol.TypeText
import io.github.noamcohen48.tap.protocol.WaitAppVisible
import io.github.noamcohen48.tap.protocol.WaitGone
import io.github.noamcohen48.tap.protocol.WaitScreenStable
import io.github.noamcohen48.tap.protocol.WaitVisible
import io.github.noamcohen48.tap.api.v1.AllOf as ProtoAllOf
import io.github.noamcohen48.tap.api.v1.AnyOf as ProtoAnyOf
import io.github.noamcohen48.tap.api.v1.At as ProtoAt
import io.github.noamcohen48.tap.api.v1.AutScope as ProtoAutScope
import io.github.noamcohen48.tap.api.v1.Bounds as ProtoBounds
import io.github.noamcohen48.tap.api.v1.ClearText as ProtoClearText
import io.github.noamcohen48.tap.api.v1.Command as ProtoCommand
import io.github.noamcohen48.tap.api.v1.CommandResult as ProtoCommandResult
import io.github.noamcohen48.tap.api.v1.Count as ProtoCount
import io.github.noamcohen48.tap.api.v1.DeviceInfo as ProtoDeviceInfo
import io.github.noamcohen48.tap.api.v1.DeviceInfoQuery as ProtoDeviceInfoQuery
import io.github.noamcohen48.tap.api.v1.Direction as ProtoDirection
import io.github.noamcohen48.tap.api.v1.Done as ProtoDone
import io.github.noamcohen48.tap.api.v1.DumpHierarchy as ProtoDumpHierarchy
import io.github.noamcohen48.tap.api.v1.ElementSnapshot as ProtoElementSnapshot
import io.github.noamcohen48.tap.api.v1.Error as ProtoError
import io.github.noamcohen48.tap.api.v1.ErrorCode as ProtoErrorCode
import io.github.noamcohen48.tap.api.v1.ExactlyOne as ProtoExactlyOne
import io.github.noamcohen48.tap.api.v1.Exists as ProtoExists
import io.github.noamcohen48.tap.api.v1.First as ProtoFirst
import io.github.noamcohen48.tap.api.v1.Flag as ProtoFlag
import io.github.noamcohen48.tap.api.v1.LongTap as ProtoLongTap
import io.github.noamcohen48.tap.api.v1.Match as ProtoMatch
import io.github.noamcohen48.tap.api.v1.MatchMode as ProtoMatchMode
import io.github.noamcohen48.tap.api.v1.Node as ProtoNode
import io.github.noamcohen48.tap.api.v1.NodeFlag as ProtoNodeFlag
import io.github.noamcohen48.tap.api.v1.PressKey as ProtoPressKey
import io.github.noamcohen48.tap.api.v1.Related as ProtoRelated
import io.github.noamcohen48.tap.api.v1.Relation as ProtoRelation
import io.github.noamcohen48.tap.api.v1.ResourceId as ProtoResourceId
import io.github.noamcohen48.tap.api.v1.Scroll as ProtoScroll
import io.github.noamcohen48.tap.api.v1.ScrollUntil as ProtoScrollUntil
import io.github.noamcohen48.tap.api.v1.Selector as ProtoSelector
import io.github.noamcohen48.tap.api.v1.SetText as ProtoSetText
import io.github.noamcohen48.tap.api.v1.Snapshot as ProtoSnapshot
import io.github.noamcohen48.tap.api.v1.StabilitySignal as ProtoStabilitySignal
import io.github.noamcohen48.tap.api.v1.Swipe as ProtoSwipe
import io.github.noamcohen48.tap.api.v1.SystemScope as ProtoSystemScope
import io.github.noamcohen48.tap.api.v1.Tap as ProtoTap
import io.github.noamcohen48.tap.api.v1.TextProperty as ProtoTextProperty
import io.github.noamcohen48.tap.api.v1.TypeText as ProtoTypeText
import io.github.noamcohen48.tap.api.v1.WaitAppVisible as ProtoWaitAppVisible
import io.github.noamcohen48.tap.api.v1.WaitGone as ProtoWaitGone
import io.github.noamcohen48.tap.api.v1.WaitScreenStable as ProtoWaitScreenStable
import io.github.noamcohen48.tap.api.v1.WaitVisible as ProtoWaitVisible

/*
 * Proto ↔ protocol conversion as extension functions: `toProto()` on every protocol model and
 * `toCommand()` / `toSelector()` / `toResponse()` / `to<Enum>()` on the generated proto classes.
 * Every enum maps by name (proto prefix stripped), so a value added to one side without the
 * other fails `EnumMirrorTest` rather than mapping silently; every `Command.op` /
 * `CommandResult.outcome` / `Node.kind` case maps to exactly one protocol class in an exhaustive
 * `when`, so a case added to one side without the other does not compile. `GoldenRoundTripTest`
 * proves every golden request/response survives a round trip unchanged.
 */
private const val ERR = "ERR_"
private const val DIR = "DIR_"
private const val MATCH = "MATCH_"
private const val PROPERTY = "PROPERTY_"
private const val FLAG = "FLAG_"
private const val RELATION = "RELATION_"
private const val STABILITY = "STABILITY_"

// ---- enums -------------------------------------------------------------------------------

fun ProtoErrorCode.toErrorCode(): ErrorCode = ErrorCode.valueOf(named(name, ERR, "errorCode"))

fun ErrorCode.toProto(): ProtoErrorCode = ProtoErrorCode.valueOf(ERR + name)

fun ProtoDirection.toDirection(): Direction = Direction.valueOf(named(name, DIR, "direction"))

fun Direction.toProto(): ProtoDirection = ProtoDirection.valueOf(DIR + name)

fun ProtoStabilitySignal.toStabilitySignal(): StabilitySignal =
    if (this == ProtoStabilitySignal.STABILITY_UNSPECIFIED) {
        StabilitySignal.ALL
    } else {
        StabilitySignal.valueOf(named(name, STABILITY, "signal"))
    }

fun StabilitySignal.toProto(): ProtoStabilitySignal = ProtoStabilitySignal.valueOf(STABILITY + name)

private fun ProtoMatchMode.toMatchMode(): MatchMode =
    if (this == ProtoMatchMode.MATCH_UNSPECIFIED) MatchMode.EXACT else MatchMode.valueOf(named(name, MATCH, "mode"))

private fun MatchMode.toProto(): ProtoMatchMode = ProtoMatchMode.valueOf(MATCH + name)

private fun ProtoTextProperty.toTextProperty(): TextProperty = TextProperty.valueOf(named(name, PROPERTY, "property"))

private fun TextProperty.toProto(): ProtoTextProperty = ProtoTextProperty.valueOf(PROPERTY + name)

private fun ProtoNodeFlag.toNodeFlag(): NodeFlag = NodeFlag.valueOf(named(name, FLAG, "property"))

private fun NodeFlag.toProto(): ProtoNodeFlag = ProtoNodeFlag.valueOf(FLAG + name)

private fun ProtoRelation.toRelation(): Relation = Relation.valueOf(named(name, RELATION, "relation"))

private fun Relation.toProto(): ProtoRelation = ProtoRelation.valueOf(RELATION + name)

private fun named(
    protoName: String,
    prefix: String,
    field: String,
): String {
    require(protoName.startsWith(prefix) && !protoName.endsWith("UNSPECIFIED") && protoName != "UNRECOGNIZED") {
        "$field must be set to a known value (got $protoName)"
    }
    return protoName.removePrefix(prefix)
}

// ---- selectors ---------------------------------------------------------------------------

/**
 * [autPackage] fills in `ResourceId.aut_package` resources; null (no session, as in the golden
 * round trip) rejects them. An unset `scope`/`pick` is the protocol default.
 */
fun ProtoSelector.toSelector(autPackage: String? = null): Selector =
    Selector(
        node = node.toNode(autPackage),
        scope =
            when (scopeCase) {
                ProtoSelector.ScopeCase.AUT, ProtoSelector.ScopeCase.SCOPE_NOT_SET -> Scope.Aut
                ProtoSelector.ScopeCase.SYSTEM -> Scope.System(system.packageName)
            },
        pick =
            when (pickCase) {
                ProtoSelector.PickCase.EXACTLY_ONE, ProtoSelector.PickCase.PICK_NOT_SET -> Pick.ExactlyOne
                ProtoSelector.PickCase.FIRST -> Pick.First
                ProtoSelector.PickCase.AT -> Pick.At(at.index)
            },
    )

fun Selector.toProto(): ProtoSelector =
    ProtoSelector
        .newBuilder()
        .apply {
            node = this@toProto.node.toProto()
            when (val scope = this@toProto.scope) {
                Scope.Aut -> aut = ProtoAutScope.getDefaultInstance()
                is Scope.System -> system = ProtoSystemScope.newBuilder().setPackageName(scope.packageName).build()
            }
            when (val pick = this@toProto.pick) {
                Pick.ExactlyOne -> exactlyOne = ProtoExactlyOne.getDefaultInstance()
                Pick.First -> first = ProtoFirst.getDefaultInstance()
                is Pick.At -> at = ProtoAt.newBuilder().setIndex(pick.index).build()
            }
        }.build()

private fun ProtoNode.toNode(autPackage: String?): Node =
    when (kindCase) {
        ProtoNode.KindCase.MATCH -> Node.Match(match.property.toTextProperty(), match.value, match.mode.toMatchMode())
        ProtoNode.KindCase.FLAG -> Node.Flag(flag.property.toNodeFlag(), flag.value)
        ProtoNode.KindCase.RESOURCE -> resource.toResource(autPackage)
        ProtoNode.KindCase.RELATED -> Node.Related(related.relation.toRelation(), related.node.toNode(autPackage))
        ProtoNode.KindCase.ALL_OF -> Node.AllOf(allOf.nodesList.map { it.toNode(autPackage) })
        ProtoNode.KindCase.ANY_OF -> Node.AnyOf(anyOf.nodesList.map { it.toNode(autPackage) })
        ProtoNode.KindCase.KIND_NOT_SET -> throw IllegalArgumentException("Node.kind must be set")
    }

private fun Node.toProto(): ProtoNode =
    ProtoNode
        .newBuilder()
        .apply {
            when (val value = this@toProto) {
                is Node.Match -> {
                    match =
                        ProtoMatch
                            .newBuilder()
                            .setProperty(value.property.toProto())
                            .setValue(value.value)
                            .setMode(value.mode.toProto())
                            .build()
                }

                is Node.Flag -> {
                    flag =
                        ProtoFlag
                            .newBuilder()
                            .setProperty(value.property.toProto())
                            .setValue(value.value)
                            .build()
                }

                is Node.Resource -> {
                    resource =
                        ProtoResourceId
                            .newBuilder()
                            .apply {
                                name = value.name
                                value.packageName?.let { packageName = it }
                            }.build()
                }

                is Node.Related -> {
                    related =
                        ProtoRelated
                            .newBuilder()
                            .setRelation(value.relation.toProto())
                            .setNode(value.node.toProto())
                            .build()
                }

                is Node.AllOf -> {
                    allOf = ProtoAllOf.newBuilder().addAllNodes(value.nodes.map { it.toProto() }).build()
                }

                is Node.AnyOf -> {
                    anyOf = ProtoAnyOf.newBuilder().addAllNodes(value.nodes.map { it.toProto() }).build()
                }
            }
        }.build()

private fun ProtoResourceId.toResource(autPackage: String?): Node.Resource {
    val explicit = if (hasPackageName()) packageName else null
    if (!this.autPackage) return Node.Resource(name, explicit)
    require(explicit == null) { "ResourceId '$name': package_name and aut_package are mutually exclusive" }
    requireNotNull(autPackage) { "ResourceId '$name': aut_package needs a session" }
    return Node.Resource(name, autPackage)
}

// ---- commands ----------------------------------------------------------------------------

/** Protocol operations the daemon drives itself; they have no tap.v1 `Command.op` case. */
internal val HOST_INTERNAL_OPS = setOf("health", "screenshot", "sync_bootstrap", "sync_poll")

/** Protocol result kinds only those operations produce; no tap.v1 `CommandResult.outcome` case. */
internal val HOST_INTERNAL_RESULTS = setOf("artifact", "sync")

internal fun Command.isHostInternal(): Boolean = this is Health || this is Screenshot || this is SyncBootstrap || this is SyncPoll

internal fun CommandResult.isHostInternal(): Boolean = this is ArtifactResult || this is SyncResult

/** tap.v1 default for an absent `ScrollUntil.max_scrolls`, as documented in command.proto. */
private const val DEFAULT_MAX_SCROLLS = 20

/**
 * Proto → protocol. [autPackage] fills in `aut_package` resources; null (no session, as in
 * the golden round trip) rejects them. Range violations surface as [IllegalArgumentException]
 * from the command's own constructor, which the servicer maps to `INVALID_ARGUMENT`.
 */
fun ProtoCommand.toCommand(autPackage: String? = null): Command {
    fun sel(value: ProtoSelector) = value.toSelector(autPackage)
    val proto = this
    return when (proto.opCase) {
        ProtoCommand.OpCase.DEVICE_INFO -> {
            DeviceInfoQuery
        }

        ProtoCommand.OpCase.PRESS_KEY -> {
            PressKey(proto.pressKey.keyCode)
        }

        ProtoCommand.OpCase.DUMP_HIERARCHY -> {
            DumpHierarchy
        }

        ProtoCommand.OpCase.EXISTS -> {
            Exists(sel(proto.exists.selector))
        }

        ProtoCommand.OpCase.COUNT -> {
            Count(sel(proto.count.selector))
        }

        ProtoCommand.OpCase.SNAPSHOT -> {
            Snapshot(sel(proto.snapshot.selector))
        }

        ProtoCommand.OpCase.WAIT_VISIBLE -> {
            WaitVisible(sel(proto.waitVisible.selector))
        }

        ProtoCommand.OpCase.WAIT_GONE -> {
            WaitGone(sel(proto.waitGone.selector))
        }

        ProtoCommand.OpCase.WAIT_APP_VISIBLE -> {
            WaitAppVisible(proto.waitAppVisible.packageName)
        }

        ProtoCommand.OpCase.WAIT_SCREEN_STABLE -> {
            proto.waitScreenStable.let {
                WaitScreenStable(
                    packageName = it.packageName,
                    stableForMs = if (it.hasStableForMs()) it.stableForMs else DEFAULT_STABLE_FOR_MS,
                    signal = it.signal.toStabilitySignal(),
                )
            }
        }

        ProtoCommand.OpCase.TAP -> {
            Tap(sel(proto.tap.selector))
        }

        ProtoCommand.OpCase.LONG_TAP -> {
            LongTap(sel(proto.longTap.selector))
        }

        ProtoCommand.OpCase.SET_TEXT -> {
            SetText(sel(proto.setText.selector), proto.setText.text)
        }

        ProtoCommand.OpCase.TYPE_TEXT -> {
            TypeText(sel(proto.typeText.selector), proto.typeText.text)
        }

        ProtoCommand.OpCase.CLEAR_TEXT -> {
            ClearText(sel(proto.clearText.selector))
        }

        ProtoCommand.OpCase.SWIPE -> {
            proto.swipe.let {
                Swipe(
                    sel(it.selector),
                    it.direction.toDirection(),
                    if (it.hasDistancePercent()) it.distancePercent else DEFAULT_GESTURE_PERCENT,
                )
            }
        }

        ProtoCommand.OpCase.SCROLL -> {
            proto.scroll.let {
                Scroll(
                    sel(it.selector),
                    it.direction.toDirection(),
                    if (it.hasDistancePercent()) it.distancePercent else DEFAULT_GESTURE_PERCENT,
                )
            }
        }

        ProtoCommand.OpCase.SCROLL_UNTIL -> {
            proto.scrollUntil.let {
                ScrollUntil(
                    selector = sel(it.selector),
                    container = sel(it.container),
                    direction = if (it.direction == ProtoDirection.DIR_UNSPECIFIED) Direction.DOWN else it.direction.toDirection(),
                    distancePercent = if (it.hasDistancePercent()) it.distancePercent else DEFAULT_GESTURE_PERCENT,
                    maxScrolls = if (it.hasMaxScrolls()) it.maxScrolls else DEFAULT_MAX_SCROLLS,
                )
            }
        }

        ProtoCommand.OpCase.OP_NOT_SET, null -> {
            throw IllegalArgumentException("Command.op must be set")
        }
    }
}

/**
 * Protocol → proto (the golden round-trip test); [timeoutMs] null means the session default.
 * Host-internal operations have no tap.v1 form and are rejected.
 */
fun Command.toProto(timeoutMs: Long? = null): ProtoCommand =
    ProtoCommand
        .newBuilder()
        .apply {
            timeoutMs?.let { this.timeoutMs = it }
            when (val value = this@toProto) {
                is Health, is Screenshot, is SyncBootstrap, is SyncPoll -> {
                    throw IllegalArgumentException("${value::class.simpleName} is host-internal, not part of tap.v1")
                }

                is DeviceInfoQuery -> {
                    deviceInfo = ProtoDeviceInfoQuery.getDefaultInstance()
                }

                is PressKey -> {
                    pressKey = ProtoPressKey.newBuilder().setKeyCode(value.keyCode).build()
                }

                is DumpHierarchy -> {
                    dumpHierarchy = ProtoDumpHierarchy.getDefaultInstance()
                }

                is Exists -> {
                    exists = ProtoExists.newBuilder().setSelector(value.selector.toProto()).build()
                }

                is Count -> {
                    count = ProtoCount.newBuilder().setSelector(value.selector.toProto()).build()
                }

                is Snapshot -> {
                    snapshot = ProtoSnapshot.newBuilder().setSelector(value.selector.toProto()).build()
                }

                is WaitVisible -> {
                    waitVisible = ProtoWaitVisible.newBuilder().setSelector(value.selector.toProto()).build()
                }

                is WaitGone -> {
                    waitGone = ProtoWaitGone.newBuilder().setSelector(value.selector.toProto()).build()
                }

                is WaitAppVisible -> {
                    waitAppVisible = ProtoWaitAppVisible.newBuilder().setPackageName(value.packageName).build()
                }

                is WaitScreenStable -> {
                    waitScreenStable =
                        ProtoWaitScreenStable
                            .newBuilder()
                            .setPackageName(value.packageName)
                            .setStableForMs(value.stableForMs)
                            .setSignal(value.signal.toProto())
                            .build()
                }

                is Tap -> {
                    tap = ProtoTap.newBuilder().setSelector(value.selector.toProto()).build()
                }

                is LongTap -> {
                    longTap = ProtoLongTap.newBuilder().setSelector(value.selector.toProto()).build()
                }

                is SetText -> {
                    setText =
                        ProtoSetText
                            .newBuilder()
                            .setSelector(value.selector.toProto())
                            .setText(value.text)
                            .build()
                }

                is TypeText -> {
                    typeText =
                        ProtoTypeText
                            .newBuilder()
                            .setSelector(value.selector.toProto())
                            .setText(value.text)
                            .build()
                }

                is ClearText -> {
                    clearText = ProtoClearText.newBuilder().setSelector(value.selector.toProto()).build()
                }

                is Swipe -> {
                    swipe =
                        ProtoSwipe
                            .newBuilder()
                            .setSelector(value.selector.toProto())
                            .setDirection(value.direction.toProto())
                            .setDistancePercent(value.distancePercent)
                            .build()
                }

                is Scroll -> {
                    scroll =
                        ProtoScroll
                            .newBuilder()
                            .setSelector(value.selector.toProto())
                            .setDirection(value.direction.toProto())
                            .setDistancePercent(value.distancePercent)
                            .build()
                }

                is ScrollUntil -> {
                    scrollUntil =
                        ProtoScrollUntil
                            .newBuilder()
                            .setSelector(value.selector.toProto())
                            .setContainer(value.container.toProto())
                            .setDirection(value.direction.toProto())
                            .setDistancePercent(value.distancePercent)
                            .setMaxScrolls(value.maxScrolls)
                            .build()
                }
            }
        }.build()

// ---- responses ---------------------------------------------------------------------------

fun Response.toProto(
    requestId: Long,
    generation: Long,
): ProtoCommandResult =
    ProtoCommandResult
        .newBuilder()
        .apply {
            durationMs = this@toProto.durationMs
            this.requestId = requestId
            sessionGeneration = generation
            when (val response = this@toProto) {
                is Response.Error -> {
                    error =
                        ProtoError
                            .newBuilder()
                            .apply {
                                code = response.code.toProto()
                                response.detail?.let { detail = it }
                                response.message?.let { message = it }
                            }.build()
                }

                is Response.Ok -> {
                    when (val result = response.result) {
                        is Done -> done = ProtoDone.getDefaultInstance()
                        is BoolResult -> bool = result.value
                        is Moved -> moved = result.moved
                        is CountResult -> count = result.count
                        is TextResult -> text = result.text
                        is SnapshotResult -> snapshot = result.snapshot.toProto()
                        is DeviceInfoResult -> deviceInfo = result.deviceInfo.toProto()
                        // Only host-internal operations produce these, and they never reach tap.v1.
                        is ArtifactResult, is SyncResult -> error("${result::class.simpleName} has no tap.v1 form")
                    }
                }
            }
        }.build()

fun ProtoCommandResult.toResponse(): Response {
    val proto = this
    val result: CommandResult =
        when (proto.outcomeCase) {
            ProtoCommandResult.OutcomeCase.DONE -> {
                Done
            }

            ProtoCommandResult.OutcomeCase.BOOL -> {
                BoolResult(proto.bool)
            }

            ProtoCommandResult.OutcomeCase.MOVED -> {
                Moved(proto.moved)
            }

            ProtoCommandResult.OutcomeCase.COUNT -> {
                CountResult(proto.count)
            }

            ProtoCommandResult.OutcomeCase.TEXT -> {
                TextResult(proto.text)
            }

            ProtoCommandResult.OutcomeCase.SNAPSHOT -> {
                SnapshotResult(proto.snapshot.toSnapshot())
            }

            ProtoCommandResult.OutcomeCase.DEVICE_INFO -> {
                DeviceInfoResult(proto.deviceInfo.toDeviceInfo())
            }

            ProtoCommandResult.OutcomeCase.ERROR -> {
                return Response.failure(
                    proto.error.code.toErrorCode(),
                    detail = if (proto.error.hasDetail()) proto.error.detail else null,
                    message = if (proto.error.hasMessage()) proto.error.message else null,
                    durationMs = proto.durationMs,
                )
            }

            ProtoCommandResult.OutcomeCase.OUTCOME_NOT_SET, null -> {
                throw IllegalArgumentException("CommandResult.outcome must be set")
            }
        }
    return Response.ok(result, proto.durationMs)
}

fun DeviceInfo.toProto(): ProtoDeviceInfo =
    ProtoDeviceInfo
        .newBuilder()
        .apply {
            val value = this@toProto
            apiLevel = value.apiLevel
            manufacturer = value.manufacturer
            model = value.model
            product = value.product
            displayWidth = value.displayWidth
            displayHeight = value.displayHeight
            displayRotation = value.displayRotation
            value.currentPackage?.let { currentPackage = it }
        }.build()

private fun ProtoDeviceInfo.toDeviceInfo() =
    DeviceInfo(
        apiLevel,
        manufacturer,
        model,
        product,
        displayWidth,
        displayHeight,
        displayRotation,
        if (hasCurrentPackage()) currentPackage else null,
    )

private fun ElementSnapshot.toProto(): ProtoElementSnapshot =
    ProtoElementSnapshot
        .newBuilder()
        .apply {
            val value = this@toProto
            value.className?.let { className = it }
            value.packageName?.let { packageName = it }
            value.resourceName?.let { resourceName = it }
            value.text?.let { text = it }
            value.contentDescription?.let { contentDescription = it }
            value.hint?.let { hint = it }
            bounds =
                ProtoBounds
                    .newBuilder()
                    .setLeft(value.bounds.left)
                    .setTop(value.bounds.top)
                    .setRight(value.bounds.right)
                    .setBottom(value.bounds.bottom)
                    .build()
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

private fun ProtoElementSnapshot.toSnapshot(): ElementSnapshot {
    val proto = this
    return ElementSnapshot(
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
