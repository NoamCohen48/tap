package com.company.tap.protocol

import kotlinx.serialization.Serializable

const val FRAMING_VERSION: Byte = 1
const val MAX_CONTROL_PAYLOAD = 1024 * 1024
const val MAX_REQUEST_TIMEOUT_MS = 120_000L
const val MAX_TEXT_INPUT_CHARS = 256
const val HOST_BUILD_ID = "0.1.0"
const val DRIVER_APK_BUILD_ID = "0.1.0"
const val DRIVER_TEST_APK_BUILD_ID = "0.1.0"
const val UIAUTOMATOR_BUILD_ID = "2.4.0"
const val OPERATION_VERSION = 1

val SUPPORTED_PROTOCOL_VERSIONS = listOf(ProtocolVersion(1, 0))
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

enum class Operation {
    HEALTH,
    DEVICE_INFO,
    PRESS_KEY,
    EXISTS,
    COUNT,
    SNAPSHOT,
    TAP,
    LONG_TAP,
    WAIT_VISIBLE,
    WAIT_GONE,
    WAIT_APP_VISIBLE,
    DUMP_HIERARCHY,
    SET_TEXT,
    TYPE_TEXT,
    CLEAR_TEXT,
    SWIPE,
    SCROLL,
    SCROLL_UNTIL,
    SCREENSHOT,
    SYNC_BOOTSTRAP,
    SYNC_STATE,
}

/** Gesture direction: the direction the content moves for scrolls, the finger for swipes. */
enum class Direction {
    UP,
    DOWN,
    LEFT,
    RIGHT,
}

const val DEFAULT_GESTURE_PERCENT = 80
const val MAX_SCROLLS = 100

/** `COUNT` stops counting here; a screen with more matches reports this value. */
const val MAX_MATCH_COUNT = 1_000

/** Android key codes the host may name symbolically; any non-negative code is accepted. */
const val KEYCODE_HOME = 3
const val KEYCODE_BACK = 4
const val KEYCODE_ENTER = 66

enum class TargetScope {
    AUT,
    SYSTEM,
}

@Serializable
data class ProtocolVersion(val major: Int, val minor: Int) : Comparable<ProtocolVersion> {
    init {
        require(major >= 0 && minor >= 0) { "Protocol versions must not be negative" }
    }

    override fun compareTo(other: ProtocolVersion): Int =
        compareValuesBy(this, other, ProtocolVersion::major, ProtocolVersion::minor)
}

@Serializable
data class OperationSupport(val name: String, val version: Int)

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
    val supportedOperations: List<OperationSupport>,
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
data class Request(
    val sessionId: String,
    val sessionGeneration: Long,
    val operation: Operation,
    val operationVersion: Int = OPERATION_VERSION,
    val timeoutMs: Long,
    val selector: Selector? = null,
    val containerSelector: Selector? = null,
    val inputText: String? = null,
    /** `SWIPE`, `SCROLL`, `SCROLL_UNTIL`; defaults to `DOWN` for scrolls. */
    val direction: Direction? = null,
    /** Gesture length as a percentage of the element's size, 1..100. */
    val distancePercent: Int = DEFAULT_GESTURE_PERCENT,
    val maxScrolls: Int = 20,
    /** `PRESS_KEY`: an Android `KeyEvent` key code. */
    val keyCode: Int? = null,
    /** `WAIT_APP_VISIBLE`: the package whose focused window must appear. */
    val packageName: String? = null,
    val observedPid: Int? = null,
    val observedStartToken: String? = null,
    val expectedProcessStartUuid: String? = null,
    val expectedSessionIdentity: String? = null,
)

@Serializable
data class Response(
    val ok: Boolean,
    val value: Boolean? = null,
    val text: String? = null,
    val errorCode: ErrorCode? = null,
    /** Stable sub-reason from [ErrorDetail]; null when the code alone is specific enough. */
    val detail: String? = null,
    /** Human-readable context. Not stable; never branch on it. */
    val message: String? = null,
    val durationMs: Long,
    val syncState: SyncState? = null,
    /** Set when the request transferred a blob; the bytes arrived in the preceding blob frames. */
    val artifact: ArtifactInfo? = null,
    /** `COUNT`: matches found, capped at [MAX_MATCH_COUNT]. */
    val count: Int? = null,
    /** `SNAPSHOT`: the resolved element's accessibility state at one instant. */
    val snapshot: ElementSnapshot? = null,
    /** `DEVICE_INFO`. */
    val deviceInfo: DeviceInfo? = null,
) {
    init {
        require(ok == (errorCode == null)) { "errorCode must be present exactly when ok is false" }
    }

    companion object {
        fun failure(
            code: ErrorCode,
            durationMs: Long,
            detail: String? = null,
            message: String? = null,
            value: Boolean? = null,
        ): Response = Response(
            ok = false,
            value = value,
            errorCode = code,
            detail = detail,
            message = message,
            durationMs = durationMs,
        )
    }
}

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
