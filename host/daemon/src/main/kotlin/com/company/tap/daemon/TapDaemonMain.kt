package com.company.tap.daemon

import com.company.tap.api.v1.ClientConnectionServiceGrpc
import com.company.tap.api.v1.InfoRequest
import com.company.tap.host.Adb
import com.company.tap.server.AppService
import com.company.tap.server.ClientConnectionService
import com.company.tap.server.DAEMON_VERSION
import com.company.tap.server.DeviceService
import com.company.tap.server.TokenAuthInterceptor
import io.grpc.ManagedChannelBuilder
import io.grpc.Metadata
import io.grpc.Server
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.stub.MetadataUtils
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import kotlinx.coroutines.runBlocking
import java.io.File
import java.lang.management.ManagementFactory
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.system.exitProcess

/** Explicit total budget for the shutdown hook before `server.awaitTermination`. */
const val DAEMON_SHUTDOWN_HOOK_TIMEOUT_MS = DAEMON_SHUTDOWN_TOTAL_MS

private const val STOP_SLACK_MS = 5_000L

/**
 * `tap start  [--port N] [--state-dir DIR] [--adb PATH] [--driver-apk APK --driver-test-apk APK]` — background
 * `tap serve  (same options)` — run in the foreground
 * `tap status [--state-dir DIR]`
 * `tap stop   [--state-dir DIR]`
 *
 * Starting the daemon is explicit: clients never spawn it. `start` is idempotent — a live
 * daemon is reported and reused — and like the ADB server a started daemon stays up until
 * `stop`, so every test process on the machine shares it.
 *
 * `serve` holds `<state-dir>/daemon.lock` for its whole life (one daemon per state dir), binds
 * loopback only and writes `<state-dir>/daemon.json` (0600: port, pid, token, version). Every RPC
 * must present that per-instance token (`authorization: Bearer <token>`), so only the owner of
 * the state dir can drive the daemon; `stop` and `status` use it to prove they reach the daemon
 * the descriptor names before trusting its pid.
 */
fun main(args: Array<String>) {
    val command = args.firstOrNull() ?: usage()
    val allowed = COMMAND_OPTIONS[command] ?: usage()
    val options = parseOptions(args.drop(1))
    (options.keys - allowed).firstOrNull()?.let { unknown ->
        System.err.println("option $unknown is not valid for `tap $command`")
        usage()
    }
    options["--port"]?.let { value ->
        if (value.toIntOrNull()?.takeIf { it in 0..65535 } == null) {
            System.err.println("--port must be an integer in 0..65535, got '$value'")
            usage()
        }
    }
    if (("--driver-apk" in options) != ("--driver-test-apk" in options)) {
        System.err.println("--driver-apk and --driver-test-apk go together")
        usage()
    }
    val stateDir = Path.of(options["--state-dir"] ?: defaultStateDir()).toAbsolutePath()
    when (command) {
        "start" -> start(options, stateDir)
        "serve" -> serve(options, stateDir)
        "status" -> status(stateDir)
        "stop" -> stop(stateDir)
        "version" -> println("tap daemon ${DAEMON_VERSION}")
    }
}

private fun usage(): Nothing {
    System.err.println(
        """
        usage: tap start   [--port N] [--state-dir DIR] [--adb PATH] [--driver-apk APK --driver-test-apk APK]
                           (background; reuses a running daemon)
               tap serve   (same options as start; foreground)
               tap status  [--state-dir DIR]
               tap stop    [--state-dir DIR]
               tap version
        """.trimIndent(),
    )
    exitProcess(2)
}

private val SERVE_OPTIONS = setOf("--port", "--state-dir", "--adb", "--driver-apk", "--driver-test-apk")
private val COMMAND_OPTIONS =
    mapOf(
        "start" to SERVE_OPTIONS,
        "serve" to SERVE_OPTIONS,
        "status" to setOf("--state-dir"),
        "stop" to setOf("--state-dir"),
        "version" to emptySet(),
    )

