package io.github.noamcohen48.tap.driver

import androidx.test.uiautomator.StaleObjectException
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

/**
 * A `StaleObjectException`: UiAutomator could not refresh a node the command resolved (its view
 * was detached or replaced). Before the mutation gate nothing was sent, so it is
 * `STALE_BEFORE_INPUT`, which a caller may retry with a fresh lookup; after it the input may
 * have started, so it is `STALE_DURING_COMMAND` / `TARGET_GONE`.
 */
internal fun staleElement(mutationStarted: Boolean): CommandFailure =
    if (mutationStarted) {
        staleTarget(ErrorCode.ERR_NOT_FOUND, "The target went stale after the input started")
    } else {
        CommandFailure(ErrorCode.ERR_STALE_BEFORE_INPUT, message = "The target went stale before any input")
    }

/**
 * Runs [block], turning a `StaleObjectException` into [staleElement] by whether the mutation gate
 * had opened when it was thrown ([mutationStarted] is read then, not before).
 */
internal inline fun <T> mappingStaleElements(
    mutationStarted: () -> Boolean,
    block: () -> T,
): T =
    try {
        block()
    } catch (_: StaleObjectException) {
        throw staleElement(mutationStarted())
    }

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
