package io.github.noamcohen48.tap.driver

import androidx.test.uiautomator.UiObject2
import io.github.noamcohen48.tap.api.v1.Direction
import io.github.noamcohen48.tap.api.v1.ErrorCode
import io.github.noamcohen48.tap.api.v1.ScrollUntil
import io.github.noamcohen48.tap.driver.engine.CommandContext
import io.github.noamcohen48.tap.protocol.CommandFailure
import io.github.noamcohen48.tap.protocol.DEFAULT_GESTURE_PERCENT
import io.github.noamcohen48.tap.protocol.DEFAULT_MAX_SCROLLS
import io.github.noamcohen48.tap.protocol.ErrorDetail

/**
 * `scroll_until`: scrolls a container until a target appears among its descendants. The gate is
 * passed once, before the first gesture; from then on every exit reports that input was sent.
 */
internal class ScrollUntilCommand(
    private val objects: UiObjectAccess,
) {
    /** Defaults: direction DOWN, [DEFAULT_GESTURE_PERCENT] per scroll, [DEFAULT_MAX_SCROLLS] scrolls. */
    fun scrollUntil(
        context: CommandContext,
        command: ScrollUntil,
        target: CompiledSelector,
        container: CompiledSelector,
    ) {
        val direction = uiDirection(if (command.direction == Direction.DIR_UNSPECIFIED) Direction.DIR_DOWN else command.direction)
        val percent = fraction(if (command.hasDistancePercent()) command.distancePercent else DEFAULT_GESTURE_PERCENT)
        val maxScrolls = if (command.hasMaxScrolls()) command.maxScrolls else DEFAULT_MAX_SCROLLS
        var noProgressAttempts = 0

        repeat(maxScrolls) {
            context.checkCancelled()
            if (context.isExpired()) throw waitTimeout()
            val resolved = objects.resolve(container)
            val scrollable =
                resolved.element ?: throw if (context.mutationStarted) {
                    // Once a gesture was sent, losing the container is a stale target, never a
                    // "nothing happened" NOT_FOUND/AMBIGUOUS.
                    staleTarget(resolved, "Scroll container was not uniquely resolved after scrolling")
                } else {
                    CommandFailure(requireNotNull(resolved.errorCode), message = "Scroll container was not uniquely resolved")
                }
            val before =
                try {
                    if (objects.containerHasObject(scrollable, target)) return
                    val fingerprint = visibleFingerprint(scrollable)
                    if (context.isExpired()) throw waitTimeout()
                    // Gate once, before the first gesture: from then on the command is definitive,
                    // later cancels are ignored and every exit reports that input was sent (the
                    // pipeline rewrites non-mutating codes to INDETERMINATE).
                    if (!context.mutationStarted) context.markMutationStarted()
                    scrollable.scroll(direction, percent)
                    fingerprint
                } finally {
                    scrollable.recycle()
                }
            val remaining = context.remainingMs()
            if (remaining <= 0) throw waitTimeout()
            Thread.sleep(minOf(SETTLE_MS, remaining))
            val afterResolution = objects.resolve(container)
            val after =
                afterResolution.element
                    ?: throw staleTarget(afterResolution, "Scroll container was not uniquely resolved after scrolling")
            val (found, afterFingerprint) =
                try {
                    objects.containerHasObject(after, target) to visibleFingerprint(after)
                } finally {
                    after.recycle()
                }
            if (found) return
            noProgressAttempts = if (afterFingerprint == before) noProgressAttempts + 1 else 0
            if (noProgressAttempts >= 2) throw CommandFailure(ErrorCode.ERR_NOT_FOUND, detail = ErrorDetail.END_REACHED)
        }

        if (context.isExpired()) throw waitTimeout()
        val finalResolution = objects.resolve(container)
        val finalContainer =
            finalResolution.element
                ?: throw staleTarget(finalResolution, "Scroll container was not uniquely resolved after the final attempt")
        val found =
            try {
                objects.containerHasObject(finalContainer, target)
            } finally {
                finalContainer.recycle()
            }
        if (!found) throw CommandFailure(ErrorCode.ERR_NOT_FOUND, detail = ErrorDetail.MAX_SCROLLS)
    }

    private fun waitTimeout(): CommandFailure = CommandFailure(ErrorCode.ERR_WAIT_TIMEOUT)

    /** What the container shows, to tell a scroll that moved from one at the end of the content. */
    private fun visibleFingerprint(root: UiObject2): String =
        buildString {
            fun appendNode(
                node: UiObject2,
                depth: Int,
            ) {
                if (depth > MAX_FINGERPRINT_DEPTH) return
                append(node.className).append('|')
                append(node.resourceName).append('|')
                append(node.text).append('|')
                append(node.contentDescription).append(';')
                node.children.forEach { child ->
                    try {
                        appendNode(child, depth + 1)
                    } finally {
                        child.recycle()
                    }
                }
            }
            appendNode(root, 0)
        }

    private companion object {
        /** Pause after each scroll before the container is read again. */
        const val SETTLE_MS = 100L
        const val MAX_FINGERPRINT_DEPTH = 32
    }
}