/** `serve` exit status when another process holds the state dir's lock. */
private const val EXIT_LOCKED = 3

private fun defaultStateDir(): String =
    System.getenv("TAP_STATE_DIR")
        ?: Path.of(System.getProperty("user.home"), ".tap").toString()

private fun parseOptions(args: List<String>): Map<String, String> {
    val options = mutableMapOf<String, String>()
    var i = 0
    while (i < args.size) {
        val key = args[i]
        if (!key.startsWith("--")) usage()
        val value = args.getOrNull(i + 1)?.takeUnless { it.startsWith("--") }
        if (value == null) usage()
        if (options.put(key, value) != null) {
            System.err.println("option $key given twice")
            usage()
        }
        i += 2
    }
    return options
}

private fun serve(
    options: Map<String, String>,
    stateDir: Path,
) {
    Files.createDirectories(stateDir)
    val lock = DaemonLock.tryAcquire(stateDir)
    if (lock == null) {
        System.err.println("another tap daemon holds ${stateDir.resolve(DaemonLock.FILE_NAME)}; use `tap status`")
        exitProcess(EXIT_LOCKED)
    }
    val port = options["--port"]?.toInt() ?: 0
    val adb = Adb(options["--adb"] ?: System.getenv("TAP_ADB") ?: "adb")
    val log: (String) -> Unit = { line -> println("[tap] $line") }

    // The override replaces the bundled driver for every attach this daemon serves.
    val driver =
        options["--driver-apk"]?.let { apk ->
            try {
                DriverApks.override(Path.of(apk), Path.of(options.getValue("--driver-test-apk")))
            } catch (error: IllegalArgumentException) {
                System.err.println(error.message)
                exitProcess(2)
            }
        } ?: DriverApks.extractBundled(stateDir)
    if (driver == null) log("no driver in this build and no --driver-apk; attach needs skip_driver_install")

    val token = DaemonDescriptor.newToken()
    val daemon = TapDaemon(DaemonConfig(adb, stateDir, driver = driver, log = log))
    val server: Server =
        NettyServerBuilder
            .forAddress(InetSocketAddress("127.0.0.1", port))
            .addService(ClientConnectionService(daemon))
            .addService(DeviceService(daemon))
            .addService(AppService(daemon))
            .intercept(TokenAuthInterceptor(token))
            .maxInboundMessageSize(8 * 1024 * 1024)
            .build()
            .start()

    DaemonDescriptor(server.port, ProcessHandle.current().pid(), token, DAEMON_VERSION, adb.executable)
        .write(stateDir.resolve(DaemonDescriptor.FILE_NAME))
    log("listening on 127.0.0.1:${server.port} (state $stateDir, adb ${adb.executable}, driver ${driver?.driverApk ?: "none"})")

    Runtime.getRuntime().addShutdownHook(
        Thread {
            log("shutting down")
            server.shutdown()
            // One deadline covers both device cleanup and gRPC termination, so the two cannot add up
            // past the advertised hook budget: TapDaemon.close receives only the remaining budget and
            // itself returns within it (on exhaustion it detaches all remaining state and launches
            // every remaining cleanup fire-and-forget on its own scope, without serially awaiting),
            // then awaitTermination receives only what is still remaining. Detached cleanups are best
            // effort and may be cut short by process exit; next open recovers a non-terminal journal.
            val shutdownDeadlineNanos = System.nanoTime() + DAEMON_SHUTDOWN_HOOK_TIMEOUT_MS * 1_000_000L
            runBlocking {
                runCatching { daemon.close(remainingShutdownMs(shutdownDeadlineNanos)) }
                    .onFailure { log("shutdown cleanup failed: ${it.message}") }
            }
            val grpcTerminationMs = remainingShutdownMs(shutdownDeadlineNanos)
            if (grpcTerminationMs > 0L) server.awaitTermination(grpcTerminationMs, TimeUnit.MILLISECONDS)
            if (!server.isTerminated) {
                server.shutdownNow()
                log("shutdown budget ${DAEMON_SHUTDOWN_HOOK_TIMEOUT_MS}ms exceeded; forcing process exit")
            }
            runCatching { DaemonDescriptor.deleteIfOwned(stateDir, token) }
            lock.close()
        },
    )
    server.awaitTermination()
}

