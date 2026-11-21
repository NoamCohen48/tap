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
import kotlin.test.assertNotEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EventRetentionTest {
    @TempDir lateinit var root: Path

    @Test fun `independent death diagnostics recover after restart without raw registered secrets`() =
        runBlocking {
            val daemon = TapDaemon(DaemonConfig(Adb("unused-adb"), root, driver = null))
            val connection = daemon.connectClient("incident", 60_000)
            val archive = daemon.retainedLogs.archive(connection.id, connection.id)
            val protection = daemon.retainedLogs.start("incident", connection.id, listOf(connection.id))
            val redactor = LogRedactor().apply { literal("hidden-stack") }
            val death =
                io.github.noamcohen48.tap.api.v1.HistoryDeath
                    .newBuilder()
                    .setAttachedDeviceId("original-attachment")
                    .setSerial("original-serial")
                    .setDeath(
                        io.github.noamcohen48.tap.api.v1.AppDeath
                            .newBuilder()
                            .setSequence(1)
                            .setKind(io.github.noamcohen48.tap.api.v1.AppDeath.Kind.KIND_CRASH)
                            .setPackageName("com.shop")
                            .setMessage("password=hidden-message")
                            .setStackTrace("at Cart.pay hidden-stack"),
                    ).build()
            daemon.retainedLogs.appendDeath(archive, durableDeath(death, redactor))
            daemon.retainedLogs.stop(protection.id)
            daemon.close()
            val recovered = RetainedLogs(root)
            val receipt = recovered.entries(recovered.archive(connection.id, connection.id), protection.id).single().death
            assertEquals("original-attachment", receipt.attachedDeviceId)
            assertEquals("password=<redacted>", receipt.death.message)
            assertEquals("at Cart.pay <redacted>", receipt.death.stackTrace)
            Files.walk(root.resolve("history-logs")).use { paths ->
                paths.filter(Files::isRegularFile).forEach {
                    val data = String(Files.readAllBytes(it))
                    assertFalse("hidden-message" in data || "hidden-stack" in data)
                }
            }
        }

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
            val protection = daemon.retainedLogs.start("cut", connection.id, listOf(connection.id))
            connection.events.append(LoggedEvent.newBuilder().setSerial("device"))
            connection.inFlight = 1
            // The calls still running are a gap entry at the end of the range, not a recording error.
            assertEquals("", daemon.stopHistoryRecording(protection.id).captureError)
            connection.inFlight = 0
            connection.events.append(LoggedEvent.newBuilder().setSerial("device"))
            val archive = daemon.retainedLogs.archive(connection.id, connection.id)
            assertEquals(listOf(1L, 2L, 3L), daemon.retainedLogs.entries(archive).map { it.cursor })
            val frozen = daemon.retainedLogs.entries(archive, protection.id)
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
                                    "password=private-crash-output",
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
            assertEquals("password=<redacted>", event.error.appDeath.message)
            assertEquals("ExampleException", event.error.appDeath.exceptionClass)
            daemon.close()
        }

    @Test fun `events persist in the shared store and survive connection close and restart`() =
        runBlocking {
            val daemon = TapDaemon(DaemonConfig(Adb("unused-adb"), root, driver = null))
            val connection = daemon.connectClient("script", 60_000)
            val protection = daemon.retainedLogs.start("script", connection.id, listOf(connection.id))
            connection.events.append(
                LoggedEvent
                    .newBuilder()
                    .setAtEpochMs(10)
                    .setSerial("device")
                    .setCommand(Command.newBuilder().setSetText(SetText.newBuilder().setText("password=secret-value")))
                    .setError(Error.newBuilder().setCode(ErrorCode.ERR_NOT_FOUND).setMessage("private output")),
            )
            val stopped = daemon.retainedLogs.stop(protection.id)
            assertEquals("", stopped.captureError)
            assertEquals(connection.id, stopped.spansList.single().sourceId)
            assertTrue(stopped.retainedBytes > 0)
            daemon.disconnectClient(connection.id, "released")
            daemon.close()
            val recovered = RetainedLogs(root)
            val archive = recovered.archive(connection.id, connection.id)
            assertTrue(archive.events)
            assertTrue(archive.closed)
            val event = recovered.entries(archive, protection.id).single().event
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
            daemon.retainedLogs.stop(daemon.retainedLogs.named("earlier"))
            daemon.retainedLogs.start("interrupted", connection.id, listOf(connection.id))
            connection.inFlight = 1
            daemon.disconnectClient(connection.id, "released")
            assertEquals(
                "",
                daemon.retainedLogs
                    .list()
                    .single { it.name == "earlier" }
                    .captureError,
            )
            assertNotEquals("", daemon.retainedLogs.errors(daemon.retainedLogs.stop(daemon.retainedLogs.named("interrupted"))))
            assertTrue(
                daemon.retainedLogs
                    .entries(daemon.retainedLogs.archive(connection.id, connection.id))
                    .last()
                    .hasGap(),
            )
            daemon.close()
        }
}
