package com.company.tap.junit5

import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Resolved once per JVM from system properties, falling back to `TAP_*` environment variables. */
data class TapConfig(
    /** Serials roles are pinned to, in order; empty = any device in the service pool. */
    val serials: List<String>,
    val autPackage: String,
    val artifactsDir: Path,
    val acquireTimeout: Duration,
    /** Explicit role → serial pins (`tap.device.<role>`). */
    val pinnedRoles: Map<String, String>,
) {
    companion object {
        val current: TapConfig by lazy { load() }

        internal fun load(
            property: (String) -> String? = { key ->
                System.getProperty(key) ?: System.getenv(key.uppercase().replace('.', '_'))
            },
            allProperties: () -> Map<String, String> = {
                System.getProperties().entries.associate { it.key.toString() to it.value.toString() }
            },
        ): TapConfig {
            val serials = property("tap.serials").orEmpty().split(',').map(String::trim).filter(String::isNotEmpty)
            require(serials.distinct().size == serials.size) { "tap.serials contains duplicates: $serials" }
            val autPackage = requireNotNull(property("tap.autPackage") ?: property("tap.aut")) {
                "tap.autPackage (or TAP_AUTPACKAGE / TAP_AUT) is required"
            }
            val pinned = allProperties()
                .filterKeys { it.startsWith("tap.device.") }
                .map { (key, value) -> key.removePrefix("tap.device.") to value.trim() }
                .toMap()
            if (serials.isNotEmpty()) {
                pinned.forEach { (role, serial) ->
                    require(serial in serials) { "tap.device.$role=$serial is not listed in tap.serials" }
                }
            }
            return TapConfig(
                serials = serials,
                autPackage = autPackage,
                artifactsDir = Path.of(property("tap.artifactsDir") ?: "build/tap-artifacts"),
                acquireTimeout = (property("tap.acquireTimeoutSeconds")?.toLong() ?: 300L).seconds,
                pinnedRoles = pinned,
            )
        }
    }
}
