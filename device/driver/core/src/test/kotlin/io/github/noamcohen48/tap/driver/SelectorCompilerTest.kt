package io.github.noamcohen48.tap.driver

import io.github.noamcohen48.tap.api.v1.ErrorCode
import io.github.noamcohen48.tap.api.v1.MatchMode
import io.github.noamcohen48.tap.api.v1.NodeFlag
import io.github.noamcohen48.tap.protocol.ErrorDetail
import io.github.noamcohen48.tap.protocol.InvalidCommandException
import io.github.noamcohen48.tap.protocol.Nodes
import io.github.noamcohen48.tap.protocol.and
import io.github.noamcohen48.tap.protocol.inAnyWindow
import io.github.noamcohen48.tap.protocol.inPackage
import io.github.noamcohen48.tap.protocol.or
import io.github.noamcohen48.tap.protocol.pickAt
import io.github.noamcohen48.tap.protocol.toSelector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SelectorCompilerTest {
    private val compiler = SelectorCompiler(AUT)

    @Test
    fun plainPredicatesCompileToOneNativeSelectorScopedToTheAut() {
        val compiled = compiler.compile((Nodes.text("OK") and Nodes.flag(NodeFlag.FLAG_ENABLED)).toSelector())

        val native = compiled as CompiledSelector.Native
        assertEquals(SearchScope.FocusedWindow(AUT), native.scope)
        val by = native.by.toString()
        assertTrue(by, by.contains("PKG='\\Q$AUT\\E'"))
        assertTrue(by, by.contains("TEXT='\\QOK\\E'"))
        assertTrue(by, by.contains("ENABLED='true'"))
    }

    @Test
    fun autResourceIsQualifiedWithTheSessionAut() {
        val native = compiler.compile(Nodes.autResource("submit").toSelector()) as CompiledSelector.Native

        assertTrue(native.by.toString(), native.by.toString().contains("RES='\\Q$AUT:id/submit\\E'"))
    }

    @Test
    fun regexAndAnyOfNeedTheTraversalPlan() {
        assertTrue(compiler.compile(Nodes.text("a.*", MatchMode.MATCH_REGEX).toSelector()) is CompiledSelector.Traversal)
        assertTrue(compiler.compile((Nodes.text("a") or Nodes.text("b")).toSelector()) is CompiledSelector.Traversal)
    }

    @Test
    fun repeatedSingleValuedConstraintNeedsTheTraversalPlan() {
        val selector = (Nodes.text("a", MatchMode.MATCH_CONTAINS) and Nodes.text("b", MatchMode.MATCH_CONTAINS)).toSelector()

        assertTrue(compiler.compile(selector) is CompiledSelector.Traversal)
    }

    @Test
    fun anyPackageBecomesTheScope() {
        val compiled = compiler.compile(Nodes.text("Allow").toSelector().inPackage(SYSTEM_UI)) as CompiledSelector.Native

        assertEquals(SearchScope.FocusedWindow(SYSTEM_UI), compiled.scope)
        assertTrue(compiled.by.toString(), compiled.by.toString().contains("PKG='\\Q$SYSTEM_UI\\E'"))
    }

    @Test
    fun anotherPackageMayNameItsOwnResources() {
        val selector = Nodes.androidResource(SYSTEM_UI, "button").toSelector().inPackage(SYSTEM_UI)

        assertEquals(SearchScope.FocusedWindow(SYSTEM_UI), compiler.compile(selector).scope)
    }

    @Test
    fun anyWindowScopeSearchesEveryPackage() {
        val native = compiler.compile(Nodes.text("OK").toSelector().inAnyWindow()) as CompiledSelector.Native

        assertEquals(SearchScope.AllWindows, native.scope)
        assertTrue(native.by.toString(), !native.by.toString().contains("PKG="))
        val traversal = compiler.compile((Nodes.text("a") or Nodes.text("b")).toSelector().inAnyWindow())
        assertEquals(SearchScope.AllWindows, traversal.scope)
    }

    @Test
    fun autScopedSelectorCannotNameAnotherAppsResourceAtAnyDepth() {
        val nested = Nodes.text("x") and Nodes.parent(Nodes.androidResource("com.other", "list"))

        assertScopeDenied { compiler.compile(nested.toSelector()) }
    }

    @Test
    fun pickIsCarriedByTheCompiledSelector() {
        val compiled = compiler.compile(Nodes.text("row").toSelector().pickAt(2))

        assertEquals(SelectorPick.Outcome.Chosen(2), compiled.pick.choose(3))
    }

    private fun assertScopeDenied(block: () -> Unit) {
        try {
            block()
            fail("expected SCOPE_DENIED")
        } catch (denied: InvalidCommandException) {
            assertEquals(ErrorCode.ERR_INVALID_SELECTOR, denied.code)
            assertEquals(ErrorDetail.SCOPE_DENIED, denied.detail)
        }
    }

    private companion object {
        const val AUT = "com.example.app"
        const val SYSTEM_UI = "com.android.systemui"
    }
}
