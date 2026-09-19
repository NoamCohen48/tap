package com.company.tap.sdk

import com.company.tap.api.v1.ElementSnapshot
import com.company.tap.api.v1.ErrorCode
import com.company.tap.api.v1.Operation
import kotlin.time.Duration

/**
 * Wait builder returned by [Device.await] / [Element.await]. [visible] and [gone] poll on the
 * device in a single RPC; property waits poll snapshots from the host.
 */
class ElementWait internal constructor(
    private val device: Device,
    private val selector: Selector,
    private val timeout: Duration,
) {
    /** Waits until at least one match exists; returns the lazy element. */
    fun visible(): Element {
        deviceWait(Operation.OP_WAIT_VISIBLE, "${selector.render()} to be visible")
        return Element(device, selector)
    }

    /** Waits until no match exists. */
    fun gone() {
        deviceWait(Operation.OP_WAIT_GONE, "${selector.render()} to be gone")
    }

    fun enabled(): Element = property("enabled") { it.enabled }
    fun disabled(): Element = property("disabled") { !it.enabled }
    fun checked(): Element = property("checked") { it.checked }
    fun unchecked(): Element = property("unchecked") { !it.checked }
    fun focused(): Element = property("focused") { it.focused }
    fun textEquals(expected: String): Element = property("text == \"$expected\"") { it.hasText() && it.text == expected }
    fun textContains(part: String): Element = property("text containing \"$part\"") { it.hasText() && part in it.text }

    /** Waits until exactly [expected] matches are visible. */
    fun count(expected: Int): Element {
        var last: Int? = null
        device.awaitUntil("${selector.render()} count == $expected", timeout, observe = { "count=$last" }) {
            last = Element(device, selector).count()
            last == expected
        }
        return Element(device, selector)
    }

    private fun deviceWait(operation: Operation, description: String) {
        val result = device.execute(operation, selector, timeout)
        if (result.ok) return
        if (result.errorCode == ErrorCode.ERR_WAIT_TIMEOUT) {
            throw WaitTimeoutException(description, device.serial, result.durationMs)
        }
        throw CommandException(result, operation.name.removePrefix("OP_"), device.serial, selector.render())
    }

    private fun property(description: String, predicate: (ElementSnapshot) -> Boolean): Element {
        val element = Element(device, selector)
        var last: String? = null
        device.awaitUntil("${selector.render()} to be $description", timeout, observe = { last }) {
            val snapshot = try {
                element.snapshot()
            } catch (e: CommandException) {
                if (e.code == ErrorCode.ERR_NOT_FOUND) {
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
