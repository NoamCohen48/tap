package com.company.tap.host

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * A controllable [Process] stand-in. [stdout] is the complete output the drain reads;
 * [exitCode] is reported once the process exits. With [exitDelayMs] the process exits on its
 * own after that long; with [NEVER] it only exits when destroyed, like a hung child. Death by
 * [destroy]/[destroyForcibly] is recorded in [destroyed]/[destroyedForcibly] and releases any
 * thread parked in [waitFor], so tests can prove children are reaped without real subprocesses.
 */
class FakeProcess(
    stdout: String = "",
    private val exitCode: Int = 0,
    exitDelayMs: Long = 0,
) : Process() {
    companion object {
        const val NEVER = Long.MAX_VALUE
    }

    private val exited = CountDownLatch(1)
    val destroyed = AtomicBoolean(false)
    val destroyedForcibly = AtomicBoolean(false)
    private val input = ByteArrayInputStream(stdout.toByteArray())
    private val output = ByteArrayOutputStream()

    init {
        if (exitDelayMs != NEVER) {
            thread(isDaemon = true, name = "fake-process-exit") {
                if (exitDelayMs > 0) Thread.sleep(exitDelayMs)
                exited.countDown()
            }
        }
    }

    override fun getInputStream(): InputStream = input

    override fun getOutputStream(): OutputStream = output

    override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))

    override fun waitFor(): Int {
        exited.await()
        return exitCode
    }

    override fun waitFor(
        timeout: Long,
        unit: TimeUnit,
    ): Boolean = exited.await(timeout, unit)

    override fun exitValue(): Int {
        if (exited.count > 0) throw IllegalThreadStateException("process has not exited")
        return exitCode
    }

    override fun destroy() {
        destroyed.set(true)
        exited.countDown()
    }

    override fun destroyForcibly(): Process {
        destroyedForcibly.set(true)
        exited.countDown()
        return this
    }

    override fun isAlive(): Boolean = exited.count > 0
}
