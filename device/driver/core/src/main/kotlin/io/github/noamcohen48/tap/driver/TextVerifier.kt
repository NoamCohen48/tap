package io.github.noamcohen48.tap.driver

/**
 * The rules text-input commands verify their effect by. Pure, so they are unit tested off the
 * device; the commands supply the live reads.
 */
internal object TextVerifier {
    /** Poll interval while waiting for the edited text to show. */
    const val POLL_MS = 25L

    /** Upper bound for `set_text` / `clear_text` verification after the text was set. */
    const val EDIT_VERIFY_MS = 1_000L

    /**
     * The text a user sees. An empty `EditText` reports its hint as `text` (with
     * `isShowingHintText` set) on API 26+, which would make a successful clear look like a
     * mismatch and leak the hint into snapshots, so a shown hint is no text at all.
     */
    fun displayedText(
        text: CharSequence?,
        showingHintText: Boolean,
    ): String? = if (showingHintText) null else text?.toString()

    /** `type_text` appends to what the field showed before the focusing click. */
    fun expectedAfterTyping(
        initial: String,
        typed: String,
    ): String = initial + typed

    /**
     * Polls [read] until it returns [expected] or [deadlineMs] passes; true when it matched. A
     * null read (the target did not resolve) never matches. Reads at least once.
     */
    inline fun awaitText(
        expected: String,
        deadlineMs: Long,
        now: () -> Long,
        sleep: (Long) -> Unit,
        read: () -> String?,
    ): Boolean {
        do {
            if (read() == expected) return true
            val remaining = deadlineMs - now()
            if (remaining > 0) sleep(minOf(POLL_MS, remaining))
        } while (now() < deadlineMs)
        return false
    }
}
