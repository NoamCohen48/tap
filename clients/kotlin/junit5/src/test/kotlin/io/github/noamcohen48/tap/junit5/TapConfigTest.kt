package io.github.noamcohen48.tap.junit5

import io.github.noamcohen48.tap.sdk.Timeouts
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

class TapConfigTest {
    @Test
    fun `environment names follow one rule`() {
        assertEquals("TAP_AUT_PACKAGE", TapConfig.envName("tap.autPackage"))
        assertEquals("TAP_ARTIFACTS_DIR", TapConfig.envName("tap.artifactsDir"))
        assertEquals("TAP_ACQUIRE_TIMEOUT_SECONDS", TapConfig.envName("tap.acquireTimeoutSeconds"))
        assertEquals("TAP_MANAGE_DAEMON", TapConfig.envName("tap.manageDaemon"))
        assertEquals("TAP_SERIALS", TapConfig.envName("tap.serials"))
    }

    @Test
    fun `defaults and overrides`() {
        val defaults = TapConfig.load(property = { mapOf("tap.aut" to "com.shop")[it] }, allProperties = { emptyMap() })
        assertEquals("com.shop", defaults.autPackage)
        assertEquals(Timeouts.ACQUIRE, defaults.acquireTimeout)
        assertEquals(false, defaults.manageDaemon)

        val set =
            mapOf(
                "tap.autPackage" to "com.other",
                "tap.serials" to "a,b",
                "tap.acquireTimeoutSeconds" to "7",
                "tap.manageDaemon" to "true",
            )
        val config = TapConfig.load(property = set::get, allProperties = { mapOf("tap.device.sender" to "b") })
        assertEquals("com.other", config.autPackage)
        assertEquals(listOf("a", "b"), config.serials)
        assertEquals(7.seconds, config.acquireTimeout)
        assertEquals(true, config.manageDaemon)
        assertEquals(mapOf("sender" to "b"), config.pinnedRoles)
    }
}
