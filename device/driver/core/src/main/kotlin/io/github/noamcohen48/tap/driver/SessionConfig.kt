package io.github.noamcohen48.tap.driver

import io.github.noamcohen48.tap.driver.engine.CommandPipeline
import java.util.Base64

/**
 * The instrumentation arguments the host passes to `am instrument`, parsed once and immutable.
 * Fault-injection arguments are not part of it: the `validation` flavor reads those itself.
 */
internal class SessionConfig(
    val sessionId: String,
    val generation: Long,
    val secret: ByteArray,
    val port: Int,
    val expectedAut: String,
    val syncAuthority: String,
    val allowedSystemPackages: Set<String>,
    val uninterruptibleGraceMs: Long,
    val heartbeatTimeoutMs: Long,
) {
    companion object {
        /** [argument] looks up one instrumentation argument (`Bundle::getString`). */
        fun from(argument: (String) -> String?): SessionConfig {
            fun required(name: String): String = requireNotNull(argument(name)) { "Missing instrumentation argument $name" }
            val allowedSystemPackages =
                required("tapSystemPackages")
                    .split(',')
                    .filter(String::isNotBlank)
                    .toSet()
            require(allowedSystemPackages.isNotEmpty()) { "tapSystemPackages is empty" }
            val uninterruptibleGraceMs =
                argument("tapUninterruptibleGraceMs")?.toLong()
                    ?: CommandPipeline.DEFAULT_UNINTERRUPTIBLE_GRACE_MS
            require(uninterruptibleGraceMs >= 0) { "tapUninterruptibleGraceMs is negative" }
            val heartbeatTimeoutMs = argument("tapHeartbeatTimeoutMs")?.toLong() ?: DEFAULT_HEARTBEAT_TIMEOUT_MS
            require(heartbeatTimeoutMs > 0) { "The heartbeat timeout is bounded; it cannot be disabled" }
            return SessionConfig(
                sessionId = required("tapSession"),
                generation = required("tapGeneration").toLong(),
                secret = Base64.getUrlDecoder().decode(required("tapSecret")),
                port = required("tapPort").toInt(),
                expectedAut = required("tapAutPackage"),
                syncAuthority = required("tapSyncAuthority"),
                allowedSystemPackages = allowedSystemPackages,
                uninterruptibleGraceMs = uninterruptibleGraceMs,
                heartbeatTimeoutMs = heartbeatTimeoutMs,
            )
        }

        /** A silent host for this long means it is gone; the driver poisons itself and exits. */
        const val DEFAULT_HEARTBEAT_TIMEOUT_MS = 30_000L
    }
}
