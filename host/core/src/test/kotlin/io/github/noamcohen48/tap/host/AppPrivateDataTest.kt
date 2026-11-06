package io.github.noamcohen48.tap.host

import io.github.noamcohen48.tap.protocol.InvalidCommandException
import io.github.noamcohen48.tap.protocol.Responses
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Execute the actual POSIX scripts in a disposable sandbox, never against real app data. */
class AppPrivateDataTest {
    @TempDir lateinit var temp: Path

    @Test fun pathsAreNormalizedAndBounded() {
        for (path in listOf(
            "",
            "/data/a",
            "../a",
            "a/../b",
            "a//b",
            "a/",
            ".",
            "a/./b",
            "a\\b",
            "a\u0000b",
            "a\nb",
            "a/.tap-private-stage-x",
            "x".repeat(1025),
        )) {
            assertFailsWith<InvalidCommandException>(path) { AppPrivateData.validatePath(path) }
        }
        AppPrivateData.validatePath("", allowRoot = true)
        AppPrivateData.validatePath("files/é ' \$literal.bin")
        listOf("../db", "a/b", "a-wal", "a-shm", "a-journal").forEach { name ->
            assertFailsWith<InvalidCommandException> { AppPrivateData.validateDatabaseName(name) }
        }
    }

    @Test fun binaryCrudAndExplicitOverwrite() =
        runBlocking {
            session { app, adb ->
                val source = temp.resolve("source")
                val destination = temp.resolve("read")
                val bytes = ByteArray(4096) { (it % 256).toByte() }
                Files.write(source, bytes)
                assertTrue(app.data.capabilities().available)
                val path = "files/é ' \$literal.bin"
                app.data.write(path, source)
                assertEquals(listOf("é ' \$literal.bin"), app.data.list("files").map { it.name })
                assertEquals(bytes.size.toLong(), app.data.read(path, destination))
                assertContentEquals(bytes, Files.readAllBytes(destination))
                assertEquals("EXISTS", assertFailsWith<AppDataException> { app.data.write(path, source) }.detail)
                Files.write(source, byteArrayOf(0, -1))
                app.data.write(path, source, overwrite = true)
                app.data.read(path, destination)
                assertContentEquals(byteArrayOf(0, -1), Files.readAllBytes(destination))
                app.data.delete(path)
                assertTrue(app.data.list("files").isEmpty())
                assertEquals("NOT_FOUND", assertFailsWith<AppDataException> { app.data.read(path, destination) }.detail)
                assertEquals(0, adb.appStops)
                assertTrue(Files.list(adb.transfers).use { it.count() } == 0L)
            }
        }

    @Test fun runningUidIncludingNativeOrSharedProcessesRefusesAccess() =
        runBlocking {
            session { app, adb ->
                adb.running = listOf(31337)
                val source = temp.resolve("source")
                Files.write(source, byteArrayOf(1))
                val file = PrivateDataFile("test.db", source, 1)
                assertTrue(app.data.capabilities().available)
                for (action in listOf<suspend () -> Unit>(
                    { app.data.list() },
                    { app.data.read("files/a", temp.resolve("read")) },
                    { app.data.write("files/a", source) },
                    { app.data.delete("files/a") },
                    { app.data.exportDatabase("test.db", temp) },
                    { app.data.importDatabase("test.db", listOf(file)) },
                )) {
                    assertEquals("APP_RUNNING", assertFailsWith<AppDataException> { action() }.detail)
                }
                assertFalse(Files.exists(adb.root.resolve("files")))
                assertEquals(0, adb.appStops)
            }
        }

    @Test fun symlinkLeavesAndParentsAreNeverFollowed() =
        runBlocking {
            session { app, adb ->
                val outside = temp.resolve("outside")
                Files.createDirectory(outside)
                Files.write(outside.resolve("secret"), byteArrayOf(42))
                Files.createSymbolicLink(adb.root.resolve("escape"), outside)
                Files.createSymbolicLink(adb.root.resolve("broken"), temp.resolve("missing"))
                assertTrue(
                    app.data
                        .list()
                        .first { it.name == "escape" }
                        .isSymlink,
                )
                val source = temp.resolve("source")
                Files.write(source, byteArrayOf(1))
                for (path in listOf("escape/secret", "broken")) {
                    assertEquals("SYMLINK", assertFailsWith<AppDataException> { app.data.read(path, temp.resolve("read")) }.detail)
                    assertEquals("SYMLINK", assertFailsWith<AppDataException> { app.data.write(path, source, true) }.detail)
                    assertEquals("SYMLINK", assertFailsWith<AppDataException> { app.data.delete(path) }.detail)
                }
                assertContentEquals(byteArrayOf(42), Files.readAllBytes(outside.resolve("secret")))
            }
        }

