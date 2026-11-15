package io.github.noamcohen48.tap.sdk

import io.github.noamcohen48.tap.api.v1.AcknowledgeAppDeathsRequest
import io.github.noamcohen48.tap.api.v1.ListAppDeathsRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import io.github.noamcohen48.tap.api.v1.AppDeath as AppDeathProto

/**
 * A crash or ANR of an app on the device, as Android logged it and the host server recorded it
 * ([Device.appDeaths], [CommandException.appDeath], [AppDiedException]).
 *
 * Only *watched* deaths ([watched]) explain command failures, end waits and fail tests: deaths of
 * an app started through this attachment (`launch`, `coldLaunch`, `foreground`, `openLink`) or
 * named by a command's selector (anything inside `device.app(pkg)`), from then on.
 */
data class AppDeath(
    /** Per attachment, from 1, in the order Android logged the deaths. */
    val sequence: Long,
    val kind: Kind,
    val packageName: String,
    /** The process: [packageName] or `packageName:suffix`. */
    val processName: String,
    val pid: Int,
    /** The device clock when Android logged it. */
    val at: Instant,
    /** A crash's exception class (`java.lang.IllegalStateException`, or `Native crash`); empty for an ANR. */
    val exceptionClass: String,
    /** A crash's exception message, or an ANR's reason (`Input dispatching timed out …`). */
    val message: String,
    /** A crash's stack trace from the crash buffer (at most 32 KiB); empty when it scrolled out, and for an ANR. */
    val stackTrace: String,
    /** Whether the app was watched when it died. */
    val watched: Boolean,
    /**
     * Whether a client already has it: a command's error carried it, or a [Device.guard],
     * [Device.allowDeaths], [Device.expectDeath] block or an end-of-test check took it. A handled
     * death explains no later failure, ends no wait and fails no test.
     */
    val handled: Boolean,
    /** The serial of the device it died on. */
    val serial: String,
) {
    enum class Kind {
        /** A Java exception or a native crash ended the process. */
        CRASH,

        /** The app stopped answering input, or a broadcast or service, in time. */
        ANR,
    }

    /** `com.shop crashed (pid 23554): java.lang.IllegalStateException: Out of stock`. */
    val summary: String
        get() =
            if (kind == Kind.ANR) {
                "$packageName stopped responding (pid $pid): $message"
            } else {
                "$packageName crashed (pid $pid): " + listOf(exceptionClass, message).filter { it.isNotEmpty() }.joinToString(": ")
            }
}

/**
 * A watched app died during a [Device.guard] / [App.guard] block, or during a test (the JUnit
 * extension's end-of-test check). [deaths] are the deaths, oldest first; [death] the first.
 *
 * When a command in the block failed because of the death, that [CommandException] is the
 * [cause]. When the block failed in another way, that failure is suppressed ([suppressed]): the
 * death is the likelier reason.
 */
class AppDiedException(
    val deaths: List<AppDeath>,
    cause: Throwable? = null,
) : TapException(describe(deaths), cause) {
    val death: AppDeath get() = deaths.first()

    private companion object {
        fun describe(deaths: List<AppDeath>): String =
            buildString {
                if (deaths.size > 1) append("${deaths.size} app deaths; first: ")
                append(deaths.first().located)
                deaths.first().stackTrace.lineSequence().take(STACK_LINES).filter { it.isNotBlank() }.forEach { append('\n').append(it) }
                deaths.drop(1).forEach { append("\nthen ").append(it.located) }
            }

        val AppDeath.located: String get() = "[$serial] $summary"

        const val STACK_LINES = 12
    }
}

internal fun AppDeathProto.toModel(serial: String): AppDeath =
    AppDeath(
        sequence = sequence,
        kind = if (kind == AppDeathProto.Kind.KIND_ANR) AppDeath.Kind.ANR else AppDeath.Kind.CRASH,
        packageName = packageName,
        processName = processName,
        pid = pid,
        at = Instant.ofEpochMilli(atEpochMs),
        exceptionClass = exceptionClass,
        message = message,
        stackTrace = stackTrace,
        watched = watched,
        handled = handled,
        serial = serial,
    )

