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
 * Keys `type_text` has pressed but not yet released, with the time each went down. Whatever
 * happens mid-sequence (a rejected event, the deadline), every key still down is released
 * before the command returns, so no key is left stuck on the device.
 */
internal class PressedKeys {
    private val down = linkedMapOf<Int, Long>()

    val isEmpty: Boolean get() = down.isEmpty()

    /**
     * The down time a key event with [action] for [keyCode] carries when injected at [now]: a
     * press starts a gesture at [now]; a release belongs to the press it ends.
     */
    fun downTimeFor(
        action: Int,
        keyCode: Int,
        now: Long,
    ): Long = if (action == KeyEvent.ACTION_UP) down[keyCode] ?: now else now

    /** Records one injection attempt of a key event with [action] (`KeyEvent.ACTION_*`). */
    fun record(
        action: Int,
        keyCode: Int,
        downTime: Long,
        injected: Boolean,
    ) {
        if (!injected) return
        when (action) {
            KeyEvent.ACTION_DOWN -> down[keyCode] = downTime
            KeyEvent.ACTION_UP -> down -= keyCode
        }
    }

    /**
     * Releases every key still down, trying each up to [attempts] times with [release]
     * (true = injected), which gets the key's down time. Returns false when some key could not
     * be released.
     */
    fun releaseAll(
        attempts: Int = RELEASE_ATTEMPTS,
        release: (keyCode: Int, downTime: Long) -> Boolean,
    ): Boolean {
        var allReleased = true
        for ((keyCode, downTime) in down.entries.toList()) {
            var released = false
            repeat(attempts) { if (!released) released = release(keyCode, downTime) }
            if (released) down -= keyCode else allReleased = false
        }
        return allReleased
    }

    private companion object {
        const val RELEASE_ATTEMPTS = 3
    }
}
