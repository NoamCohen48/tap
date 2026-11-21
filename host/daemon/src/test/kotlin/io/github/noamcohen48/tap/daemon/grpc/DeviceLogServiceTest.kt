package io.github.noamcohen48.tap.daemon.grpc

import io.github.noamcohen48.tap.api.v1.ClientConnectionServiceGrpc
import io.github.noamcohen48.tap.api.v1.ClientConnectionServiceGrpcKt
import io.github.noamcohen48.tap.api.v1.Command
import io.github.noamcohen48.tap.api.v1.CommandResult
import io.github.noamcohen48.tap.api.v1.DeleteHistoryRecordingRequest
import io.github.noamcohen48.tap.api.v1.DeviceCall
import io.github.noamcohen48.tap.api.v1.DeviceLogsRequest
import io.github.noamcohen48.tap.api.v1.Done
import io.github.noamcohen48.tap.api.v1.FailureReason
import io.github.noamcohen48.tap.api.v1.HistoryLogFilter
import io.github.noamcohen48.tap.api.v1.HistorySelection
import io.github.noamcohen48.tap.api.v1.InfoRequest
import io.github.noamcohen48.tap.api.v1.InfoResponse
import io.github.noamcohen48.tap.api.v1.ListHistoryRecordingsRequest
import io.github.noamcohen48.tap.api.v1.LogEntry
import io.github.noamcohen48.tap.api.v1.LogPriority
import io.github.noamcohen48.tap.api.v1.MarkLogRequest
import io.github.noamcohen48.tap.api.v1.StartHistoryRecordingRequest
import io.github.noamcohen48.tap.api.v1.ReadHistoryRequest
import io.github.noamcohen48.tap.api.v1.ReadLogRequest
import io.github.noamcohen48.tap.api.v1.RedactLogRequest
import io.github.noamcohen48.tap.api.v1.StopHistoryRecordingRequest
import io.github.noamcohen48.tap.api.v1.TypeText
import io.github.noamcohen48.tap.api.v1.WatchLogRequest
import io.github.noamcohen48.tap.host.Adb
import io.github.noamcohen48.tap.host.FakeProcess
import io.github.noamcohen48.tap.host.LogcatCapture
import io.github.noamcohen48.tap.host.LogcatLine
import io.github.noamcohen48.tap.host.MediaClock
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
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeviceLogServiceTest {
    @Test
    fun `protected history remains readable after release`(): Unit =
        runBlocking {
            val device = Device()
            AttachedHarness.with(device, captureLogs = true) {
                val log = attached.logs!!.also { device.echo = it }
                log.line(line("before"))
                val id =
                    history
                        .startHistoryRecording(
                            StartHistoryRecordingRequest
                                .newBuilder()
                                .setConnectionId(connectionId)
                                .setName("flow")
                                .build(),
                        ).recording.id
                log.line(line("inside"))
                val saved = history.stopHistoryRecording(StopHistoryRecordingRequest.newBuilder().setRecordingId(id).build()).recording
                assertTrue(saved.issuesList.isEmpty())
                log.line(line("after"))
                daemon.disconnectClient(connectionId, "test release")
                val response =
                    history.readHistory(
                        ReadHistoryRequest
                            .newBuilder()
                            .setSelection(HistorySelection.newBuilder().setRecordingId(id))
                            .setIncludeLogs(true)
                            .build(),
                    )
                assertEquals(listOf("inside"), response.entriesList.filter { it.hasLine() }.map { it.line.message })
                assertTrue(response.sourcesList.all { it.clockId.isNotEmpty() })
                assertTrue(response.issuesList.isEmpty())
                assertEquals(
                    "flow",
                    history
                        .listHistoryRecordings(ListHistoryRecordingsRequest.getDefaultInstance())
                        .recordingsList
                        .single()
                        .name,
                )
            }
        }

    @Test
    fun `a stop keeps the lines logged before it that the capture had not delivered yet`(): Unit =
        runBlocking {
            val device = Device()
            AttachedHarness.with(device, captureLogs = true) {
                val log = attached.logs!!.also { device.echo = it }
                val id = startRecording("flow")
                device.lagging += line("late") // logged before the stop, still in logcat's backlog
                history.stopHistoryRecording(StopHistoryRecordingRequest.newBuilder().setRecordingId(id).build())
                log.line(line("after"))

                val response = readRecording(id)
                assertEquals(listOf("late"), response.entriesList.filter { it.hasLine() }.map { it.line.message })
                // The fake device has no crash log, so its crash follower may leave a gap on the connection's source.
                assertEquals(emptyList<String>(), response.entriesList.filter { it.hasGap() && it.sourceId == attached.id }.map { it.gap })
            }
        }

    @Test
    fun `a device whose log could not catch up gets a gap inside the stopped recording`(): Unit =
        runBlocking {
            AttachedHarness.with(Device(), captureLogs = true) {
                val id = startRecording("flow")
                history.stopHistoryRecording(StopHistoryRecordingRequest.newBuilder().setRecordingId(id).build())

                val gaps = readRecording(id).entriesList.filter { it.hasGap() && it.sourceId == attached.id }.map { it.gap }
                assertTrue(gaps.single().contains("had not caught up"), gaps.toString())
            }
        }

    private suspend fun AttachedHarness.startRecording(name: String): String =
        history
            .startHistoryRecording(
                StartHistoryRecordingRequest
                    .newBuilder()
                    .setConnectionId(connectionId)
                    .setName(name)
                    .build(),
            ).recording.id

    private suspend fun AttachedHarness.readRecording(id: String) =
        history.readHistory(
            ReadHistoryRequest
                .newBuilder()
                .setSelection(HistorySelection.newBuilder().setRecordingId(id))
                .setIncludeLogs(true)
                .build(),
        )

    @Test
    fun `protection caller errors are typed rather than INTERNAL`(): Unit =
        runBlocking {
            AttachedHarness.with(Device(), captureLogs = true) {
                assertEquals(
                    Status.Code.INVALID_ARGUMENT,
                    status {
                        history.startHistoryRecording(
                            StartHistoryRecordingRequest
                                .newBuilder()
                                .setConnectionId(connectionId)
                                .setName("../bad")
                                .build(),
                        )
                    }.status.code,
                )
                assertEquals(
                    Status.Code.NOT_FOUND,
                    status {
                        history.stopHistoryRecording(StopHistoryRecordingRequest.newBuilder().setRecordingId("absent").build())
                    }.status.code,
                )
                val id =
                    history
                        .startHistoryRecording(
                            StartHistoryRecordingRequest
                                .newBuilder()
                                .setConnectionId(connectionId)
                                .setName("flow")
                                .build(),
                        ).recording.id
                assertEquals(
                    Status.Code.FAILED_PRECONDITION,
                    status {
                        history.deleteHistoryRecording(DeleteHistoryRecordingRequest.newBuilder().setRecordingId(id).build())
                    }.status.code,
                )
            }
        }

    @Test
    fun `a step assigned after its driver lines arrived survives durable reads`(): Unit =
        runBlocking {
            AttachedHarness.with(Device(), captureLogs = true) {
                val log = attached.logs!!.also { it.driverUid = DRIVER }
                val sentAt = System.currentTimeMillis() - 1
                log.line(line("> #7 tap text=Pay", uid = DRIVER, tag = "Tap"))
                log.line(line("< #7 tap ok 1ms", uid = DRIVER, tag = "Tap"))
                log.commandStep(7, "late-step", sentAt)
                val entries = read { step = "late-step" }
                assertEquals(2, entries.size)
                assertTrue(entries.all { it.action.step == "late-step" })
                assertTrue(read { afterCursor = Long.MAX_VALUE }.isEmpty())
            }
        }

    /** A device whose logcat child prints nothing: the tests write the lines themselves. */
    private class Device : Adb("fake-adb") {
        val children = CopyOnWriteArrayList<FakeProcess>()

        /** Where a line the daemon logs on the device arrives; null: logging it fails. */
        var echo: LogcatCapture.Sink? = null

        /** Lines the device logged before the daemon's, still on their way to the capture. */
        val lagging = mutableListOf<LogcatLine>()

        override suspend fun logLine(
            serial: String,
            tag: String,
            message: String,
        ) {
            val sink = echo ?: throw java.io.IOException("device offline")
            lagging.forEach(sink::line)
            lagging.clear()
            sink.line(LogcatLine(1_700_000_000_000, 2000, 10, 11, 'I', tag, message))
        }

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

    @Test
    fun `history reads between markers newest first and pages on with the cursor alone`(): Unit =
        runBlocking {
            AttachedHarness.with(Device(), captureLogs = true) {
                val log = attached.logs!!
                log.line(line("before"))
                mark("start")
                log.line(line("one"))
                log.line(line("two"))
                log.line(line("three"))
                mark("end")
                log.line(line("after"))

                suspend fun read(build: ReadHistoryRequest.Builder.() -> Unit) =
                    history.readHistory(
                        ReadHistoryRequest
                            .newBuilder()
                            .setSelection(HistorySelection.newBuilder().addSourceIds(attached.id))
                            .setIncludeLogs(true)
                            .apply(build)
                            .build(),
                    )

                fun texts(response: io.github.noamcohen48.tap.api.v1.ReadHistoryResponse) =
                    response.entriesList.map { if (it.hasMarker()) "marker:${it.marker}" else it.line.message }

                // A marker bound is the marker's own position: lines received in its millisecond
                // but before it stay out.
                assertEquals(
                    listOf("marker:start", "one", "two", "three", "marker:end"),
                    texts(
                        read {
                            fromMarker = "start"
                            untilMarker = "end"
                        },
                    ),
                )
                val newest =
                    read {
                        fromMarker = "start"
                        untilMarker = "end"
                        newestFirst = true
                        limit = 2
                    }
                assertEquals(listOf("marker:end", "three"), texts(newest))
                val rest = history.readHistory(ReadHistoryRequest.newBuilder().setCursor(newest.page.nextCursor).setLimit(10).build())
                assertEquals(listOf("two", "one", "marker:start"), texts(rest))
                assertEquals("", rest.page.nextCursor)

                assertEquals(
                    Status.Code.INVALID_ARGUMENT,
                    status {
                        history.readHistory(
                            ReadHistoryRequest
                                .newBuilder()
                                .setCursor(newest.page.nextCursor)
                                .setFromMarker("end")
                                .build(),
                        )
                    }.status.code,
                )
                assertEquals(Status.Code.NOT_FOUND, status { read { fromMarker = "absent" } }.status.code)
                assertEquals(
                    Status.Code.INVALID_ARGUMENT,
                    status {
                        history.readHistory(
                            ReadHistoryRequest.newBuilder().setLogFilter(HistoryLogFilter.newBuilder().addTags("App")).build(),
                        )
                    }.status.code,
                )
            }
        }

    private suspend fun AttachedHarness.mark(name: String): Long =
        logs
            .markLog(
                MarkLogRequest
                    .newBuilder()
                    .setClientConnectionId(connectionId)
                    .setAttachedDeviceId(attached.id)
                    .setName(name)
                    .build(),
            ).cursor

    private fun messages(entries: List<LogEntry>): List<String> =
        entries.map {
            if (it.hasLine()) {
                it.line.message
            } else if (it.hasMarker()) {
                "marker:${it.marker}"
            } else {
                "action:${it.action.operation}"
            }
        }

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
                assertEquals(
                    listOf("marker:checkout", "pay clicked", "card declined"),
                    messages(
                        read {
                            marker = "checkout"
                            addPackages("com.shop")
                        },
                    ),
                )
                assertEquals(
                    listOf("card declined"),
                    messages(
                        read {
                            afterCursor = cursor
                            minPriority = LogPriority.LOG_PRIORITY_ERROR
                        },
                    ),
                )
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
                    RedactLogRequest
                        .newBuilder()
                        .setClientConnectionId(
                            connectionId,
                        ).setAttachedDeviceId(attached.id)
                        .addPatterns("""order (\w+)""")
                        .build(),
                )

                assertEquals(listOf("login as noam with <redacted>, order <redacted>"), messages(read()))
                val evidence =
                    devices.deviceLogs(
                        DeviceLogsRequest
                            .newBuilder()
                            .setClientConnectionId(connectionId)
                            .setAttachedDeviceId(attached.id)
                            .build(),
                    )
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

                assertEquals(
                    listOf("s-1", "s-1"),
                    attached.events
                        .after(0)
                        .first
                        .map { it.step },
                )
                val step = read { step = "s-1" }
                assertEquals(
                    listOf("action:set_dark_mode", "action:set_dark_mode", "action:type_text", "typed", "action:type_text"),
                    messages(step),
                )
                assertTrue(step.filter { it.hasAction() }.all { it.action.step == "s-1" })
                assertEquals(
                    Status.Code.INVALID_ARGUMENT,
                    status {
                        read {
                            this.step = "s-1"
                            follow = true
                        }
                    }.status.code,
                )
                assertEquals(Status.Code.INVALID_ARGUMENT, status { read { this.step = "not a step" } }.status.code)
            }
        }

    @Test
    fun `a call's tap-step header is its step and a malformed one is refused`() {
        val service =
            object : ClientConnectionServiceGrpcKt.ClientConnectionServiceCoroutineImplBase() {
                override suspend fun info(request: InfoRequest): InfoResponse =
                    InfoResponse.newBuilder().setDaemonVersion(STEP.get() ?: "none").build()
            }
        val name = InProcessServerBuilder.generateName()
        val server =
            InProcessServerBuilder
                .forName(
                    name,
                ).directExecutor()
                .addService(ServerInterceptors.intercept(service, StepInterceptor()))
                .build()
                .start()
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
                assertEquals(
                    Status.Code.INVALID_ARGUMENT,
                    status { watch.watchLog(WatchLogRequest.getDefaultInstance()).toList() }.status.code,
                )
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

                val evidence =
                    devices.deviceLogs(
                        DeviceLogsRequest
                            .newBuilder()
                            .setClientConnectionId(connectionId)
                            .setAttachedDeviceId(attached.id)
                            .build(),
                    )
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
                val pattern =
                    RedactLogRequest
                        .newBuilder()
                        .setClientConnectionId(
                            connectionId,
                        ).setAttachedDeviceId(attached.id)
                        .addPatterns("(")
                assertEquals(Status.Code.INVALID_ARGUMENT, status { logs.redactLog(pattern.build()) }.status.code)
                assertEquals(
                    Status.Code.PERMISSION_DENIED,
                    status {
                        logs.readLog(request().setClientConnectionId("someone-else").build()).first()
                    }.status.code,
                )
            }
        }

    @Test
    fun `detaching ends the logcat child`(): Unit =
        runBlocking {
            val device = Device()
            AttachedHarness.with(device, captureLogs = true) {
                withTimeout(5_000) { while (device.children.isEmpty()) yield() }
            }
            assertTrue(
                device.children
                    .single()
                    .destroyed
                    .get(),
            )
        }

    private companion object {
        const val SHOP = 10_123
        const val OTHER = 10_456
        const val DRIVER = 10_247
    }
}