/** The deadline of each `ListAppDeaths` call: it reads the server's record, not the device (plus the settle wait). */
private val LIST_TIMEOUT = 10.seconds

/**
 * What a check asks the server to wait for after the latest input before it lists
 * (`ListAppDeathsRequest.settle_ms`): Android logs a crash 10–200 ms after the input that caused it.
 */
private const val SETTLE_MS = 300L

/** How often [expectedDeath] asks again while it waits for the death after its block. */
private val EXPECT_POLL = 100.milliseconds

/** The longest server-side wait of one `ListAppDeaths` call; [awaitedDeaths] asks again for longer. */
private val MAX_SERVER_WAIT = 10.minutes

/**
 * `ListAppDeaths` on [device]; [sequenceOnly] returns no deaths, [settle] first waits [SETTLE_MS]
 * after the latest input, [wait] first waits for a death after [after] (of any app).
 */
internal suspend fun listAppDeaths(
    device: Device,
    operation: String,
    after: Long,
    packageName: String?,
    sequenceOnly: Boolean = false,
    settle: Boolean = false,
    wait: Duration = Duration.ZERO,
): Pair<List<AppDeath>, Long> {
    ensureTapBound(operation)
    return device.admitted(operation) {
        mapped(device.serial) {
            device.ownerConnection.client.devices
                .withDeadlineAfter((LIST_TIMEOUT + wait).inWholeMilliseconds, TimeUnit.MILLISECONDS)
                .listAppDeaths(
                    ListAppDeathsRequest
                        .newBuilder()
                        .setClientConnectionId(device.ownerConnection.id)
                        .setAttachedDeviceId(device.attachedDeviceId)
                        .setAfterSequence(after)
                        .setSequenceOnly(sequenceOnly)
                        .setSettleMs(if (settle) SETTLE_MS else 0)
                        .setWaitMs(wait.inWholeMilliseconds)
                        .apply { packageName?.let { setPackageName(it) } }
                        .build(),
                ).let { response -> response.deathsList.map { it.toModel(device.serial) } to response.lastSequence }
        }
    }
}

/** [Device.awaitAppDeaths]: the server waits for any app's death, so another app's wakes this and it waits again. */
internal suspend fun awaitedDeaths(
    device: Device,
    after: Long,
    timeout: Duration,
    packageName: String?,
    operation: String,
): List<AppDeath> {
    val started = TimeSource.Monotonic.markNow()
    var from = after
    while (true) {
        val left = timeout - started.elapsedNow()
        val (listed, last) = listAppDeaths(device, operation, from, null, wait = left.coerceIn(Duration.ZERO, MAX_SERVER_WAIT))
        val wanted = listed.filter { packageName == null || it.packageName == packageName }
        if (wanted.isNotEmpty() || left <= Duration.ZERO || started.elapsedNow() >= timeout) return wanted
        from = maxOf(from, last)
    }
}

/** `AcknowledgeAppDeaths`: [deaths] are handled, so they explain no later failure and fail no test. */
internal suspend fun acknowledge(
    device: Device,
    operation: String,
    deaths: Collection<AppDeath>,
) {
    val open = deaths.filter { !it.handled }
    if (open.isEmpty()) return
    device.admitted(operation) {
        mapped(device.serial) {
            device.ownerConnection.client.devices
                .withDeadlineAfter(LIST_TIMEOUT.inWholeMilliseconds, TimeUnit.MILLISECONDS)
                .acknowledgeAppDeaths(
                    AcknowledgeAppDeathsRequest
                        .newBuilder()
                        .setClientConnectionId(device.ownerConnection.id)
                        .setAttachedDeviceId(device.attachedDeviceId)
                        .addAllSequences(open.map { it.sequence })
                        .build(),
                )
        }
    }
}

