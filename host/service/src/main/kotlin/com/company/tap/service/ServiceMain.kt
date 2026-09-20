package com.company.tap.service

import com.company.tap.api.v1.ConnectionServiceGrpc
import com.company.tap.api.v1.InfoRequest
import com.company.tap.host.Adb
import com.company.tap.service.servicer.AppServicer
import com.company.tap.service.servicer.ConnectionServicer
import com.company.tap.service.servicer.DeviceServicer
import com.company.tap.service.servicer.SERVICE_VERSION
import com.company.tap.service.servicer.SessionServicer
import io.grpc.ManagedChannelBuilder
import io.grpc.Server
import io.grpc.StatusRuntimeException
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/** Explicit total budget for the shutdown hook before `server.awaitTermination`. */
const val SERVICE_SHUTDOWN_HOOK_TIMEOUT_MS = SERVICE_SHUTDOWN_TOTAL_MS

/**
 * `tap start  [--port N] [--state-dir DIR] [--adb PATH]` — start in the background
 * `tap serve  [--port N] [--state-dir DIR] [--adb PATH]` — run in the foreground
 * `tap status [--state-dir DIR]`
 * `tap stop   [--state-dir DIR]`
 *
 * Starting the service is explicit: clients never spawn it. `start` is idempotent — a live
 * service is reported and reused — and like the ADB server a started service stays up until
 * `stop`, so every test process on the machine shares it.
 *
 * The server binds loopback only and writes `<state-dir>/service.json` (port, pid, version) so
 * clients can find it. There is no authentication between client and service: both run as the
 * same user on the same machine; device access is still gated by the per-session driver secret.
 */
fun main(args: Array<String>) {
    val command = args.firstOrNull() ?: usage()
    val options = parseOptions(args.drop(1))
    (options.keys - KNOWN_OPTIONS).firstOrNull()?.let { unknown ->
        System.err.println("unknown option $unknown")
        usage()
    }
    val stateDir = Path.of(options["--state-dir"] ?: defaultStateDir()).toAbsolutePath()
    when (command) {
        "start" -> start(options, stateDir)
        "serve" -> serve(options, stateDir)
        "status" -> status(stateDir)
        "stop" -> stop(stateDir)
        "version" -> println("tap service ${SERVICE_VERSION}")
        else -> usage()
    }
}

private fun usage(): Nothing {
    System.err.println(
        """
        usage: tap start   [--port N] [--state-dir DIR] [--adb PATH]   (background; reuses a running service)
               tap serve   [--port N] [--state-dir DIR] [--adb PATH]   (foreground)
               tap status  [--state-dir DIR]
               tap stop    [--state-dir DIR]
               tap version
        """.trimIndent(),
    )
    exitProcess(2)
}

private val KNOWN_OPTIONS = setOf("--port", "--state-dir", "--adb")

private fun defaultStateDir(): String = System.getenv("TAP_STATE_DIR")
    ?: Path.of(System.getProperty("user.home"), ".tap").toString()

private fun parseOptions(args: List<String>): Map<String, String> {
    val options = mutableMapOf<String, String>()
    var i = 0
    while (i < args.size) {
        val key = args[i]
        if (!key.startsWith("--")) usage()
        val value = args.getOrNull(i + 1)?.takeUnless { it.startsWith("--") }
        if (value == null) usage()
        options[key] = value
        i += 2
    }
    return options
}

private fun serve(options: Map<String, String>, stateDir: Path) {
    Files.createDirectories(stateDir)
    val port = options["--port"]?.toInt() ?: 0
    val adb = Adb(options["--adb"] ?: System.getenv("TAP_ADB") ?: "adb")
    val log: (String) -> Unit = { line -> println("[tap] $line") }

    val bundled = BundledDriver.extract(stateDir)
    if (bundled == null) log("no bundled driver in this build; sessions must pass driver_apk/driver_test_apk or skip_driver_install")

    val service = TapService(ServiceConfig(adb, stateDir, bundledDriver = bundled, log = log))
    val server: Server = NettyServerBuilder.forAddress(InetSocketAddress("127.0.0.1", port))
        .addService(ConnectionServicer(service))
        .addService(DeviceServicer(service))
        .addService(SessionServicer(service))
        .addService(AppServicer(service))
        .maxInboundMessageSize(8 * 1024 * 1024)
        .build()
        .start()

    val descriptor = stateDir.resolve("service.json")
    writeAtomically(
        descriptor,
        """{"port":${server.port},"pid":${ProcessHandle.current().pid()},"version":"${SERVICE_VERSION}","adb":"${adb.executable}"}""",
    )
    log("listening on 127.0.0.1:${server.port} (state $stateDir, adb ${adb.executable}, bundled driver ${bundled != null})")

    Runtime.getRuntime().addShutdownHook(Thread {
        log("shutting down")
        server.shutdown()
        // Bounded shutdown: service.close() itself propagates a remaining deadline to every
        // session close, and this hook adds an outer bound so a stuck cleanup can never hold
        // the hook forever before server.awaitTermination. Never runBlocking(NonCancellable).
        runBlocking {
            val finished =
                withTimeoutOrNull(SERVICE_SHUTDOWN_HOOK_TIMEOUT_MS) {
                    runCatching { service.close(SERVICE_SHUTDOWN_HOOK_TIMEOUT_MS) }
                        .onFailure { log("shutdown cleanup failed: ${it.message}") }
                    true
                }
            if (finished == null) log("shutdown budget ${SERVICE_SHUTDOWN_HOOK_TIMEOUT_MS}ms exceeded; some devices may be quarantined")
        }
        server.awaitTermination(10, TimeUnit.SECONDS)
        runCatching { Files.deleteIfExists(descriptor) }
    })
    server.awaitTermination()
}

