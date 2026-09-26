package io.github.noamcohen48.tap.protocol

import kotlinx.serialization.Serializable

const val FRAMING_VERSION: Byte = 1
const val MAX_CONTROL_PAYLOAD = 1024 * 1024
const val MAX_REQUEST_TIMEOUT_MS = 120_000L
const val MAX_TEXT_INPUT_CHARS = 256

/**
 * How long the driver lets a running command overrun its deadline before its watchdog poisons
 * the session and answers `INDETERMINATE`/`DRIVER_UNHEALTHY` (instrumentation argument
 * `tapUninterruptibleGraceMs` overrides it on the driver).
 */
const val DRIVER_UNINTERRUPTIBLE_GRACE_MS = 10_000L

/**
 * Host response budget beyond a command's own timeout. It must exceed
 * [DRIVER_UNINTERRUPTIBLE_GRACE_MS] so the driver's own verdict on a late command (plus watchdog
 * poll and transit) arrives before the host gives up and poisons a healthy session.
 */
const val HOST_RESPONSE_PADDING_MS = DRIVER_UNINTERRUPTIBLE_GRACE_MS + 5_000L
const val HOST_BUILD_ID = ENGINE_VERSION
const val DRIVER_APK_BUILD_ID = ENGINE_VERSION
const val DRIVER_TEST_APK_BUILD_ID = ENGINE_VERSION
const val UIAUTOMATOR_BUILD_ID = "2.4.0"

val SUPPORTED_PROTOCOL_VERSIONS = listOf(ProtocolVersion(2, 0))
val SUPPORTED_CAPABILITIES = listOf(
    "artifact.screenshot.v1",
    "diagnostic.hierarchy.v1",
    "input.key-events.v1",
    "product.probe.v1",
    "synchronization.v1",
)

enum class FrameType(val wireValue: Byte) {
    HELLO(1),
    CHALLENGE(2),
    AUTH(3),
    AUTH_RESULT(4),
    REQUEST(5),
    RESPONSE(6),
    CLOSE(7),
    CANCEL(8),
    PING(9),
    PONG(10),
    BLOB_START(11),
    BLOB_CHUNK(12),
    BLOB_END(13);

    companion object {
        fun fromWireValue(value: Byte): FrameType =
            entries.firstOrNull { it.wireValue == value }
                ?: throw ProtocolException("Unknown frame type: $value")
    }
}

/** Gesture direction: the direction the content moves for scrolls, the finger for swipes. */
enum class Direction {
    UP,
    DOWN,
    LEFT,
    RIGHT,
}

/** What [WaitScreenStable] observes. */
enum class StabilitySignal {
    /** Accessibility tree only (cheap: no screenshots) — Maestro's "app settled". */
    TREE,
    /** Window pixels only — Maestro's "animation ended". */
    PIXELS,
    /** Both must be quiet at once. */
    ALL,
}

const val DEFAULT_GESTURE_PERCENT = 80
const val MAX_SCROLLS = 100

/** [WaitScreenStable]: how long the AUT window must stay unchanged; bounded by the request timeout. */
const val DEFAULT_STABLE_FOR_MS = 500L
const val MAX_STABLE_FOR_MS = 30_000L

/** [Count] stops counting here; a screen with more matches reports this value. */
const val MAX_MATCH_COUNT = 1_000

/** Android key codes the host may name symbolically; any non-negative code is accepted. */
const val KEYCODE_HOME = 3
const val KEYCODE_BACK = 4
const val KEYCODE_ENTER = 66

@Serializable
data class ProtocolVersion(val major: Int, val minor: Int) : Comparable<ProtocolVersion> {
    init {
        require(major >= 0 && minor >= 0) { "Protocol versions must not be negative" }
    }

    override fun compareTo(other: ProtocolVersion): Int =
        compareValuesBy(this, other, ProtocolVersion::major, ProtocolVersion::minor)
}

@Serializable
data class Negotiation(
    val enabledCapabilities: List<String>,
    val selectedVersion: ProtocolVersion,
)

@Serializable
data class Hello(
    val hostBuildId: String,
    val hostNonce: String,
    val sessionGeneration: Long,
    val sessionId: String,
    val supportedVersions: List<ProtocolVersion>,
)

@Serializable
data class Challenge(
    val androidApiLevel: Int,
    val capabilities: List<String>,
    val driverApkBuildId: String,
    val driverInstanceId: String,
    val hostNonce: String,
    val driverNonce: String,
    val driverTestApkBuildId: String,
    val sessionGeneration: Long,
    val sessionId: String,
    /** Wire names (`op` values) of the commands this driver executes, sorted. */
    val supportedOperations: List<String>,
    val supportedVersions: List<ProtocolVersion>,
    val uiAutomatorBuildId: String,
)

@Serializable
data class Authentication(
    val negotiation: Negotiation,
    val transcriptHmac: String,
)

@Serializable
data class AuthenticationResult(
    val ok: Boolean,
    val enabledCapabilities: List<String>? = null,
    val error: String? = null,
    val selectedVersion: ProtocolVersion? = null,
    val transcriptHmac: String? = null,
)

@Serializable
data class SyncState(
    val initialized: Boolean,
    val processId: Int,
    val processStartUuid: String,
    val sessionIdentity: String,
    val generation: Long,
    val busyCount: Int,
    val lastTransitionElapsedMs: Long,
    val error: String? = null,
)

/** One accessibility node's state, read once; never a handle. */
@Serializable
data class ElementSnapshot(
    val className: String? = null,
    val packageName: String? = null,
    val resourceName: String? = null,
    val text: String? = null,
    val contentDescription: String? = null,
    val hint: String? = null,
    val bounds: Bounds,
    val checkable: Boolean,
    val checked: Boolean,
    val clickable: Boolean,
    val enabled: Boolean,
    val focusable: Boolean,
    val focused: Boolean,
    val longClickable: Boolean,
    val scrollable: Boolean,
    val selected: Boolean,
    val childCount: Int,
)

@Serializable
data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

@Serializable
data class DeviceInfo(
    val apiLevel: Int,
    val manufacturer: String,
    val model: String,
    val product: String,
    val displayWidth: Int,
    val displayHeight: Int,
    val displayRotation: Int,
    val currentPackage: String? = null,
)

data class Frame(
    val type: FrameType,
    val requestId: Long,
    val payload: ByteArray,
)

class ProtocolException(message: String) : RuntimeException(message)
