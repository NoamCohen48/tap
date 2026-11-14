package io.github.noamcohen48.tap.daemon.grpc

import io.github.noamcohen48.tap.api.v1.AppServiceGrpcKt
import io.github.noamcohen48.tap.api.v1.Command
import io.github.noamcohen48.tap.api.v1.CommandResult
import io.github.noamcohen48.tap.api.v1.DeviceLogServiceGrpcKt
import io.github.noamcohen48.tap.api.v1.WatchServiceGrpcKt
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
import io.grpc.Metadata
import io.grpc.Server
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import io.grpc.stub.MetadataUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.security.SecureRandom

/** One attached device on a fake driver, behind in-process Device and App services. */
internal class AttachedHarness private constructor(
    private val driver: FakeDriverServer,
    private val client: DriverClient,
    val daemon: TapDaemon,
    val connectionId: String,
    val attached: AttachedDevice,
    private val server: Server,
    private val channel: ManagedChannel,
) {
    val devices = DeviceServiceGrpcKt.DeviceServiceCoroutineStub(channel)
    val apps = AppServiceGrpcKt.AppServiceCoroutineStub(channel)
    val logs = DeviceLogServiceGrpcKt.DeviceLogServiceCoroutineStub(channel)
    val watch = WatchServiceGrpcKt.WatchServiceCoroutineStub(channel)

    /** Sends [command], with the `tap-step` header when [step] is given, and answers it, as the driver would, with [result]. */
    suspend fun execute(
        command: Command,
        result: CommandResult.Builder,
        step: String? = null,
    ): CommandResult =
        coroutineScope {
            val stub =
                if (step == null) {
                    devices
                } else {
                    devices.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(Metadata().apply { put(StepInterceptor.HEADER, step) }))
                }
            val call =
                async {
                    stub.execute(
                        ExecuteRequest
                            .newBuilder()
                            .setClientConnectionId(connectionId)
                            .setAttachedDeviceId(attached.id)
                            .setCommand(command)
                            .build(),
                    )
                }
            val frame = withTimeout(2_000) { withContext(Dispatchers.IO) { driver.nextFrame() } }
            driver.respond(frame.requestId, Responses.of(result, durationMs = 1).stamped(durationMs = 1, requestId = frame.requestId, generation = GENERATION))
            withTimeout(5_000) { call.await() }.result
        }

    private suspend fun close() {
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
            captureLogs: Boolean = false,
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
                        TapDaemon.AttachDeviceOptions(skipDriverInstall = true, defaultTimeoutMs = 5_000, leaseTimeoutMs = 0, captureLogs = captureLogs),
                    )
                val name = InProcessServerBuilder.generateName()
                val server =
                    InProcessServerBuilder
                        .forName(name)
                        .directExecutor()
                        .addService(DeviceService(daemon))
                        .addService(AppService(daemon))
                        .addService(DeviceLogService(daemon))
                        .addService(WatchService(daemon))
                        .intercept(StepInterceptor())
                        .build()
                        .start()
                val channel = InProcessChannelBuilder.forName(name).directExecutor().build()
                val harness = AttachedHarness(driver, client, daemon, connection.id, attached, server, channel)
                try {
                    harness.block()
                } finally {
                    harness.close()
                    runCatching { daemon.detachDevice(attached.id, connection.id) }
                }
            }
        }
    }
}
