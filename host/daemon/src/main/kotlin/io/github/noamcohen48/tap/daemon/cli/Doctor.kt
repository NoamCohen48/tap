package io.github.noamcohen48.tap.daemon.cli

import io.github.noamcohen48.tap.host.Adb
import io.github.noamcohen48.tap.host.AdbDeviceState
import io.github.noamcohen48.tap.host.DeviceDoctor
import io.github.noamcohen48.tap.host.DeviceDoctor.Outcome
import io.github.noamcohen48.tap.host.DeviceDoctor.UnquarantineOutcome
import io.github.noamcohen48.tap.host.TapHostException
import java.nio.file.Path

/**
 * `tap doctor`: the server's state, then for each device ADB lists (or [serial]) what a session
 * that is no longer running left on it. With [fix] it runs the recovery an attach would run.
 * Devices a live session holds are skipped. Exit status 1 when something is left unfixed or a
 * device is quarantined, else 0.
 */
internal suspend fun doctor(
    stateDir: Path,
    adb: Adb,
    serial: String?,
    fix: Boolean,
    out: (String) -> Unit = ::println,
    server: (Path) -> String = ::serverLine,
): Int {
    val serverState = server(stateDir)
    out("server: $serverState")
    if (serverState.startsWith("stale") || serverState.startsWith("not responding")) {
        out("  `tap stop` removes the stale daemon.json; `tap start` starts a new server")
    }
    val listed = adb.deviceStates()
    val devices = if (serial == null) listed else listed.filter { it.serial == serial }
    if (devices.isEmpty()) {
        out(if (serial == null) "no devices: `adb devices` lists none" else "$serial: not listed by `adb devices`")
        return if (serial == null) 0 else 1
    }
    var unhealthy = false
    for (device in devices) {
        if (device.state != AdbDeviceState.ONLINE) {
            val hint = if (device.state == AdbDeviceState.UNAUTHORIZED) "; accept the USB debugging prompt on the device" else ""
            out("${device.serial}: ${device.rawState}$hint")
            continue
        }
        when (val outcome = DeviceDoctor.check(adb, stateDir.resolve("sessions"), device.serial, fix)) {
            Outcome.Clean -> out("${device.serial}: clean")
            Outcome.InUse -> out("${device.serial}: in use by a session; skipped")
            is Outcome.Quarantined -> {
                unhealthy = true
                out("${device.serial}: quarantined: ${outcome.reason}")
                out("  check the device, then `tap unquarantine --serial ${device.serial}` puts it back in service")
            }
            is Outcome.Findings -> {
                if (outcome.findings.any { !it.fixed }) unhealthy = true
                out("${device.serial}:")
                outcome.findings.forEach { out("  - ${it.problem}${if (it.fixed) " (fixed)" else ""}") }
            }
        }
    }
    if (unhealthy && !fix) out("run `tap doctor --fix` to clean up what ended sessions left")
    return if (unhealthy) 1 else 0
}

private fun serverLine(stateDir: Path): String {
    var line = ""
    status(stateDir) { line = it }
    return line
}

/**
 * `tap unquarantine --serial SERIAL`: puts a quarantined device back in service after a person (or
 * their agent) checked it ([DeviceDoctor.unquarantine]). Prints why it was quarantined, what the
 * cleanup retry fixed and what is still off. Exit status 1 when another session holds the device
 * or ADB cannot reach it, else 0 (also when it was not quarantined).
 */
internal suspend fun unquarantine(
    stateDir: Path,
    adb: Adb,
    serial: String,
    out: (String) -> Unit = ::println,
): Int {
    val outcome =
        try {
            DeviceDoctor.unquarantine(adb, stateDir.resolve("sessions"), serial)
        } catch (unreachable: TapHostException) {
            out("$serial: not changed: ${unreachable.message}")
            return 1
        }
    return when (outcome) {
        UnquarantineOutcome.InUse -> {
            out("$serial: in use by a session; not changed")
            1
        }
        UnquarantineOutcome.NotQuarantined -> {
            out("$serial: not quarantined; nothing to do")
            0
        }
        is UnquarantineOutcome.Unquarantined -> {
            out("$serial: back in service (it was quarantined: ${outcome.reason})")
            outcome.cleaned.forEach { out("  - $it") }
            outcome.remaining.forEach { out("  - still off: $it") }
            0
        }
    }
}

