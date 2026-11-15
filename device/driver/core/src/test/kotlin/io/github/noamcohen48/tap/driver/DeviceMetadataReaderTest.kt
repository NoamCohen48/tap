package io.github.noamcohen48.tap.driver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class DeviceMetadataReaderTest {
    private class FakeRoot(
        private val value: CharSequence?,
        private val failure: RuntimeException? = null,
    ) : DeviceMetadataReader.Root {
        var recycled = 0
        override val packageName: CharSequence?
            get() {
                failure?.let { throw it }
                return value
            }

        override fun recycle() {
            recycled++
        }
    }

    @Test
    fun `rotation is read afresh and does not acquire an accessibility root`() {
        var rotation = 0
        val reader = DeviceMetadataReader({ rotation }, { error("rotation must not read a root") })
        for (value in listOf(0, 1, 2, 3, 0)) {
            rotation = value
            assertEquals(value, reader.displayRotation)
        }
    }

    @Test
    fun `package acquires and recycles a fresh root for each snapshot`() {
        val first = FakeRoot("app.first")
        val second = FakeRoot("app.second")
        val roots = listOf(first, second).iterator()
        val reader = DeviceMetadataReader({ error("package must not read rotation") }, { roots.next() })
        assertEquals("app.first", reader.currentPackage)
        assertEquals(1, first.recycled)
        assertEquals(0, second.recycled)
        assertEquals("app.second", reader.currentPackage)
        assertEquals(1, first.recycled)
        assertEquals(1, second.recycled)
    }

    @Test
    fun `missing root is unknown without an implicit retry`() {
        var reads = 0
        val reader =
            DeviceMetadataReader({ 0 }, {
                reads++
                null
            })
        assertNull(reader.currentPackage)
        assertEquals(1, reads)
    }

    @Test
    fun `missing package still recycles the root`() {
        val root = FakeRoot(null)
        assertNull(DeviceMetadataReader({ 0 }, { root }).currentPackage)
        assertEquals(1, root.recycled)
    }

    @Test
    fun `package read failure still recycles and preserves the error`() {
        val failure = IllegalStateException("read failed")
        val root = FakeRoot(null, failure)
        val reader = DeviceMetadataReader({ 0 }, { root })
        assertSame(failure, assertThrows(IllegalStateException::class.java) { reader.currentPackage })
        assertEquals(1, root.recycled)
    }

    @Test
    fun `package copies a mutable name before releasing the root`() {
        val value = StringBuilder("app.first")
        val root = FakeRoot(value)
        val snapshot = DeviceMetadataReader({ 0 }, { root }).currentPackage
        value.replace(0, value.length, "app.second")
        assertEquals("app.first", snapshot)
        assertEquals(1, root.recycled)
    }
}
