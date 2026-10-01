package io.github.noamcohen48.tap.sdk

import io.github.noamcohen48.tap.api.v1.ClearText
import io.github.noamcohen48.tap.api.v1.Command
import io.github.noamcohen48.tap.api.v1.Count
import io.github.noamcohen48.tap.api.v1.DoubleTap
import io.github.noamcohen48.tap.api.v1.Drag
import io.github.noamcohen48.tap.api.v1.Exists
import io.github.noamcohen48.tap.api.v1.Fling
import io.github.noamcohen48.tap.api.v1.LongTap
import io.github.noamcohen48.tap.api.v1.PerformImeAction
import io.github.noamcohen48.tap.api.v1.Pinch
import io.github.noamcohen48.tap.api.v1.PinchDirection
import io.github.noamcohen48.tap.api.v1.Scroll
import io.github.noamcohen48.tap.api.v1.SetText
import io.github.noamcohen48.tap.api.v1.Snapshot
import io.github.noamcohen48.tap.api.v1.Swipe
import io.github.noamcohen48.tap.api.v1.Tap
import kotlin.time.Duration
import kotlin.time.TimeSource

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

    /** Matches in the selector's scope right now, ignoring its match limit. */
    suspend fun count(timeout: Duration? = null): Int = run(timeout) { count = Count.newBuilder().setSelector(target).build() }.count

    /** State of the one matching node at this instant (`AMBIGUOUS`/`NOT_FOUND` otherwise). */
    suspend fun snapshot(timeout: Duration? = null): ElementSnapshot =
        run(timeout) { snapshot = Snapshot.newBuilder().setSelector(target).build() }.snapshot.toModel()

    /**
     * Raw accessibility text of the one matching node, or null when it has none. On API 26+ an
     * empty field reports its hint here; `snapshot().showingHint` says so.
     */
    suspend fun text(timeout: Duration? = null): String? = snapshot(timeout).text

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

    /** Two taps at the centre of the one matching node's visible bounds, inside Android's double-tap window. */
    suspend fun doubleTap(timeout: Duration? = null) {
        run(timeout) { doubleTap = DoubleTap.newBuilder().setSelector(target).build() }
    }

    /**
     * Runs the one matching text field's keyboard action (Search, Go, Send, Done, … as the app
     * configured it) exactly as the keyboard's action key does, through accessibility
     * `ACTION_IME_ENTER`. API 30+: older devices fail with `UNSUPPORTED` / `REQUIRES_API_30`
     * before any input (pressing Enter is not equivalent). The field must have input focus (tap
     * it first): Android offers the action only then, and a node that does not offer it fails
     * with `ACTION_REJECTED` before any input.
     */
    suspend fun imeAction(timeout: Duration? = null) {
        run(timeout) { performImeAction = PerformImeAction.newBuilder().setSelector(target).build() }
    }

    /**
     * Presses the one matching node until it is a long press, moves the finger to the centre of
     * the one node matching [destination] (resolved on the same screen, before any input), holds
     * there briefly and lifts. Nothing is reported about what the app did with the drop.
     */
    suspend fun dragTo(
        destination: Selector,
        timeout: Duration? = null,
    ) {
        run(timeout) {
            drag =
                Drag
                    .newBuilder()
                    .setSelector(target)
                    .setTarget(destination.proto)
                    .build()
        }
    }

    /** Two fingers moving apart from the one matching node's centre, across [percent] of its size. */
    suspend fun pinchOpen(
        percent: Int = DEFAULT_GESTURE_PERCENT,
        timeout: Duration? = null,
    ) = pinch(PinchDirection.PINCH_OPEN, percent, timeout)

    /** Two fingers moving together towards the one matching node's centre, across [percent] of its size. */
    suspend fun pinchClose(
        percent: Int = DEFAULT_GESTURE_PERCENT,
        timeout: Duration? = null,
    ) = pinch(PinchDirection.PINCH_CLOSE, percent, timeout)

    private suspend fun pinch(
        direction: PinchDirection,
        percent: Int,
        timeout: Duration?,
    ) {
        run(timeout) {
            pinch =
                Pinch
                    .newBuilder()
                    .setSelector(target)
                    .setDirection(direction)
                    .setPercent(percent)
                    .build()
        }
    }

    /**
     * One fast swipe across the one matching node towards [direction]'s content edge (as for
     * [scroll]: `DOWN` flings towards content below). Returns once the finger lifts; the content
     * may keep moving, so wait for what you need next.
     */
    suspend fun fling(
        direction: Direction,
        timeout: Duration? = null,
    ) {
        run(timeout) {
            fling =
                Fling
                    .newBuilder()
                    .setSelector(target)
                    .setDirection(direction.toProto())
                    .build()
        }
    }

    /**
     * Accessibility text replacement (`ACTION_SET_TEXT`) on the one matching node. Fails with
     * `ACTION_REJECTED` only when the node refuses the action; the field is not read back, so
     * assert the effect yourself with a selector that survives the edit:
     * `app.await(res("email")).textEquals("new")`.
     */
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

    /**
     * Taps the one matching node, waits until it reports focus (with [awaitFocus]; bounded by
     * the wait timeout), then types [value] as real key events with [Device.typeText]. Three
     * steps, so a failure says which one failed. Pass `awaitFocus = false` when focus goes
     * elsewhere (a child or a separate input view), and wait for what that app needs yourself.
     * The field is not read back: assert the effect yourself.
     */
    suspend fun typeText(
        value: String,
        awaitFocus: Boolean = true,
        timeout: Duration? = null,
    ) {
        tap(timeout)
        if (awaitFocus) await().focused()
        device.typeText(value, timeout)
    }

    /** [setText] with an empty string: `ACTION_SET_TEXT` on the one matching node, not read back. */
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
                    .setDirection(direction.toProto())
                    .setDistancePercent(distancePercent)
                    .build()
        }
    }

    /**
     * One scroll gesture on the one matching node towards [direction]'s content edge
     * (UiAutomator semantics: `DOWN` reveals content below). Nothing is reported about whether
     * content moved; observe that with a query or wait.
     */
    suspend fun scroll(
        direction: Direction,
        distancePercent: Int = DEFAULT_GESTURE_PERCENT,
        timeout: Duration? = null,
    ) {
        run(timeout) {
            scroll =
                Scroll
                    .newBuilder()
                    .setSelector(target)
                    .setDirection(direction.toProto())
                    .setDistancePercent(distancePercent)
                    .build()
        }
    }

    /**
     * Client-side loop: checks whether [target] exists inside this container ([descendant]) and,
     * while it does not, [scroll]s once, up to [maxScrolls] scrolls within [timeout] (default
     * the device's wait timeout). Returns the target as a lazy element scoped to this container,
     * so a later action cannot hit a duplicate elsewhere on screen; a container with
     * `first()`/`at()` cannot be carried into a relation, and then the bare [target] is used.
     *
     * Throws [WaitTimeoutException] when the target never appeared. The list may have scrolled
     * by then. Every step is an ordinary command, so a failing step throws its own
     * [CommandException].
     */
    suspend fun scrollUntil(
        target: Selector,
        direction: Direction = Direction.DOWN,
        maxScrolls: Int = 20,
        distancePercent: Int = DEFAULT_GESTURE_PERCENT,
        timeout: Duration? = null,
    ): Element {
        require(maxScrolls >= 0) { "maxScrolls must not be negative" }
        val found = Element(device, if (selector.hasPick) target else selector.descendant(target))
        val started = TimeSource.Monotonic.markNow()
        val budget = timeout ?: device.timeouts.wait
        var scrolls = 0
        while (true) {
            if (found.exists()) return found
            if (scrolls == maxScrolls || started.elapsedNow() >= budget) break
            scroll(direction, distancePercent)
            scrolls++
        }
        throw WaitTimeoutException(
            "${target.render()} to scroll into view in ${selector.render()}",
            device.serial,
            started.elapsedNow().inWholeMilliseconds,
            polls = scrolls,
            lastObservation = "not found after $scrolls scrolls",
        )
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
