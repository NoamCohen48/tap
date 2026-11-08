package io.github.noamcohen48.tap.driver

import androidx.test.uiautomator.UiObject2
import io.github.noamcohen48.tap.api.v1.Node
import io.github.noamcohen48.tap.api.v1.Selector
import io.github.noamcohen48.tap.api.v1.SelectorNarrowing
import io.github.noamcohen48.tap.api.v1.TextProperty

/**
 * The device side of `Error.narrowing`: the lookups [SelectorNarrowingRules] counts with. Each
 * count is the ordinary capped lookup over every window, and a near miss reads one property of
 * the first nodes the other operands match. It runs only after a command has already failed,
 * never on the lookup path.
 */
internal class SelectorNarrowingReader(
    private val compiler: SelectorCompiler,
    private val objects: UiObjectAccess,
) {
    /** [SelectorNarrowingRules.explain] over the device's windows, timed by [nowMs]. */
    fun explain(
        selectors: List<Selector>,
        nowMs: () -> Long,
    ): SelectorNarrowing? = SelectorNarrowingRules(::count, ::nearMisses, nowMs).explain(selectors)

    private fun count(node: Node): Int? = objects.count(compile(node))

    /** Up to [MAX_NEAR_MISSES] distinct values [operand]'s property has on the nodes [rest] matches. */
    private fun nearMisses(
        operand: Node,
        rest: Node,
    ): List<String> {
        val read: (UiObject2) -> String? =
            when (operand.kindCase) {
                Node.KindCase.MATCH -> {
                    when (operand.match.property) {
                        // The raw accessibility text, as text selectors match it.
                        TextProperty.PROPERTY_TEXT -> { element -> element.accessibilityNodeInfo.text?.toString() }
                        TextProperty.PROPERTY_CONTENT_DESCRIPTION -> UiObject2::getContentDescription
                        TextProperty.PROPERTY_HINT -> UiObject2::getHint
                        TextProperty.PROPERTY_CLASS_NAME -> UiObject2::getClassName
                        TextProperty.PROPERTY_PACKAGE_NAME -> UiObject2::getApplicationPackage
                        else -> return emptyList()
                    }
                }

                Node.KindCase.RESOURCE -> UiObject2::getResourceName

                else -> return emptyList()
            }
        val elements = objects.matches(compile(rest), NEAR_MISS_SAMPLE)
        try {
            return elements
                .asSequence()
                .mapNotNull(read)
                .filter { it.isNotEmpty() }
                .map { if (it.length > MAX_NEAR_MISS_LENGTH) it.take(MAX_NEAR_MISS_LENGTH - 1) + "…" else it }
                .distinct()
                .take(MAX_NEAR_MISSES)
                .toList()
        } finally {
            elements.forEach(::recycleQuietly)
        }
    }

    private fun compile(node: Node): CompiledSelector = compiler.compile(Selector.newBuilder().setNode(node).build())

    private companion object {
        const val MAX_NEAR_MISSES = 3
        const val MAX_NEAR_MISS_LENGTH = 100
        const val NEAR_MISS_SAMPLE = 50
    }
}
