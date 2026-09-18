package com.company.tap.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class SelectorTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun factoriesEncodeMinimalJson() {
        assertEquals("""{"node":{"text":{"value":"OK"}}}""", json.encodeToString(Selector.text("OK")))
        assertEquals(
            """{"node":{"resource":{"name":"view_button","packageName":"com.app"}}}""",
            json.encodeToString(Selector.androidResource("com.app", "view_button")),
        )
        assertEquals(
            """{"node":{"text":{"value":"Allow"}},"scope":"SYSTEM","scopePackage":"com.perm"}""",
            json.encodeToString(Selector.text("Allow").inSystemPackage("com.perm")),
        )
    }

    @Test
    fun relationalSelectorRoundTrips() {
        val selector = Selector(
            NodeSelector(
                className = StringMatch("android.widget.Button", MatchMode.ENDS_WITH),
                clickable = true,
                ancestor = NodeSelector(resource = ResourceId("card", "com.app")),
                child = NodeSelector(text = StringMatch("Play", MatchMode.STARTS_WITH)),
            ),
        )
        assertEquals(selector, json.decodeFromString<Selector>(json.encodeToString(selector)))
        assertEquals(SelectorPlanKind.NATIVE, SelectorValidation.validate(selector))
    }

    @Test
    fun regexAnywhereSelectsTraversalPlan() {
        val nested = Selector(NodeSelector(descendant = NodeSelector(text = StringMatch("^Item \\d+$", MatchMode.REGEX))))
        assertEquals(SelectorPlanKind.TRAVERSAL, SelectorValidation.validate(nested))
        assertEquals(SelectorPlanKind.NATIVE, SelectorValidation.validate(Selector.text("Item 1", MatchMode.CONTAINS)))
    }

    @Test
    fun rejectsRe2UnsupportedRegex() {
        assertDetail(ErrorDetail.INVALID_REGEX, Selector.text("(a)\\1", MatchMode.REGEX))
        assertDetail(ErrorDetail.INVALID_REGEX, Selector.text("(?<=a)b", MatchMode.REGEX))
        assertDetail(ErrorDetail.INVALID_REGEX, Selector.text("[", MatchMode.REGEX))
    }

    @Test
    fun enforcesStructuralLimits() {
        var deep = NodeSelector(text = StringMatch("leaf"))
        repeat(MAX_SELECTOR_DEPTH) { deep = NodeSelector(child = deep) }
        assertDetail(ErrorDetail.SELECTOR_TOO_DEEP, Selector(deep))

        fun tree(depth: Int): NodeSelector =
            if (depth == 0) NodeSelector(text = StringMatch("leaf"))
            else NodeSelector(text = StringMatch("t"), child = tree(depth - 1), descendant = tree(depth - 1))
        val wide = tree(8) // 511 nodes, depth 9
        assertDetail(ErrorDetail.SELECTOR_TOO_LARGE, Selector(wide))

        assertDetail(ErrorDetail.EMPTY_NODE, Selector(NodeSelector()))
        assertDetail(ErrorDetail.EMPTY_NODE, Selector(NodeSelector(text = StringMatch("x"), child = NodeSelector())))
        assertDetail(ErrorDetail.EMPTY_VALUE, Selector.rawResource(""))
        assertDetail(ErrorDetail.STRING_TOO_LONG, Selector.text("x".repeat(MAX_SELECTOR_STRING_CHARS + 1)))
    }

    @Test
    fun enforcesScopeAndLimitShape() {
        assertDetail(ErrorDetail.SCOPE_PACKAGE_REQUIRED, Selector.text("x").copy(scope = TargetScope.SYSTEM))
        assertDetail(ErrorDetail.SCOPE_PACKAGE_UNEXPECTED, Selector.text("x").copy(scopePackage = "com.other"))
        assertDetail(ErrorDetail.INDEX_UNEXPECTED, Selector.text("x").copy(index = 1))
        assertDetail(ErrorDetail.INDEX_REQUIRED, Selector.text("x").copy(limit = MatchLimit.AT, acceptAccessibilityOrder = true))
        assertDetail(ErrorDetail.INDEX_REQUIRED, Selector.text("x").at(-1))
        assertDetail(ErrorDetail.ORDER_NOT_ACCEPTED, Selector.text("x").copy(limit = MatchLimit.FIRST))
        assertEquals(SelectorPlanKind.NATIVE, SelectorValidation.validate(Selector.text("x").first()))
        assertEquals(SelectorPlanKind.NATIVE, SelectorValidation.validate(Selector.text("x").at(2)))
    }

    private fun assertDetail(detail: String, selector: Selector) {
        val error = assertFailsWith<InvalidSelectorException> { SelectorValidation.validate(selector) }
        assertEquals(detail, error.detail, error.message)
    }
}
