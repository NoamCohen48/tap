package io.github.noamcohen48.tap.driver

import io.github.noamcohen48.tap.driver.engine.CommandPipeline
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.Base64

class SessionConfigTest {
    private val required =
        mapOf(
            "tapSession" to "s-1",
            "tapGeneration" to "7",
            "tapSecret" to Base64.getUrlEncoder().encodeToString(byteArrayOf(1, 2, 3)),
            "tapPort" to "7912",
            "tapAutPackage" to "com.example.app",
            "tapSyncAuthority" to "com.example.app.tap-sync",
        )

    @Test
    fun parsesTheRequiredArgumentsAndAppliesDefaults() {
        val config = SessionConfig.from(required::get)

        assertEquals("s-1", config.sessionId)
        assertEquals(7L, config.generation)
        assertArrayEquals(byteArrayOf(1, 2, 3), config.secret)
        assertEquals(7912, config.port)
        assertEquals(CommandPipeline.DEFAULT_UNINTERRUPTIBLE_GRACE_MS, config.uninterruptibleGraceMs)
        assertEquals(SessionConfig.DEFAULT_HEARTBEAT_TIMEOUT_MS, config.heartbeatTimeoutMs)
    }

    @Test
    fun overridesTheTimeouts() {
        val config = SessionConfig.from((required + mapOf("tapUninterruptibleGraceMs" to "0", "tapHeartbeatTimeoutMs" to "500"))::get)

        assertEquals(0L, config.uninterruptibleGraceMs)
        assertEquals(500L, config.heartbeatTimeoutMs)
    }

    @Test
    fun rejectsMissingOrUnboundedArguments() {
        assertThrows(IllegalArgumentException::class.java) { SessionConfig.from((required - "tapSecret")::get) }
        assertThrows(IllegalArgumentException::class.java) { SessionConfig.from((required + ("tapHeartbeatTimeoutMs" to "0"))::get) }
    }
}
