package io.github.noamcohen48.tap.sdk

import io.github.noamcohen48.tap.api.v1.Command
import io.github.noamcohen48.tap.api.v1.ErrorCode as ErrorCodeProto
import io.github.noamcohen48.tap.api.v1.WaitGone
import io.github.noamcohen48.tap.api.v1.WaitVisible
import kotlin.time.Duration

/**
 * Wait builder returned by [Device.await] / [Element.await]. [visible], [one] and [gone] poll on the
 * device in a single RPC; property waits poll snapshots from the host with [delay]-based
 * polling, so test-root cancellation and sibling failure cancel them promptly.
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

    /** Wait until the one matching node is enabled. */
    suspend fun enabled(): Element = property("enabled") { it.enabled }

    /** Wait until the one matching node is disabled. */
    suspend fun disabled(): Element = property("disabled") { !it.enabled }

    /** Wait until the one matching node is checked. */
    suspend fun checked(): Element = property("checked") { it.checked }

    /** Wait until the one matching node is unchecked. */
    suspend fun unchecked(): Element = property("unchecked") { !it.checked }

    /** Wait until the one matching node has focus. */
    suspend fun focused(): Element = property("focused") { it.focused }

    /** Wait until the one matching node's text equals [expected]. */
    suspend fun textEquals(expected: String): Element = property("text == \"$expected\"") { it.text == expected }

    /** Wait until the one matching node's text contains [part]. */
    suspend fun textContains(part: String): Element = property("text containing \"$part\"") { it.text?.contains(part) == true }

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
        build: Command.Builder.() -> Unit,
    ) {
        val result = device.execute(timeout, build)
        if (!result.hasError()) return
        if (result.error.code == ErrorCodeProto.ERR_WAIT_TIMEOUT) {
            throw WaitTimeoutException.of(result, description, device.serial)
        }
        throw CommandException(result, operation, device.serial, selector.render())
    }

    private suspend fun property(
        description: String,
        predicate: (ElementSnapshot) -> Boolean,
    ): Element {
        val element = Element(device, selector)
        var last: String? = null
        device.awaitUntil("${selector.render()} to be $description", timeout, observe = { last }) {
            val snapshot =
                try {
                    element.snapshot()
                } catch (e: CommandException) {
                    if (e.code == ErrorCode.NOT_FOUND) {
                        last = "not found"
                        return@awaitUntil false
                    }
                    throw e
                }
            last = "text=${snapshot.text} enabled=${snapshot.enabled} checked=${snapshot.checked} focused=${snapshot.focused}"
            predicate(snapshot)
        }
        return element
    }
}
