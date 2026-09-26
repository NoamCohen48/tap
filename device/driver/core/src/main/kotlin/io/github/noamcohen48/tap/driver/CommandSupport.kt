package io.github.noamcohen48.tap.driver

import androidx.test.uiautomator.UiObject2
import io.github.noamcohen48.tap.api.v1.Direction
import io.github.noamcohen48.tap.api.v1.ErrorCode
import io.github.noamcohen48.tap.driver.engine.CommandContext
import io.github.noamcohen48.tap.protocol.CommandFailure
import io.github.noamcohen48.tap.protocol.ErrorDetail

/*
 * Helpers shared by the command groups (gestures, text input, waits, scrolling, artifacts).
 */

internal fun CommandContext.elapsed(): Long = nowMs() - acceptedAtMs

/** Pre-mutation resolution; `NOT_FOUND`/`AMBIGUOUS` here still promise no input. */
internal fun UiObjectAccess.resolveTarget(target: CompiledSelector): UiObject2 {
    val resolved = resolve(target)
    return resolved.element ?: throw CommandFailure(requireNotNull(resolved.errorCode))
}

/**
 * A target that resolved before the mutation but not after it. `NOT_FOUND`/`AMBIGUOUS`
 * promise no mutation, so post-mutation cardinality failures report `STALE_DURING_COMMAND`
 * with the cardinality ([resolutionError]) as detail.
 */
internal fun staleTarget(
    resolutionError: ErrorCode,
    message: String? = null,
): CommandFailure =
    CommandFailure(
        ErrorCode.ERR_STALE_DURING_COMMAND,
        detail =
            when (resolutionError) {
                ErrorCode.ERR_AMBIGUOUS -> ErrorDetail.TARGET_AMBIGUOUS
                else -> ErrorDetail.TARGET_GONE
            },
        message = message,
    )

internal fun staleTarget(
    resolution: UiObjectAccess.Resolution,
    message: String? = null,
): CommandFailure = staleTarget(requireNotNull(resolution.errorCode), message)

/** Validation already rejected an unspecified or unknown direction where one is required. */
internal fun uiDirection(direction: Direction): androidx.test.uiautomator.Direction =
    when (direction) {
        Direction.DIR_UP -> androidx.test.uiautomator.Direction.UP
        Direction.DIR_DOWN -> androidx.test.uiautomator.Direction.DOWN
        Direction.DIR_LEFT -> androidx.test.uiautomator.Direction.LEFT
        Direction.DIR_RIGHT -> androidx.test.uiautomator.Direction.RIGHT
        Direction.DIR_UNSPECIFIED, Direction.UNRECOGNIZED ->
            throw CommandFailure(ErrorCode.ERR_INVALID_REQUEST, message = "A direction is required")
    }

/** A wire `distance_percent` (0–100) as the fraction UiAutomator takes. */
internal fun fraction(distancePercent: Int): Float = distancePercent / 100f

/**
 * The node's text without a displayed hint (see [TextVerifier.displayedText]). The object is
 * refreshed by the read.
 */
internal fun UiObject2.displayedText(): String? {
    val node = accessibilityNodeInfo
    return TextVerifier.displayedText(node.text, node.isShowingHintText)
}
