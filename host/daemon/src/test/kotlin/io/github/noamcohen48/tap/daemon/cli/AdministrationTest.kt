package io.github.noamcohen48.tap.daemon.cli

import io.github.noamcohen48.tap.api.v1.ClientConnectionServiceGrpc
import io.github.noamcohen48.tap.api.v1.ConnectRequest
import io.github.noamcohen48.tap.api.v1.DeviceEntry
import io.github.noamcohen48.tap.api.v1.DeviceProperties
import io.github.noamcohen48.tap.api.v1.DeviceServiceGrpc
import io.github.noamcohen48.tap.api.v1.DeviceState
import io.github.noamcohen48.tap.api.v1.Hold
import io.github.noamcohen48.tap.api.v1.ListDevicesRequest
import io.github.noamcohen48.tap.api.v1.ListDevicesResponse
import io.github.noamcohen48.tap.api.v1.ObserveRequest
import io.github.noamcohen48.tap.daemon.core.DaemonConfig
import io.github.noamcohen48.tap.daemon.core.TapDaemon
import io.github.noamcohen48.tap.daemon.grpc.ClientConnectionService
import io.github.noamcohen48.tap.daemon.grpc.TokenAuthInterceptor
import io.github.noamcohen48.tap.host.Adb
import io.grpc.ManagedChannelBuilder
import io.grpc.Metadata
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import io.grpc.stub.MetadataUtils
import io.grpc.stub.StreamObserver
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Real loopback authentication and services, with no Android device or Python installation. */
class AdministrationTest {
    private class Fixture : AutoCloseable {
        val state: Path = Files.createTempDirectory("tap-admin")
        val startup = startupConfiguration(mapOf("--scrcpy" to "/custom/scrcpy", "--driver-idle-timeout-ms" to "1000"), state) { null }
        val daemon =
            TapDaemon(
                DaemonConfig(
                    adb = object : Adb("fake-adb") {},
                    stateDir = state,
                    driver = null,
                    log = {},
                    startupOptions = startup.values,
                    startupOptionSources = startup.sources,
                ),
            )
        val token = DaemonDescriptor.newToken()
        val device =
            DeviceEntry
                .newBuilder()
                .setSerial("phone")
                .setState(DeviceState.DEVICE_QUARANTINED)
                .setQuarantineReason("restore failed")
                .setProperties(DeviceProperties.newBuilder().setApiLevel(34).setModel("Example"))
                .build()
        val server =
            NettyServerBuilder
                .forAddress(InetSocketAddress("127.0.0.1", 0))
                .addService(ClientConnectionService(daemon))
                .addService(
                    object : DeviceServiceGrpc.DeviceServiceImplBase() {
                        override fun listDevices(
                            request: ListDevicesRequest,
                            observer: StreamObserver<ListDevicesResponse>,
                        ) {
                            observer.onNext(ListDevicesResponse.newBuilder().addDevices(device).build())
                            observer.onCompleted()
                        }
                    },
                ).intercept(TokenAuthInterceptor(token))
                .build()
                .start()
        val descriptor = DaemonDescriptor(server.port, ProcessHandle.current().pid(), token, "test", "fake-adb")
        val channel = ManagedChannelBuilder.forAddress("127.0.0.1", server.port).usePlaintext().build()
        val stub =
            ClientConnectionServiceGrpc
                .newBlockingStub(channel)
                .withInterceptors(
                    MetadataUtils.newAttachHeadersInterceptor(
                        Metadata().apply { put(TokenAuthInterceptor.AUTHORIZATION, "Bearer $token") },
                    ),
                ).withDeadlineAfter(10, TimeUnit.SECONDS)

        init {
            descriptor.write(state.resolve(DaemonDescriptor.FILE_NAME))
        }

        fun held(name: String = "test-client"): String =
            stub
                .connect(
                    ConnectRequest
                        .newBuilder()
                        .setName(name)
                        .setHold(Hold.newBuilder().setIdleTimeoutMs(60000))
                        .build(),
                ).clientConnectionId

