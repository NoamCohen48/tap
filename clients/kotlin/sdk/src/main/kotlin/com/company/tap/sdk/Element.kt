package com.company.tap.sdk

import com.company.tap.api.v1.Direction
import com.company.tap.api.v1.ElementSnapshot
import com.company.tap.api.v1.Operation
import kotlin.time.Duration

/**
 * A selector bound to a device. Every method resolves the selector again on the device, so
 * nothing goes stale between calls; mutations require exactly one match.
 */
class Element internal constructor(
    val device: Device,
    val selector: Selector,
) {
    private fun run(operation: Operation, timeout: Duration?, configure: com.company.tap.api.v1.Command.Builder.() -> Unit = {}) =
        device.executeOrThrow(operation, selector, timeout ?: device.timeouts.action, configure)

    // --- Queries ------------------------------------------------------------------------------

    fun exists(timeout: Duration? = null): Boolean = run(Operation.OP_EXISTS, timeout).value

    /** Matches in the focused window right now, ignoring the selector's match limit. */
    fun count(timeout: Duration? = null): Int = run(Operation.OP_COUNT, timeout).count

    /** State of the one matching node at this instant (`AMBIGUOUS`/`NOT_FOUND` otherwise). */
    fun snapshot(timeout: Duration? = null): ElementSnapshot = run(Operation.OP_SNAPSHOT, timeout).snapshot

    fun text(timeout: Duration? = null): String? = snapshot(timeout).let { if (it.hasText()) it.text else null }
    fun isEnabled(timeout: Duration? = null): Boolean = snapshot(timeout).enabled
    fun isChecked(timeout: Duration? = null): Boolean = snapshot(timeout).checked

    // --- Actions (exactly one match required) -------------------------------------------------

    fun tap(timeout: Duration? = null) {
        run(Operation.OP_TAP, timeout)
    }

    fun longTap(timeout: Duration? = null) {
        run(Operation.OP_LONG_TAP, timeout)
    }

    /** Accessibility text replacement, verified on the device. */
    fun setText(value: String, timeout: Duration? = null) {
        run(Operation.OP_SET_TEXT, timeout) { inputText = value }
    }

    /** Focus plus real key events; unsupported characters are rejected before any input. */
    fun typeText(value: String, timeout: Duration? = null) {
        run(Operation.OP_TYPE_TEXT, timeout) { inputText = value }
    }

    fun clearText(timeout: Duration? = null) {
        run(Operation.OP_CLEAR_TEXT, timeout)
    }

    /** Finger gesture across the element in [direction]. */
    fun swipe(direction: Direction, distancePercent: Int = DEFAULT_GESTURE_PERCENT, timeout: Duration? = null) {
        run(Operation.OP_SWIPE, timeout) { setDirection(direction); setDistancePercent(distancePercent) }
    }

    /**
     * One scroll segment of this (scrollable) element towards [direction]'s content edge
     * (UiAutomator semantics: `DOWN` reveals content below). Returns `true` while more content
     * remains in that direction, `false` once the end was reached or no scroll was observed.
     */
    fun scroll(direction: Direction, distancePercent: Int = DEFAULT_GESTURE_PERCENT, timeout: Duration? = null): Boolean =
        run(Operation.OP_SCROLL, timeout) { setDirection(direction); setDistancePercent(distancePercent) }.value

    /**
     * Scrolls this container until [target] is visible inside it, or fails with `NOT_FOUND`
     * (`END_REACHED`/`MAX_SCROLLS`) or `WAIT_TIMEOUT`. Returns the target as a lazy element.
     */
    fun scrollUntil(
        target: Selector,
        direction: Direction = Direction.DIR_DOWN,
        maxScrolls: Int = 20,
        distancePercent: Int = DEFAULT_GESTURE_PERCENT,
        timeout: Duration? = null,
    ): Element {
        device.executeOrThrow(Operation.OP_SCROLL_UNTIL, target, timeout ?: device.timeouts.wait) {
            containerSelector = this@Element.selector.proto
            setDirection(direction)
            setMaxScrolls(maxScrolls)
            setDistancePercent(distancePercent)
        }
        return Element(device, target)
    }

    // --- Derived elements ---------------------------------------------------------------------

    fun await(timeout: Duration = device.timeouts.wait): ElementWait = ElementWait(device, selector, timeout)
    fun descendant(other: Selector): Element = Element(device, selector.descendant(other))
    fun child(other: Selector): Element = Element(device, selector.child(other))
    fun first(): Element = Element(device, selector.first())
    fun at(index: Int): Element = Element(device, selector.at(index))

    override fun toString(): String = "Element(${selector.render()} on ${device.serial})"
}
