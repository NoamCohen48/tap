package io.github.noamcohen48.tap.protocol

import io.github.noamcohen48.tap.wire.v1.ProtocolVersion

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
const val UIAUTOMATOR_BUILD_ID = UIAUTOMATOR_VERSION

/**
 * 4.0: no `scroll_until`, `swipe`/`scroll` report `done`, any-package and any-window selector
 * scopes. 3.0: payloads are protobuf (`tap.wire.v1`); 2.0 was canonical JSON.
 */
val PROTOCOL_VERSION: ProtocolVersion = protocolVersion(4, 0)
val SUPPORTED_PROTOCOL_VERSIONS: List<ProtocolVersion> = listOf(PROTOCOL_VERSION)
val SUPPORTED_CAPABILITIES = listOf(
    "artifact.screenshot.v1",
    "diagnostic.hierarchy.v1",
    "input.key-events.v1",
    "product.probe.v1",
    "synchronization.v1",
)

fun protocolVersion(major: Int, minor: Int): ProtocolVersion {
    require(major >= 0 && minor >= 0) { "Protocol versions must not be negative" }
    return ProtocolVersion.newBuilder().setMajor(major).setMinor(minor).build()
}

operator fun ProtocolVersion.compareTo(other: ProtocolVersion): Int =
    compareValuesBy(this, other, ProtocolVersion::getMajor, ProtocolVersion::getMinor)

/** `3.0`. */
fun ProtocolVersion.render(): String = "$major.$minor"

val PROTOCOL_VERSION_ORDER: Comparator<ProtocolVersion> = compareBy({ it.major }, { it.minor })

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

// Defaults of optional command fields. The driver is the one place they are applied.

/** `Swipe`/`Scroll.distance_percent`. */
const val DEFAULT_GESTURE_PERCENT = 80

/** `WaitScreenStable.stable_for_ms`: how long the AUT window must stay unchanged. */
const val DEFAULT_STABLE_FOR_MS = 500L
const val MAX_STABLE_FOR_MS = 30_000L

/** `Count` stops counting here; a screen with more matches reports this value. */
const val MAX_MATCH_COUNT = 1_000

/** Android key codes the host may name symbolically; any non-negative code is accepted. */
const val KEYCODE_HOME = 3
const val KEYCODE_BACK = 4
const val KEYCODE_ENTER = 66

class Frame(
    val type: FrameType,
    val requestId: Long,
    val payload: ByteArray,
) {
    override fun toString(): String = "Frame($type, requestId=$requestId, ${payload.size} bytes)"
}

class ProtocolException(message: String) : RuntimeException(message)
