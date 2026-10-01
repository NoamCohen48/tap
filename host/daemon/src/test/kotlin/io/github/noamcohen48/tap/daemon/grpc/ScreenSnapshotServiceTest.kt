package io.github.noamcohen48.tap.daemon.grpc

import io.github.noamcohen48.tap.api.v1.CommandResult
import io.github.noamcohen48.tap.api.v1.DeviceServiceGrpcKt
import io.github.noamcohen48.tap.api.v1.FailureReason
import io.github.noamcohen48.tap.api.v1.NodeChange
import io.github.noamcohen48.tap.api.v1.ResolveRefRequest
import io.github.noamcohen48.tap.api.v1.ScreenSnapshotRequest
import io.github.noamcohen48.tap.daemon.core.DaemonConfig
import io.github.noamcohen48.tap.daemon.core.DaemonDeviceSession
import io.github.noamcohen48.tap.daemon.core.DeviceSessionOpener
import io.github.noamcohen48.tap.daemon.core.TapDaemon
import io.github.noamcohen48.tap.daemon.snapshot.Dumps
import io.github.noamcohen48.tap.host.Adb
import io.github.noamcohen48.tap.host.AppLifecycle
import io.github.noamcohen48.tap.host.DeviceSessionConfig
import io.github.noamcohen48.tap.host.DriverClient
import io.github.noamcohen48.tap.host.FakeDriverServer
import io.github.noamcohen48.tap.protocol.Responses
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
import kotlin.test.assertTrue

/** ScreenSnapshot runs the driver's hierarchy dump; ResolveRef maps its errors to status + reason. */
class ScreenSnapshotServiceTest {
    private class Device(
        override val client: DriverClient,
    ) : DaemonDeviceSession {
        override val serial = "snapshot-serial"
        override val generation = 3L

        override fun app(packageName: String): AppLifecycle = error("no app in this fake")

        override fun checkUsable() = Unit

        override suspend fun close(timeoutMs: Long) = Unit
    }

    private class Opener(
        val device: DaemonDeviceSession,
    ) : DeviceSessionOpener {
        override suspend fun open(config: DeviceSessionConfig): DaemonDeviceSession = device
    }

    @Test
    fun `snapshot dumps through the driver, refs resolve, unknown and unaddressable refs are typed failures`(): Unit =
        runBlocking {
            val secret = ByteArray(32).also(SecureRandom()::nextBytes)
            FakeDriverServer("snapshot-session", 3, secret).use { driver ->
                val client = DriverClient.connect(driver.port, "snapshot-session", 3, secret, serial = "snapshot-serial", heartbeatIntervalMs = 0)
                val config = DaemonConfig(adb = object : Adb("fake-adb") {}, stateDir = Files.createTempDirectory("tap-snapshot"), driver = null, log = {})
                val daemon = TapDaemon(config, Opener(Device(client)))
                val connection = daemon.connectClient("snapshot")
                val attached =
                    daemon.attachDevice(
                        connection.id,
                        "snapshot-serial",
                        TapDaemon.AttachDeviceOptions(skipDriverInstall = true, defaultTimeoutMs = 5_000, leaseTimeoutMs = 0),
                    )
                val name = InProcessServerBuilder.generateName()
                val server = InProcessServerBuilder.forName(name).directExecutor().addService(DeviceService(daemon)).build().start()
                val channel = InProcessChannelBuilder.forName(name).directExecutor().build()
                val stub = DeviceServiceGrpcKt.DeviceServiceCoroutineStub(channel)
                try {
                    suspend fun snapshot(xml: String) =
                        run {
                            val call =
                                async {
                                    stub.screenSnapshot(
                                        ScreenSnapshotRequest
                                            .newBuilder()
                                            .setClientConnectionId(connection.id)
                                            .setAttachedDeviceId(attached.id)
                                            .setTimeoutMs(4_000)
                                            .build(),
                                    )
                                }
                            val frame = withTimeout(2_000) { withContext(Dispatchers.IO) { driver.nextFrame() } }
                            val request = Request.parseFrom(frame.payload)
                            assertTrue(request.command.hasDumpHierarchy())
                            assertEquals(4_000L, request.timeoutMs)
                            driver.respond(
                                frame.requestId,
                                Responses
                                    .of(CommandResult.newBuilder().setText(xml), durationMs = 1)
                                    .stamped(durationMs = 1, requestId = frame.requestId, generation = 3),
                            )
                            withTimeout(2_000) { call.await() }
                        }

                    fun resolve(ref: String) =
                        ResolveRefRequest
                            .newBuilder()
                            .setClientConnectionId(connection.id)
                            .setAttachedDeviceId(attached.id)
                            .setRef(ref)
                            .build()

                    suspend fun failure(ref: String): Pair<Status.Code, FailureReason> {
                        val error = assertFailsWith<StatusException> { stub.resolveRef(resolve(ref)) }
                        assertTrue(error.status.description!!.contains(ref.removePrefix("@")), error.status.description)
                        return error.status.code to error.trailers!!.get(FAILURE_TRAILER)!!.reason
                    }

                    // Before any snapshot every ref is unknown.
                    assertEquals(Status.Code.NOT_FOUND to FailureReason.FAILURE_REASON_UNKNOWN_REF, failure("@e1"))

                    val first = snapshot(Dumps.xml("emulator-5554-MainActivity"))
                    assertEquals(1, first.snapshotId)
                    assertEquals(71, first.nodesCount)
                    val button = first.nodesList.single { it.resourceName == "${Dumps.AUT}:id/view_button" }
                    val resolved = stub.resolveRef(resolve("@${button.ref}"))
                    assertEquals(button.selector, resolved.selector)
                    assertEquals(1, resolved.snapshotId)
                    assertEquals(Status.Code.NOT_FOUND to FailureReason.FAILURE_REASON_UNKNOWN_REF, failure("@e999"))
                    assertEquals(
                        Status.Code.INVALID_ARGUMENT,
                        assertFailsWith<StatusException> { stub.resolveRef(resolve("@")) }.status.code,
                    )

                    // A node no selector can single out: its only property is over the selector limits.
                    val unaddressable = Dumps.wrap(Dumps.node(className = "Root", children = Dumps.node(className = "", text = "x".repeat(2_000))))
                    val second = snapshot(unaddressable)
                    assertEquals(2, second.snapshotId)
                    assertEquals(71, second.removedCount)
                    assertTrue(second.nodesList.all { it.change == NodeChange.NODE_ADDED })
                    val bare = second.nodesList.single { !it.hasSelector() }
                    assertEquals(Status.Code.FAILED_PRECONDITION to FailureReason.FAILURE_REASON_REF_NOT_ADDRESSABLE, failure("@${bare.ref}"))
                    // The first snapshot's refs are gone with it.
                    assertEquals(Status.Code.NOT_FOUND to FailureReason.FAILURE_REASON_UNKNOWN_REF, failure(button.ref))
                } finally {
                    channel.shutdownNow()
                    server.shutdownNow()
                    channel.awaitTermination(2, TimeUnit.SECONDS)
                    server.awaitTermination(2, TimeUnit.SECONDS)
                    runCatching { client.close() }
                }
            }
        }
}
