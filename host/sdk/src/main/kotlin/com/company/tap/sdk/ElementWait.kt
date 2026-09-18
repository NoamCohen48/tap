package com.company.tap.sdk

import com.company.tap.host.RemoteCommandException
import com.company.tap.host.render
import com.company.tap.protocol.ElementSnapshot
import com.company.tap.protocol.ErrorCode
import com.company.tap.protocol.Operation
import com.company.tap.protocol.Selector
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
        deviceWait(Operation.WAIT_VISIBLE, "${selector.render()} to be visible")
        return Element(device, selector)
    }

    /** Waits until no match exists. */
    fun gone() {
        deviceWait(Operation.WAIT_GONE, "${selector.render()} to be gone")
    }

    fun enabled(): Element = property("enabled") { it.enabled }
    fun disabled(): Element = property("disabled") { !it.enabled }
    fun checked(): Element = property("checked") { it.checked }
    fun unchecked(): Element = property("unchecked") { !it.checked }
    fun focused(): Element = property("focused") { it.focused }
    fun textEquals(expected: String): Element = property("text == \"$expected\"") { it.text == expected }
    fun textContains(part: String): Element = property("text containing \"$part\"") { it.text?.contains(part) == true }

    /** Waits until exactly [expected] matches are visible. */
    fun count(expected: Int): Element {
        var last: Int? = null
        device.awaitUntil(
            "${selector.render()} count == $expected",
            timeout,
            observe = { "count=$last" },
        ) {
            last = Element(device, selector).count()
            last == expected
        }
        return Element(device, selector)
    }

    private fun deviceWait(operation: Operation, description: String) {
        val command = device.client.submit(operation, selector, timeoutMs = timeout.inWholeMilliseconds)
        val response = command.await()
        if (response.ok) return
        if (response.errorCode == ErrorCode.WAIT_TIMEOUT) {
            throw WaitTimeoutException(description, device.serial, selector, response.durationMs, 0, null)
        }
        command.awaitOrThrow() // the terminal response is cached; this converts it to the typed exception
    }

    private fun property(description: String, predicate: (ElementSnapshot) -> Boolean): Element {
        val element = Element(device, selector)
        var last: String? = null
        device.awaitUntil("${selector.render()} to be $description", timeout, observe = { last }) {
            val snapshot = try {
                element.snapshot()
            } catch (e: RemoteCommandException) {
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