    @Test fun databaseBundlesKeepWalAndJournalButDiscardOldCoordinationState() =
        runBlocking {
            session { app, adb ->
                val db = adb.root.resolve("databases")
                Files.createDirectory(db)
                for ((suffix, bytes) in mapOf(
                    "" to "base",
                    "-wal" to "committed WAL",
                    "-journal" to "rollback",
                    "-shm" to "coordination",
                )) {
                    Files.writeString(db.resolve("test.db$suffix"), bytes)
                }
                val exported = Files.createDirectory(temp.resolve("export"))
                val bundle = app.data.exportDatabase("test.db", exported)
                assertEquals(listOf("test.db", "test.db-wal", "test.db-journal"), bundle.map { it.name })
                assertEquals("committed WAL", Files.readString(exported.resolve("test.db-wal")))
                assertEquals("EXISTS", assertFailsWith<AppDataException> { app.data.importDatabase("test.db", bundle) }.detail)
                app.data.importDatabase("test.db", bundle, overwrite = true)
                assertFalse(Files.exists(db.resolve("test.db-shm")))
                assertEquals("committed WAL", Files.readString(db.resolve("test.db-wal")))
                app.data.importDatabase("test.db", bundle.take(1), overwrite = true)
                assertFalse(Files.exists(db.resolve("test.db-wal")))
                assertFalse(Files.exists(db.resolve("test.db-journal")))
                assertEquals("base", Files.readString(db.resolve("test.db")))
            }
        }

    @Test fun failedCommitRestoresOriginalFilesAndNeverLogsPayload() =
        runBlocking {
            session { app, adb ->
                val db = adb.root.resolve("databases")
                Files.createDirectory(db)
                val old = listOf("", "-wal", "-journal", "-shm")
                old.forEach { Files.writeString(db.resolve("test.db$it"), "original$it") }
                val source = temp.resolve("new")
                Files.writeString(source, "secret replacement")
                adb.failCommit = true
                val failure =
                    assertFailsWith<AppDataException> {
                        app.data.importDatabase("test.db", listOf(PrivateDataFile("test.db", source, Files.size(source))), true)
                    }
                assertFalse(failure.message.orEmpty().contains("secret replacement"))
                old.forEach { assertEquals("original$it", Files.readString(db.resolve("test.db$it"))) }
                assertEquals(old.size.toLong(), Files.list(db).use { it.count() })
            }
        }

    @Test fun unavailableRunAsIsReportedWithoutBypassOrRawOutput() =
        runBlocking {
            session { app, adb ->
                adb.runAsAvailable = false
                val access = app.data.capabilities()
                assertFalse(access.available)
                assertFalse(access.reason.orEmpty().contains("raw private error"))
                assertEquals("RUN_AS_UNAVAILABLE", assertFailsWith<AppDataException> { app.data.list() }.detail)
                assertEquals(0, adb.appStops)
            }
        }

    @Test fun lostStagingBeforeCommitCannotDeleteTheOriginalDatabase() =
        runBlocking {
            session { app, adb ->
                val db = Files.createDirectory(adb.root.resolve("databases"))
                Files.writeString(db.resolve("test.db"), "original")
                Files.writeString(db.resolve("test.db-wal"), "original WAL")
                val source = temp.resolve("new")
                Files.writeString(source, "replacement")
                adb.dropStageBeforeCommit = true
                assertFailsWith<AppDataException> {
                    app.data.importDatabase("test.db", listOf(PrivateDataFile("test.db", source, Files.size(source))), true)
                }
                assertEquals("original", Files.readString(db.resolve("test.db")))
                assertEquals("original WAL", Files.readString(db.resolve("test.db-wal")))
            }
        }

    @Test fun explicitLifecycleCallsWaitUntilPrivateTransferCompletes() =
        runBlocking {
            session { app, adb ->
                Files.writeString(adb.root.resolve("file"), "hello")
                adb.blockRead = true
                coroutineScope {
                    val read = async { app.data.read("file", temp.resolve("download")) }
                    withTimeout(5000) { adb.readStarted.await() }
                    val stop = async { app.forceStop(1000) }
                    yield()
                    assertFalse(stop.isCompleted)
                    assertEquals(0, adb.appStops)
                    adb.allowRead.complete(Unit)
                    read.await()
                    stop.await()
                    assertEquals(1, adb.appStops)
                }
            }
        }

