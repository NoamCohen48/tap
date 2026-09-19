package com.company.tap.host

import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.io.path.absolutePathString

class Adb(val executable: String = "adb") {
    data class Result(val exitCode: Int, val output: String)

    fun run(serial: String, vararg arguments: String, timeoutMs: Long = 30_000): String {
        val result = runResult(serial, *arguments, timeoutMs = timeoutMs)
        check(result.exitCode == 0) { "ADB command failed: ${result.output}" }
        return result.output
    }

    fun runResult(serial: String, vararg arguments: String, timeoutMs: Long = 30_000): Result {
        val process = ProcessBuilder(listOf(executable, "-s", serial) + arguments)
            .redirectErrorStream(true)
            .start()
        val output = CompletableFuture.supplyAsync {
            process.inputStream.bufferedReader().use { it.readText() }
        }
        if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            process.waitFor(5, TimeUnit.SECONDS)
            output.cancel(true)
            throw IllegalStateException(
                "ADB command timed out for $serial: ${arguments.joinToString(" ")}",
            )
        }
        val text = output.get(5, TimeUnit.SECONDS)
        return Result(process.exitValue(), text.trim())
    }

    /** Serials of devices currently in the `device` state (not offline/unauthorized). */
    fun devices(timeoutMs: Long = 10_000): List<String> {
        val process = ProcessBuilder(listOf(executable, "devices")).redirectErrorStream(true).start()
        val output = CompletableFuture.supplyAsync { process.inputStream.bufferedReader().use { it.readText() } }
        if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            output.cancel(true)
            throw IllegalStateException("adb devices timed out")
        }
        val text = output.get(5, TimeUnit.SECONDS)
        check(process.exitValue() == 0) { "adb devices failed: $text" }
        return text.lineSequence()
            .drop(1)
            .map { it.trim().split(Regex("\\s+")) }
            .filter { it.size >= 2 && it[1] == "device" }
            .map { it[0] }
            .toList()
    }

    /**
     * Best-effort screen preparation: wakes the display and dismisses an insecure keyguard so
     * the AUT can reach the foreground. A secure lock is not bypassed; the app-visible wait
     * then reports the lock screen package as the last observation.
     */
    fun wakeAndDismissKeyguard(serial: String) {
        runResult(serial, "shell", "input", "keyevent", "KEYCODE_WAKEUP", timeoutMs = 10_000)
        runResult(serial, "shell", "wm", "dismiss-keyguard", timeoutMs = 10_000)
    }

    fun install(serial: String, apk: Path, timeoutMs: Long = 120_000) {
        run(serial, "install", "-r", "-t", apk.absolutePathString(), timeoutMs = timeoutMs)
    }

    fun forward(serial: String, devicePort: Int): Int =
        run(serial, "forward", "tcp:0", "tcp:$devicePort").toInt()

    fun removeForward(serial: String, hostPort: Int) {
        run(serial, "forward", "--remove", "tcp:$hostPort")
    }

    fun forwards(serial: String): List<Forwarding> =
        run(serial, "forward", "--list")
            .lineSequence()
            .filter(String::isNotBlank)
            .mapNotNull { line ->
                val fields = line.trim().split(Regex("\\s+"))
                if (fields.size != 3 || fields[0] != serial) return@mapNotNull null
                val hostPort = fields[1].removePrefix("tcp:").toIntOrNull() ?: return@mapNotNull null
                val devicePort = fields[2].removePrefix("tcp:").toIntOrNull() ?: return@mapNotNull null
                Forwarding(serial, hostPort, devicePort)
            }
            .toList()

    fun processIds(serial: String, packageName: String): List<Int> {
        val result = runResult(serial, "shell", "pidof", packageName)
        if (result.exitCode != 0) {
            check(result.exitCode == 1 && result.output.isBlank()) {
                "Unable to observe process IDs for $serial: ${result.output}"
            }
            return emptyList()
        }
        val tokens = result.output.split(Regex("\\s+")).filter(String::isNotBlank)
        check(tokens.isNotEmpty()) { "pidof succeeded without reporting a PID" }
        return tokens.map { token ->
            requireNotNull(token.toIntOrNull()) { "pidof returned a nonnumeric PID: $token" }
        }
    }

    data class Forwarding(val serial: String, val hostPort: Int, val devicePort: Int)
}
