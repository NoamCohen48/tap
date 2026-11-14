package io.github.noamcohen48.tap.host

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LogcatTest {
    /** A logcat child whose output the test writes; destroying it ends the output. */
    private class Child : Process() {
        private val feed = PipedOutputStream()
        private val output = PipedInputStream(feed, 64 * 1024)

        @Volatile var alive = true

        fun print(vararg lines: String) {
            lines.forEach { feed.write((it + "\n").toByteArray()) }
            feed.flush()
        }

        fun exit() {
            alive = false
            feed.close()
        }

        override fun getOutputStream() = ByteArrayOutputStream()

        override fun getInputStream(): InputStream = output

        override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())

        override fun waitFor(): Int = 0

        override fun waitFor(
            timeout: Long,
            unit: TimeUnit,
        ): Boolean = !alive

        override fun exitValue(): Int = 0

        override fun destroy() = exit()

        override fun isAlive(): Boolean = alive
    }

    private class Recorded : LogcatCapture.Sink {
        val lines = LinkedBlockingQueue<LogcatLine>()
        val gaps = LinkedBlockingQueue<String>()

        override fun line(line: LogcatLine) {
            lines.put(line)
        }

        override fun interrupted(reason: String) {
            gaps.put(reason)
        }

        fun nextLine(): LogcatLine = assertNotNull(lines.poll(5, TimeUnit.SECONDS), "no line arrived")
    }

    /** Hands out [children] in order and records what each start asked for. */
    private class StreamingAdb(
        vararg children: Child,
    ) : Adb("fake-adb") {
        private val pending = LinkedBlockingQueue(children.toList())
        val since = CopyOnWriteArrayList<String?>()

        override fun startLogcat(
            serial: String,
            since: String?,
        ): Process {
            this.since += since
            return pending.poll() ?: error("device offline")
        }
    }

    @Test
    fun `a line carries its time uid process priority tag and message`() {
        val line = parseLogcatLine("         1794608507.879 10130 23461 23571 I Finsky  : [60] SCH: Scheduling job")

        assertEquals(LogcatLine(1794608507879, 10130, 23461, 23571, 'I', "Finsky", "[60] SCH: Scheduling job"), line)
    }

    @Test
    fun `a tag with a colon and an empty message are read whole`() {
        val tagged = assertNotNull(parseLogcatLine("1790110849.224 10016  2077  2499 D Net:Controller(0/1): onSignal a: b"))
        val empty = assertNotNull(parseLogcatLine("1790110849.224  1000  2077  2499 W Tag     :"))

        assertEquals("Net:Controller(0/1)" to "onSignal a: b", tagged.tag to tagged.message)
        assertEquals("Tag" to "", empty.tag to empty.message)
    }

    @Test
    fun `a uid printed by name is its number and an unknown name is absent`() {
        assertEquals(0, parseLogcatLine("1790110849.224  root  2077  2499 I init    : started")?.uid)
        assertNull(assertNotNull(parseLogcatLine("1790110849.224  vendr  2077  2499 I init    : started")).uid)
    }

    @Test
    fun `banners and adb errors are not lines`() {
        assertNull(parseLogcatLine("--------- beginning of main"))
        assertNull(parseLogcatLine("- waiting for device -"))
        assertNull(parseLogcatLine(""))
    }

    @Test
    fun `the capture starts at the attachment time and delivers each line`() {
        val child = Child()
        val adb = StreamingAdb(child)
        val sink = Recorded()
        val capture = LogcatCapture(adb, "serial", 1_700_000_000, sink).also { it.start() }
        try {
            child.print("--------- beginning of main", "1700000001.000 10001   10   11 D App     : one", "1700000002.500 10001   10   11 E App     : two")

            assertEquals("one", sink.nextLine().message)
            assertEquals(1_700_000_002_500, sink.nextLine().deviceTimeMs)
            assertEquals(listOf<String?>("1700000000.000"), adb.since.toList())
        } finally {
            capture.close()
        }
        assertTrue(!child.alive, "the child is ended on close")
        assertTrue(sink.gaps.isEmpty(), "closing is not an interruption")
    }

    @Test
    fun `a child that ends is restarted from the last line and the interruption is reported once`() {
        val first = Child()
        val second = Child()
        val adb = StreamingAdb(first, second)
        val sink = Recorded()
        val capture = LogcatCapture(adb, "serial", null, sink, firstRetryMs = 10).also { it.start() }
        try {
            first.print("1700000001.250 10001   10   11 D App     : before", "error: device offline")
            assertEquals("before", sink.nextLine().message)
            first.exit()

            assertEquals("logcat ended: error: device offline", sink.gaps.poll(5, TimeUnit.SECONDS))
            // The restart replays the last line's millisecond: it is not delivered twice.
            second.print("1700000001.250 10001   10   11 D App     : before", "1700000003.000 10001   10   11 D App     : after")
            assertEquals("after", sink.nextLine().message)
            assertEquals(listOf(null, "1700000001.250"), adb.since.toList())
            assertTrue(sink.gaps.isEmpty())
        } finally {
            capture.close()
        }
    }

    @Test
    fun `a logcat that cannot start is retried until closed`() {
        val adb = StreamingAdb()
        val sink = Recorded()
        val capture = LogcatCapture(adb, "serial", null, sink, firstRetryMs = 5).also { it.start() }

        assertEquals("logcat could not be read: device offline", sink.gaps.poll(5, TimeUnit.SECONDS))
        Thread.sleep(50)
        capture.close()

        assertTrue(adb.since.size > 1, "it kept trying")
        assertTrue(sink.gaps.isEmpty(), "one report per interruption")
    }
}
