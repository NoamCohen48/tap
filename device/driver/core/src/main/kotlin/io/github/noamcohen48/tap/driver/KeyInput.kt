package io.github.noamcohen48.tap.driver

import android.view.KeyEvent
import io.github.noamcohen48.tap.protocol.KEYCODE_BACK
import io.github.noamcohen48.tap.protocol.KEYCODE_HOME

/**
 * How `press_key` injects a key code: BACK and HOME go through `UiDevice`'s dedicated calls
 * (they handle the system navigation quirks); everything else is a plain key code press.
 */
internal sealed interface KeyPress {
    data object Back : KeyPress

    data object Home : KeyPress

    data class Code(val keyCode: Int) : KeyPress

    companion object {
        fun of(keyCode: Int): KeyPress =
            when (keyCode) {
                KEYCODE_BACK -> Back
                KEYCODE_HOME -> Home
                else -> Code(keyCode)
            }
    }
}

/**
 * Keys `type_text` has pressed but not yet released. Whatever happens mid-sequence (a rejected
 * event, the deadline), every key still down is released before the command returns, so no
 * key is left stuck on the device.
 */
internal class PressedKeys {
    private val down = linkedSetOf<Int>()

    val isEmpty: Boolean get() = down.isEmpty()

    /** Records one injection attempt of a key event with [action] (`KeyEvent.ACTION_*`). */
    fun record(
        action: Int,
        keyCode: Int,
        injected: Boolean,
    ) {
        if (!injected) return
        when (action) {
            KeyEvent.ACTION_DOWN -> down += keyCode
            KeyEvent.ACTION_UP -> down -= keyCode
        }
    }

    /**
     * Releases every key still down, trying each up to [attempts] times with [release]
     * (true = injected). Returns false when some key could not be released.
     */
    fun releaseAll(
        attempts: Int = RELEASE_ATTEMPTS,
        release: (keyCode: Int) -> Boolean,
    ): Boolean {
        var allReleased = true
        for (keyCode in down.toList()) {
            var released = false
            repeat(attempts) { if (!released) released = release(keyCode) }
            if (released) down -= keyCode else allReleased = false
        }
        return allReleased
    }

    private companion object {
        const val RELEASE_ATTEMPTS = 3
    }
}