/** The latest death sequence: deaths after it are new. */
internal suspend fun deathMark(
    device: Device,
    operation: String,
): Long = listAppDeaths(device, operation, 0, null, sequenceOnly = true).second

/** Watched deaths after [mark] (of [packageName] when given) that no client has yet ([AppDeath.handled]); [settle] as [listAppDeaths]. */
internal suspend fun openDeaths(
    device: Device,
    mark: Long,
    packageName: String?,
    operation: String,
    settle: Boolean = true,
): List<AppDeath> = listAppDeaths(device, operation, mark, packageName, settle = settle).first.filter { it.watched && !it.handled }

/** Runs [block], keeping its value or failure; cancellation is rethrown at once. */
private suspend fun <T> outcomeOf(block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        Result.failure(failure)
    }

/** [after] once [block]'s failure, if any, is the one thrown: a failure of [after] itself is then suppressed. */
private suspend fun <T, R> finishing(
    outcome: Result<T>,
    after: suspend () -> R,
): R =
    try {
        after()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        outcome.exceptionOrNull()?.let { it.addSuppressed(failure); throw it }
        throw failure
    }

/** [Device.guard] / [App.guard]: [packageName] null = every watched app. */
internal suspend fun <T> guarded(
    device: Device,
    packageName: String?,
    operation: String,
    block: suspend () -> T,
): T {
    val mark = deathMark(device, operation)
    val outcome = outcomeOf(block)
    val failure = outcome.exceptionOrNull()
    val carried = (failure as? CommandException)?.appDeath?.takeIf { packageName == null || it.packageName == packageName }
    val later =
        finishing(outcome) {
            openDeaths(device, mark, packageName, operation, settle = carried == null)
                .filter { it.sequence != carried?.sequence }
                .also { acknowledge(device, operation, it) }
        }
    val deaths = (listOfNotNull(carried) + later).sortedBy { it.sequence }
    if (deaths.isEmpty()) return outcome.getOrThrow()
    throw AppDiedException(deaths, cause = failure.takeIf { carried != null }).apply {
        if (failure != null && carried == null) addSuppressed(failure)
    }
}

/** [Device.allowDeaths] / [App.allowDeaths]. */
internal suspend fun <T> allowing(
    device: Device,
    packageName: String?,
    operation: String,
    block: suspend () -> T,
): T {
    val mark = deathMark(device, operation)
    val outcome = outcomeOf(block)
    finishing(outcome) { acknowledge(device, operation, openDeaths(device, mark, packageName, operation)) }
    return outcome.getOrThrow()
}

/** [Device.expectDeath] / [App.expectDeath]. */
internal suspend fun expectedDeath(
    device: Device,
    packageName: String?,
    kind: AppDeath.Kind?,
    timeout: Duration,
    operation: String,
    block: suspend () -> Unit,
): AppDeath {
    fun AppDeath.expected() = (packageName == null || this.packageName == packageName) && (kind == null || this.kind == kind)
    val mark = deathMark(device, operation)
    try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: CommandException) {
        // The command that met the expected death (the server handled it); anything else the block throws is a failure.
        return failure.appDeath?.takeIf { it.expected() } ?: throw failure
    }
    val started = TimeSource.Monotonic.markNow()
    var settle = true
    while (true) {
        val death = openDeaths(device, mark, packageName, operation, settle).firstOrNull { it.expected() }
        settle = false
        if (death != null) {
            // Only this one: another death during the block still fails the test.
            acknowledge(device, operation, listOf(death))
            return death
        }
        if (started.elapsedNow() >= timeout) {
            val what = listOfNotNull(packageName ?: "a watched app", "on ${device.serial}").joinToString(" ")
            val how = when (kind) {
                AppDeath.Kind.CRASH -> "crash"
                AppDeath.Kind.ANR -> "stop responding"
                null -> "crash or stop responding"
            }
            throw AssertionError("expected $what to $how within $timeout; it did not")
        }
        delay(EXPECT_POLL)
    }
}
