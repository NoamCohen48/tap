package io.github.noamcohen48.tap.junit5

import io.github.noamcohen48.tap.sdk.Timeouts
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Resolved once per JVM from system properties, falling back to environment variables named by
 * [envName] (`tap.artifactsDir` → `TAP_ARTIFACTS_DIR`, `tap.device.sender` → `TAP_DEVICE_SENDER`).
 */
data class TapConfig(
    /** Serials roles map to, in order from a per-test rotating start; empty = whatever the server's device list offers. */
    val serials: List<String>,
    val artifactsDir: Path,
    val acquireTimeout: Duration,
    /** Explicit role → serial pins (`tap.device.<role>`). */
    val pinnedRoles: Map<String, String>,
    /** `tap.manageDaemon`: start the daemon before the first session and stop it after the run if this process started it. */
    val manageDaemon: Boolean = false,
    /** `tap.capture`: `onFailure` (default) captures every device of a failed test into [artifactsDir]; `off` captures nothing. */
    val capture: CaptureMode = CaptureMode.ON_FAILURE,
) {
    companion object {
        val current: TapConfig by lazy { load() }

        /** The environment variable for a `tap.*` property: camelCase and dots become `_`, upper-cased. */
        internal fun envName(key: String): String = key.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").replace('.', '_').uppercase()

        internal fun load(
            property: (String) -> String? = { key -> System.getProperty(key) ?: System.getenv(envName(key)) },
            allProperties: () -> Map<String, String> = {
                System.getenv().entries.filter { it.key.startsWith("TAP_DEVICE_") }.associate {
                    "tap.device.${it.key.removePrefix("TAP_DEVICE_").lowercase()}" to it.value
                } + System.getProperties().entries.associate { it.key.toString() to it.value.toString() }
            },
        ): TapConfig {
            val serials =
                property("tap.serials")
                    .orEmpty()
                    .split(',')
                    .map(String::trim)
                    .filter(String::isNotEmpty)
            require(serials.distinct().size == serials.size) { "tap.serials contains duplicates: $serials" }
            val pinned =
                allProperties()
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
                artifactsDir = Path.of(property("tap.artifactsDir") ?: "build/tap-artifacts"),
                acquireTimeout = property("tap.acquireTimeoutSeconds")?.toLong()?.seconds ?: Timeouts.ACQUIRE,
                pinnedRoles = pinned,
                manageDaemon = property("tap.manageDaemon")?.toBoolean() ?: false,
                capture = property("tap.capture")?.let(CaptureMode::parse) ?: CaptureMode.ON_FAILURE,
            )
        }
    }
}

/** When the JUnit extension captures device artifacts (`tap.capture`). */
enum class CaptureMode(
    val value: String,
) {
    /** Capture every device of a failed test (the default). */
    ON_FAILURE("onFailure"),

    /** Never capture; tests can still call `Device.capture()` themselves. */
    OFF("off"),
    ;

    companion object {
        internal fun parse(value: String): CaptureMode =
            entries.firstOrNull { it.value.equals(value.trim(), ignoreCase = true) }
                ?: throw IllegalArgumentException("tap.capture must be one of ${entries.map { it.value }}, was '$value'")
    }
}
