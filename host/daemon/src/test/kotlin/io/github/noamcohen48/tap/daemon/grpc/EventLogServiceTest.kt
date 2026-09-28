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
import io.github.noamcohen48.tap.protocol.Commands
import io.github.noamcohen48.tap.protocol.Responses
import io.github.noamcohen48.tap.protocol.Selectors
import io.github.noamcohen48.tap.protocol.stamped
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
        override val autPackage = "com.example"

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
                        "com.example",
                        TapDaemon.AttachDeviceOptions(skipDriverInstall = true, syncAuthority = null, defaultTimeoutMs = 5_000, leaseTimeoutMs = 0),
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
                    assertEquals("com.example", first.autPackage)
                    assertTrue(first.atEpochMs > 0)
                    assertFalse(first.hasError() || first.hasFailure())
                    assertEquals(missing, second.command)
                    assertEquals(ErrorCode.ERR_NOT_FOUND, second.error.code)
                    assertEquals(listOf(2L), events(after = 1).eventsList.map { it.seq })

                    assertEquals(
                        Status.Code.NOT_FOUND,
                        assertFailsWith<StatusException> {
                            connections.events(EventsRequest.newBuilder().setClientConnectionId("nobody").build())
                        }.status.code,
                    )
                    assertEquals(Status.Code.INVALID_ARGUMENT, assertFailsWith<StatusException> { events(after = -1) }.status.code)
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
