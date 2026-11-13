package io.github.noamcohen48.tap.host

/** Android's device-wide developer-option overlays, independent of a Tap session. */
data class TouchIndicatorState(
    val showTaps: Boolean,
    val pointerLocation: Boolean,
)
