package io.github.noamcohen48.tap.daemon.grpc

import io.github.noamcohen48.tap.api.v1.ClientConnectionServiceGrpcKt
import io.github.noamcohen48.tap.api.v1.Command
import io.github.noamcohen48.tap.api.v1.CommandResult
import io.github.noamcohen48.tap.api.v1.DeviceInfo
import io.github.noamcohen48.tap.api.v1.DeviceServiceGrpcKt
import io.github.noamcohen48.tap.api.v1.Done
import io.github.noamcohen48.tap.api.v1.Error as CommandError
import io.github.noamcohen48.tap.api.v1.ErrorCode
import io.github.noamcohen48.tap.api.v1.EventsRequest
import io.github.noamcohen48.tap.api.v1.ExecuteRequest
import io.github.noamcohen48.tap.api.v1.LoggedEvent
import io.github.noamcohen48.tap.api.v1.TypeText
import io.github.noamcohen48.tap.daemon.core.DaemonConfig
import io.github.noamcohen48.tap.daemon.core.DaemonDeviceSession
import io.github.noamcohen48.tap.daemon.core.DeviceSessionOpener
import io.github.noamcohen48.tap.daemon.core.EventLog
import io.github.noamcohen48.tap.daemon.core.TapDaemon
import io.github.noamcohen48.tap.host.Adb
import io.github.noamcohen48.tap.host.AppLifecycle
import io.github.noamcohen48.tap.host.DeviceSessionConfig
import io.github.noamcohen48.tap.host.DriverClient
import io.github.noamcohen48.tap.host.FakeDriverServer
import io.github.noamcohen48.tap.host.MediaClock
import io.github.noamcohen48.tap.protocol.Commands
import io.github.noamcohen48.tap.protocol.Responses
import io.github.noamcohen48.tap.protocol.Selectors
import io.github.noamcohen48.tap.protocol.stamped
import io.github.noamcohen48.tap.wire.v1.Request
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Execute calls land in the owning connection's event log with their outcome; `Events` reads it. */
class EventLogServiceTest {
    private class Device(
        override val client: DriverClient,
    ) : DaemonDeviceSession {
        override val serial = "log-serial"
        override val generation = 3L

        override fun app(packageName: String): AppLifecycle = error("no app in this fake")

        override fun checkUsable() = Unit

        override suspend fun close(timeoutMs: Long) = Unit
    }

