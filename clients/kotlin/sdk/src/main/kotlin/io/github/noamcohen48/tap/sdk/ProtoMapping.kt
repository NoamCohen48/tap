package io.github.noamcohen48.tap.sdk

import io.github.noamcohen48.tap.api.v1.Defaults as DefaultsProto
import io.github.noamcohen48.tap.api.v1.DeviceEntry as DeviceEntryProto
import io.github.noamcohen48.tap.api.v1.DeviceInfo as DeviceInfoProto
import io.github.noamcohen48.tap.api.v1.DeviceState as DeviceStateProto
import io.github.noamcohen48.tap.api.v1.Direction as DirectionProto
import io.github.noamcohen48.tap.api.v1.ElementSnapshot as ElementSnapshotProto
import io.github.noamcohen48.tap.api.v1.ErrorCode as ErrorCodeProto
import io.github.noamcohen48.tap.api.v1.FailureReason as FailureReasonProto
import io.github.noamcohen48.tap.api.v1.InfoResponse
import io.github.noamcohen48.tap.api.v1.MatchMode as MatchModeProto
import io.github.noamcohen48.tap.api.v1.ProcessIdentity
import io.github.noamcohen48.tap.api.v1.StabilitySignal as StabilitySignalProto
import kotlin.time.Duration.Companion.milliseconds

/*
 * Proto <-> SDK model mapping. Enums map by name after the proto prefix, so a value added to the
 * schema needs a matching SDK constant; until then it maps to the SDK's "unknown" constant.
 */

internal fun MatchMode.toProto(): MatchModeProto = MatchModeProto.valueOf("MATCH_$name")

internal fun Direction.toProto(): DirectionProto = DirectionProto.valueOf("DIR_$name")

internal fun StabilitySignal.toProto(): StabilitySignalProto = StabilitySignalProto.valueOf("STABILITY_$name")

internal fun ErrorCodeProto.toModel(): ErrorCode =
    ErrorCode.entries.firstOrNull { "ERR_${it.name}" == name } ?: ErrorCode.UNKNOWN

internal fun FailureReasonProto.toModel(): FailureReason =
    FailureReason.entries.firstOrNull { "FAILURE_REASON_${it.name}" == name } ?: FailureReason.UNSPECIFIED

internal fun DeviceStateProto.toModel(): DeviceState =
    DeviceState.entries.firstOrNull { "DEVICE_${it.name}" == name } ?: DeviceState.UNKNOWN

internal fun ElementSnapshotProto.toModel(): ElementSnapshot =
    ElementSnapshot(
        className = if (hasClassName()) className else null,
        packageName = if (hasPackageName()) packageName else null,
        resourceName = if (hasResourceName()) resourceName else null,
        text = if (hasText()) text else null,
        contentDescription = if (hasContentDescription()) contentDescription else null,
        hint = if (hasHint()) hint else null,
        bounds = Bounds(bounds.left, bounds.top, bounds.right, bounds.bottom),
        checkable = checkable,
        checked = checked,
        clickable = clickable,
        enabled = enabled,
        focusable = focusable,
        focused = focused,
        longClickable = longClickable,
        scrollable = scrollable,
        selected = selected,
        childCount = childCount,
        showingHint = showingHint,
    )

internal fun DeviceInfoProto.toModel(): DeviceInfo =
    DeviceInfo(
        apiLevel = apiLevel,
        manufacturer = manufacturer,
        model = model,
        product = product,
        displayWidth = displayWidth,
        displayHeight = displayHeight,
        displayRotation = displayRotation,
        currentPackage = if (hasCurrentPackage()) currentPackage else null,
    )

internal fun DeviceEntryProto.toModel(): DeviceEntry =
    DeviceEntry(
        serial = serial,
        state = state.toModel(),
        clientConnectionId = if (hasClientConnectionId()) clientConnectionId else null,
        quarantineReason = if (hasQuarantineReason()) quarantineReason else null,
    )

internal fun ProcessIdentity.toModel(): AppProcess = AppProcess(pid, startToken)

internal fun InfoResponse.toModel(): ServerInfo =
    ServerInfo(
        daemonVersion = daemonVersion,
        hostBuildId = hostBuildId,
        protocolVersion = protocolVersion,
        adbExecutable = adbExecutable,
        stateDir = stateDir,
        driverAvailable = driverAvailable,
        pid = pid,
        defaults = defaults.toModel(),
    )

private fun DefaultsProto.toModel(): ServerDefaults =
    ServerDefaults(
        action = actionTimeoutMs.milliseconds,
        wait = waitTimeoutMs.milliseconds,
        lifecycle = lifecycleTimeoutMs.milliseconds,
        idleStable = idleStableMs.milliseconds,
        acquire = acquireTimeoutMs.milliseconds,
    )
