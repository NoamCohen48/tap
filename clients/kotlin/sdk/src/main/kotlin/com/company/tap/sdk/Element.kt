package com.company.tap.sdk

import com.company.tap.api.v1.ClearText
import com.company.tap.api.v1.Command
import com.company.tap.api.v1.Count
import com.company.tap.api.v1.Direction
import com.company.tap.api.v1.ElementSnapshot
import com.company.tap.api.v1.ErrorCode
import com.company.tap.api.v1.Exists
import com.company.tap.api.v1.LongTap
import com.company.tap.api.v1.Scroll
import com.company.tap.api.v1.ScrollUntil
import com.company.tap.api.v1.SetText
import com.company.tap.api.v1.Snapshot
import com.company.tap.api.v1.Swipe
import com.company.tap.api.v1.Tap
import com.company.tap.api.v1.TypeText
import kotlin.time.Duration

/**
 * A selector bound to a device. Every method resolves the selector again on the device, so
 * nothing goes stale between calls; mutations require exactly one match.
 *
 * All I/O methods are `suspend` and require an owning scope (`tapScope`/`tapTest`); derived
 * selectors ([descendant], [child], [first], [at], [await]) only build values, so they stay
 * non-suspend.
 */
class Element internal constructor(
    val device: Device,
    val selector: Selector,
) {
    private val target get() = selector.proto

    private suspend fun run(
        timeout: Duration?,
        build: Command.Builder.() -> Unit,
    ) = device.executeOrThrow(timeout ?: device.timeouts.action, selector, build)

    // --- Queries ------------------------------------------------------------------------------

    /** True when at least one node matches right now (any number of matches is fine). */
    suspend fun exists(timeout: Duration? = null): Boolean = run(timeout) { exists = Exists.newBuilder().setSelector(target).build() }.bool

    /** Matches in the focused window right now, ignoring the selector's match limit. */
    suspend fun count(timeout: Duration? = null): Int = run(timeout) { count = Count.newBuilder().setSelector(target).build() }.count

    /** State of the one matching node at this instant (`AMBIGUOUS`/`NOT_FOUND` otherwise). */
    suspend fun snapshot(timeout: Duration? = null): ElementSnapshot =
        run(timeout) { snapshot = Snapshot.newBuilder().setSelector(target).build() }.snapshot

    /** Text of the one matching node, or null when it has none (an empty field's hint is not text). */
    suspend fun text(timeout: Duration? = null): String? = snapshot(timeout).let { if (it.hasText()) it.text else null }

    /** `snapshot().enabled` of the one matching node. */
    suspend fun isEnabled(timeout: Duration? = null): Boolean = snapshot(timeout).enabled

    /** `snapshot().checked` of the one matching node. */
    suspend fun isChecked(timeout: Duration? = null): Boolean = snapshot(timeout).checked

    // --- Actions (exactly one match required) -------------------------------------------------

    /** Click at the centre of the one matching node's visible bounds. */
    suspend fun tap(timeout: Duration? = null) {
        run(timeout) { tap = Tap.newBuilder().setSelector(target).build() }
    }

    /** Long click on the one matching node. */
    suspend fun longTap(timeout: Duration? = null) {
        run(timeout) { longTap = LongTap.newBuilder().setSelector(target).build() }
    }

    /** Accessibility text replacement, verified on the device. */
    suspend fun setText(
        value: String,
        timeout: Duration? = null,
    ) {
        run(timeout) {
            setText =
                SetText
                    .newBuilder()
                    .setSelector(target)
                    .setText(value)
                    .build()
        }
    }

    /** Focus plus real key events; unsupported characters are rejected before any input. */
    suspend fun typeText(
        value: String,
        timeout: Duration? = null,
    ) {
        run(timeout) {
            typeText =
                TypeText
                    .newBuilder()
                    .setSelector(target)
                    .setText(value)
                    .build()
        }
    }

    /** Focus the one matching editable node and clear its text. */
    suspend fun clearText(timeout: Duration? = null) {
        run(timeout) { clearText = ClearText.newBuilder().setSelector(target).build() }
    }

    /** Finger gesture across the element in [direction]. */
    suspend fun swipe(
        direction: Direction,
        distancePercent: Int = DEFAULT_GESTURE_PERCENT,
        timeout: Duration? = null,
    ) {
        run(timeout) {
            swipe =
                Swipe
                    .newBuilder()
                    .setSelector(target)
                    .setDirection(direction)
                    .setDistancePercent(distancePercent)
                    .build()
        }
    }

    /**
     * One scroll segment of this (scrollable) element towards [direction]'s content edge
     * (UiAutomator semantics: `DOWN` reveals content below). Returns `true` while more content
     * remains in that direction, `false` once the end was reached or no scroll was observed.
     */
    suspend fun scroll(
        direction: Direction,
        distancePercent: Int = DEFAULT_GESTURE_PERCENT,
        timeout: Duration? = null,
    ): Boolean =
        run(
            timeout,
        ) {
            scroll =
                Scroll
                    .newBuilder()
                    .setSelector(target)
                    .setDirection(direction)
                    .setDistancePercent(distancePercent)
                    .build()
        }.moved

    /**
     * Scrolls this container until [target] is visible inside it and returns the target as a
     * lazy element. Once it has scrolled, a failure is a [CommandException] `INDETERMINATE` whose
     * detail says why (`END_REACHED`, `MAX_SCROLLS` or `WAIT_TIMEOUT`): the list moved, so the
     * failure is not side-effect free and is never reported as a plain wait timeout. Before the
     * first scroll it fails like any command (`NOT_FOUND`/`AMBIGUOUS` for the container), and a
     * device `WAIT_TIMEOUT` then becomes [WaitTimeoutException].
     */
    suspend fun scrollUntil(
        target: Selector,
        direction: Direction = Direction.DIR_DOWN,
        maxScrolls: Int = 20,
        distancePercent: Int = DEFAULT_GESTURE_PERCENT,
        timeout: Duration? = null,
    ): Element {
        val result =
            device.execute(timeout ?: device.timeouts.wait) {
                scrollUntil =
                    ScrollUntil
                        .newBuilder()
                        .setSelector(target.proto)
                        .setContainer(this@Element.target)
                        .setDirection(direction)
                        .setMaxScrolls(maxScrolls)
                        .setDistancePercent(distancePercent)
                        .build()
            }
        if (result.hasError()) {
            if (result.error.code == ErrorCode.ERR_WAIT_TIMEOUT) {
                throw WaitTimeoutException(
                    "${target.render()} to scroll into view in ${selector.render()}",
                    device.serial,
                    result.durationMs,
                )
            }
            throw CommandException(result, "scroll_until", device.serial, target.render())
        }
        return Element(device, target)
    }

    // --- Derived elements ---------------------------------------------------------------------

    /** An [ElementWait] on this selector. Builds a value; no I/O, so not suspend. */
    fun await(timeout: Duration = device.timeouts.wait): ElementWait = ElementWait(device, selector, timeout)

    /** The node matching [other] somewhere below this one. Builds a value; no I/O. */
    fun descendant(other: Selector): Element = Element(device, selector.descendant(other))

    /** The direct child of this node matching [other]. Builds a value; no I/O. */
    fun child(other: Selector): Element = Element(device, selector.child(other))

    /** Accept the first match in accessibility order instead of requiring exactly one. Builds a value; no I/O. */
    fun first(): Element = Element(device, selector.first())

    /** Accept the [index]-th match (0-based) in accessibility order. Builds a value; no I/O. */
    fun at(index: Int): Element = Element(device, selector.at(index))

    override fun toString(): String = "Element(${selector.render()} on ${device.serial})"
}