private fun remainingShutdownMs(deadlineNanos: Long): Long = ((deadlineNanos - System.nanoTime()).coerceAtLeast(0L) / 1_000_000L)

/**
 * Starts `serve` in the background and waits until it answers `Info`. Readiness is the health
 * RPC itself (no output parsing): a port is reserved and handed to the child with `--port`, and
 * the child's descriptor (for its token) is polled until the daemon answers or the child exits.
 * Prints `running 127.0.0.1:PORT pid=PID` when a live daemon already exists (nothing started),
 * or `started 127.0.0.1:PORT pid=PID`; exits non-zero when the child died or never became ready.
 * A concurrent `start` that loses the state-dir lock waits for the winner instead of failing.
 */
private fun start(
    options: Map<String, String>,
    stateDir: Path,
) {
    Files.createDirectories(stateDir)
    liveDaemon(stateDir)?.let {
        println("running 127.0.0.1:${it.port} pid=${it.pid}")
        return
    }
    val port = options["--port"]?.toInt()?.takeIf { it > 0 } ?: freePort()
    val log = stateDir.resolve("daemon.log")
    if (!Files.exists(log)) Files.createFile(log)
    restrictToOwner(log)
    val command =
        relaunchCommand() + listOf("serve", "--port", port.toString(), "--state-dir", stateDir.toString()) +
            options.filterKeys { it != "--port" && it != "--state-dir" }.flatMap { (key, value) -> listOf(key, value) }
    val process =
        ProcessBuilder(command)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()))
            .redirectInput(ProcessBuilder.Redirect.from(File(if (File.separatorChar == '\\') "NUL" else "/dev/null")))
            .start()
    val deadline = System.nanoTime() + START_TIMEOUT_MS * 1_000_000
    var lostLock = false
    while (System.nanoTime() < deadline) {
        if (!lostLock && !process.isAlive) {
            if (process.exitValue() != EXIT_LOCKED) {
                System.err.println("tap serve exited with ${process.exitValue()} before becoming ready (see $log)")
                exitProcess(1)
            }
            lostLock = true
        }
        val live = liveDaemon(stateDir)
        if (live != null && (lostLock || live.pid == process.pid())) {
            println("${if (lostLock) "running" else "started"} 127.0.0.1:${live.port} pid=${live.pid}")
            return
        }
        Thread.sleep(100)
    }
    if (process.isAlive) process.destroyForcibly()
    System.err.println("tap serve did not become ready within ${START_TIMEOUT_MS / 1000}s (see $log)")
    exitProcess(1)
}

private const val START_TIMEOUT_MS = 30_000L

private enum class Probe { OK, REJECTED, DOWN }

/** The descriptor in [stateDir], if the daemon it names answers `Info` with its token. */
private fun liveDaemon(stateDir: Path): DaemonDescriptor? =
    DaemonDescriptor.read(stateDir)?.takeIf { probe(it) == Probe.OK }

/**
 * `Info` against the descriptor's port with its token. [Probe.REJECTED] means something else
 * (another daemon, or an unrelated gRPC server) now owns the port.
 */