    private suspend fun session(block: suspend (AppLifecycle, SandboxAdb) -> Unit) =
        coroutineScope {
            val driver = FakeDriverServer("private-data", 1, ByteArray(32), acceptAnySession = true)
            val delegate =
                FakeAdb(
                    mapOf(
                        "shell cat /proc/sys/kernel/random/boot_id" to ok("boot-1"),
                        "shell am force-stop $DRIVER_PACKAGE" to ok(""),
                        "shell input keyevent KEYCODE_WAKEUP" to ok(""),
                        "shell wm dismiss-keyguard" to ok(""),
                        "forward tcp:0 tcp:$DEVICE_PORT" to ok(driver.port.toString()),
                        "forward --remove tcp:${driver.port}" to ok(""),
                        "shell cat /proc/4242/stat" to ok("4242 (app_process) S 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 99999 20 21"),
                    ),
                )
            delegate.responder = { _, command ->
                when {
                    command == "shell pidof $DRIVER_PACKAGE" -> {
                        val active = delegate.calls.any { "forward tcp:0" in it } && delegate.calls.count { "am force-stop" in it } < 2
                        if (active) ok("4242") else Adb.Result(1, "")
                    }

                    command.startsWith("shell rm -f ") -> {
                        ok("")
                    }

                    // already deleted by typed transfer cleanup
                    else -> {
                        null
                    }
                }
            }
            val adb = SandboxAdb(delegate, Files.createDirectory(temp.resolve("app")), Files.createDirectory(temp.resolve("transfers")))
            val config =
                DeviceSessionConfig(
                    "emulator-5554",
                    journalRoot = temp.resolve("sessions"),
                    adb = adb,
                    processStarter =
                        ProcessStarter { command ->
                            fun option(name: String): String = command[command.indexOf(name) + 1]
                            driver.secret = Base64.getUrlDecoder().decode(option("tapSecret"))
                            FakeProcess(
                                stdout = "INSTRUMENTATION_RESULT: ok\nTAP_READY session=${option(
                                    "tapSession",
                                )} generation=${option("tapGeneration")} port=${option("tapPort")} instance=test-instance\n",
                                exitDelayMs = FakeProcess.NEVER,
                            )
                        },
                )
            try {
                val opening = async(Dispatchers.IO) { DeviceSession.open(config) }
                val health = withTimeout(5000) { driver.nextFrame() }
                driver.respond(health.requestId, Responses.done(1))
                val session = withTimeout(5000) { opening.await() }
                try {
                    block(session.app("com.example.fixture"), adb)
                } finally {
                    session.close()
                }
            } finally {
                driver.close()
            }
        }

    @OptIn(RawAdb::class)
    private class SandboxAdb(
        val delegate: FakeAdb,
        val root: Path,
        val transfers: Path,
    ) : Adb("fake-adb") {
        var running = emptyList<Int>()
        var appStops = 0
        var runAsAvailable = true
        var failCommit = false
        var dropStageBeforeCommit = false
        var blockRead = false
        val readStarted = CompletableDeferred<Unit>()
        val allowRead = CompletableDeferred<Unit>()

        private fun remote(path: String) = transfers.resolve(path.substringAfterLast('/'))

        override suspend fun execResult(
            serial: String,
            vararg arguments: String,
            timeoutMs: Long,
        ): Result = delegate.runResult(serial, *arguments, timeoutMs = timeoutMs)

        override suspend fun appDataUser(serial: String) = 0

        override suspend fun appDataProcesses(
            serial: String,
            uid: Int,
        ) = running

        override suspend fun fileInfo(
            serial: String,
            path: String,
        ): DeviceFileInfo? =
            remote(path)
                .takeIf {
                    Files.exists(it)
                }?.let { DeviceFileInfo(Files.isRegularFile(it), Files.size(it)) }

        override suspend fun createAppDataTransfer(
            serial: String,
            path: String,
        ) {
            Files.createFile(remote(path))
        }

        override suspend fun removeAppDataTransfer(
            serial: String,
            path: String,
        ) {
            Files.deleteIfExists(remote(path))
        }

        override suspend fun push(
            serial: String,
            local: Path,
            devicePath: String,
            timeoutMs: Long,
        ) {
            Files.copy(local, remote(devicePath), StandardCopyOption.REPLACE_EXISTING)
        }

        override suspend fun pull(
            serial: String,
            devicePath: String,
            local: Path,
            timeoutMs: Long,
        ) {
            Files.copy(remote(devicePath), local, StandardCopyOption.REPLACE_EXISTING)
        }

        override suspend fun forceStop(
            serial: String,
            packageName: String,
        ) {
            if (packageName == "com.example.fixture") appStops++ else super.forceStop(serial, packageName)
        }

        override suspend fun processIds(
            serial: String,
            packageName: String,
        ): List<Int> =
            if (packageName ==
                "com.example.fixture"
            ) {
                emptyList()
            } else {
                super.processIds(serial, packageName)
            }

        override suspend fun hasTaskOrActivity(
            serial: String,
            packageName: String,
        ) = false

        override suspend fun runAs(
            serial: String,
            packageName: String,
            userId: Int,
            script: String,
            arguments: List<String>,
            inputFile: String?,
            outputFile: String?,
        ): Result {
            if (script == "id -u") return if (runAsAvailable) ok("10123") else Result(1, "raw private error")
            if (blockRead && outputFile != null) {
                readStarted.complete(Unit)
                allowRead.await()
            }
            if (dropStageBeforeCommit && "committed=0" in script) {
                Files.walk(root.resolve(arguments[1])).use { paths ->
                    paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
                }
            }
            val text = if (failCommit) script.replace("committed=1", "exit 70") else script
            return withContext(Dispatchers.IO) {
                val builder = ProcessBuilder(listOf("sh", "-c", text, "tap-private-data") + arguments).directory(root.toFile())
                inputFile?.let { builder.redirectInput(remote(it).toFile()) }
                outputFile?.let { builder.redirectOutput(remote(it).toFile()) }
                builder.redirectError(ProcessBuilder.Redirect.DISCARD)
                val process = builder.start()
                val output = if (outputFile == null) process.inputStream.bufferedReader().readText() else ""
                Result(process.waitFor(), output.trim())
            }
        }
    }
}
