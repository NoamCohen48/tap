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
        assertEquals("""{"node":{"kind":"match","property":"TEXT","value":"OK"}}""", json.encodeToString(Selector.text("OK")))
        assertEquals(
            """{"node":{"kind":"resource","name":"view_button","packageName":"com.app"}}""",
            json.encodeToString(Selector.androidResource("com.app", "view_button")),
        )
        assertEquals(
            """{"node":{"kind":"match","property":"TEXT","value":"Allow"},"scope":{"kind":"system","packageName":"com.perm"}}""",
            json.encodeToString(Selector.text("Allow").inSystemPackage("com.perm")),
        )
        assertEquals(
            """{"node":{"kind":"match","property":"TEXT","value":"Allow"},"pick":{"kind":"at","index":2}}""",
            json.encodeToString(Selector.text("Allow").at(2)),
        )
        assertEquals("""{"node":{"kind":"flag","property":"CLICKABLE"},"pick":{"kind":"first"}}""", json.encodeToString(Selector(Node.Flag(NodeFlag.CLICKABLE)).first()))
    }

    @Test
    fun relationalSelectorRoundTrips() {
        val selector = Selector(
            Node.allOf(
                Node.className("android.widget.Button", MatchMode.ENDS_WITH),
                Node.Flag(NodeFlag.CLICKABLE),
                Node.ancestor(Node.Resource("card", "com.app")),
                Node.child(Node.text("Play", MatchMode.STARTS_WITH)),
            ),
        )
        assertEquals(selector, json.decodeFromString<Selector>(json.encodeToString(selector)))
        assertEquals(SelectorPlanKind.NATIVE, CommandValidation.validateSelector(selector))
    }

    @Test
    fun combinatorsNormalise() {
        val a = Node.text("a")
        val b = Node.text("b")
        val c = Node.text("c")
        assertEquals(a, Node.allOf(a))
        assertEquals(Node.AllOf(listOf(a, b, c)), (a and b) and c)
        assertEquals(Node.AnyOf(listOf(a, b, c)), a or (b or c))
        assertEquals(Node.AllOf(listOf(Node.AnyOf(listOf(a, b)), c)), (a or b) and c)
        assertFailsWith<IllegalArgumentException> { Node.allOf(emptyList()) }
    }

    @Test
    fun choosesTraversalWhenBySelectorCannotExpressIt() {
        val nested = Selector(Node.descendant(Node.text("^Item \\d+$", MatchMode.REGEX)))
        assertEquals(SelectorPlanKind.TRAVERSAL, CommandValidation.validateSelector(nested))
        assertEquals(SelectorPlanKind.NATIVE, CommandValidation.validateSelector(Selector.text("Item 1", MatchMode.CONTAINS)))

        assertEquals(SelectorPlanKind.TRAVERSAL, CommandValidation.validateSelector(Selector(Node.text("Yes") or Node.text("OK"))))
        assertEquals(SelectorPlanKind.TRAVERSAL, CommandValidation.validateSelector(Selector(Node.child(Node.text("Yes") or Node.text("OK")))))

        // Two constraints on one text property, or two parents/ancestors, exceed BySelector's single slots.
        val twoTexts = Node.text("Item", MatchMode.STARTS_WITH) and Node.text("9", MatchMode.ENDS_WITH)
        assertEquals(SelectorPlanKind.TRAVERSAL, CommandValidation.validateSelector(Selector(twoTexts)))
        val nestedTwoTexts = Node.Flag(NodeFlag.CLICKABLE) and Node.AllOf(listOf(Node.text("a"), Node.AllOf(listOf(Node.text("b"), Node.hint("h")))))
        assertEquals(SelectorPlanKind.TRAVERSAL, CommandValidation.validateSelector(Selector(nestedTwoTexts)))
        val twoAncestors = Node.ancestor(Node.Resource("list")) and Node.ancestor(Node.Resource("row"))
        assertEquals(SelectorPlanKind.TRAVERSAL, CommandValidation.validateSelector(Selector(twoAncestors)))
        // Children and descendants are lists in BySelector, so several stay native.
        val twoChildren = Node.child(Node.text("a")) and Node.child(Node.text("b")) and Node.descendant(Node.text("c")) and Node.descendant(Node.text("d"))
        assertEquals(SelectorPlanKind.NATIVE, CommandValidation.validateSelector(Selector(twoChildren)))
        // The same text property at different levels is fine.
        val textInChild = Node.text("a") and Node.child(Node.text("b"))
        assertEquals(SelectorPlanKind.NATIVE, CommandValidation.validateSelector(Selector(textInChild)))
    }

    @Test
    fun rejectsRe2UnsupportedRegex() {
        assertDetail(ErrorDetail.INVALID_REGEX, Selector.text("(a)\\1", MatchMode.REGEX))
        assertDetail(ErrorDetail.INVALID_REGEX, Selector.text("(?<=a)b", MatchMode.REGEX))
        assertDetail(ErrorDetail.INVALID_REGEX, Selector.text("[", MatchMode.REGEX))
    }

    @Test
    fun enforcesStructuralLimits() {
        var deep: Node = Node.text("leaf")
        repeat(MAX_SELECTOR_DEPTH) { deep = Node.child(deep) }
        assertDetail(ErrorDetail.SELECTOR_TOO_DEEP, Selector(deep))

        fun tree(depth: Int): Node =
            if (depth == 0) Node.text("leaf")
            else Node.AllOf(listOf(Node.child(tree(depth - 1)), Node.descendant(tree(depth - 1))))
        val wide = tree(7) // 381 nodes, depth 15
        assertDetail(ErrorDetail.SELECTOR_TOO_LARGE, Selector(wide))

        assertDetail(ErrorDetail.EMPTY_NODE, Selector(Node.AllOf(emptyList())))
        assertDetail(ErrorDetail.EMPTY_NODE, Selector(Node.AllOf(listOf(Node.text("x")))))
        assertDetail(ErrorDetail.EMPTY_NODE, Selector(Node.child(Node.AnyOf(listOf(Node.text("x"))))))
        assertDetail(ErrorDetail.EMPTY_VALUE, Selector.rawResource(""))
        assertDetail(ErrorDetail.EMPTY_VALUE, Selector(Node.Resource("x", "")))
        assertDetail(ErrorDetail.STRING_TOO_LONG, Selector.text("x".repeat(MAX_SELECTOR_STRING_CHARS + 1)))
    }

    @Test
    fun commandValidationChecksEverySelectorCarriedByTheCommand() {
        CommandValidation.validate(Health)
        CommandValidation.validate(Tap(Selector.text("Save")))

        val invalidTarget =
            assertFailsWith<InvalidSelectorException> {
                CommandValidation.validate(Tap(Selector.rawResource("")))
            }
        assertEquals(ErrorDetail.EMPTY_VALUE, invalidTarget.detail)

        val invalidContainer =
            assertFailsWith<InvalidSelectorException> {
                CommandValidation.validate(
                    ScrollUntil(
                        selector = Selector.text("Item"),
                        container = Selector.rawResource(""),
                    ),
                )
            }
        assertEquals(ErrorDetail.EMPTY_VALUE, invalidContainer.detail)
    }

    @Test
    fun malformedScopeAndPickAreUnconstructible() {
        assertFailsWith<IllegalArgumentException> { Scope.System("") }
        assertFailsWith<IllegalArgumentException> { Selector.text("x").at(-1) }
        assertFailsWith<IllegalArgumentException> {
            json.decodeFromString<Selector>("""{"node":{"kind":"match","property":"TEXT","value":"x"},"pick":{"kind":"at","index":-1}}""")
        }
        assertEquals(SelectorPlanKind.NATIVE, CommandValidation.validateSelector(Selector.text("x").first()))
        assertEquals(SelectorPlanKind.NATIVE, CommandValidation.validateSelector(Selector.text("x").at(2)))
    }

    private fun assertDetail(detail: String, selector: Selector) {
        val error = assertFailsWith<InvalidSelectorException> { CommandValidation.validateSelector(selector) }
        assertEquals(detail, error.detail, error.message)
    }
}