private fun probe(descriptor: DaemonDescriptor): Probe {
    val channel = ManagedChannelBuilder.forTarget("127.0.0.1:${descriptor.port}").usePlaintext().build()
    return try {
        val headers = Metadata().apply { put(TokenAuthInterceptor.AUTHORIZATION, "Bearer ${descriptor.token}") }
        ClientConnectionServiceGrpc
            .newBlockingStub(channel)
            .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers))
            .withDeadlineAfter(2, TimeUnit.SECONDS)
            .info(InfoRequest.getDefaultInstance())
        Probe.OK
    } catch (error: StatusRuntimeException) {
        when (error.status.code) {
            Status.Code.UNAVAILABLE, Status.Code.DEADLINE_EXCEEDED -> Probe.DOWN
            else -> Probe.REJECTED
        }
    } finally {
        channel.shutdownNow()
    }
}

/** A port that was free a moment ago; `serve` fails fast if something else takes it first. */
private fun freePort(): Int = ServerSocket(0).use { it.localPort }

/**
 * How to run this same program again: the native executable itself, or the current JVM with
 * the current JVM flags, class path and main class (the JVM dist's launcher script is not re-entered).
 */
private fun relaunchCommand(): List<String> {
    val command =
        ProcessHandle
            .current()
            .info()
            .command()
            .orElse(null)
    if (System.getProperty("org.graalvm.nativeimage.imagecode") != null && command != null) return listOf(command)
    val java = Path.of(System.getProperty("java.home"), "bin", if (File.separatorChar == '\\') "java.exe" else "java")
    // Keep the launcher's JVM flags (heap, JAVA_OPTS such as the native-image tracing agent).
    val jvmFlags = ManagementFactory.getRuntimeMXBean().inputArguments
    return listOf(java.toString()) + jvmFlags + listOf("-cp", System.getProperty("java.class.path"), "com.company.tap.daemon.TapDaemonMainKt")
}

/** Prints the live daemon (never its token); exits 1 when none answers. */
private fun status(stateDir: Path) {
    val descriptor = DaemonDescriptor.read(stateDir)
    if (descriptor == null) {
        println("no daemon (no readable ${stateDir.resolve(DaemonDescriptor.FILE_NAME)})")
        exitProcess(1)
    }
    when (probe(descriptor)) {
        Probe.OK -> println("running 127.0.0.1:${descriptor.port} pid=${descriptor.pid} version=${descriptor.daemonVersion} adb=${descriptor.adb}")
        Probe.REJECTED -> {
            println("stale descriptor: port ${descriptor.port} is served by something else")
            exitProcess(1)
        }
        Probe.DOWN -> {
            println("not responding: daemon pid ${descriptor.pid} at 127.0.0.1:${descriptor.port} does not answer")
            exitProcess(1)
        }
    }
}

/**
 * Signals the daemon only after it proved (by answering `Info` with the descriptor's token) that
 * it is the process that wrote the descriptor, so a recycled pid is never killed.
 */
private fun stop(stateDir: Path) {
    val descriptor = DaemonDescriptor.read(stateDir)
    if (descriptor == null) {
        println("no daemon running (no readable ${stateDir.resolve(DaemonDescriptor.FILE_NAME)})")
        return
    }
    val handle = ProcessHandle.of(descriptor.pid).orElse(null)
    val probe = probe(descriptor)
    if (probe != Probe.OK || handle == null) {
        val why = if (probe == Probe.REJECTED) "port ${descriptor.port} rejects its token" else "daemon pid ${descriptor.pid} does not answer"
        println("stale descriptor ($why); removing it, signalling nothing")
        DaemonDescriptor.deleteIfOwned(stateDir, descriptor.token)
        return
    }
    handle.destroy()
    try {
        handle.onExit().get(DAEMON_SHUTDOWN_HOOK_TIMEOUT_MS + STOP_SLACK_MS, TimeUnit.MILLISECONDS)
    } catch (_: TimeoutException) {
        System.err.println("daemon pid ${descriptor.pid} still shutting down after ${DAEMON_SHUTDOWN_HOOK_TIMEOUT_MS}ms")
        exitProcess(1)
    }
    DaemonDescriptor.deleteIfOwned(stateDir, descriptor.token)
    println("stopped daemon pid ${descriptor.pid}")
}
