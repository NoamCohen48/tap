package io.github.noamcohen48.tap.daemon.grpc

import io.github.noamcohen48.tap.api.v1.AppServiceGrpcKt
import io.github.noamcohen48.tap.api.v1.Command
import io.github.noamcohen48.tap.api.v1.CommandResult
import io.github.noamcohen48.tap.api.v1.DeviceServiceGrpcKt
import io.github.noamcohen48.tap.api.v1.ExecuteRequest
import io.github.noamcohen48.tap.daemon.core.AttachedDevice
import io.github.noamcohen48.tap.daemon.core.DaemonConfig
import io.github.noamcohen48.tap.daemon.core.DaemonDeviceSession
import io.github.noamcohen48.tap.daemon.core.DeviceSessionOpener
import io.github.noamcohen48.tap.daemon.core.TapDaemon
import io.github.noamcohen48.tap.host.Adb
import io.github.noamcohen48.tap.host.AppLifecycle
import io.github.noamcohen48.tap.host.DeviceSessionConfig
import io.github.noamcohen48.tap.host.DriverClient
import io.github.noamcohen48.tap.host.FakeDriverServer
import io.github.noamcohen48.tap.protocol.Responses
import io.github.noamcohen48.tap.protocol.stamped
import io.grpc.ManagedChannel
import io.grpc.Server
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.security.SecureRandom

/** One attached device on a fake driver, behind in-process Device and App services. */
internal class AttachedHarness private constructor(
    val driver: FakeDriverServer,
    private val client: DriverClient,
    val connectionId: String,
    val attached: AttachedDevice,
    private val server: Server,
    private val channel: ManagedChannel,
    private val daemon: TapDaemon,
) {
    val devices = DeviceServiceGrpcKt.DeviceServiceCoroutineStub(channel)
    val apps = AppServiceGrpcKt.AppServiceCoroutineStub(channel)

    /** Sends [command] and answers it, as the driver would, with [result]. */
    suspend fun execute(
        command: Command,
        result: CommandResult.Builder,
    ): CommandResult =
        coroutineScope {
            val call =
                async {
                    devices.execute(
                        ExecuteRequest
                            .newBuilder()
                            .setClientConnectionId(connectionId)
                            .setAttachedDeviceId(attached.id)
                            .setCommand(command)
                            .build(),
                    )
                }
            val frame = withTimeout(2_000) { withContext(Dispatchers.IO) { driver.nextFrame() } }
            respond(frame.requestId, result)
            withTimeout(5_000) { call.await() }.result
        }

    /** Answers the driver request [requestId] with [result], as the driver would. */
    fun respond(
        requestId: Long,
        result: CommandResult.Builder,
    ) = driver.respond(requestId, Responses.of(result, durationMs = 1).stamped(durationMs = 1, requestId = requestId, generation = GENERATION))

    /** The request id of the next `CANCEL` the driver receives. */
    fun FakeDriverServer.nextCancel(): Long {
        val frame = nextFrame()
        check(frame.type == io.github.noamcohen48.tap.protocol.FrameType.CANCEL) { "Expected CANCEL, got ${frame.type}" }
        return frame.requestId
    }

    private suspend fun close() {
        // Detaches (stopping the attachment's log follower) before the driver goes away.
        runCatching { daemon.close(5_000) }
        channel.shutdownNow()
        server.shutdownNow()
        runCatching { client.close() }
    }

    private class Session(
        override val client: DriverClient,
        private val apps: (String) -> AppLifecycle,
    ) : DaemonDeviceSession {
        override val serial = SERIAL
        override val generation = GENERATION

        override fun app(packageName: String): AppLifecycle = apps(packageName)

        override fun checkUsable() = Unit

        override suspend fun close(timeoutMs: Long) = Unit
    }

    companion object {
        const val SERIAL = "harness-serial"
        private const val GENERATION = 3L

        suspend fun <T> with(
            adb: Adb,
            apps: (String) -> AppLifecycle = { error("no app in this fake") },
            block: suspend AttachedHarness.() -> T,
        ): T {
            val secret = ByteArray(32).also(SecureRandom()::nextBytes)
            return FakeDriverServer("harness-session", GENERATION, secret).use { driver ->
                val client = DriverClient.connect(driver.port, "harness-session", GENERATION, secret, serial = SERIAL, heartbeatIntervalMs = 0)
                val config = DaemonConfig(adb = adb, stateDir = Files.createTempDirectory("tap-harness"), driver = null, log = {})
                val opener =
                    object : DeviceSessionOpener {
                        override suspend fun open(config: DeviceSessionConfig): DaemonDeviceSession = Session(client, apps)
                    }
                val daemon = TapDaemon(config, opener)
                val connection = daemon.connectClient("harness")
                val attached =
                    daemon.attachDevice(
                        connection.id,
                        SERIAL,
                        TapDaemon.AttachDeviceOptions(skipDriverInstall = true, defaultTimeoutMs = 5_000, leaseTimeoutMs = 0),
                    )
                val name = InProcessServerBuilder.generateName()
                val server =
                    InProcessServerBuilder
                        .forName(name)
                        .directExecutor()
                        .addService(DeviceService(daemon))
                        .addService(AppService(daemon))
                        .build()
                        .start()
                val channel = InProcessChannelBuilder.forName(name).directExecutor().build()
                val harness = AttachedHarness(driver, client, connection.id, attached, server, channel, daemon)
                try {
                    harness.block()
                } finally {
                    harness.close()
                }
            }
        }
    }
}
