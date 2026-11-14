package io.github.noamcohen48.tap.protocol

import io.github.noamcohen48.tap.api.v1.StandardAction

/** Android API requirements shared by device execution and host capability discovery. */
object OperationRequirements {
    const val IME_ACTION_API = 30
    const val PAGE_ACTION_API = 29
    const val PRESS_AND_HOLD_API = 30
    const val RECORD_AUDIO_API = 30
    const val RECORD_PLAYBACK_API = 33

    /** The extra Android requirement of a standard action, or null for baseline actions. */
    fun actionApi(action: StandardAction): Int? =
        when (action) {
            StandardAction.A11Y_PAGE_UP, StandardAction.A11Y_PAGE_DOWN,
            StandardAction.A11Y_PAGE_LEFT, StandardAction.A11Y_PAGE_RIGHT,
            -> PAGE_ACTION_API

            StandardAction.A11Y_PRESS_AND_HOLD -> PRESS_AND_HOLD_API

            else -> null
        }
}
