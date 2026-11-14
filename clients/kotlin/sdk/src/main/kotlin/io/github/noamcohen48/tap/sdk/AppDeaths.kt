package io.github.noamcohen48.tap.sdk

import io.github.noamcohen48.tap.api.v1.ListAppDeathsRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
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
    /** Whether a command's error on this attachment already carried it. */
    val reported: Boolean,
) {
    companion object {
        /**
         * How long [Device.guard], [Device.watchDeaths] and the JUnit extension's end-of-test check
         * wait after their block before asking for deaths. Android logs a crash 10–200 ms after the
         * input that caused it, so a crash the block's last tap caused is still seen; one logged
         * later is seen by the next command or check.
         */
        val SETTLE: Duration = 300.milliseconds
    }

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
                append(deaths.first().summary)
                deaths.first().stackTrace.lineSequence().take(STACK_LINES).filter { it.isNotBlank() }.forEach { append('\n').append(it) }
                deaths.drop(1).forEach { append("\nthen ").append(it.summary) }
            }

        const val STACK_LINES = 12
    }
}

/** What [Device.watchDeaths] / [App.watchDeaths] returns: the block's [value] and the watched deaths during it. */
data class WatchedDeaths<T>(
    val value: T,
    val deaths: List<AppDeath>,
)

internal fun AppDeathProto.toModel(): AppDeath =
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
        reported = reported,
    )

/** The deadline of each `ListAppDeaths` call: it reads the server's record, not the device. */
private val LIST_TIMEOUT = 10.seconds

/** How often [expectedDeath] asks again while it waits for the death after its block. */
private val EXPECT_POLL = 100.milliseconds

/** `ListAppDeaths` on [device]; [sequenceOnly] returns no deaths. */
internal suspend fun listAppDeaths(
    device: Device,
    operation: String,
    after: Long,
    packageName: String?,
    sequenceOnly: Boolean = false,
): Pair<List<AppDeath>, Long> {
    ensureTapBound(operation)
    return device.admitted(operation) {
        mapped(device.serial) {
            device.ownerConnection.client.devices
                .withDeadlineAfter(LIST_TIMEOUT.inWholeMilliseconds, TimeUnit.MILLISECONDS)
                .listAppDeaths(
                    ListAppDeathsRequest
                        .newBuilder()
                        .setClientConnectionId(device.ownerConnection.id)
                        .setAttachedDeviceId(device.attachedDeviceId)
                        .setAfterSequence(after)
                        .setSequenceOnly(sequenceOnly)
                        .apply { packageName?.let { setPackageName(it) } }
                        .build(),
                ).let { response -> response.deathsList.map { it.toModel() } to response.lastSequence }
        }
    }
}

/** The latest death sequence: deaths after it are new. */
internal suspend fun deathMark(
    device: Device,
    operation: String,
): Long = listAppDeaths(device, operation, 0, null, sequenceOnly = true).second

/**
 * Watched deaths after [mark] that nothing handed to the test yet: no error carried them
 * ([AppDeath.reported]) and no [guarded] / [watching] / [expectedDeath] block claimed them.
 */
internal suspend fun unhandledDeaths(
    device: Device,
    mark: Long,
    operation: String,
): List<AppDeath> =
    listAppDeaths(device, operation, mark, null).first.filter { it.watched && !it.reported && it.sequence !in device.claimedDeaths }

/** [Device.guard] / [App.guard]: [packageName] null = every watched app. */
internal suspend fun <T> guarded(
    device: Device,
    packageName: String?,
    operation: String,
    block: suspend () -> T,
): T {
    val mark = deathMark(device, operation)
    val outcome =
        try {
            Result.success(block())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            Result.failure(failure)
        }
    val failure = outcome.exceptionOrNull()
    val carried = (failure as? CommandException)?.appDeath?.takeIf { packageName == null || it.packageName == packageName }
    if (carried == null) delay(AppDeath.SETTLE)
    val later =
        listAppDeaths(device, operation, mark, packageName).first.filter {
            it.watched && !it.reported && it.sequence !in device.claimedDeaths && it.sequence != carried?.sequence
        }
    val deaths = (listOfNotNull(carried) + later).sortedBy { it.sequence }
    if (deaths.isEmpty()) return outcome.getOrThrow()
    deaths.forEach { device.claimedDeaths += it.sequence }
    throw AppDiedException(deaths, cause = failure.takeIf { carried != null }).apply {
        if (failure != null && carried == null) addSuppressed(failure)
    }
}

/** [Device.watchDeaths] / [App.watchDeaths]. */
internal suspend fun <T> watching(
    device: Device,
    packageName: String?,
    operation: String,
    block: suspend () -> T,
): WatchedDeaths<T> {
    val mark = deathMark(device, operation)
    val value = block()
    delay(AppDeath.SETTLE)
    val deaths = listAppDeaths(device, operation, mark, packageName).first.filter { it.watched && it.sequence !in device.claimedDeaths }
    deaths.forEach { device.claimedDeaths += it.sequence }
    return WatchedDeaths(value, deaths)
}

/** [Device.expectDeath] / [App.expectDeath]. */
internal suspend fun expectedDeath(
    device: Device,
    packageName: String?,
    timeout: Duration,
    operation: String,
    block: suspend () -> Unit,
): AppDeath {
    val mark = deathMark(device, operation)
    var carried: AppDeath? = null
    try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: CommandException) {
        // The command that met the expected death; anything else the block throws is a failure.
        carried = failure.appDeath?.takeIf { packageName == null || it.packageName == packageName } ?: throw failure
    }
    val started = TimeSource.Monotonic.markNow()
    while (true) {
        val deaths = listAppDeaths(device, operation, mark, packageName).first.filter { it.watched && it.sequence !in device.claimedDeaths }
        val death = carried ?: deaths.firstOrNull()
        if (death != null) {
            (deaths + death).forEach { device.claimedDeaths += it.sequence }
            return death
        }
        if (started.elapsedNow() >= timeout) {
            throw AssertionError("expected ${packageName ?: "a watched app"} on ${device.serial} to crash or stop responding within $timeout; it did not")
        }
        delay(EXPECT_POLL)
    }
}
