package com.company.tap.service

import com.company.tap.api.v1.NodeSelector
import com.company.tap.api.v1.ResourceId
import com.company.tap.api.v1.Selector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** `ResourceId.aut_package` is resolved by the service from the session's app under test. */
class AutResourceTest {
    private fun selector(resource: ResourceId.Builder.() -> Unit): Selector = Selector.newBuilder()
        .setNode(NodeSelector.newBuilder().setResource(ResourceId.newBuilder().setName("buy").apply(resource)))
        .build()

    @Test
    fun `aut_package resolves to the session package, also inside relations`() {
        val nested = Selector.newBuilder()
            .setNode(
                NodeSelector.newBuilder()
                    .setResource(ResourceId.newBuilder().setName("row").setAutPackage(true))
                    .setDescendant(NodeSelector.newBuilder().setResource(ResourceId.newBuilder().setName("delete").setAutPackage(true))),
            ).build()
        val converted = Conversions.selector(nested, autPackage = "com.shop")
        assertEquals("com.shop", converted.node.resource?.packageName)
        assertEquals("com.shop", converted.node.descendant?.resource?.packageName)
    }

    @Test
    fun `explicit and raw resources are untouched`() {
        assertEquals("other.pkg", Conversions.selector(selector { packageName = "other.pkg" }, "com.shop").node.resource?.packageName)
        assertNull(Conversions.selector(selector { }, "com.shop").node.resource?.packageName)
    }

    @Test
    fun `aut_package with an explicit package or without a session is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            Conversions.selector(selector { autPackage = true; packageName = "other.pkg" }, "com.shop")
        }
        assertFailsWith<IllegalArgumentException> {
            Conversions.selector(selector { autPackage = true }, autPackage = null)
        }
    }
}