        fun run(vararg args: String): Triple<Int, List<String>, List<String>> {
            val out = mutableListOf<String>()
            val err = mutableListOf<String>()
            val code = runCli(listOf("--state-dir", state.toString()) + args, out::add, err::add)
            return Triple(code, out, err)
        }

        override fun close() {
            runBlocking { daemon.close() }
            channel.shutdownNow()
            server.shutdownNow()
            server.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `discovery details and status are authenticated and do not create or renew sessions`() =
        Fixture().use { f ->
            val id = f.held()
            val activity =
                f.daemon
                    .connections()
                    .single()
                    .lastActivityAtEpochMs
            val devices = f.run("devices", "--json")
            assertEquals(0, devices.first)
            assertTrue(devices.third.isEmpty())
            val device = (Json.parseToJsonElement(devices.second.single()) as JsonObject).getValue("devices").jsonArray.single()
            assertEquals("phone", (device as JsonObject).getValue("serial").jsonPrimitive.content)
            val details = f.run("devices", "show", "phone", "--json")
            assertTrue(details.second.single().contains("restore failed"))
            assertEquals(1, f.run("devices", "show", "missing").first)
            assertEquals(0, f.run("sessions", "--json").first)
            val session = f.run("sessions", "show", id, "--json")
            assertTrue(session.second.single().contains("created_at_epoch_ms"))
            assertTrue(session.second.single().contains("last_activity_at_epoch_ms"))
            assertEquals(1, f.run("sessions", "show", "test-client").first)
            val status = f.run("status", "--json")
            assertTrue(status.second.single().contains("\"state\":\"running\""))
            assertFalse(status.second.single().contains(f.token))
            assertEquals(listOf(id), f.daemon.connections().map { it.id })
            assertEquals(
                activity,
                f.daemon
                    .connections()
                    .single()
                    .lastActivityAtEpochMs,
            )
        }

    @Test
    fun `force close interrupts observed clients and cannot accidentally resolve a name`() =
        Fixture().use { f ->
            val id = f.stub.connect(ConnectRequest.newBuilder().setName("JUnit").build()).clientConnectionId
            val stream = f.stub.observe(ObserveRequest.newBuilder().setClientConnectionId(id).build())
            stream.next()
            assertEquals(2, f.run("sessions", "close", id).first)
            assertTrue(f.daemon.clientConnectionExists(id))
            assertEquals(1, f.run("sessions", "close", "JUnit", "--force").first)
            val result = f.run("sessions", "close", id, "--force", "--reason", "stuck test", "--json")
            assertEquals(0, result.first)
            assertTrue(result.second.single().contains("administrator: stuck test"))
            val rest = generateSequence { if (stream.hasNext()) stream.next() else null }.toList()
            assertEquals("administrator: stuck test", rest.last().closing.reason)
            assertFalse(f.daemon.clientConnectionExists(id))
            assertEquals(1, f.run("sessions", "close", id, "--force").first)
        }

    @Test
    fun `restart guards sessions preserves running flags and stops before starting`() =
        Fixture().use { f ->
            f.held()
            val actions = mutableListOf<String>()
            var settings = emptyMap<String, String>()
            val stopper: (Path, (String) -> Unit) -> Int = { _, _ ->
                actions += "stop"
                0
            }
            val starter: (Map<String, String>, Path) -> Unit = { options, _ ->
                actions += "start"
                settings = options
            }
            assertFailsWith<IllegalStateException> { restartDaemon(CommandLine("restart"), f.state, {}, stopper, starter) }
            assertTrue(actions.isEmpty())
            assertEquals(
                0,
                restartDaemon(CommandLine("restart", mapOf("--force" to "true", "--port" to "12345")), f.state, {}, stopper, starter),
            )
            assertEquals(listOf("stop", "start"), actions)
            assertEquals("/custom/scrcpy", settings["--scrcpy"])
            assertEquals("1000", settings["--driver-idle-timeout-ms"])
            assertEquals("12345", settings["--port"])
            actions.clear()
            assertEquals(1, restartDaemon(CommandLine("restart", mapOf("--force" to "true")), f.state, {}, { _, _ -> 1 }, starter))
            assertTrue(actions.isEmpty())
        }

    @Test
    fun `wrong tokens and recycled PIDs fail without leaking credentials`() =
        Fixture().use { f ->
            f.descriptor.copy(token = "wrong-token").write(f.state.resolve(DaemonDescriptor.FILE_NAME))
            val denied = f.run("sessions", "--json")
            assertEquals(1, denied.first)
            assertFalse(denied.second.joinToString().contains("wrong-token"))
            f.descriptor.copy(pid = f.descriptor.pid + 1).write(f.state.resolve(DaemonDescriptor.FILE_NAME))
            assertEquals(1, f.run("status").first)
            assertEquals(1, f.run("devices").first)
            assertEquals(0, stop(f.state) {})
            assertFalse(Files.exists(f.state.resolve(DaemonDescriptor.FILE_NAME)))
            assertTrue(f.server.isShutdown.not())
        }

    @Test
    fun `configuration and diagnostics are allowlisted and inspection is passive`() =
        Fixture().use { f ->
            val id = f.held()
            val activity =
                f.daemon
                    .connections()
                    .single()
                    .lastActivityAtEpochMs
            val config = f.run("config", "show", "--json")
            assertEquals(0, config.first)
            assertTrue(config.second.single().contains("/custom/scrcpy"))
            assertTrue(config.second.single().contains("command line"))
            assertFalse(config.second.single().contains(f.token))
            Files.writeString(
                f.state.resolve("daemon.log"),
                "Bearer ${f.token}\npassword=super-secret\n[tap] lifecycle serial=phone stage=attach_total outcome=ok elapsed_ms=17\n",
            )
            Files.createDirectories(f.state.resolve("sessions"))
            Files.writeString(f.state.resolve("sessions/private.json"), "secret-journal")
            val zip = f.state.resolve("diagnostics.zip")
            assertEquals(0, f.run("diagnostics", "--out", zip.toString()).first)
            ZipFile(zip.toFile()).use { archive ->
                val names =
                    archive
                        .entries()
                        .asSequence()
                        .map { it.name }
                        .toList()
                assertFalse(names.any { "daemon.json" in it || "private" in it })
                val content =
                    names.joinToString { name ->
                        archive.getInputStream(archive.getEntry(name)).bufferedReader().use { it.readText() }
                    }
                assertFalse(content.contains(f.token))
                assertFalse(content.contains("super-secret"))
                assertFalse(content.contains("secret-journal"))
                assertTrue(content.contains("elapsed_ms=17"))
            }
            if (Files
                    .getFileStore(
                        zip,
                    ).supportsFileAttributeView(
                        "posix",
                    )
            ) {
                assertEquals(
                    "rw-------",
                    java.nio.file.attribute.PosixFilePermissions
                        .toString(Files.getPosixFilePermissions(zip)),
                )
            }
            assertEquals(1, f.run("diagnostics", "--out", zip.toString()).first)
            assertEquals(listOf(id), f.daemon.connections().map { it.id })
            assertEquals(
                activity,
                f.daemon
                    .connections()
                    .single()
                    .lastActivityAtEpochMs,
            )
        }

    @Test
    fun `watch emits newline-delimited JSON snapshots without renewing sessions`() =
        Fixture().use { f ->
            val id = f.held()
            val activity =
                f.daemon
                    .connections()
                    .single()
                    .lastActivityAtEpochMs
            for (command in listOf("status", "devices", "sessions")) {
                val output = mutableListOf<String>()
                var count = 0
                val result =
                    runCli(
                        listOf(command, "--state-dir", f.state.toString(), "--watch", "--json", "--interval", "0.1"),
                        output::add,
                        { error(it) },
                        keepGoing = { output.size < 2 },
                        pause = {
                            assertEquals(100L, it)
                            count++
                        },
                    )
                assertEquals(0, result)
                assertEquals(2, output.size)
                output.forEach { assertTrue((Json.parseToJsonElement(it) as JsonObject).containsKey("observed_at")) }
                assertEquals(1, count)
            }
            assertEquals(listOf(id), f.daemon.connections().map { it.id })
            assertEquals(
                activity,
                f.daemon
                    .connections()
                    .single()
                    .lastActivityAtEpochMs,
            )
        }
}
