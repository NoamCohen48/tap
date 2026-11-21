package io.github.noamcohen48.tap.driver

import android.app.UiAutomation
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import io.github.noamcohen48.tap.api.v1.ErrorCode
import io.github.noamcohen48.tap.protocol.ErrorDetail
import io.github.noamcohen48.tap.protocol.CommandFailure
import io.github.noamcohen48.tap.protocol.KeyStep

/**
 * Injects a [KeyStep] plan as a virtual keyboard would, sleeping until each step is due. A
 * repeat keeps its press's down time and carries `FLAG_LONG_PRESS` when marked, as Android's own
 * key repeat does (Appium's long key press sends the same repeat). A failure after the first
 * down releases the key still down; a release that fails is `INDETERMINATE` (the key may be stuck).
 */
internal class KeyPresses(
    private val uiAutomation: () -> UiAutomation,
) {
    fun press(
        keyCode: Int,
        plan: List<KeyStep>,
    ) {
        val start = SystemClock.uptimeMillis()
        var downTime: Long? = null
        var sent = false
        try {
            for (step in plan) {
                val due = start + step.atMs
                val wait = due - SystemClock.uptimeMillis()
                if (wait > 0) SystemClock.sleep(wait)
                val now = SystemClock.uptimeMillis()
                val pressedAt = if (step.down && step.repeat == 0) now else downTime ?: now
                val event =
                    KeyEvent(
                        pressedAt,
                        now,
                        if (step.down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP,
                        keyCode,
                        step.repeat,
                        0,
                        KeyCharacterMap.VIRTUAL_KEYBOARD,
                        0,
                        if (step.longPress) KeyEvent.FLAG_LONG_PRESS else 0,
                        InputDevice.SOURCE_KEYBOARD,
                    )
                if (!uiAutomation().injectInputEvent(event, true)) {
                    throw CommandFailure(
                        ErrorCode.ERR_ACTION_REJECTED,
                        if (sent) ErrorDetail.PARTIAL_INPUT else null,
                        "Key $keyCode was not injected",
                    )
                }
                sent = true
                downTime = if (step.down) pressedAt else null
            }
        } finally {
            val held = downTime
            if (held != null) {
                val release = KeyEvent(held, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, keyCode, 0, 0,
                    KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD)
                if (!uiAutomation().injectInputEvent(release, true)) {
                    @Suppress("ThrowFromFinallyBlock")
                    throw CommandFailure(ErrorCode.ERR_INDETERMINATE, detail = ErrorDetail.KEY_RELEASE_FAILED)
                }
            }
        }
    }
}
