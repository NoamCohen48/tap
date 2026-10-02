package io.github.noamcohen48.tap.host

/**
 * Root of every operational failure host core reports on purpose: a device that is busy,
 * quarantined or offline, a session that can no longer be used, a driver that would not start,
 * an ADB command that failed. A server maps these subtypes to its status codes; an
 * [IllegalArgumentException] is invalid caller input, and any other exception (including a bare
 * [IllegalStateException] from a `check`) is a host-core bug.
 */
open class TapHostException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * The device's session journal keeps it out of service: a recorded quarantine, a recovery that
 * could not prove the previous driver/boot state, or a session whose ADB cleanup left sticky reap
 * uncertainty. Only an explicit reset (or a reboot, where the journal allows it) clears it.
 */
open class DeviceQuarantinedException(
    val serial: String,
    message: String,
    cause: Throwable? = null,
) : TapHostException(message, cause)

/** The session journal could not be read; it was preserved and replaced by a quarantine record. */
class CorruptJournalException(
    serial: String,
    message: String,
    cause: Throwable? = null,
) : DeviceQuarantinedException(serial, message, cause)

/**
 * This [DeviceSession] can no longer run operations (its driver connection was lost or poisoned).
 * Nothing is repaired in place: close the session and open a new one.
 */
open class SessionUnusableException(
    val serial: String,
    message: String,
    cause: Throwable? = null,
) : TapHostException(message, cause)

/** The session started closing; operations admitted from now on are rejected. */
class SessionClosingException(
    serial: String,
) : SessionUnusableException(serial, "Session on $serial is closing; new operations are rejected")

/**
 * The driver could not be brought up: instrumentation never reported ready, no reserved port
 * could be bound, the old driver would not die, or the connection never authenticated.
 */
open class DriverStartException(
    message: String,
    cause: Throwable? = null,
) : TapHostException(message, cause)

/**
 * An ADB command exited non-zero or answered with output that cannot mean success. [serial] is
 * null for server-level commands (`adb devices`); [exitCode] is null when the process exited 0
 * but its output was unusable.
 */
class AdbCommandException(
    val serial: String?,
    val command: List<String>,
    val exitCode: Int?,
    val output: String,
    message: String = "ADB command failed${exitCode?.let { " (exit $it)" } ?: ""}" +
        "${serial?.let { " on $it" } ?: ""}: ${command.joinToString(" ")}: $output",
) : TapHostException(message)

/** An ADB command did not finish within its own timeout; the process was killed and reaped. */
class AdbTimeoutException(
    val serial: String?,
    val command: List<String>,
    message: String,
) : TapHostException(message)

/** The device's API level is below [requiredApi], which the operation needs. */
class UnsupportedApiException(
    val serial: String,
    val requiredApi: Int,
    val apiLevel: Int,
    what: String,
) : TapHostException("$what needs API $requiredApi; $serial runs API $apiLevel")

/** A device or app setting did not read back as written: the device did not take it. */
class DeviceSettingException(
    val serial: String,
    message: String,
) : TapHostException(message)
