package com.company.tap.sdk

import com.company.tap.host.render
import com.company.tap.protocol.Direction
import com.company.tap.protocol.DEFAULT_GESTURE_PERCENT
import com.company.tap.protocol.ElementSnapshot
import com.company.tap.protocol.Operation
import com.company.tap.protocol.Selector
import kotlin.time.Duration

/**
 * A lazy element: a selector bound to a device. Constructing one performs no I/O and holds
 * no device-side handle; every method resolves the selector again on the device.
 */
class Element internal constructor(
    val device: Device,
    val selector: Selector,
) {
    private val client get() = device.client
    private fun actionMs(timeout: Duration?) = (timeout ?: device.timeouts.action).inWholeMilliseconds

    // --- Queries ------------------------------------------------------------------------------

    fun exists(timeout: Duration? = null): Boolean =
        client.executeOrThrow(Operation.EXISTS, selector, timeoutMs = actionMs(timeout)).value == true

    /** Matches in the focused window right now, ignoring the selector's match limit. */
    fun count(timeout: Duration? = null): Int =
        requireNotNull(client.executeOrThrow(Operation.COUNT, selector, timeoutMs = actionMs(timeout)).count)

    /** State of the one matching node at this instant (`AMBIGUOUS`/`NOT_FOUND` otherwise). */
    fun snapshot(timeout: Duration? = null): ElementSnapshot =
        requireNotNull(client.executeOrThrow(Operation.SNAPSHOT, selector, timeoutMs = actionMs(timeout)).snapshot)

    fun text(timeout: Duration? = null): String? = snapshot(timeout).text
    fun isEnabled(timeout: Duration? = null): Boolean = snapshot(timeout).enabled
    fun isChecked(timeout: Duration? = null): Boolean = snapshot(timeout).checked

    // --- Actions (exactly one match required) -------------------------------------------------

    fun tap(timeout: Duration? = null) {
        client.executeOrThrow(Operation.TAP, selector, timeoutMs = actionMs(timeout))
    }

    fun longTap(timeout: Duration? = null) {
        client.executeOrThrow(Operation.LONG_TAP, selector, timeoutMs = actionMs(timeout))
    }

    /** Accessibility text replacement, verified on the device. */
    fun setText(value: String, timeout: Duration? = null) {
        client.executeOrThrow(Operation.SET_TEXT, selector, inputText = value, timeoutMs = actionMs(timeout))
    }

    /** Focus plus real key events; unsupported characters are rejected before any input. */
    fun typeText(value: String, timeout: Duration? = null) {
        client.executeOrThrow(Operation.TYPE_TEXT, selector, inputText = value, timeoutMs = actionMs(timeout))
    }

    fun clearText(timeout: Duration? = null) {
        client.executeOrThrow(Operation.CLEAR_TEXT, selector, timeoutMs = actionMs(timeout))
    }

    /** Finger gesture across the element in [direction]. */
    fun swipe(direction: Direction, distancePercent: Int = DEFAULT_GESTURE_PERCENT, timeout: Duration? = null) {
        client.executeOrThrow(
            Operation.SWIPE, selector,
            direction = direction, distancePercent = distancePercent, timeoutMs = actionMs(timeout),
        )
    }

    /**
     * One scroll segment of this (scrollable) element towards [direction]'s content edge
     * (UiAutomator semantics: `DOWN` reveals content below). Returns `true` while more content
     * remains in that direction, `false` once the end was reached or no scroll was observed.
     */
    fun scroll(direction: Direction, distancePercent: Int = DEFAULT_GESTURE_PERCENT, timeout: Duration? = null): Boolean =
        client.executeOrThrow(
            Operation.SCROLL, selector,
            direction = direction, distancePercent = distancePercent, timeoutMs = actionMs(timeout),
        ).value == true

    /**
     * Scrolls this container until [target] is visible inside it, or fails with `NOT_FOUND`
     * (`END_REACHED`/`MAX_SCROLLS`) or `WAIT_TIMEOUT`. Returns the target as a lazy element.
     */
    fun scrollUntil(
        target: Selector,
        direction: Direction = Direction.DOWN,
        maxScrolls: Int = 20,
        distancePercent: Int = DEFAULT_GESTURE_PERCENT,
        timeout: Duration? = null,
    ): Element {
        client.executeOrThrow(
            Operation.SCROLL_UNTIL,
            selector = target,
            containerSelector = selector,
            direction = direction,
            maxScrolls = maxScrolls,
            distancePercent = distancePercent,
            timeoutMs = (timeout ?: device.timeouts.wait).inWholeMilliseconds,
        )
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
