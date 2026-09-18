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
    PONG(10);

    companion object {
        fun fromWireValue(value: Byte): FrameType =
            entries.firstOrNull { it.wireValue == value }
                ?: throw ProtocolException("Unknown frame type: $value")
    }
}

enum class Operation {
    HEALTH,
    EXISTS,
    TAP,
    WAIT_VISIBLE,
    DUMP_HIERARCHY,
    SET_TEXT,
    TYPE_TEXT,
    SCROLL_UNTIL,
    SYNC_BOOTSTRAP,
    SYNC_STATE,
}

enum class SelectorKind {
    TEXT,
    RAW_RESOURCE,
    ANDROID_RESOURCE,
}

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
data class Selector(
    val kind: SelectorKind,
    val value: String,
    val packageName: String? = null,
    val scope: TargetScope = TargetScope.AUT,
    val scopePackage: String? = null,
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
    val maxScrolls: Int = 20,
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

data class Frame(
    val type: FrameType,
    val requestId: Long,
    val payload: ByteArray,
)

class ProtocolException(message: String) : RuntimeException(message)
