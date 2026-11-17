package io.github.noamcohen48.tap.daemon.core

import io.github.noamcohen48.tap.api.v1.Command
import io.github.noamcohen48.tap.api.v1.Error
import io.github.noamcohen48.tap.api.v1.ErrorCode
import io.github.noamcohen48.tap.api.v1.LoggedEvent
import io.github.noamcohen48.tap.api.v1.SetText
import io.github.noamcohen48.tap.host.Adb
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EventRetentionTest {
    @TempDir lateinit var root: Path

    @Test fun `late completion keeps its original attachment redactor and identity`() =
        runBlocking {
            val daemon = TapDaemon(DaemonConfig(Adb("unused-adb"), root, driver = null))
            val connection = daemon.connectClient("script", 60_000)
            val first = LogRedactor().apply { literal("first-private-value") }
            val second = LogRedactor().apply { literal("second-private-value") }

            fun call(
                id: String,
                text: String,
            ) = LoggedEvent
                .newBuilder()
                .setSerial("same-device")
                .setAttachedDeviceId(id)
                .setCommand(Command.newBuilder().setSetText(SetText.newBuilder().setText(text)))
            connection.events.appendRedacted(call("old-attachment", "first-private-value"), first)
            connection.events.appendRedacted(call("new-attachment", "second-private-value"), second)
            connection.events.appendRedacted(call("old-attachment", "first-private-value"), first)
            val entries = daemon.retainedLogs.entries(daemon.retainedLogs.archive(connection.id, connection.id))
            assertEquals(listOf("old-attachment", "new-attachment", "old-attachment"), entries.map { it.event.attachedDeviceId })
            assertEquals(listOf("<redacted>", "<redacted>", "<redacted>"), entries.map { it.event.command.setText.text })
            daemon.close()
        }

    @Test fun `stop with admitted calls preserves a gap and later events cannot enter its range`() =
        runBlocking {
            val daemon = TapDaemon(DaemonConfig(Adb("unused-adb"), root, driver = null))
            val connection = daemon.connectClient("script", 60_000)
            daemon.retainedLogs.start("cut", connection.id, listOf(connection.id))
            connection.events.append(LoggedEvent.newBuilder().setSerial("device"))
            connection.inFlight = 1
            assertFalse(daemon.stopLogRecording("cut").complete)
            connection.inFlight = 0
            connection.events.append(LoggedEvent.newBuilder().setSerial("device"))
            val archive = daemon.retainedLogs.archive(connection.id, connection.id)
            assertEquals(listOf(1L, 2L, 3L), daemon.retainedLogs.entries(archive).map { it.cursor })
            val frozen = daemon.retainedLogs.entries(archive, "cut")
            assertEquals(2, frozen.size)
            assertTrue(frozen.last().hasGap())
            assertEquals(
                listOf(1L, 2L),
                daemon.retainedLogs
                    .entries(archive)
                    .filter { it.hasEvent() }
                    .map { it.event.seq },
            )
            daemon.close()
        }

    @Test fun `unknown future wire fields are not persisted as unclassified raw data`() =
        runBlocking {
            val daemon = TapDaemon(DaemonConfig(Adb("unused-adb"), root, driver = null))
            val connection = daemon.connectClient("script", 60_000)
            val hidden = "future-private-value".toByteArray()
            val encoded =
                LoggedEvent
                    .newBuilder()
                    .setSerial("device")
                    .setError(
                        Error.newBuilder().setCode(ErrorCode.ERR_AUT_CRASHED).setAppDeath(
                            io.github.noamcohen48.tap.api.v1.AppDeath
                                .newBuilder()
                                .setMessage(
                                    "private crash output",
                                ).setExceptionClass("ExampleException"),
                        ),
                    ).build()
                    .toByteArray() +
                    byteArrayOf(0xa2.toByte(), 0x06, hidden.size.toByte()) + hidden
            connection.events.append(LoggedEvent.parseFrom(encoded).toBuilder())
            val event =
                daemon.retainedLogs
                    .entries(daemon.retainedLogs.archive(connection.id, connection.id))
                    .single()
                    .event
            assertFalse(String(event.toByteArray()).contains("future-private-value"))
            assertEquals("", event.error.appDeath.message)
            assertEquals("ExampleException", event.error.appDeath.exceptionClass)
            daemon.close()
        }

    @Test fun `events persist in the shared store and survive connection close and restart`() =
        runBlocking {
            val daemon = TapDaemon(DaemonConfig(Adb("unused-adb"), root, driver = null))
            val connection = daemon.connectClient("script", 60_000)
            daemon.retainedLogs.start("script", connection.id, listOf(connection.id))
            connection.events.append(
                LoggedEvent
                    .newBuilder()
                    .setAtEpochMs(10)
                    .setSerial("device")
                    .setCommand(Command.newBuilder().setSetText(SetText.newBuilder().setText("password=secret-value")))
                    .setError(Error.newBuilder().setCode(ErrorCode.ERR_NOT_FOUND).setMessage("private output")),
            )
            val stopped = daemon.retainedLogs.stop("script")
            assertTrue(stopped.complete)
            assertTrue(stopped.rangesList.single().events)
            assertTrue(stopped.retainedBytes > 0)
            daemon.disconnectClient(connection.id, "released")
            daemon.close()
            val recovered = RetainedLogs(root)
            val archive = recovered.archive(connection.id, connection.id)
            assertTrue(archive.events)
            assertTrue(archive.closed)
            val event = recovered.entries(archive, "script").single().event
            assertEquals("password=<redacted>", event.command.setText.text)
            assertEquals(ErrorCode.ERR_NOT_FOUND, event.error.code)
            assertEquals("", event.error.message)
            Files.walk(root.resolve("history-logs")).use { files ->
                files.filter(Files::isRegularFile).forEach {
                    val bytes = String(Files.readAllBytes(it))
                    assertFalse("secret-value" in bytes)
                    assertFalse("private output" in bytes)
                }
            }
        }

    @Test fun `sensitive short values never reach event storage`() =
        runBlocking {
            val daemon = TapDaemon(DaemonConfig(Adb("unused-adb"), root, driver = null))
            val connection = daemon.connectClient("script", 60_000)
            connection.events.append(
                LoggedEvent
                    .newBuilder()
                    .setSerial("device")
                    .setCommand(Command.newBuilder().setSetText(SetText.newBuilder().setSensitive(true).setText("x"))),
            )
            val event =
                daemon.retainedLogs
                    .entries(daemon.retainedLogs.archive(connection.id, connection.id))
                    .single()
                    .event
            assertEquals("<redacted>", event.command.setText.text)
            assertTrue(event.command.setText.sensitive)
            daemon.close()
        }

    @Test fun `in flight close reports missing outcomes without downgrading an earlier stopped range`() =
        runBlocking {
            val daemon = TapDaemon(DaemonConfig(Adb("unused-adb"), root, driver = null))
            val connection = daemon.connectClient("script", 60_000)
            daemon.retainedLogs.start("earlier", connection.id, listOf(connection.id))
            connection.events.append(LoggedEvent.newBuilder().setSerial("device").setIndeterminate(false))
            daemon.retainedLogs.stop("earlier")
            daemon.retainedLogs.start("interrupted", connection.id, listOf(connection.id))
            connection.inFlight = 1
            daemon.disconnectClient(connection.id, "released")
            assertTrue(
                daemon.retainedLogs
                    .list()
                    .single { it.name == "earlier" }
                    .complete,
            )
            assertFalse(daemon.retainedLogs.stop("interrupted").complete)
            assertTrue(
                daemon.retainedLogs
                    .entries(daemon.retainedLogs.archive(connection.id, connection.id))
                    .last()
                    .hasGap(),
            )
            daemon.close()
        }
}
