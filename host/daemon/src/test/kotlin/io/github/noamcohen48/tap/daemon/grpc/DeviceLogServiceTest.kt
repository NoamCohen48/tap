package io.github.noamcohen48.tap.daemon.grpc

import io.github.noamcohen48.tap.api.v1.CommandResult
import io.github.noamcohen48.tap.api.v1.DeviceCall
import io.github.noamcohen48.tap.api.v1.DeviceLogsRequest
import io.github.noamcohen48.tap.api.v1.Done
import io.github.noamcohen48.tap.api.v1.FailureReason
import io.github.noamcohen48.tap.api.v1.LogEntry
import io.github.noamcohen48.tap.api.v1.LogPriority
import io.github.noamcohen48.tap.api.v1.MarkLogRequest
import io.github.noamcohen48.tap.api.v1.ReadLogRequest
import io.github.noamcohen48.tap.api.v1.RedactLogRequest
import io.github.noamcohen48.tap.api.v1.TypeText
import io.github.noamcohen48.tap.api.v1.WatchLogRequest
import io.github.noamcohen48.tap.host.MediaClock
import io.github.noamcohen48.tap.api.v1.Command
import io.github.noamcohen48.tap.host.Adb
import io.github.noamcohen48.tap.host.FakeProcess
import io.github.noamcohen48.tap.host.LogcatLine
import io.github.noamcohen48.tap.api.v1.ClientConnectionServiceGrpc
import io.github.noamcohen48.tap.api.v1.ClientConnectionServiceGrpcKt
import io.github.noamcohen48.tap.api.v1.InfoRequest
import io.github.noamcohen48.tap.api.v1.InfoResponse
import io.grpc.Context
import io.grpc.Metadata
import io.grpc.ServerInterceptors
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.StatusRuntimeException
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import io.grpc.kotlin.GrpcContextElement
import io.grpc.stub.MetadataUtils
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeviceLogServiceTest {
    /** A device whose logcat child prints nothing: the tests write the lines themselves. */
    private class Device : Adb("fake-adb") {
        val children = CopyOnWriteArrayList<FakeProcess>()

        override fun startLogcat(
            serial: String,
            since: String?,
        ): Process = FakeProcess(exitDelayMs = FakeProcess.NEVER, blockingStdout = true, stdoutEndsOnDestroy = true).also { children += it }

        val installed = mutableMapOf("com.shop" to SHOP, "com.other" to OTHER, "com.other.plugin" to OTHER)
        var packageReads = 0

        override suspend fun packageUids(serial: String): Map<String, Int> {
            packageReads++
            return installed.toMap()
        }

        override suspend fun logcat(
            serial: String,
            buffers: List<String>,
            sinceEpochSeconds: Long?,
            tags: List<String>,
        ): String = "ring buffer token=abc\n"

        override suspend fun dumpsys(
            serial: String,
            vararg arguments: String,
        ): String = ""
    }

    private fun line(
        message: String,
        uid: Int = SHOP,
        priority: Char = 'D',
        tag: String = "Shop",
    ) = LogcatLine(1_700_000_000_000, uid, 10, 11, priority, tag, message)

    private fun AttachedHarness.request(): ReadLogRequest.Builder =
        ReadLogRequest.newBuilder().setClientConnectionId(connectionId).setAttachedDeviceId(attached.id)

    private suspend fun AttachedHarness.read(configure: ReadLogRequest.Builder.() -> Unit = {}): List<LogEntry> =
        logs.readLog(request().apply(configure).build()).toList().flatMap { it.entriesList }

    private suspend fun AttachedHarness.mark(name: String): Long =
        logs.markLog(MarkLogRequest.newBuilder().setClientConnectionId(connectionId).setAttachedDeviceId(attached.id).setName(name).build()).cursor

    private fun messages(entries: List<LogEntry>): List<String> =
        entries.map { if (it.hasLine()) it.line.message else if (it.hasMarker()) "marker:${it.marker}" else "action:${it.action.operation}" }

    private suspend fun status(block: suspend () -> Unit): StatusException = assertFailsWith<StatusException> { block() }

    @Test
    fun `the log is read from the start from a marker and for one app`(): Unit =
        runBlocking {
            AttachedHarness.with(Device(), captureLogs = true) {
                val log = attached.logs!!
                log.line(line("starting"))
                val cursor = mark("checkout")
                log.line(line("pay clicked"))
                log.line(line("unrelated", uid = OTHER))
                log.line(line("card declined", priority = 'E'))

                assertEquals(listOf("starting", "marker:checkout", "pay clicked", "unrelated", "card declined"), messages(read()))
                assertEquals(listOf("marker:checkout", "pay clicked", "card declined"), messages(read { marker = "checkout"; addPackages("com.shop") }))
                assertEquals(listOf("card declined"), messages(read { afterCursor = cursor; minPriority = LogPriority.LOG_PRIORITY_ERROR }))
                assertEquals(listOf("card declined"), messages(read { tail = 1 }))
            }
        }

    @Test
    fun `each app line names the packages installed under its uid`(): Unit =
        runBlocking {
            val device = Device()
            AttachedHarness.with(device, captureLogs = true) {
                val log = attached.logs!!
                log.line(line("shop"))
                log.line(line("shared uid", uid = OTHER))
                log.line(line("second user", uid = 1_000_000 + SHOP))
                log.line(line("system server", uid = 1000))
                log.line(line("not installed", uid = 10_999))
                val lines = read().filter { it.hasLine() }.associate { it.line.message to it.line.packagesList }
                assertEquals(
                    mapOf(
                        "shop" to listOf("com.shop"),
                        "shared uid" to listOf("com.other", "com.other.plugin"),
                        "second user" to listOf("com.shop"),
                        "system server" to emptyList(),
                        "not installed" to emptyList(),
                    ),
                    lines,
                )
                // The unknown app id asked once; a second read within the refresh interval does not ask again.
                val reads = device.packageReads
                read()
                assertEquals(reads, device.packageReads)
            }
        }

    @Test
    fun `typed sensitive text and caller patterns are redacted in the log and in the failure evidence`(): Unit =
        runBlocking {
            AttachedHarness.with(Device(), captureLogs = true) {
                val log = attached.logs!!
                log.line(line("login as noam with hunter2-pin, order A17"))
                execute(
                    Command.newBuilder().setTypeText(TypeText.newBuilder().setText("hunter2-pin").setSensitive(true)).build(),
                    CommandResult.newBuilder().setDone(Done.getDefaultInstance()),
                )
                logs.redactLog(
                    RedactLogRequest.newBuilder().setClientConnectionId(connectionId).setAttachedDeviceId(attached.id).addPatterns("""order (\w+)""").build(),
                )

                assertEquals(listOf("login as noam with <redacted>, order <redacted>"), messages(read()))
                val evidence = devices.deviceLogs(DeviceLogsRequest.newBuilder().setClientConnectionId(connectionId).setAttachedDeviceId(attached.id).build())
                assertTrue("with <redacted>, order <redacted>" in evidence.logcat, evidence.logcat)
                assertFalse("ring buffer" in evidence.logcat, "the captured log replaces the ring buffer read")
                assertFalse(evidence.logcatTruncated)
            }
        }

    @Test
    fun `a host call is written to the log at its start and end and the driver's lines become actions`(): Unit =
        runBlocking {
            AttachedHarness.with(Device(), captureLogs = true) {
                val log = attached.logs!!.also { it.driverUid = DRIVER }
                attached.recorded<Unit>({ setDevice(DeviceCall.newBuilder().setOperation("set_dark_mode").setEnabled(true)) }) { }
                log.line(line("> #4 tap text=\"Pay\"", uid = DRIVER, tag = "Tap"))
                log.line(line("pay clicked"))

                val entries = read()
                assertEquals(listOf("action:set_dark_mode", "action:set_dark_mode", "action:tap", "pay clicked"), messages(entries))
                assertEquals("ok", entries[1].action.outcome)
                assertEquals(listOf("action:tap", "pay clicked"), messages(read { lastAction = true }))
                assertEquals(listOf("pay clicked"), messages(read { excludeActions = true }))
            }
        }

    @Test
    fun `the step a call carried is on its event and its log actions and a read can ask for it`(): Unit =
        runBlocking {
            AttachedHarness.with(Device(), captureLogs = true) {
                val log = attached.logs!!.also { it.driverUid = DRIVER }
                log.line(line("before"))
                withContext(GrpcContextElement(Context.current().withValue(STEP, "s-1"))) {
                    attached.recorded<Unit>({ setDevice(DeviceCall.newBuilder().setOperation("set_dark_mode").setEnabled(true)) }) { }
                }
                val requestId =
                    execute(
                        Command.newBuilder().setTypeText(TypeText.newBuilder().setText("milk")).build(),
                        CommandResult.newBuilder().setDone(Done.getDefaultInstance()),
                        step = "s-1",
                    ).requestId
                log.line(line("> #$requestId type_text", uid = DRIVER, tag = "Tap"))
                log.line(line("typed"))
                log.line(line("< #$requestId type_text ok 3ms", uid = DRIVER, tag = "Tap"))
                log.line(line("after"))

                assertEquals(listOf("s-1", "s-1"), attached.events.after(0).first.map { it.step })
                val step = read { step = "s-1" }
                assertEquals(listOf("action:set_dark_mode", "action:set_dark_mode", "action:type_text", "typed", "action:type_text"), messages(step))
                assertTrue(step.filter { it.hasAction() }.all { it.action.step == "s-1" })
                assertEquals(Status.Code.INVALID_ARGUMENT, status { read { this.step = "s-1"; follow = true } }.status.code)
                assertEquals(Status.Code.INVALID_ARGUMENT, status { read { this.step = "not a step" } }.status.code)
            }
        }

    @Test
    fun `a call's tap-step header is its step and a malformed one is refused`() {
        val service =
            object : ClientConnectionServiceGrpcKt.ClientConnectionServiceCoroutineImplBase() {
                override suspend fun info(request: InfoRequest): InfoResponse = InfoResponse.newBuilder().setDaemonVersion(STEP.get() ?: "none").build()
            }
        val name = InProcessServerBuilder.generateName()
        val server = InProcessServerBuilder.forName(name).directExecutor().addService(ServerInterceptors.intercept(service, StepInterceptor())).build().start()
        val channel = InProcessChannelBuilder.forName(name).directExecutor().build()
        try {
            fun info(step: String?): String {
                val headers = Metadata().apply { step?.let { put(StepInterceptor.HEADER, it) } }
                return ClientConnectionServiceGrpc
                    .newBlockingStub(channel)
                    .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers))
                    .info(InfoRequest.getDefaultInstance())
                    .daemonVersion
            }
            assertEquals("none", info(null))
            assertEquals("run-1.step_7", info("run-1.step_7"))
            for (bad in listOf("", "two words", "x".repeat(65), "a/b")) {
                assertEquals(Status.Code.INVALID_ARGUMENT, assertFailsWith<StatusRuntimeException> { info(bad) }.status.code, bad)
            }
        } finally {
            channel.shutdownNow()
            server.shutdownNow()
        }
    }

    @Test
    fun `a follower gets the backlog first and then each new entry`(): Unit =
        runBlocking {
            AttachedHarness.with(Device(), captureLogs = true) {
                val log = attached.logs!!
                log.line(line("before"))
                val followed = async { logs.readLog(request().setFollow(true).build()).take(2).toList() }
                // The follower is registered once its first response is on its way.
                withTimeout(5_000) {
                    while (!followed.isCompleted) {
                        log.mark("live")
                        delay(10)
                    }
                }

                val (backlog, live) = withTimeout(5_000) { followed.await() }
                assertEquals("before", messages(backlog.entriesList).first())
                assertTrue(live.entriesCount > 0 && live.entriesList.all { it.hasMarker() })
                assertEquals(backlog.entriesList.last().cursor + 1, live.entriesList.first().cursor, "no gap and no overlap")
            }
        }

    @Test
    fun `a watcher follows the redacted log without owning the device until it is detached`(): Unit =
        runBlocking {
            AttachedHarness.with(Device(), captureLogs = true) {
                val log = attached.logs!!
                log.line(line("login token=abc"))
                val cursor = mark("checkout")
                log.line(line("pay clicked"))
                val request = WatchLogRequest.newBuilder().setAttachedDeviceId(attached.id)

                val first = watch.watchLog(request.build()).first()
                assertEquals(listOf("login token=<redacted>", "marker:checkout", "pay clicked"), messages(first.entriesList))
                assertEquals(MediaClock.id, first.clockId)
                assertEquals(0, first.dropped)
                assertTrue(first.entriesList.all { it.hostMonotonicNs > 0 && it.hostTimeMs > 0 })
                assertTrue(first.entriesList.zipWithNext().all { (a, b) -> a.hostMonotonicNs <= b.hostMonotonicNs })

                val following = CompletableDeferred<Unit>()
                val followed = async { watch.watchLog(request.setAfterCursor(cursor).build()).onEach { following.complete(Unit) }.toList() }
                // The follower is registered before its first response is sent.
                withTimeout(5_000) { following.await() }
                log.line(line("paid"))
                daemon.detachDevice(attached.id, connectionId)
                val entries = withTimeout(5_000) { followed.await() }.flatMap { it.entriesList }
                assertEquals(listOf("pay clicked", "paid"), messages(entries), "after the cursor, live, and ended by the detach")

                assertEquals(Status.Code.NOT_FOUND, status { watch.watchLog(request.build()).toList() }.status.code)
                assertEquals(Status.Code.INVALID_ARGUMENT, status { watch.watchLog(WatchLogRequest.getDefaultInstance()).toList() }.status.code)
            }
            AttachedHarness.with(Device()) {
                val refused = status { watch.watchLog(WatchLogRequest.newBuilder().setAttachedDeviceId(attached.id).build()).toList() }
                assertEquals(Status.Code.FAILED_PRECONDITION, refused.status.code)
            }
        }

    @Test
    fun `an attachment without capture refuses the log and reads the ring buffer for evidence`(): Unit =
        runBlocking {
            val device = Device()
            AttachedHarness.with(device) {
                val refused = status { read() }
                assertEquals(Status.Code.FAILED_PRECONDITION, refused.status.code)
                assertEquals(FailureReason.FAILURE_REASON_DAEMON_PRECONDITION, refused.trailers?.get(FAILURE_TRAILER)?.reason)
                assertEquals(Status.Code.FAILED_PRECONDITION, status { mark("m") }.status.code)

                val evidence = devices.deviceLogs(DeviceLogsRequest.newBuilder().setClientConnectionId(connectionId).setAttachedDeviceId(attached.id).build())
                assertEquals("ring buffer token=<redacted>", evidence.logcat.trim())
            }
            assertTrue(device.children.isEmpty(), "no logcat child without capture")
        }

    @Test
    fun `bad input is refused before anything is read`(): Unit =
        runBlocking {
            AttachedHarness.with(Device(), captureLogs = true) {
                val missing = status { read { marker = "never written" } }
                assertEquals(Status.Code.NOT_FOUND, missing.status.code)
                assertEquals(FailureReason.FAILURE_REASON_UNKNOWN_LOG_MARKER, missing.trailers?.get(FAILURE_TRAILER)?.reason)
                assertEquals(Status.Code.INVALID_ARGUMENT, status { read { addPackages("com.not.installed") } }.status.code)
                assertEquals(Status.Code.INVALID_ARGUMENT, status { read { messageRegex = "(" } }.status.code)
                assertEquals(Status.Code.INVALID_ARGUMENT, status { read { tail = 0 } }.status.code)
                assertEquals(Status.Code.INVALID_ARGUMENT, status { mark("two\nlines") }.status.code)
                val pattern = RedactLogRequest.newBuilder().setClientConnectionId(connectionId).setAttachedDeviceId(attached.id).addPatterns("(")
                assertEquals(Status.Code.INVALID_ARGUMENT, status { logs.redactLog(pattern.build()) }.status.code)
                assertEquals(Status.Code.PERMISSION_DENIED, status { logs.readLog(request().setClientConnectionId("someone-else").build()).first() }.status.code)
            }
        }

    @Test
    fun `detaching ends the logcat child`(): Unit =
        runBlocking {
            val device = Device()
            AttachedHarness.with(device, captureLogs = true) {
                withTimeout(5_000) { while (device.children.isEmpty()) yield() }
            }
            assertTrue(device.children.single().destroyed.get())
        }

    private companion object {
        const val SHOP = 10_123
        const val OTHER = 10_456
        const val DRIVER = 10_247
    }
}
