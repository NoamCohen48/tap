package io.github.noamcohen48.tap.daemon.core

import io.github.noamcohen48.tap.api.v1.LogEntry
import io.github.noamcohen48.tap.api.v1.LogPriority
import io.github.noamcohen48.tap.host.LogcatLine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.util.regex.Pattern
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeviceLogTest {
    private val log = DeviceLog().apply { driverUid = DRIVER }

    private fun line(
        message: String,
        uid: Int? = APP,
        priority: Char = 'D',
        tag: String = "App",
    ) = LogcatLine(1_700_000_000_000, uid, 10, 11, priority, tag, message)

    private fun DeviceLog.messages(
        start: LogStart = LogStart.Oldest,
        filter: LogFilter = LogFilter(),
    ): List<String> = read(start, filter).backlog.map(::summary)

    private fun summary(entry: LogEntry): String =
        when (entry.kindCase) {
            LogEntry.KindCase.LINE -> entry.line.message
            LogEntry.KindCase.MARKER -> "marker:${entry.marker}"
            LogEntry.KindCase.GAP -> "gap:${entry.gap}"
            LogEntry.KindCase.ACTION -> "action:${entry.action.operation}" + if (entry.action.finished) ":${entry.action.outcome}" else ""
            else -> "?"
        }

    @Test
    fun `entries are numbered in arrival order whatever their kind`() {
        log.line(line("one"))
        val marker = log.mark("checkout")
        log.interrupted("logcat ended")
        log.line(line("two"))

        assertEquals(listOf("one", "marker:checkout", "gap:logcat ended", "two"), log.messages())
        assertEquals(listOf(1L, 2L, 3L, 4L), log.read().backlog.map { it.cursor })
        assertEquals(2, marker)
    }

    @Test
    fun `the driver's own lines become actions and an app using the tag stays a line`() {
        log.line(line("> #7 tap text=\"Pay\"", uid = DRIVER, tag = "Tap"))
        log.line(line("onPayClicked"))
        log.line(line("< #7 tap ok 243ms", uid = DRIVER, tag = "Tap"))
        log.line(line("< #8 tap ok 1ms", uid = APP, tag = "Tap"))

        val entries = log.read().backlog
        assertEquals(listOf("action:tap", "onPayClicked", "action:tap:ok", "< #8 tap ok 1ms"), entries.map(::summary))
        with(entries[0].action) {
            assertEquals("text=\"Pay\"", target)
            assertEquals(7, requestId)
            assertEquals(1_700_000_000_000, deviceTimeMs)
        }
        assertEquals(243, entries[2].action.durationMs)
    }

    @Test
    fun `a host action is written at its start and at its end`() {
        log.hostAction("launch", "com.shop")
        log.hostAction("launch", "com.shop", "APP_LIFECYCLE", 1_200)

        val (started, finished) = log.read().backlog.map { it.action }
        assertFalse(started.finished)
        assertEquals("com.shop", started.target)
        assertEquals("APP_LIFECYCLE" to 1_200L, finished.outcome to finished.durationMs)
    }

    @Test
    fun `line filters keep markers actions and gaps`() {
        log.line(line("debug"))
        log.line(line("other app", uid = 10_999, priority = 'E'))
        log.mark("m")
        log.line(line("payments failed", priority = 'E', tag = "Payments"))
        log.hostAction("launch", "com.shop")

        assertEquals(listOf("marker:m", "payments failed", "action:launch"), log.messages(filter = LogFilter(uids = setOf(APP), minPriority = LogPriority.LOG_PRIORITY_WARN)))
        assertEquals(listOf("marker:m", "payments failed", "action:launch"), log.messages(filter = LogFilter(tags = setOf("Payments"))))
        assertEquals(listOf("marker:m", "payments failed"), log.messages(filter = LogFilter(message = Pattern.compile("fail"), excludeActions = true)))
    }

    @Test
    fun `a read starts after a cursor at the newest marker of a name or at the newest action`() {
        log.line(line("one"))
        log.mark("step")
        log.line(line("two"))
        val second = log.mark("step")
        log.line(line("three"))
        log.line(line("> #1 tap", uid = DRIVER, tag = "Tap"))
        log.line(line("four"))
        log.line(line("< #1 tap ok 5ms", uid = DRIVER, tag = "Tap"))

        assertEquals(listOf("three", "action:tap", "four", "action:tap:ok"), log.messages(LogStart.After(second)))
        assertEquals("marker:step", log.messages(LogStart.Marker("step")).first())
        assertEquals(5, log.messages(LogStart.Marker("step")).size)
        assertEquals(listOf("action:tap", "four", "action:tap:ok"), log.messages(LogStart.LastAction))
        assertEquals(listOf("three", "four"), log.messages(LogStart.Tail(2), LogFilter(excludeActions = true, tags = setOf("App"))).filterNot { it.startsWith("marker") })
        assertFailsWith<UnknownLogMarkerException> { log.read(LogStart.Marker("missing")) }
    }

    @Test
    fun `secrets are redacted when read including by a pattern added after the line`() {
        log.line(line("login password=hunter2"))
        log.line(line("order A17 placed"))
        log.redactor.add(listOf("""order (\w+)"""))
        log.redactor.literal("placed")

        assertEquals(listOf("login password=<redacted>", "order <redacted> <redacted>"), log.messages())
        // The message filter sees what the caller sees.
        assertEquals(emptyList(), log.messages(filter = LogFilter(message = Pattern.compile("hunter2"))))
    }

    @Test
    fun `the oldest entries are dropped past the size limit and a read says how many it missed`() {
        val small = DeviceLog(maxBytes = 400)
        repeat(10) { small.line(line("message number $it")) }

        val all = small.read()
        assertTrue(all.backlog.size < 10)
        assertEquals(10L - all.backlog.size, all.dropped)
        assertEquals("message number 9", all.backlog.last().line.message)
        assertEquals(0, small.read(LogStart.After(all.backlog.first().cursor - 1)).dropped)
        assertEquals(0, small.read(LogStart.Tail(1)).dropped)
    }

    @Test
    fun `a follower gets the backlog then new entries that pass its filter and ends when the log closes`(): Unit =
        runBlocking {
            log.line(line("before"))
            val subscription = log.read(filter = LogFilter(tags = setOf("App")), follow = true)
            log.line(line("skipped", tag = "Other"))
            log.line(line("token=abc after"))
            log.mark("end")
            log.close()

            assertEquals(listOf("before"), subscription.backlog.map(::summary))
            assertEquals(listOf("token=<redacted> after", "marker:end"), log.live(subscription).toList().flatten().map(::summary))
        }

    @Test
    fun `a follower that falls behind is ended and the log goes on`(): Unit =
        runBlocking {
            val tight = DeviceLog(readerCapacity = 2)
            val subscription = tight.read(follow = true)
            repeat(3) { tight.line(line("line $it")) }

            assertFailsWith<LogReaderOverflowException> { tight.live(subscription).toList() }
            assertEquals(3, tight.read().backlog.size)
        }

    @Test
    fun `a driver request's actions carry its step whether the lines arrive before or after it is known`() {
        val sentAt = System.currentTimeMillis()
        log.line(line("> #7 tap text=\"Pay\"", uid = DRIVER, tag = "Tap"))
        log.commandStep(7, "s-1", sentAt)
        log.line(line("pay clicked"))
        log.line(line("< #7 tap ok 243ms", uid = DRIVER, tag = "Tap"))
        log.hostAction("launch", "com.shop", step = "s-2")
        log.line(line("started"))
        log.hostAction("launch", "com.shop", "ok", 12, step = "s-2")
        log.line(line("> #8 tap text=\"Back\"", uid = DRIVER, tag = "Tap"))

        val actions = log.read().backlog.filter { it.hasAction() }.map { if (it.action.hasStep()) it.action.step else null }
        assertEquals(listOf("s-1", "s-1", "s-2", "s-2", null), actions)
        assertEquals(listOf("action:tap", "pay clicked", "action:tap:ok"), log.messages(filter = LogFilter(step = "s-1")))
        assertEquals(listOf("action:launch", "started", "action:launch:ok"), log.messages(filter = LogFilter(step = "s-2")))
        assertEquals(emptyList(), log.messages(filter = LogFilter(step = "s-3")))
    }

    @Test
    fun `a request id used again after a reconnect does not give an older action its step`() {
        log.line(line("> #7 tap text=\"Pay\"", uid = DRIVER, tag = "Tap"))
        log.line(line("< #7 tap ok 2ms", uid = DRIVER, tag = "Tap"))

        log.commandStep(7, "s-9", System.currentTimeMillis() + 60_000)

        assertTrue(log.read().backlog.none { it.action.hasStep() })
    }

    @Test
    fun `an entry is one line of text`() {
        log.line(line("hello", priority = 'W'))
        log.mark("checkout")
        log.hostAction("launch", "com.shop", "ok", 12)

        assertEquals(
            listOf("1700000000.000 10123    10    11 W App: hello", "── marker checkout", "── launch  com.shop  → ok  12 ms"),
            log.read().backlog.map(DeviceLog::text),
        )
    }

    private companion object {
        const val APP = 10_123
        const val DRIVER = 10_247
    }
}
