package io.github.noamcohen48.tap.protocol

import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * `REQUEST` payload: the session envelope plus one [Command]. The request ID travels in the
 * frame header, not here.
 *
 * ```json
 * {"sessionId":"…","generation":7,"timeoutMs":5000,"command":{"op":"tap","selector":{…}}}
 * ```
 */
@Serializable
data class Request(
    val sessionId: String,
    val generation: Long,
    /** Budget for the whole command, 0..[MAX_REQUEST_TIMEOUT_MS], measured from acceptance. */
    val timeoutMs: Long,
    val command: Command,
)

/**
 * The result type a command produces. Pure typing: never serialized (a generic `Command<R>`
 * cannot be, star projections being prohibited by kotlinx.serialization), but it lets a client
 * write `execute(Count(selector)).count` and lets [CommandHandler] enforce the return type per
 * operation.
 */
interface Returning<R : CommandResult>

/**
 * One operation the driver executes. Each class carries exactly the arguments its operation
 * needs and validates their ranges on construction, so a malformed command cannot be built on
 * the host or decoded on the driver. On the wire the class is named by the `op` discriminator.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("op")
sealed interface Command {
    /** Wire name of this command: its `op` discriminator value. */
    val op: String get() = CommandNames.of(this)

    companion object {
        /** Every `op` this build knows, sorted; what a driver advertises in [Challenge.supportedOperations]. */
        val names: List<String> get() = CommandNames.all
    }
}

/** A command that may change device state; a lost response leaves it [ErrorCode.INDETERMINATE]. */
sealed interface Mutation : Command

/** Something with a target selector; the driver validates it before any lookup. */
sealed interface Targeted : Command {
    val selector: Selector
}

// Session and device --------------------------------------------------------------------------

/** Liveness probe; the reply is the only observation. */
@Serializable
@SerialName("health")
data object Health : Command, Returning<Done>

/** Static device facts plus the currently focused package. */
@Serializable
@SerialName("device_info")
data object DeviceInfoQuery : Command, Returning<DeviceInfoResult>

/** Injects one Android `KeyEvent` key code (down + up). */
@Serializable
@SerialName("press_key")
data class PressKey(val keyCode: Int) : Mutation, Returning<Done> {
    init {
        require(keyCode >= 0) { "keyCode must not be negative" }
    }
}

/** PNG of the whole display; the bytes arrive in blob frames before the response. */
@Serializable
@SerialName("screenshot")
data object Screenshot : Command, Returning<ArtifactResult>

/** Diagnostic accessibility hierarchy dump. Never on the selector hot path. */
@Serializable
@SerialName("dump_hierarchy")
data object DumpHierarchy : Command, Returning<TextResult>

// Queries -------------------------------------------------------------------------------------

/** True when at least one node matches right now; never waits. */
@Serializable
@SerialName("exists")
data class Exists(override val selector: Selector) : Targeted, Returning<BoolResult>

/** Number of matching nodes, capped at [MAX_MATCH_COUNT]; the selector's match limit is ignored. */
@Serializable
@SerialName("count")
data class Count(override val selector: Selector) : Targeted, Returning<CountResult>

/** The single matching node's state at one instant. */
@Serializable
@SerialName("snapshot")
data class Snapshot(override val selector: Selector) : Targeted, Returning<SnapshotResult>

// Waits ---------------------------------------------------------------------------------------

/** Waits until at least one node matches; `WAIT_TIMEOUT` otherwise. */
@Serializable
@SerialName("wait_visible")
data class WaitVisible(override val selector: Selector) : Targeted, Returning<Done>

/** Waits until nothing matches the selector; `WAIT_TIMEOUT` otherwise. */
@Serializable
@SerialName("wait_gone")
data class WaitGone(override val selector: Selector) : Targeted, Returning<Done>

/** Waits until [packageName] owns the focused window. */
@Serializable
@SerialName("wait_app_visible")
data class WaitAppVisible(val packageName: String) : Command, Returning<Done> {
    init {
        require(packageName.isNotBlank()) { "packageName must not be blank" }
    }
}

/**
 * Waits until [packageName]'s window has been quiet for [stableForMs] according to [signal];
 * `WAIT_TIMEOUT` when the request budget runs out first.
 */
