package io.github.noamcohen48.tap.daemon

import io.github.noamcohen48.tap.api.v1.AllOf
import io.github.noamcohen48.tap.api.v1.Related
import io.github.noamcohen48.tap.api.v1.Relation
import io.github.noamcohen48.tap.api.v1.ResourceId
import io.github.noamcohen48.tap.api.v1.Selector
import io.github.noamcohen48.tap.protocol.Node
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import io.github.noamcohen48.tap.api.v1.Node as ProtoNode

/** `ResourceId.aut_package` is resolved by the daemon from the session's app under test. */
class AutResourceTest {
    private fun resource(
        name: String,
        build: ResourceId.Builder.() -> Unit = {},
    ): ProtoNode = ProtoNode.newBuilder().setResource(ResourceId.newBuilder().setName(name).apply(build)).build()

    private fun selector(resource: ResourceId.Builder.() -> Unit): Selector =
        Selector.newBuilder().setNode(resource("buy", resource)).build()

    @Test
    fun `aut_package resolves to the session package, also inside relations and combinators`() {
        val nested =
            Selector
                .newBuilder()
                .setNode(
                    ProtoNode.newBuilder().setAllOf(
                        AllOf
                            .newBuilder()
                            .addNodes(resource("row") { autPackage = true })
                            .addNodes(
                                ProtoNode.newBuilder().setRelated(
                                    Related.newBuilder().setRelation(Relation.RELATION_DESCENDANT).setNode(
                                        resource("delete") {
                                            autPackage =
                                                true
                                        },
                                    ),
                                ),
                            ),
                    ),
                ).build()
        val converted = nested.toSelector(autPackage = "com.shop").node as Node.AllOf
        assertEquals(Node.Resource("row", "com.shop"), converted.nodes[0])
        assertEquals(Node.descendant(Node.Resource("delete", "com.shop")), converted.nodes[1])
    }

    @Test
    fun `explicit and raw resources are untouched`() {
        assertEquals("other.pkg", (selector { packageName = "other.pkg" }.toSelector("com.shop").node as Node.Resource).packageName)
        assertNull((selector { }.toSelector("com.shop").node as Node.Resource).packageName)
    }

    @Test
    fun `aut_package with an explicit package or without a session is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            selector {
                autPackage = true
                packageName = "other.pkg"
            }.toSelector("com.shop")
        }
        assertFailsWith<IllegalArgumentException> {
            selector { autPackage = true }.toSelector(autPackage = null)
        }
    }

    @Test
    fun `an unset node kind, scope or pick is rejected or defaulted as the contract says`() {
        assertFailsWith<IllegalArgumentException> {
            Selector
                .newBuilder()
                .setNode(ProtoNode.getDefaultInstance())
                .build()
                .toSelector()
        }
        val defaults = selector { }.toSelector()
        assertEquals(io.github.noamcohen48.tap.protocol.Scope.Aut, defaults.scope)
        assertEquals(io.github.noamcohen48.tap.protocol.Pick.ExactlyOne, defaults.pick)
    }
}
