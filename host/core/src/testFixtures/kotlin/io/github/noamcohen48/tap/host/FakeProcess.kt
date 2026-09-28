package io.github.noamcohen48.tap.host

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
 * [destroy]/[destroyForcibly] is recorded in [destroyed]/[destroyedForcibly]. Tests can make a
 * stubborn child with [survivesDestroy] and a drain that ignores close with [blockingStdout].
 */
class FakeProcess(
    stdout: String = "",
    private val exitCode: Int = 0,
    exitDelayMs: Long = 0,
    private val survivesDestroy: Boolean = false,
    blockingStdout: Boolean = false,
    /** With [blockingStdout]: stdout ends when the process is destroyed, as a real child's does. */
    private val stdoutEndsOnDestroy: Boolean = false,
    private val timedWaitAlwaysFalse: Boolean = false,
    /**
     * When true with [timedWaitAlwaysFalse], the timed wait actually consumes the supplied
     * timeout (sleeping it) instead of returning false immediately. Lets the aggregate-reap test
     * prove the reap shares one deadline: a per-step two-budget implementation would consume the
     * timeout twice. Opt-in so unrelated fakes stay fast.
     */
    private val timedWaitConsumesTimeout: Boolean = false,
) : Process() {
    companion object {
        const val NEVER = Long.MAX_VALUE
    }

    private val exited = CountDownLatch(1)
    val destroyed = AtomicBoolean(false)
    val destroyedForcibly = AtomicBoolean(false)
    val stdinClosed = AtomicBoolean(false)
    val stdoutClosed = AtomicBoolean(false)
    val stderrClosed = AtomicBoolean(false)
    private val stdoutRelease = CountDownLatch(if (blockingStdout) 1 else 0)
    private val input: InputStream =
        if (blockingStdout) {
            object : InputStream() {
                override fun read(): Int {
                    while (true) {
                        try {
                            stdoutRelease.await()
                            return -1
                        } catch (_: InterruptedException) {
                            // Model a native/blocking drain that does not respond to cancellation.
                        }
                    }
                }

                override fun close() {
                    stdoutClosed.set(true)
                }
            }
        } else {
            object : ByteArrayInputStream(stdout.toByteArray()) {
                override fun close() {
                    stdoutClosed.set(true)
                    super.close()
                }
            }
        }
    private val output =
        object : ByteArrayOutputStream() {
            override fun close() {
                stdinClosed.set(true)
                super.close()
            }
        }
    private val error =
        object : ByteArrayInputStream(ByteArray(0)) {
            override fun close() {
                stderrClosed.set(true)
                super.close()
            }
        }

    init {
        if (exitDelayMs != NEVER) {
            thread(isDaemon = true, name = "fake-process-exit") {
                if (exitDelayMs > 0) Thread.sleep(exitDelayMs)
                exited.countDown()
            }
        }
    }

    fun releaseStdout() = stdoutRelease.countDown()

    private fun endOnDestroy() {
        exited.countDown()
        if (stdoutEndsOnDestroy) stdoutRelease.countDown()
    }

    fun forceExit() = exited.countDown()

    override fun getInputStream(): InputStream = input

    override fun getOutputStream(): OutputStream = output

    override fun getErrorStream(): InputStream = error

    override fun waitFor(): Int {
        exited.await()
        return exitCode
    }

    override fun waitFor(
        timeout: Long,
        unit: TimeUnit,
    ): Boolean {
        if (timedWaitAlwaysFalse) {
            if (timedWaitConsumesTimeout) {
                try {
                    unit.sleep(timeout)
                } catch (_: InterruptedException) {
                    // Still unproven: the deadline, not the interrupt, decides the verdict.
                }
            }
            return false
        }
        return exited.await(timeout, unit)
    }

    override fun exitValue(): Int {
        if (exited.count > 0) throw IllegalThreadStateException("process has not exited")
        return exitCode
    }

    override fun destroy() {
        destroyed.set(true)
        if (!survivesDestroy) endOnDestroy()
    }

    override fun destroyForcibly(): Process {
        destroyedForcibly.set(true)
        if (!survivesDestroy) endOnDestroy()
        return this
    }

    override fun isAlive(): Boolean = exited.count > 0
}