@Serializable
@SerialName("wait_screen_stable")
data class WaitScreenStable(
    val packageName: String,
    val stableForMs: Long = DEFAULT_STABLE_FOR_MS,
    val signal: StabilitySignal = StabilitySignal.ALL,
) : Command, Returning<Done> {
    init {
        require(packageName.isNotBlank()) { "packageName must not be blank" }
        require(stableForMs in 1..MAX_STABLE_FOR_MS) { "stableForMs must be in 1..$MAX_STABLE_FOR_MS" }
    }
}

// Input ---------------------------------------------------------------------------------------

@Serializable
@SerialName("tap")
data class Tap(override val selector: Selector) : Targeted, Mutation, Returning<Done>

@Serializable
@SerialName("long_tap")
data class LongTap(override val selector: Selector) : Targeted, Mutation, Returning<Done>

/** Replaces the field's text through the accessibility action; verified by reading it back. */
@Serializable
@SerialName("set_text")
data class SetText(override val selector: Selector, val text: String) : Targeted, Mutation, Returning<Done> {
    init {
        require(text.length <= MAX_TEXT_INPUT_CHARS) { "text must be at most $MAX_TEXT_INPUT_CHARS chars" }
    }
}

/** Types [text] as key events into the focused field; verified by reading it back. */
@Serializable
@SerialName("type_text")
data class TypeText(override val selector: Selector, val text: String) : Targeted, Mutation, Returning<Done> {
    init {
        require(text.length <= MAX_TEXT_INPUT_CHARS) { "text must be at most $MAX_TEXT_INPUT_CHARS chars" }
    }
}

@Serializable
@SerialName("clear_text")
data class ClearText(override val selector: Selector) : Targeted, Mutation, Returning<Done>

// Gestures ------------------------------------------------------------------------------------

/** One swipe across the element; [direction] is where the finger goes. */
@Serializable
@SerialName("swipe")
data class Swipe(
    override val selector: Selector,
    val direction: Direction,
    /** Gesture length as a percentage of the element's size, 1..100. */
    val distancePercent: Int = DEFAULT_GESTURE_PERCENT,
) : Targeted, Mutation, Returning<Moved> {
    init {
        require(distancePercent in 1..100) { "distancePercent must be in 1..100" }
    }
}

/** One scroll of the container; [direction] is where the content moves. */
@Serializable
@SerialName("scroll")
data class Scroll(
    override val selector: Selector,
    val direction: Direction,
    val distancePercent: Int = DEFAULT_GESTURE_PERCENT,
) : Targeted, Mutation, Returning<Moved> {
    init {
        require(distancePercent in 1..100) { "distancePercent must be in 1..100" }
    }
}

/**
 * Scrolls [container] until [selector] matches, up to [maxScrolls] times; `NOT_FOUND` when the
 * container stops moving or the budget is spent first. Both selectors must share one scope.
 */
@Serializable
@SerialName("scroll_until")
data class ScrollUntil(
    override val selector: Selector,
    val container: Selector,
    val direction: Direction = Direction.DOWN,
    val distancePercent: Int = DEFAULT_GESTURE_PERCENT,
    val maxScrolls: Int = 20,
) : Targeted, Mutation, Returning<Done> {
    init {
        require(distancePercent in 1..100) { "distancePercent must be in 1..100" }
        require(maxScrolls in 1..MAX_SCROLLS) { "maxScrolls must be in 1..$MAX_SCROLLS" }
    }
}

// Synchronization -----------------------------------------------------------------------------

/** First contact with the AUT's sync provider for the process the host observed. */
@Serializable
@SerialName("sync_bootstrap")
data class SyncBootstrap(val observedPid: Int, val observedStartToken: String) : Command, Returning<SyncResult> {
    init {
        require(observedStartToken.isNotBlank()) { "observedStartToken must not be blank" }
    }
}

/** Reads the sync provider's state, failing `AUT_MISMATCH` when the process identity moved. */
@Serializable
@SerialName("sync_poll")
data class SyncPoll(
    val observedPid: Int,
    val observedStartToken: String,
    val expectedProcessStartUuid: String,
    val expectedSessionIdentity: String,
) : Command, Returning<SyncResult> {
    init {
        require(observedStartToken.isNotBlank()) { "observedStartToken must not be blank" }
        require(expectedProcessStartUuid.isNotBlank()) { "expectedProcessStartUuid must not be blank" }
        require(expectedSessionIdentity.isNotBlank()) { "expectedSessionIdentity must not be blank" }
    }
}

// Dispatch ------------------------------------------------------------------------------------

/**
 * One method per command with the command's own result type. The driver implements it; the
 * `when` in [dispatch] is exhaustive, so adding a command without a handler does not compile.
 */
