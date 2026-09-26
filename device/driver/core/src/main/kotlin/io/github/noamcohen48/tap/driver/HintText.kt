package io.github.noamcohen48.tap.driver

/** What a user sees as a node's text. Pure, so it is unit tested off the device. */
internal object HintText {
    /**
     * An empty `EditText` reports its hint as `text` (with `isShowingHintText` set) on API 26+,
     * which would leak the hint into snapshots, so a shown hint is no text at all.
     */
    fun displayedText(
        text: CharSequence?,
        showingHintText: Boolean,
    ): String? = if (showingHintText) null else text?.toString()
}