/**
 * Starts `serve` in the background and waits until it answers `Info`. Readiness is the health
 * RPC itself on a port chosen here (no output parsing): a port is reserved, handed to the child
 * with `--port`, and polled until it answers or the child exits. Prints
 * `running 127.0.0.1:PORT pid=PID` when a live service already exists (nothing started), or
 * `started 127.0.0.1:PORT pid=PID`; exits non-zero when the child died or never became ready.
 */
private fun start(options: Map<String, String>, stateDir: Path) {
    Files.createDirectories(stateDir)
    liveService(stateDir)?.let { (port, pid) ->
        println("running 127.0.0.1:$port pid=$pid")
        return
    }
    val port = options["--port"]?.toInt()?.takeIf { it > 0 } ?: freePort()
    val log = stateDir.resolve("service.log").toFile()
    val command = relaunchCommand() + listOf("serve", "--port", port.toString(), "--state-dir", stateDir.toString()) +
        options.filterKeys { it == "--adb" }.flatMap { (key, value) -> listOf(key, value) }
    val process = ProcessBuilder(command)
        .redirectErrorStream(true)
        .redirectOutput(ProcessBuilder.Redirect.appendTo(log))
        .redirectInput(ProcessBuilder.Redirect.from(File(if (File.separatorChar == '\\') "NUL" else "/dev/null")))
        .start()
    val address = "127.0.0.1:$port"
    val deadline = System.nanoTime() + START_TIMEOUT_MS * 1_000_000
    while (System.nanoTime() < deadline) {
        if (!process.isAlive) {
            System.err.println("tap serve exited with ${process.exitValue()} before becoming ready (see $log)")
            exitProcess(1)
        }
        if (infoAnswers(address)) {
            println("started $address pid=${process.pid()}")
            return
        }
        Thread.sleep(100)
    }
    process.destroyForcibly()
    System.err.println("tap serve did not become ready within ${START_TIMEOUT_MS / 1000}s (see $log)")
    exitProcess(1)
}

private const val START_TIMEOUT_MS = 30_000L

/** `(port, pid)` of the service the descriptor points at, if it answers `Info`. */
private fun liveService(stateDir: Path): Pair<Int, Long>? {
    val descriptor = stateDir.resolve("service.json").takeIf(Files::exists) ?: return null
    val text = runCatching { Files.readString(descriptor) }.getOrNull() ?: return null
    val port = Regex(""""port":(\d+)""").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: return null
    val pid = Regex(""""pid":(\d+)""").find(text)?.groupValues?.get(1)?.toLongOrNull() ?: return null
    return if (infoAnswers("127.0.0.1:$port")) port to pid else null
}

private fun infoAnswers(address: String): Boolean {
    val channel = ManagedChannelBuilder.forTarget(address).usePlaintext().build()
    return try {
        ConnectionServiceGrpc.newBlockingStub(channel).withDeadlineAfter(2, TimeUnit.SECONDS).info(InfoRequest.getDefaultInstance())
        true
    } catch (_: StatusRuntimeException) {
        false
    } finally {
        channel.shutdownNow()
    }
}

/** A port that was free a moment ago; `serve` fails fast if something else takes it first. */
private fun freePort(): Int = ServerSocket(0).use { it.localPort }

/**
 * How to run this same program again: the native executable itself, or the current JVM with
 * the current class path and main class (the JVM dist's launcher script is not re-entered).
 */
private fun relaunchCommand(): List<String> {
    val command = ProcessHandle.current().info().command().orElse(null)
    if (System.getProperty("org.graalvm.nativeimage.imagecode") != null && command != null) return listOf(command)
    val java = Path.of(System.getProperty("java.home"), "bin", if (File.separatorChar == '\\') "java.exe" else "java")
    return listOf(java.toString(), "-cp", System.getProperty("java.class.path"), "com.company.tap.service.ServiceMainKt")
}

private fun status(stateDir: Path) {
    val descriptor = stateDir.resolve("service.json")
    if (!Files.exists(descriptor)) {
        println("no service descriptor at $descriptor")
        exitProcess(1)
    }
    println(Files.readString(descriptor))
}

private fun stop(stateDir: Path) {
    val descriptor = stateDir.resolve("service.json")
    if (!Files.exists(descriptor)) {
        println("no service running (no descriptor at $descriptor)")
        return
    }
    val pid = Regex(""""pid":(\d+)""").find(Files.readString(descriptor))?.groupValues?.get(1)?.toLong()
    val handle = pid?.let { ProcessHandle.of(it).orElse(null) }
    if (handle == null) {
        println("stale descriptor (pid $pid not running); removing it")
        Files.deleteIfExists(descriptor)
        return
    }
    handle.destroy()
    handle.onExit().get(15, TimeUnit.SECONDS)
    Files.deleteIfExists(descriptor)
    println("stopped service pid $pid")
}

private fun writeAtomically(target: Path, content: String) {
    val temp = Files.createTempFile(target.parent, "service", ".json.tmp")
    Files.write(temp, content.toByteArray(StandardCharsets.UTF_8))
    Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
}
