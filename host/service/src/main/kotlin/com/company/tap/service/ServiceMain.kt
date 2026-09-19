package com.company.tap.service

import com.company.tap.host.Adb
import io.grpc.Server
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * `tap serve [--port N] [--state-dir DIR] [--adb PATH] [--serials a,b]`
 * `tap status [--state-dir DIR]`
 * `tap stop [--state-dir DIR]`
 *
 * Like the ADB server, a started service stays up until stopped: clients that auto-start it
 * do not own it, so a second client on the same machine can share it.
 *
 * The server binds loopback only and writes `<state-dir>/service.json` (port, pid, version) so
 * clients can find it. There is no authentication between client and service: both run as the
 * same user on the same machine; device access is still gated by the per-session driver secret.
 */
fun main(args: Array<String>) {
    val command = args.firstOrNull() ?: usage()
    val options = parseOptions(args.drop(1))
    val stateDir = Path.of(options["--state-dir"] ?: defaultStateDir()).toAbsolutePath()
    when (command) {
        "serve" -> serve(options, stateDir)
        "status" -> status(stateDir)
        "stop" -> stop(stateDir)
        "version" -> println("tap service $SERVICE_VERSION")
        else -> usage()
    }
}

private fun usage(): Nothing {
    System.err.println(
        """
        usage: tap serve   [--port N] [--state-dir DIR] [--adb PATH] [--serials a,b]
               tap status  [--state-dir DIR]
               tap stop    [--state-dir DIR]
               tap version
        """.trimIndent(),
    )
    exitProcess(2)
}

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
    val serials = options["--serials"]?.split(',')?.map(String::trim)?.filter(String::isNotEmpty)?.toSet()
    val log: (String) -> Unit = { line -> println("[tap] $line") }

    val bundled = BundledDriver.extract(stateDir)
    if (bundled == null) log("no bundled driver in this build; sessions must pass driver_apk/driver_test_apk or skip_driver_install")

    val service = TapService(ServiceConfig(adb, stateDir, allowedSerials = serials, bundledDriver = bundled, log = log))
    val scheduler = Executors.newSingleThreadScheduledExecutor { Thread(it, "tap-scheduler").apply { isDaemon = true } }
    // Commands block for up to two minutes each; never let them share a fixed-size pool.
    val executor = Executors.newCachedThreadPool { Thread(it, "tap-rpc").apply { isDaemon = true } }
    val server: Server = NettyServerBuilder.forAddress(InetSocketAddress("127.0.0.1", port))
        .executor(executor)
        .addService(RunServicer(service, scheduler))
        .addService(PoolServicer(service))
        .addService(SessionServicer(service, executor))
        .addService(AppServicer(service))
        .maxInboundMessageSize(8 * 1024 * 1024)
        .build()
        .start()

    val descriptor = stateDir.resolve("service.json")
    writeAtomically(
        descriptor,
        """{"port":${server.port},"pid":${ProcessHandle.current().pid()},"version":"$SERVICE_VERSION","adb":"${adb.executable}"}""",
    )
    log("listening on 127.0.0.1:${server.port} (state $stateDir, adb ${adb.executable}, bundled driver ${bundled != null})")
    println("TAP_SERVICE_READY port=${server.port}")
    System.out.flush()

    Runtime.getRuntime().addShutdownHook(Thread {
        log("shutting down")
        server.shutdown()
        runCatching { service.close() }
        server.awaitTermination(10, TimeUnit.SECONDS)
        runCatching { Files.deleteIfExists(descriptor) }
    })
    server.awaitTermination()
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
