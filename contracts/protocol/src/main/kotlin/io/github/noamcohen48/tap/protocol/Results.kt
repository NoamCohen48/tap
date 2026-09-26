package io.github.noamcohen48.tap.protocol

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * What a successful command produced. Each [Command] names its result type through
 * [Returning]; on the wire the class is named by the `kind` discriminator.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("kind")
sealed interface CommandResult

/** The command ran to completion and has nothing else to report. */
@Serializable
@SerialName("done")
data object Done : CommandResult

/** [Exists]. */
@Serializable
@SerialName("bool")
data class BoolResult(val value: Boolean) : CommandResult

/** [Swipe], [Scroll]: whether the content moved at all (false at the end of a list). */
@Serializable
@SerialName("moved")
data class Moved(val moved: Boolean) : CommandResult

/** [Count]: matches found, capped at [MAX_MATCH_COUNT]. */
@Serializable
@SerialName("count")
data class CountResult(val count: Int) : CommandResult

/** [DumpHierarchy]. */
@Serializable
@SerialName("text")
data class TextResult(val text: String) : CommandResult

/** [Snapshot]. */
@Serializable
@SerialName("snapshot")
data class SnapshotResult(val snapshot: ElementSnapshot) : CommandResult

/** [DeviceInfoQuery]. */
@Serializable
@SerialName("device_info")
data class DeviceInfoResult(val deviceInfo: DeviceInfo) : CommandResult

/** [Screenshot]: the metadata of a blob that arrived in the preceding blob frames. */
@Serializable
@SerialName("artifact")
data class ArtifactResult(val artifact: ArtifactInfo) : CommandResult

/** [SyncBootstrap], [SyncPoll]. */
@Serializable
@SerialName("sync")
data class SyncResult(val state: SyncState) : CommandResult {
    val idle: Boolean get() = state.busyCount == 0
}

/**
 * `RESPONSE` payload: exactly one per request, either [Ok] with the command's [CommandResult]
 * or [Error] with a code from the closed [ErrorCode] taxonomy.
 *
 * ```json
 * {"type":"ok","durationMs":31,"result":{"kind":"count","count":3}}
 * {"type":"error","durationMs":5000,"code":"WAIT_TIMEOUT"}
 * ```
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("type")
sealed interface Response {
    /** Milliseconds from acceptance to this response, measured by the driver. */
    val durationMs: Long

    @Serializable
    @SerialName("ok")
    data class Ok(val result: CommandResult, override val durationMs: Long) : Response

    @Serializable
    @SerialName("error")
    data class Error(
        val code: ErrorCode,
        /** Stable sub-reason from [ErrorDetail]; null when the code alone is specific enough. */
        val detail: String? = null,
        /** Human-readable context. Not stable; never branch on it. */
        val message: String? = null,
        override val durationMs: Long,
    ) : Response

    val ok: Boolean get() = this is Ok

    companion object {
        fun ok(result: CommandResult, durationMs: Long): Response = Ok(result, durationMs)

        fun failure(
            code: ErrorCode,
            durationMs: Long,
            detail: String? = null,
            message: String? = null,
        ): Response = Error(code, detail, message, durationMs)
    }
}

/** Convenience views over either variant; each resolves to the member on the concrete type. */
val Response.result: CommandResult? get() = (this as? Response.Ok)?.result
val Response.errorCode: ErrorCode? get() = (this as? Response.Error)?.code
val Response.detail: String? get() = (this as? Response.Error)?.detail
val Response.message: String? get() = (this as? Response.Error)?.message

/**
 * Thrown by a [CommandHandler] method to end the command with an error response. The engine
 * converts it; handlers never build responses themselves.
 */
class CommandFailure(
    val code: ErrorCode,
    val detail: String? = null,
    message: String? = null,
) : RuntimeException(message ?: code.name) {
    val remoteMessage: String? = message
}
