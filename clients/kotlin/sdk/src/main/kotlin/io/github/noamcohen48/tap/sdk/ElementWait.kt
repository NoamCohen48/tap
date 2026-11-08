package io.github.noamcohen48.tap.sdk

import io.github.noamcohen48.tap.api.v1.Command
import io.github.noamcohen48.tap.api.v1.ErrorCode as ErrorCodeProto
import io.github.noamcohen48.tap.api.v1.Node
import io.github.noamcohen48.tap.api.v1.NodeFlag
import io.github.noamcohen48.tap.api.v1.TextProperty
import io.github.noamcohen48.tap.api.v1.WaitGone
import io.github.noamcohen48.tap.api.v1.WaitState
import io.github.noamcohen48.tap.api.v1.WaitVisible
import kotlin.time.Duration

/**
 * Wait builder returned by [App.await] / [Screen.await] / [Element.await]. Every wait but [count] polls on
 * the device in a single RPC; [count] polls from the host with `delay`-based polling, so test-root
 * cancellation and sibling failure cancel it promptly.
 *
 * All terminal methods are `suspend` and require an owning scope (`tapScope`/`tapTest`).
 */
class ElementWait internal constructor(
    private val device: Device,
    private val selector: Selector,
    private val timeout: Duration,
) {
    /**
     * Waits until at least one match exists; returns the lazy element. A timeout's
     * [WaitTimeoutException.reason] is [WaitReason.NO_MATCH].
     */
    suspend fun visible(): Element {
        deviceWait("wait_visible", "${selector.render()} to be visible") {
            waitVisible =
                WaitVisible.newBuilder().setSelector(selector.proto).build()
        }
        return Element(device, selector)
    }

    /**
     * Waits until exactly one match exists — what a mutation such as `tap()` needs — and returns
     * the lazy element. A timeout's [WaitTimeoutException.reason] is [WaitReason.NO_MATCH] or
     * [WaitReason.AMBIGUOUS] with [WaitTimeoutException.matchCount] from the last poll.
     */
    suspend fun one(): Element {
        deviceWait("wait_visible", "${selector.render()} to match exactly one node") {
            waitVisible =
                WaitVisible
                    .newBuilder()
                    .setSelector(selector.proto)
                    .setExactlyOne(true)
                    .build()
        }
        return Element(device, selector)
    }

    /** Waits until no match exists. A timeout's reason is [WaitReason.STILL_PRESENT], with the count. */
    suspend fun gone() {
        deviceWait("wait_gone", "${selector.render()} to be gone") { waitGone = WaitGone.newBuilder().setSelector(selector.proto).build() }
    }

    /**
     * Waits until the one node the selector picks satisfies [condition], then returns it.
     *
     * The selector says *which* node: exactly one match, or its `first()` / `at(n)`. The condition
     * says what that node must become, written with the selector builders
     * (`enabled() and text("Pay")`, `checked(false)`, `text("Total", MatchMode.STARTS_WITH)`,
     * `hasChild(text("Done"))`), and is checked on that node alone, every poll, all parts at the
     * same moment. Polled on the device in one RPC. With several matches and no pick the wait
     * fails at once with `AMBIGUOUS`. A timeout's [WaitTimeoutException.reason] is
     * [WaitReason.STATE_NOT_REACHED] ([WaitTimeoutException.lastState] is the node at the end and
     * [WaitTimeoutException.conditionParts] says which part failed) or [WaitReason.NO_MATCH].
     *
     * @throws IllegalArgumentException when [condition] has its own `first()` / `at(n)`.
     */
    suspend fun until(condition: Selector): Element {
        require(!condition.hasPick) {
            "until: the condition ${condition.render()} has a match choice (first()/at()); " +
                "a condition is checked on the one node the selector picks, so put the pick on the selector"
        }
        return state(condition.proto.node)
    }

    /** `until(enabled())`: wait until the one matching node is enabled. */
    suspend fun enabled(): Element = state(flag(NodeFlag.FLAG_ENABLED, true))

    /** `until(enabled(false))`. */
    suspend fun disabled(): Element = state(flag(NodeFlag.FLAG_ENABLED, false))

    /** `until(checked())`; a node that is not checkable never is. */
    suspend fun checked(): Element = state(flag(NodeFlag.FLAG_CHECKED, true))

    /** `until(checked(false))`. */
    suspend fun unchecked(): Element = state(flag(NodeFlag.FLAG_CHECKED, false))

    /** `until(focused())`: wait until the one matching node has input focus. */
    suspend fun focused(): Element = state(flag(NodeFlag.FLAG_FOCUSED, true))

    /** `until(text(expected))`: the raw accessibility text equals [expected]. */
    suspend fun textEquals(expected: String): Element = state(match(TextProperty.PROPERTY_TEXT, expected, MatchMode.EXACT))

    /** `until(text(part, MatchMode.CONTAINS))`: the raw accessibility text contains [part] (non-empty). */
    suspend fun textContains(part: String): Element = state(match(TextProperty.PROPERTY_TEXT, part, MatchMode.CONTAINS))

    /** Waits until exactly [expected] matches are visible. */
    suspend fun count(expected: Int): Element {
        var last: Int? = null
        device.awaitUntil("${selector.render()} count == $expected", timeout, observe = { "count=$last" }) {
            last = Element(device, selector).count()
            last == expected
        }
        return Element(device, selector)
    }

    private suspend fun deviceWait(
        operation: String,
        description: String,
        // A state wait's condition, for its timeout report.
        condition: Node? = null,
        build: Command.Builder.() -> Unit,
    ) {
        val result = device.execute(timeout, build)
        if (!result.hasError()) return
        if (result.error.code == ErrorCodeProto.ERR_WAIT_TIMEOUT) {
            throw WaitTimeoutException.of(result, description, device.serial, condition = condition, selectors = listOf(selector.proto))
        }
        throw CommandException(result, operation, device.serial, selector.render(), listOf(selector.proto))
    }

    private suspend fun state(condition: Node): Element {
        deviceWait("wait_state", "${selector.render()} to satisfy ${describe(condition)}", condition) {
            waitState = WaitState.newBuilder().setSelector(selector.proto).setCondition(condition).build()
        }
        return Element(device, selector)
    }
}
