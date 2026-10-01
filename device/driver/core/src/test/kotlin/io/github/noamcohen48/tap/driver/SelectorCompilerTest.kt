package io.github.noamcohen48.tap.driver

import io.github.noamcohen48.tap.api.v1.MatchMode
import io.github.noamcohen48.tap.api.v1.NodeFlag
import io.github.noamcohen48.tap.api.v1.ResourceId
import io.github.noamcohen48.tap.api.v1.TextProperty
import io.github.noamcohen48.tap.protocol.Nodes
import io.github.noamcohen48.tap.protocol.and
import io.github.noamcohen48.tap.protocol.matchesId
import io.github.noamcohen48.tap.protocol.or
import io.github.noamcohen48.tap.protocol.pickAt
import io.github.noamcohen48.tap.protocol.toSelector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectorCompilerTest {
    private val compiler = SelectorCompiler()

    @Test
    fun plainPredicatesCompileToOneNativeSelector() {
        val compiled = compiler.compile((Nodes.text("OK") and Nodes.flag(NodeFlag.FLAG_ENABLED)).toSelector())

        val by = (compiled as CompiledSelector.Native).by.toString()
        assertTrue(by, by.contains("TEXT='\\QOK\\E'"))
        assertTrue(by, by.contains("ENABLED='true'"))
        assertTrue(by, !by.contains("PKG="))
    }

    @Test
    fun packagePredicateIsANativePackageMatch() {
        val exact = compiler.compile((Nodes.text("Allow") and Nodes.packageName(SYSTEM_UI)).toSelector()) as CompiledSelector.Native
        assertTrue(exact.by.toString(), exact.by.toString().contains("PKG='\\Q$SYSTEM_UI\\E'"))

        val prefix = compiler.compile(Nodes.match(TextProperty.PROPERTY_PACKAGE_NAME, "com.android.", MatchMode.MATCH_STARTS_WITH).toSelector())
        assertTrue(prefix is CompiledSelector.Native)
    }

    @Test
    fun qualifiedResourceMatchesOnlyThatPackage() {
        val native = compiler.compile(Nodes.androidResource(AUT, "submit").toSelector()) as CompiledSelector.Native

        assertTrue(native.by.toString(), native.by.toString().contains("RES='\\Q$AUT:id/submit\\E'"))
    }

    @Test
    fun packageLessResourceIsNativeAndAgreesWithTheTraversalMatcher() {
        assertTrue(compiler.compile(Nodes.resource("submit").toSelector()) is CompiledSelector.Native)

        val pattern = SelectorCompiler.anyPackageResource("sub.mit")
        val id = ResourceId.newBuilder().setName("sub.mit").build()
        listOf("sub.mit", "$AUT:id/sub.mit", "android:id/sub.mit", "subxmit", "$AUT:id/subxmit", ":id/sub.mit", "a:b:id/sub.mit", "$AUT:id/sub.mit2")
            .forEach { actual -> assertEquals(actual, id.matchesId(actual), pattern.matcher(actual).matches()) }
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
    fun pickIsCarriedByTheCompiledSelector() {
        val compiled = compiler.compile(Nodes.text("row").toSelector().pickAt(2))

        assertEquals(SelectorPick.Outcome.Chosen(2), compiled.pick.choose(3))
    }

    private companion object {
        const val AUT = "com.example.app"
        const val SYSTEM_UI = "com.android.systemui"
    }
}
