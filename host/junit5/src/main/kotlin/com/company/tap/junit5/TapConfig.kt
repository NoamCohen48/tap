package com.company.tap.junit5

import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Resolved once per JVM from system properties, falling back to `TAP_*` environment variables. */
data class TapConfig(
    val serials: List<String>,
    val autPackage: String,
    val driverApk: Path?,
    val driverTestApk: Path?,
    val artifactsDir: Path,
    val acquireTimeout: Duration,
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
            require(serials.isNotEmpty()) { "tap.serials (or TAP_SERIALS) must list at least one device serial" }
            require(serials.distinct().size == serials.size) { "tap.serials contains duplicates: $serials" }
            val autPackage = requireNotNull(property("tap.autPackage")) { "tap.autPackage (or TAP_AUTPACKAGE) is required" }
            val pinned = allProperties()
                .filterKeys { it.startsWith("tap.device.") }
                .map { (key, value) -> key.removePrefix("tap.device.") to value.trim() }
                .toMap()
            pinned.forEach { (role, serial) ->
                require(serial in serials) { "tap.device.$role=$serial is not listed in tap.serials" }
            }
            return TapConfig(
                serials = serials,
                autPackage = autPackage,
                driverApk = property("tap.driverApk")?.let(Path::of),
                driverTestApk = property("tap.driverTestApk")?.let(Path::of),
                artifactsDir = Path.of(property("tap.artifactsDir") ?: "build/tap-artifacts"),
                acquireTimeout = (property("tap.acquireTimeoutSeconds")?.toLong() ?: 300L).seconds,
                pinnedRoles = pinned,
            )
        }
    }
}
