package io.github.noamcohen48.tap.driver

import io.github.noamcohen48.tap.api.v1.ErrorCode
import io.github.noamcohen48.tap.api.v1.MatchMode
import io.github.noamcohen48.tap.api.v1.NodeFlag
import io.github.noamcohen48.tap.protocol.ErrorDetail
import io.github.noamcohen48.tap.protocol.InvalidCommandException
import io.github.noamcohen48.tap.protocol.Nodes
import io.github.noamcohen48.tap.protocol.and
import io.github.noamcohen48.tap.protocol.inSystemPackage
import io.github.noamcohen48.tap.protocol.or
import io.github.noamcohen48.tap.protocol.pickAt
import io.github.noamcohen48.tap.protocol.toSelector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SelectorCompilerTest {
    private val compiler = SelectorCompiler(AUT, setOf(SYSTEM_UI))

    @Test
    fun plainPredicatesCompileToOneNativeSelectorScopedToTheAut() {
        val compiled = compiler.compile((Nodes.text("OK") and Nodes.flag(NodeFlag.FLAG_ENABLED)).toSelector())

        val native = compiled as CompiledSelector.Native
        assertEquals(AUT, native.scopePackage)
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
    fun allowedSystemPackageBecomesTheScope() {
        val compiled = compiler.compile(Nodes.text("Allow").toSelector().inSystemPackage(SYSTEM_UI))

        assertEquals(SYSTEM_UI, compiled.scopePackage)
    }

    @Test
    fun systemPackageOffTheAllowlistIsScopeDenied() {
        assertScopeDenied { compiler.compile(Nodes.text("Allow").toSelector().inSystemPackage("com.evil")) }
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
