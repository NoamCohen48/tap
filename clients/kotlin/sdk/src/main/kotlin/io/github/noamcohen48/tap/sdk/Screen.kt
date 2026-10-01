package io.github.noamcohen48.tap.sdk

import kotlin.time.Duration

/**
 * Whatever is visible on a device: every window, of any app or the system UI.
 *
 * `device.screen` adds no package predicate, so a selector matches in any window: a permission
 * dialog, the notification shade, a second app. Use [Device.app] when the node must belong to
 * one app.
 *
 * A node another window (the keyboard, a dialog) covers completely is not found; a gesture on a
 * node it covers partly, with the touch point underneath, fails with [CommandException]
 * `NOT_INTERACTABLE` / `OBSCURED` before any input.
 *
 * ```kotlin
 * device.screen.await(text("Allow")).visible()
 * device.screen.element(text("Allow")).tap()
 * ```
 */
class Screen internal constructor(
    val device: Device,
) {
    /** A lazy element: [selector] as is, matched in every visible window. Performs no I/O. */
    fun element(selector: Selector): Element = Element(device, selector)

    /** A wait on [selector] in every visible window. */
    fun await(
        selector: Selector,
        timeout: Duration = device.timeouts.wait,
    ): ElementWait = ElementWait(device, selector, timeout)

    override fun toString(): String = "Screen(${device.serial})"
}
