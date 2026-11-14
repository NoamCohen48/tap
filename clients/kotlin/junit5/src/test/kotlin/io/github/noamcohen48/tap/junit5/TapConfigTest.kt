package io.github.noamcohen48.tap.junit5

import io.github.noamcohen48.tap.sdk.Timeouts
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds

class TapConfigTest {
    @Test
    fun `environment names follow one rule`() {
        assertEquals("TAP_ARTIFACTS_DIR", TapConfig.envName("tap.artifactsDir"))
        assertEquals("TAP_ACQUIRE_TIMEOUT_SECONDS", TapConfig.envName("tap.acquireTimeoutSeconds"))
        assertEquals("TAP_MANAGE_DAEMON", TapConfig.envName("tap.manageDaemon"))
        assertEquals("TAP_SERIALS", TapConfig.envName("tap.serials"))
    }

    @Test
    fun `defaults and overrides`() {
        val defaults = TapConfig.load(property = { null }, allProperties = { emptyMap() })
        assertEquals(Timeouts.ACQUIRE, defaults.acquireTimeout)
        assertEquals(false, defaults.manageDaemon)
        assertEquals(CaptureMode.ON_FAILURE, defaults.capture)
        assertEquals(true, defaults.report)
        assertEquals(AppDeathMode.FAIL, defaults.appDeaths)

        val set =
            mapOf(
                "tap.serials" to "a,b",
                "tap.acquireTimeoutSeconds" to "7",
                "tap.manageDaemon" to "true",
                "tap.capture" to "off",
                "tap.report" to "off",
                "tap.appDeaths" to "OFF",
            )
        val config = TapConfig.load(property = set::get, allProperties = { mapOf("tap.device.sender" to "b") })
        assertEquals(listOf("a", "b"), config.serials)
        assertEquals(7.seconds, config.acquireTimeout)
        assertEquals(true, config.manageDaemon)
        assertEquals(mapOf("sender" to "b"), config.pinnedRoles)
        assertEquals(CaptureMode.OFF, config.capture)
        assertEquals(false, config.report)
        assertEquals(AppDeathMode.OFF, config.appDeaths)
        assertFailsWith<IllegalArgumentException> { TapConfig.load(property = mapOf("tap.appDeaths" to "warn")::get, allProperties = { emptyMap() }) }
        assertFailsWith<IllegalArgumentException> {
            TapConfig.load(property = mapOf("tap.report" to "always")::get, allProperties = { emptyMap() })
        }
        assertFailsWith<IllegalArgumentException> {
            TapConfig.load(property = mapOf("tap.capture" to "always")::get, allProperties = { emptyMap() })
        }
    }
}