interface CommandHandler {
    fun health(command: Health): Done
    fun deviceInfo(command: DeviceInfoQuery): DeviceInfoResult
    fun pressKey(command: PressKey): Done
    fun screenshot(command: Screenshot): ArtifactResult
    fun dumpHierarchy(command: DumpHierarchy): TextResult
    fun exists(command: Exists): BoolResult
    fun count(command: Count): CountResult
    fun snapshot(command: Snapshot): SnapshotResult
    fun waitVisible(command: WaitVisible): Done
    fun waitGone(command: WaitGone): Done
    fun waitAppVisible(command: WaitAppVisible): Done
    fun waitScreenStable(command: WaitScreenStable): Done
    fun tap(command: Tap): Done
    fun longTap(command: LongTap): Done
    fun setText(command: SetText): Done
    fun typeText(command: TypeText): Done
    fun clearText(command: ClearText): Done
    fun swipe(command: Swipe): Moved
    fun scroll(command: Scroll): Moved
    fun scrollUntil(command: ScrollUntil): Done
    fun syncBootstrap(command: SyncBootstrap): SyncResult
    fun syncPoll(command: SyncPoll): SyncResult
}

fun Command.dispatch(handler: CommandHandler): CommandResult = when (this) {
    is Health -> handler.health(this)
    is DeviceInfoQuery -> handler.deviceInfo(this)
    is PressKey -> handler.pressKey(this)
    is Screenshot -> handler.screenshot(this)
    is DumpHierarchy -> handler.dumpHierarchy(this)
    is Exists -> handler.exists(this)
    is Count -> handler.count(this)
    is Snapshot -> handler.snapshot(this)
    is WaitVisible -> handler.waitVisible(this)
    is WaitGone -> handler.waitGone(this)
    is WaitAppVisible -> handler.waitAppVisible(this)
    is WaitScreenStable -> handler.waitScreenStable(this)
    is Tap -> handler.tap(this)
    is LongTap -> handler.longTap(this)
    is SetText -> handler.setText(this)
    is TypeText -> handler.typeText(this)
    is ClearText -> handler.clearText(this)
    is Swipe -> handler.swipe(this)
    is Scroll -> handler.scroll(this)
    is ScrollUntil -> handler.scrollUntil(this)
    is SyncBootstrap -> handler.syncBootstrap(this)
    is SyncPoll -> handler.syncPoll(this)
}

/**
 * `op` names are owned by the `@SerialName` annotations; this reads them back through the
 * sealed serializer so the name exists in exactly one place. Cached per class after the first
 * encode.
 */
@OptIn(ExperimentalSerializationApi::class)
internal object CommandNames {
    private val byClass = ConcurrentHashMap<KClass<out Command>, String>()

    val all: List<String> by lazy {
        val subclasses = Command.serializer().descriptor.getElementDescriptor(1)
        (0 until subclasses.elementsCount).map(subclasses::getElementName).sorted()
    }

    fun of(command: Command): String = byClass.getOrPut(command::class) {
        CanonicalJson.codec.encodeToJsonElement(Command.serializer(), command)
            .jsonObject.getValue("op").jsonPrimitive.content
    }
}

/**
 * Decodes a `REQUEST` payload on the driver. A payload this build cannot represent is still
 * answered, never dropped: an unknown `op` is [ErrorCode.UNSUPPORTED] (a newer host), anything
 * else that fails to decode or violates a command's own range checks is [ErrorCode.INVALID_REQUEST].
 */
object RequestDecoder {
    sealed interface Outcome {
        data class Decoded(val request: Request) : Outcome
        data class Rejected(val code: ErrorCode, val message: String?) : Outcome
    }

    fun decode(payload: String): Outcome = try {
        Outcome.Decoded(ProtocolJson.codec.decodeFromString(Request.serializer(), payload))
    } catch (error: kotlinx.serialization.SerializationException) {
        val op = runCatching {
            ProtocolJson.codec.parseToJsonElement(payload).jsonObject["command"]?.jsonObject?.get("op")?.jsonPrimitive?.content
        }.getOrNull()
        if (op != null && op !in Command.names) Outcome.Rejected(ErrorCode.UNSUPPORTED, "Unknown op '$op'")
        else Outcome.Rejected(ErrorCode.INVALID_REQUEST, error.message)
    } catch (error: IllegalArgumentException) {
        Outcome.Rejected(ErrorCode.INVALID_REQUEST, error.message)
    }
}
