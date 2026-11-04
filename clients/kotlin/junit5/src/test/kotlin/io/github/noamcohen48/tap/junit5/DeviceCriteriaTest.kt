package io.github.noamcohen48.tap.junit5

import io.github.noamcohen48.tap.sdk.DeviceEntry
import io.github.noamcohen48.tap.sdk.DeviceProperties
import io.github.noamcohen48.tap.sdk.DeviceState
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeviceCriteriaTest {
    private fun entry(
        serial: String,
        api: Int,
        emulator: Boolean = false,
        manufacturer: String = "samsung",
        model: String = "SM-J810G",
    ) = DeviceEntry(serial, DeviceState.FREE, null, null, DeviceProperties(api, manufacturer, model, emulator))

    private val emulator = entry("emulator-5554", 34, emulator = true, manufacturer = "Google", model = "sdk_gphone64_x86_64")
    private val samsung = entry("85e49002", 29)
    private val entries = listOf(emulator, samsung).associateBy { it.serial }

    @Test
    fun `every part that is set must hold`() {
        assertTrue(DeviceCriteria(minApi = 34).matches(emulator))
        assertFalse(DeviceCriteria(minApi = 35).matches(emulator))
        assertTrue(DeviceCriteria(maxApi = 29).matches(samsung))
        assertFalse(DeviceCriteria(maxApi = 28).matches(samsung))
        assertTrue(DeviceCriteria(kind = DeviceKind.EMULATOR).matches(emulator))
        assertFalse(DeviceCriteria(kind = DeviceKind.PHYSICAL).matches(emulator))
        assertTrue(DeviceCriteria(manufacturer = "SAMSUNG").matches(samsung))
        assertTrue(DeviceCriteria(modelContains = "j810").matches(samsung))
        assertFalse(DeviceCriteria(minApi = 29, modelContains = "pixel").matches(samsung))
    }

    @Test
    fun `a device with unknown properties fits only empty criteria`() {
        val unknown = DeviceEntry("x", DeviceState.FREE, null, null, null)
        assertTrue(DeviceCriteria().matches(unknown))
        assertTrue(DeviceCriteria().matches(null))
        assertFalse(DeviceCriteria(minApi = 1).matches(unknown))
        assertFalse(DeviceCriteria(minApi = 1).matches(null))
    }

    @Test
    fun `a role gets the device its criteria accept whatever the order`() {
        val criteria = mapOf("receiver" to DeviceCriteria(kind = DeviceKind.PHYSICAL))
        assertEquals(
            mapOf("sender" to "emulator-5554", "receiver" to "85e49002"),
            chooseSerials(listOf("sender", "receiver"), criteria, listOf("85e49002", "emulator-5554"), entries),
        )
    }

    @Test
    fun `no assignment when two roles fit only one device`() {
        val physical = DeviceCriteria(kind = DeviceKind.PHYSICAL)
        assertNull(chooseSerials(listOf("a", "b"), mapOf("a" to physical, "b" to physical), listOf("85e49002", "emulator-5554"), entries))
    }

    @Test
    fun `a pinned role gets its pin only when the pin fits`() {
        val serials = listOf("85e49002", "emulator-5554")
        val roles = listOf("a", "b")
        assertEquals(
            mapOf("a" to "emulator-5554", "b" to "85e49002"),
            chooseSerials(roles, mapOf("b" to DeviceCriteria(minApi = 26)), serials, entries, pinned = mapOf("a" to "emulator-5554")),
        )
        assertNull(chooseSerials(roles, mapOf("a" to DeviceCriteria(maxApi = 30)), serials, entries, pinned = mapOf("a" to "emulator-5554")))
    }

    @RequireDevice(minApi = 26)
    @RequireDevice(role = "other", maxApi = 30)
    private class Annotated {
        @RequireDevice(minApi = 33, kind = DeviceKind.EMULATOR)
        fun replaces() = Unit

        fun inherits() = Unit

        @RequireDevice(role = "typo")
        fun unknownRole() = Unit
    }

    private fun method(name: String) = Annotated::class.java.getDeclaredMethod(name)

    @Test
    fun `a method's criteria replace its class's and unused class roles are ignored`() {
        assertEquals(
            mapOf("device" to DeviceCriteria(minApi = 33, kind = DeviceKind.EMULATOR)),
            declaredCriteria(Annotated::class.java, method("replaces"), listOf("device")),
        )
        assertEquals(
            mapOf("device" to DeviceCriteria(minApi = 26)),
            declaredCriteria(Annotated::class.java, method("inherits"), listOf("device")),
        )
    }

    @Test
    fun `a method naming a role it does not declare is a usage error`() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                declaredCriteria(Annotated::class.java, method("unknownRole"), listOf("device"))
            }
        assertTrue("typo" in failure.message!!, failure.message)
    }

    @Test
    fun `a skip message says what each device is`() {
        assertEquals("85e49002 (API 29, samsung SM-J810G)", describeDevice("85e49002", samsung))
        assertEquals("emulator-5554 (API 34, emulator)", describeDevice("emulator-5554", emulator))
        assertEquals("x (properties unknown)", describeDevice("x", null))
    }
}
