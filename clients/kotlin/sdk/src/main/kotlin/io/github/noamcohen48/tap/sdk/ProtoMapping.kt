package io.github.noamcohen48.tap.sdk

import io.github.noamcohen48.tap.api.v1.Defaults as DefaultsProto
import io.github.noamcohen48.tap.api.v1.DeviceEntry as DeviceEntryProto
import io.github.noamcohen48.tap.api.v1.DeviceInfo as DeviceInfoProto
import io.github.noamcohen48.tap.api.v1.DeviceState as DeviceStateProto
import io.github.noamcohen48.tap.api.v1.Direction as DirectionProto
import io.github.noamcohen48.tap.api.v1.DisplayRotation as DisplayRotationProto
import io.github.noamcohen48.tap.api.v1.ElementSnapshot as ElementSnapshotProto
import io.github.noamcohen48.tap.api.v1.ErrorCode as ErrorCodeProto
import io.github.noamcohen48.tap.api.v1.FailureReason as FailureReasonProto
import io.github.noamcohen48.tap.api.v1.InfoResponse
import io.github.noamcohen48.tap.api.v1.IntentExtra as IntentExtraProto
import io.github.noamcohen48.tap.api.v1.MatchMode as MatchModeProto
import io.github.noamcohen48.tap.api.v1.Orientation as OrientationProto
import io.github.noamcohen48.tap.api.v1.PermissionChoice as PermissionChoiceProto
import io.github.noamcohen48.tap.api.v1.PermissionPrompt as PermissionPromptProto
import io.github.noamcohen48.tap.api.v1.ProcessIdentity
import io.github.noamcohen48.tap.api.v1.StabilitySignal as StabilitySignalProto
import io.github.noamcohen48.tap.api.v1.Toast as ToastProto
import kotlin.time.Duration.Companion.milliseconds

/*
 * Proto <-> SDK model mapping. Enums map by name after the proto prefix, so a value added to the
 * schema needs a matching SDK constant; until then it maps to the SDK's "unknown" constant.
 */

internal fun MatchMode.toProto(): MatchModeProto = MatchModeProto.valueOf("MATCH_$name")

internal fun Direction.toProto(): DirectionProto = DirectionProto.valueOf("DIR_$name")

internal fun StabilitySignal.toProto(): StabilitySignalProto = StabilitySignalProto.valueOf("STABILITY_$name")

internal fun Orientation.toProto(): OrientationProto = OrientationProto.valueOf("ORIENTATION_$name")

internal fun DisplayRotation.toProto(): DisplayRotationProto = DisplayRotationProto.valueOf("DISPLAY_ROTATION_$name")

internal fun PermissionChoice.toProto(): PermissionChoiceProto = PermissionChoiceProto.valueOf("PERMISSION_$name")

internal fun PermissionPromptProto.toModel(): PermissionPrompt =
    PermissionPrompt(
        packageName = packageName,
        // A choice this client does not know yet is left out rather than guessed.
        choices = choicesList.mapNotNull { choice -> PermissionChoice.entries.find { "PERMISSION_${it.name}" == choice.name } },
    )

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
        displayRotation = DisplayRotation.entries[displayRotation and 3],
        currentPackage = if (hasCurrentPackage()) currentPackage else null,
        screenOn = screenOn,
        keyguardLocked = keyguardLocked,
        keyguardSecure = keyguardSecure,
        keyboardShown = keyboardShown,
        autoRotate = autoRotate,
        animationsEnabled = animationsEnabled,
        darkMode = darkMode,
        fontScale = fontScale,
        densityDpi = densityDpi,
        airplaneMode = airplaneMode,
        wifiEnabled = wifiEnabled,
        mobileDataEnabled = mobileDataEnabled,
    )

internal fun ToastProto.toModel(): Toast = Toast(text = text, packageName = packageName)

/** `am start` extras from [App.launch]'s map; an unsupported value type fails before the call. */
internal fun intentExtras(extras: Map<String, Any>): List<IntentExtraProto> =
    extras.map { (key, value) ->
        IntentExtraProto.newBuilder().setKey(key).apply {
            when (value) {
                is String -> stringValue = value
                is Boolean -> boolValue = value
                is Int -> intValue = value
                is Long -> longValue = value
                is Float -> floatValue = value
                else -> throw IllegalArgumentException(
                    "Intent extra '$key' is a ${value::class.simpleName}; use String, Boolean, Int, Long or Float",
                )
            }
        }.build()
    }

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