    @Test
    fun `commands are logged with their outcome, diagnostic queries are not, and Events pages by seq`(): Unit =
        runBlocking {
            val secret = ByteArray(32).also(SecureRandom()::nextBytes)
            FakeDriverServer("log-session", 3, secret).use { driver ->
                val client = DriverClient.connect(driver.port, "log-session", 3, secret, serial = "log-serial", heartbeatIntervalMs = 0)
                val config = DaemonConfig(adb = object : Adb("fake-adb") {}, stateDir = Files.createTempDirectory("tap-events"), driver = null, log = {})
                val opener =
                    object : DeviceSessionOpener {
                        override suspend fun open(config: DeviceSessionConfig): DaemonDeviceSession = Device(client)
                    }
                val daemon = TapDaemon(config, opener)
                val connection = daemon.connectClient("events")
                val attached =
                    daemon.attachDevice(
                        connection.id,
                        "log-serial",
                        TapDaemon.AttachDeviceOptions(skipDriverInstall = true, defaultTimeoutMs = 5_000, leaseTimeoutMs = 0),
                    )
                val name = InProcessServerBuilder.generateName()
                val server =
                    InProcessServerBuilder
                        .forName(name)
                        .directExecutor()
                        .addService(DeviceService(daemon))
                        .addService(ClientConnectionService(daemon))
                        .build()
                        .start()
                val channel = InProcessChannelBuilder.forName(name).directExecutor().build()
                val devices = DeviceServiceGrpcKt.DeviceServiceCoroutineStub(channel)
                val connections = ClientConnectionServiceGrpcKt.ClientConnectionServiceCoroutineStub(channel)
                val sent = mutableListOf<Request>()
                try {
                    suspend fun execute(
                        command: Command,
                        result: CommandResult.Builder,
                    ): CommandResult {
                        val call =
                            async {
                                devices.execute(
                                    ExecuteRequest
                                        .newBuilder()
                                        .setClientConnectionId(connection.id)
                                        .setAttachedDeviceId(attached.id)
                                        .setCommand(command)
                                        .build(),
                                )
                            }
                        val frame = withTimeout(2_000) { withContext(Dispatchers.IO) { driver.nextFrame() } }
                        sent += Request.parseFrom(frame.payload)
                        driver.respond(
                            frame.requestId,
                            Responses.of(result, durationMs = 1).stamped(durationMs = 1, requestId = frame.requestId, generation = 3),
                        )
                        return withTimeout(2_000) { call.await() }.result
                    }

                    suspend fun events(after: Long = 0) =
                        connections.events(EventsRequest.newBuilder().setClientConnectionId(connection.id).setAfterSeq(after).build())

                    val tap = Commands.tap(Selectors.text("OK"))
                    execute(tap, CommandResult.newBuilder().setDone(Done.getDefaultInstance()))
                    execute(Commands.deviceInfo(), CommandResult.newBuilder().setDeviceInfo(DeviceInfo.getDefaultInstance()))
                    val missing = Commands.tap(Selectors.text("Nope"))
                    execute(missing, CommandResult.newBuilder().setError(CommandError.newBuilder().setCode(ErrorCode.ERR_NOT_FOUND)))

                    val all = events()
                    assertEquals(0, all.dropped)
                    assertEquals(listOf(1L, 2L), all.eventsList.map { it.seq })
                    val (first, second) = all.eventsList
                    assertEquals(LoggedEvent.CallCase.COMMAND, first.callCase)
                    assertEquals(tap, first.command)
                    assertEquals("log-serial", first.serial)
                    assertTrue(first.atEpochMs > 0)
                    for (event in all.eventsList) {
                        assertEquals(MediaClock.id, event.clockId)
                        assertTrue(event.hasStartedMonotonicNs() && event.hasFinishedMonotonicNs())
                        assertTrue(event.finishedMonotonicNs >= event.startedMonotonicNs)
                        assertEquals((event.finishedMonotonicNs - event.startedMonotonicNs) / 1_000_000L, event.durationMs)
                    }
                    assertFalse(first.hasError() || first.hasFailure())
                    assertEquals(missing, second.command)
                    assertEquals(ErrorCode.ERR_NOT_FOUND, second.error.code)
                    assertEquals(listOf(2L), events(after = 1).eventsList.map { it.seq })

                    // Watchers see each logged call start, then end: a started call has no seq or
                    // outcome yet, and its event repeats the serial and the start boundary.
                    val watched = daemon.watch(0).second.backlog.filter { it.clientConnectionId == connection.id }
                    val calls = watched.filter { it.hasCallStarted() || it.hasEvent() }
                    assertEquals(listOf("start", "end", "start", "end"), calls.map { if (it.hasCallStarted()) "start" else "end" })
                    val startedTap = calls[0].callStarted.call
                    assertEquals(0L, startedTap.seq)
                    assertEquals(tap, startedTap.command)
                    assertEquals("log-serial", startedTap.serial)
                    assertFalse(startedTap.hasFinishedMonotonicNs() || startedTap.hasError())
                    assertEquals(first.startedMonotonicNs, startedTap.startedMonotonicNs)
                    // device_info is not logged, so nothing announces it either.
                    assertEquals(missing, calls[2].callStarted.call.command)
                    assertEquals(second.startedMonotonicNs, calls[2].callStarted.call.startedMonotonicNs)

                    assertEquals(
                        Status.Code.NOT_FOUND,
                        assertFailsWith<StatusException> {
                            connections.events(EventsRequest.newBuilder().setClientConnectionId("nobody").build())
                        }.status.code,
                    )
                    assertEquals(Status.Code.INVALID_ARGUMENT, assertFailsWith<StatusException> { events(after = -1) }.status.code)

                    // A sensitive text reaches the driver but not the log; the flag says it was withheld.
                    val password = Commands.setText(Selectors.text("Password"), "hunter2")
                    val sensitive = password.toBuilder().setSetText(password.setText.toBuilder().setSensitive(true)).build()
                    execute(sensitive, CommandResult.newBuilder().setDone(Done.getDefaultInstance()))
                    val typed = Command.newBuilder().setTypeText(TypeText.newBuilder().setText("s3cret").setSensitive(true)).build()
                    execute(typed, CommandResult.newBuilder().setDone(Done.getDefaultInstance()))
                    execute(password, CommandResult.newBuilder().setDone(Done.getDefaultInstance()))
                    assertEquals("hunter2", sent[sent.size - 3].command.setText.text)
                    assertEquals("s3cret", sent[sent.size - 2].command.typeText.text)
                    val (hidden, hiddenTyped, plain) = events(after = 2).eventsList
                    assertEquals("", hidden.command.setText.text)
                    assertTrue(hidden.command.setText.sensitive)
                    assertEquals(sensitive.setText.selector, hidden.command.setText.selector)
                    assertEquals("", hiddenTyped.command.typeText.text)
                    assertTrue(hiddenTyped.command.typeText.sensitive)
                    assertEquals(password, plain.command)
                } finally {
                    channel.shutdownNow()
                    server.shutdownNow()
                    channel.awaitTermination(2, TimeUnit.SECONDS)
                    server.awaitTermination(2, TimeUnit.SECONDS)
                    runCatching { client.close() }
                }
            }
        }

    @Test
    fun `the log keeps the newest events and counts the evicted ones`() {
        val log = EventLog(capacity = 3)
        repeat(5) { log.append(LoggedEvent.newBuilder()) }
        val (kept, dropped) = log.after(0)
        assertEquals(listOf(3L, 4L, 5L), kept.map { it.seq })
        assertEquals(2, dropped)
        assertEquals(listOf(5L), log.after(4).first.map { it.seq })
    }
}
