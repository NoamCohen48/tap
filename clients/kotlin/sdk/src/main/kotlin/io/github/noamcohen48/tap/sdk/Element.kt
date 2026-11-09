package io.github.noamcohen48.tap.sdk

import io.github.noamcohen48.tap.api.v1.ClearText
import io.github.noamcohen48.tap.api.v1.Command
import io.github.noamcohen48.tap.api.v1.Count
import io.github.noamcohen48.tap.api.v1.DoubleTap
import io.github.noamcohen48.tap.api.v1.Drag
import io.github.noamcohen48.tap.api.v1.Exists
import io.github.noamcohen48.tap.api.v1.Fling
import io.github.noamcohen48.tap.api.v1.LongTap
import io.github.noamcohen48.tap.api.v1.MultiTap
import io.github.noamcohen48.tap.api.v1.TapOutsidePanel
import io.github.noamcohen48.tap.api.v1.TimedPress
import io.github.noamcohen48.tap.api.v1.PerformAccessibilityAction
import io.github.noamcohen48.tap.api.v1.PerformImeAction
import io.github.noamcohen48.tap.api.v1.Pinch
import io.github.noamcohen48.tap.api.v1.PinchDirection
import io.github.noamcohen48.tap.api.v1.Scroll
import io.github.noamcohen48.tap.api.v1.SetProgress
import io.github.noamcohen48.tap.api.v1.SetText
import io.github.noamcohen48.tap.api.v1.Snapshot
import io.github.noamcohen48.tap.api.v1.Swipe
import io.github.noamcohen48.tap.api.v1.Tap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
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

    /**
     * [count] taps (1..100) at the initially resolved visible centre, with [interval] between
     * release and the next press (40..1000 ms; default 100 ms). The sequence, including each
     * 50 ms press, must fit in 30 seconds and the remaining [timeout]. Resolves once, never
     * retries; does not promise app-specific multi-click recognition. Single tap is unchanged.
     */
    suspend fun tap(count: Int, interval: Duration = 100.milliseconds, timeout: Duration? = null) {
        val gap = interval.inWholeMilliseconds
        require(count in 1..100) { "count must be in 1..100" }
        require(interval in 40.milliseconds..1000.milliseconds) { "interval must be in 40..1000 ms" }
        require(count * 50L + (count - 1L) * gap <= 30_000) { "The tap sequence must not exceed 30000 ms" }
        if (count == 1) {
            run(timeout) { tap = Tap.newBuilder().setSelector(target).build() }
        } else {
            run(timeout) { multiTap = MultiTap.newBuilder().setSelector(target).setCount(count).setIntervalMs(gap).build() }
        }
    }

    /** Three taps with [interval] between releases and presses; see [tap]. */
    suspend fun tripleTap(timeout: Duration? = null, interval: Duration = 100.milliseconds) {
        tap(count = 3, interval = interval, timeout = timeout)
    }

    /** Long click on the one matching node, using Android's default long-press timing. */
    suspend fun longTap(timeout: Duration? = null) {
        run(timeout) { longTap = LongTap.newBuilder().setSelector(target).build() }
    }

    /**
     * Holds a physical finger for [duration] (1..30000 ms); short presses may not trigger a
     * long-click handler. Must fit the remaining [timeout], before any input. Requires the
     * default display. Use the overload without [duration] for Android's default long press.
     * The existing positional [timeout] argument keeps its meaning.
     */
    suspend fun longTap(timeout: Duration? = null, duration: Duration) {
        require(duration in 1.milliseconds..30000.milliseconds) { "duration must be in 1..30000 ms" }
        run(timeout) { hold = TimedPress.newBuilder().setSelector(target).setDurationMs(duration.inWholeMilliseconds).build() }
    }

    /** Two taps at the centre of the one matching node's visible bounds, inside Android's double-tap window. */
    suspend fun doubleTap(timeout: Duration? = null) {
        run(timeout) { doubleTap = DoubleTap.newBuilder().setSelector(target).build() }
    }

    /**
     * Taps outside the panel this element is in: a dialog, popup, menu, drawer or bottom sheet,
     * Views or Compose. Select any node inside the panel, such as its title; it need not be
     * tappable itself. This is how a test checks "a touch outside closes it".
     *
     * The panel is the nearest node from this one up that offers Android's dismiss action or has
     * a pane title (drawers and bottom sheets), and the touch goes beside it, where no control of
     * the app's own would take it; otherwise it is the element's window (dialogs, popups, menus),
     * and the touch goes just past its edge. Sides are tried below, above, right, left, skipping
     * the system bars and gesture area, the keyboard and windows above. Wait until the panel has
     * stopped moving first: the point is placed from its bounds at that moment.
     *
     * Fails before any input with `NOT_INTERACTABLE` and detail `NO_OUTSIDE_POINT` when no side
     * has room (a full-screen window that is not a panel, such as a plain screen), or
     * `TOUCH_REACHES_BELOW` when the only free points are on the screen under a panel that lets
     * touches through (an autocomplete dropdown, a non-focusable popup): set [allowReachBelow]
     * to send the touch there anyway. Whether the panel closes is the app's choice: assert it,
     * for example with `app.await(selector).gone()`. Default display only.
     */
    suspend fun tapOutsidePanel(
        timeout: Duration? = null,
        allowReachBelow: Boolean = false,
    ) {
        run(timeout) { tapOutsidePanel = TapOutsidePanel.newBuilder().setSelector(target).setAllowReachBelow(allowReachBelow).build() }
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
     * Performs a standard accessibility [action] on the one matching node, as a screen reader
     * does: no touch, so a covered node is no obstacle. The node must offer it
     * ([ElementSnapshot.actions]); otherwise `ACTION_REJECTED` / `ACTION_NOT_OFFERED` before any
     * input. A node that refuses an offered action is `ACTION_REJECTED`. Only Android's answer is
     * reported: assert what the app did.
     */
    suspend fun performAction(
        action: StandardAction,
        timeout: Duration? = null,
    ) {
        run(timeout) { performAccessibilityAction = PerformAccessibilityAction.newBuilder().setSelector(target).setStandard(action.toProto()).build() }
    }

    /**
     * Performs the custom accessibility action labelled [label] (exactly) on the one matching
     * node: the "Archive" or "Delete" a list item offers screen reader users instead of a swipe
     * ([ElementSnapshot.customActions]). Not offered, or offered twice under that label:
     * `ACTION_REJECTED` / `ACTION_NOT_OFFERED` before any input.
     */
    suspend fun performCustomAction(
        label: String,
        timeout: Duration? = null,
    ) {
        run(timeout) { performAccessibilityAction = PerformAccessibilityAction.newBuilder().setSelector(target).setCustom(label).build() }
    }

    /**
     * Sets the one matching range node (SeekBar, Slider, RatingBar) to [value] in its own units
     * ([ElementSnapshot.range]) through accessibility `ACTION_SET_PROGRESS`: exact, where a drag
     * would land on whatever pixel maps to. A node without the action, or a value outside its
     * min..max, is `ACTION_REJECTED` (`ACTION_NOT_OFFERED` / `OUT_OF_RANGE`) before any input;
     * the view is not left to clamp it.
     */
    suspend fun setProgress(
        value: Float,
        timeout: Duration? = null,
    ) {
        require(value.isFinite()) { "value must be finite, not $value" }
        run(timeout) { setProgress = SetProgress.newBuilder().setSelector(target).setValue(value).build() }
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
     *
     * With [sensitive] (a password, a token) the server logs the call without [value], so the
     * connection's event log, `tap-watcher` and exports never hold it.
     */
    suspend fun setText(
        value: String,
        timeout: Duration? = null,
        sensitive: Boolean = false,
    ) {
        run(timeout) {
            setText =
                SetText
                    .newBuilder()
                    .setSelector(target)
                    .setText(value)
                    .setSensitive(sensitive)
                    .build()
        }
    }

    /**
     * Taps the one matching node, waits until it reports focus (with [awaitFocus]; bounded by
     * the wait timeout), then types [value] as real key events with [Device.typeText]. Three
     * steps, so a failure says which one failed. Pass `awaitFocus = false` when focus goes
     * elsewhere (a child or a separate input view), and wait for what that app needs yourself.
     * The field is not read back: assert the effect yourself. [sensitive] as for [setText].
     */
    suspend fun typeText(
        value: String,
        awaitFocus: Boolean = true,
        timeout: Duration? = null,
        sensitive: Boolean = false,
    ) {
        tap(timeout)
        if (awaitFocus) await().focused()
        device.typeText(value, timeout, sensitive)
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
