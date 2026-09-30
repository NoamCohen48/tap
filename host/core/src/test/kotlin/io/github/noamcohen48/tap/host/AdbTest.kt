package io.github.noamcohen48.tap.host

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AdbTest {
    private val serial = "emulator-5554"

    /** Runs every command through a fake child answering [stdout]/[exitCode]; records argv. */
    private fun scriptedAdb(
        stdout: String,
        exitCode: Int = 0,
        commands: MutableList<List<String>> = mutableListOf(),
    ): Adb =
        testAdb(
            ProcessStarter { command ->
                commands += command
                FakeProcess(stdout = stdout, exitCode = exitCode)
            },
        )

    @Test
    fun `device states keep offline unauthorized and other rows`() =
        runBlocking {
            val adb =
                scriptedAdb(
                    """
                    * daemon not running; starting now at tcp:5037
                    * daemon started successfully
                    List of devices attached
                    emulator-5554	device
                    85e49002	unauthorized
                    192.168.1.20:5555	offline
                    R58M12ABCDE	recovery
                    0123456789ABCDEF	no permissions (missing udev rules? user is in the plugdev group); see [http://developer.android.com/tools/device.html]

                    """.trimIndent(),
                )
            assertEquals(
                listOf(
                    AdbDevice("emulator-5554", AdbDeviceState.ONLINE, "device"),
                    AdbDevice("85e49002", AdbDeviceState.UNAUTHORIZED, "unauthorized"),
                    AdbDevice("192.168.1.20:5555", AdbDeviceState.OFFLINE, "offline"),
                    AdbDevice("R58M12ABCDE", AdbDeviceState.OTHER, "recovery"),
                    AdbDevice(
                        "0123456789ABCDEF",
                        AdbDeviceState.OTHER,
                        "no permissions (missing udev rules? user is in the plugdev group); see [http://developer.android.com/tools/device.html]",
                    ),
                ),
                adb.deviceStates(),
            )
            assertEquals(listOf("emulator-5554"), adb.devices())
        }

    @Test
    fun `a failing adb devices is a typed command failure`() =
        runBlocking {
            val failure = assertFailsWith<AdbCommandException> { scriptedAdb("error: protocol fault", exitCode = 1).deviceStates() }
            assertNull(failure.serial)
            assertEquals(1, failure.exitCode)
            assertEquals("error: protocol fault", failure.output)
        }

    @Test
    fun `a non-zero exit carries serial command exit code and output`() =
        runBlocking {
            val adb = scriptedAdb("Failure [DELETE_FAILED_INTERNAL_ERROR]", exitCode = 1)
            val failure = assertFailsWith<AdbCommandException> { adb.uninstall(serial, "com.example") }
            assertEquals(serial, failure.serial)
            assertEquals(listOf("uninstall", "com.example"), failure.command)
            assertEquals(1, failure.exitCode)
            assertEquals("Failure [DELETE_FAILED_INTERNAL_ERROR]", failure.output)
        }

    @Test
    fun `an exiting activity still counts until its record is gone`() =
        runBlocking {
            // API 34 after `pm clear`: the process is dead, the task and its record linger.
            val exiting =
                """
                ACTIVITY MANAGER ACTIVITIES (dumpsys activity activities)
                Display #0 (activities from top to bottom):
                  * Task{ad960a2 #794 type=standard A=10198:com.example U=0 visible=false sz=1}
                    * Hist  #0: ActivityRecord{1017c6d u0 com.example/.MainActivity t794 f} isExiting}
                  * Task{1e0bb8 #1 type=home ?? U=0 visible=true sz=1}
                    * Hist  #0: ActivityRecord{77a1d1 u0 com.example.launcher/.Home t1}
                """.trimIndent()
            assertTrue(scriptedAdb(exiting).hasActivities(serial, "com.example"))
            // Gone: only other packages' records and non-record mentions remain.
            val gone =
                """
                  * Task{1e0bb8 #1 type=home ?? U=0 visible=true sz=1}
                    * Hist  #0: ActivityRecord{77a1d1 u0 com.example.launcher/.Home t1}
                  mLastFocusedRootTask=Task{ad960a2 #794 type=standard A=10198:com.example}
                    source=ActivityRecord{416ef61 u0 com.example/.MainActivity t793} SCREEN_ORIENTATION_UNSPECIFIED
                """.trimIndent()
            assertFalse(scriptedAdb(gone).hasActivities(serial, "com.example"))
        }

    @Test
    fun `shell arguments from callers are quoted`() =
        runBlocking {
            val commands = mutableListOf<List<String>>()
            val adb = scriptedAdb("Status: ok", commands = commands)
            adb.startActivity(serial, "com.example/.Outer\$Inner", 5_000)
            adb.forceStop(serial, "com.example;reboot")
            adb.grantPermission(serial, "com.example", "android.permission.CAMERA && id")
            adb.clearData(serial, "com.example")
            assertEquals(
                listOf(
                    listOf("shell", "am", "start", "-W", "-n", "'com.example/.Outer\$Inner'"),
                    listOf("shell", "am", "force-stop", "'com.example;reboot'"),
                    listOf("shell", "pm", "grant", "com.example", "'android.permission.CAMERA && id'"),
                    listOf("shell", "pm", "clear", "com.example"),
                ),
                commands.map { it.drop(3) },
            )
        }

    @Test
    fun `a driver signed by another build or newer is uninstalled and reinstalled, anything else fails`() =
        runBlocking {
            val apk = Files.createTempFile("tap-driver", ".apk")
            fun adbWith(
                commands: MutableList<List<String>>,
                firstInstall: String,
            ): Adb {
                var installs = 0
                return testAdb(
                    ProcessStarter { command ->
                        commands += command
                        if ("install" in command && installs++ == 0) {
                            FakeProcess(stdout = firstInstall, exitCode = 1)
                        } else {
                            FakeProcess(stdout = "Success", exitCode = 0)
                        }
                    },
                )
            }

            val commands = mutableListOf<List<String>>()
            installDriverPackage(
                adbWith(commands, "Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: Package $DRIVER_PACKAGE signatures do not match]"),
                serial,
                DRIVER_PACKAGE,
                apk,
            )
            assertEquals(
                listOf("install", "uninstall", "install"),
                commands.map { it[3] },
            )
            assertEquals(listOf("uninstall", DRIVER_PACKAGE), commands[1].drop(3))

            // An older engine meeting a newer driver on the device (rollback, or a dev build).
            val downgrade = mutableListOf<List<String>>()
            installDriverPackage(adbWith(downgrade, "Failure [INSTALL_FAILED_VERSION_DOWNGRADE]"), serial, DRIVER_PACKAGE, apk)
            assertEquals(listOf("install", "uninstall", "install"), downgrade.map { it[3] })

            val other = mutableListOf<List<String>>()
            assertFailsWith<AdbCommandException> {
                installDriverPackage(adbWith(other, "Failure [INSTALL_FAILED_INSUFFICIENT_STORAGE]"), serial, DRIVER_PACKAGE, apk)
            }
            assertEquals(listOf("install"), other.map { it[3] })
        }

    @Test
    fun `a permission reads as granted only from its own granted line`() =
        runBlocking {
            val dumpsys =
                """
                    install permissions:
                      android.permission.INTERNET: granted=true
                    User 0: ceDataInode=1 installed=true
                      runtime permissions:
                        android.permission.CAMERA: granted=false, flags=[ USER_SENSITIVE_WHEN_GRANTED ]
                        android.permission.CAMERA_EXTRA: granted=true
                """.trimIndent()
            val adb = scriptedAdb(dumpsys)
            assertTrue(adb.isPermissionGranted(serial, "com.example", "android.permission.INTERNET"))
            assertFalse(adb.isPermissionGranted(serial, "com.example", "android.permission.CAMERA"))
            assertFalse(adb.isPermissionGranted(serial, "com.example", "android.permission.RECORD_AUDIO"))
        }

    @Test
    fun `installed package reads versionName and versionCode on API 29 and 34`() =
        runBlocking {
            for (output in listOf(DUMPSYS_API_29, DUMPSYS_API_34)) {
                val commands = mutableListOf<List<String>>()
                val adb = scriptedAdb(output, commands = commands)
                assertEquals(InstalledPackage(DRIVER_PACKAGE, "0.1.0", 100), adb.installedPackage(serial, DRIVER_PACKAGE))
                assertEquals(listOf("shell", "dumpsys", "package", DRIVER_PACKAGE), commands.single().drop(3))
            }
        }

    @Test
    fun `installed package tolerates a missing versionName and ignores other sections`() {
        assertEquals(
            InstalledPackage(DRIVER_TEST_PACKAGE, null, 0),
            parseDumpsysPackage(DUMPSYS_TEST_APK, DRIVER_TEST_PACKAGE),
        )
        // The Key Set Manager / Dexopt sections name the package in brackets too; only a
        // `Package [name] (...)` header counts, and a longer package with the same prefix never matches.
        assertNull(parseDumpsysPackage(DUMPSYS_TEST_APK, DRIVER_PACKAGE))
        // An updated system app lists the live package first and the hidden system one later.
        assertEquals(
            InstalledPackage("com.android.chrome", "120.0.6099.230", 609923033),
            parseDumpsysPackage(DUMPSYS_UPDATED_SYSTEM_APP, "com.android.chrome"),
        )
    }

    @Test
    fun `installed package is null when absent and typed when unreadable`() =
        runBlocking {
            assertNull(scriptedAdb(DUMPSYS_NOT_INSTALLED).installedPackage(serial, DRIVER_PACKAGE))
            assertNull(scriptedAdb("Unable to find package: $DRIVER_PACKAGE").installedPackage(serial, DRIVER_PACKAGE))
            val malformed =
                assertFailsWith<AdbCommandException> {
                    scriptedAdb("Packages:\n  Package [$DRIVER_PACKAGE] (1a2b3c):\n    userId=10187\n")
                        .installedPackage(serial, DRIVER_PACKAGE)
                }
            assertTrue("without a versionCode" in malformed.message.orEmpty(), malformed.message)
            assertFailsWith<AdbCommandException> {
                scriptedAdb("error: device offline", exitCode = 1).installedPackage(serial, DRIVER_PACKAGE)
            }
        }

    @Test
    fun `shell quoting leaves safe tokens alone and single-quotes everything else`() {
        assertEquals("com.example/.Main", shellQuote("com.example/.Main"))
        assertEquals("abc-DEF_123", shellQuote("abc-DEF_123"))
        assertEquals("'a; reboot'", shellQuote("a; reboot"))
        assertEquals("'\$(id)'", shellQuote("\$(id)"))
        assertEquals("'it'\\''s'", shellQuote("it's"))
        assertEquals("''", shellQuote(""))
    }

    @Test
    fun `instrumentation arguments reach the device shell quoted`() =
        runBlocking {
            val adb = FakeAdb()
            val captured = CompletableDeferred<List<String>>()
            val starter =
                ProcessStarter { command ->
                    captured.complete(command)
                    throw IllegalStateException("stop after capture")
                }
            assertFailsWith<IllegalStateException> {
                startDriverWithRetry(
                    adb,
                    serial,
                    "session-1",
                    1,
                    "c2VjcmV0",
                    "com.example",
                    driverArguments = mapOf("tapFault" to "x; reboot"),
                    processStarter = starter,
                    onStarting = {},
                )
            }
            val command = captured.await()
            assertEquals(listOf("fake-adb", "-s", serial, "shell", "am", "instrument"), command.take(6))
            assertTrue("'x; reboot'" in command, "unsafe value must be one quoted token: $command")
            assertEquals("c2VjcmV0", command[command.indexOf("tapSecret") + 1])
        }

    @Test
    fun `process stat reads the start token after the parenthesised command name`() =
        runTest {
            val adb =
                FakeAdb(
                    mapOf(
                        "shell cat /proc/1234/stat" to ok("1234 (my app (x)) S 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 424242 20 21"),
                    ),
                )
            assertEquals(Adb.ProcessStat.Live("424242"), adb.processStat(serial, 1234))
        }

    @Test
    fun `process stat distinguishes a dead process from an unreadable one`() =
        runTest {
            val gone = FakeAdb(mapOf("shell cat /proc/7/stat" to Adb.Result(1, "cat: /proc/7/stat: No such file or directory")))
            assertEquals(Adb.ProcessStat.Gone, gone.processStat(serial, 7))

            val denied = FakeAdb(mapOf("shell cat /proc/7/stat" to Adb.Result(1, "cat: /proc/7/stat: Permission denied")))
            assertFailsWith<AdbCommandException> { denied.processStat(serial, 7) }

            val malformed = FakeAdb(mapOf("shell cat /proc/7/stat" to ok("7 (short) S 1 2")))
            assertFailsWith<AdbCommandException> { malformed.processStat(serial, 7) }
        }

    @Test
    fun `listening port is matched in hex against LISTEN sockets only`() =
        runTest {
            val table =
                """
                sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid
                 0: 00000000:6A2F 00000000:0000 0A 00000000:00000000 00:00000000 00000000  2000
                 1: 0100007F:6A30 0100007F:E4A2 01 00000000:00000000 00:00000000 00000000  2000
                """.trimIndent()
            val adb = FakeAdb(mapOf("shell cat /proc/net/tcp /proc/net/tcp6" to ok(table)))
            assertTrue(adb.isPortListening(serial, 27183)) // 0x6A2F, state 0A
            assertFalse(adb.isPortListening(serial, 27184)) // 0x6A30 is ESTABLISHED, not LISTEN
        }

    @Test
    fun `listening port tolerates a kernel without tcp6 but not a failed read`() =
        runTest {
            val ipv4Only =
                "sl  local_address rem_address   st\n 0: 00000000:6A2F 00000000:0000 0A\n" +
                    "cat: /proc/net/tcp6: No such file or directory"
            val noIpv6 = FakeAdb(mapOf("shell cat /proc/net/tcp /proc/net/tcp6" to Adb.Result(1, ipv4Only)))
            assertTrue(noIpv6.isPortListening(serial, 27183))

            val offline = FakeAdb(mapOf("shell cat /proc/net/tcp /proc/net/tcp6" to Adb.Result(1, "error: device offline")))
            assertFailsWith<AdbCommandException> { offline.isPortListening(serial, 27183) }
        }

    @Test
    fun `launcher activity is the last resolved component of the package or null`() =
        runTest {
            val cmd = "shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER com.example"
            val adb = FakeAdb(mapOf(cmd to ok("priority=0 preferredOrder=0 match=0x108000\ncom.example/.MainActivity")))
            assertEquals("com.example/.MainActivity", adb.launcherActivity(serial, "com.example"))
            val none = FakeAdb(mapOf(cmd to ok("No activity found")))
            assertNull(none.launcherActivity(serial, "com.example"))
        }

    @Test
    fun `process ids treat pidof exit 1 with no output as no process`() =
        runTest {
            val adb = FakeAdb(mapOf("shell pidof com.example" to Adb.Result(1, "")))
            assertEquals(emptyList(), adb.processIds(serial, "com.example"))
            val two = FakeAdb(mapOf("shell pidof com.example" to ok("100 200")))
            assertEquals(listOf(100, 200), two.processIds(serial, "com.example"))
        }

    @Test
    fun `every device command is serial specific`() =
        runTest {
            val adb = FakeAdb(mapOf("shell pm path com.example" to ok("package:/data/app/x.apk")))
            assertTrue(adb.isInstalled(serial, "com.example"))
            assertEquals(listOf("$serial: shell pm path com.example"), adb.calls)
        }

    @Test
    @OptIn(RawAdb::class)
    fun `local timeout keeps the documented message and reaps the child`() =
        runBlocking {
            val starter = SwitchableProcessStarter { error("no process expected yet") }
            val adbHooks = TestAdbHooks()
            val adb = testAdb(starter, adbHooks)
            val child = FakeProcess(stdout = "partial", exitDelayMs = FakeProcess.NEVER)
            starter.delegate = ProcessStarter { child }
            val failure =
                assertFailsWith<AdbTimeoutException> {
                    adb.runResult(serial, "shell", "sleep", "30", timeoutMs = 100)
                }
            assertTrue("timed out" in failure.message.orEmpty(), failure.message.orEmpty())
            assertTrue(
                child.destroyed.get() || child.destroyedForcibly.get(),
                "timed-out child must be destroyed",
            )
            withTimeout(2_000) { while (child.isAlive) delay(10) }
            assertTrue(child.stdinClosed.get(), "timed-out child stdin must close")
            assertTrue(child.stdoutClosed.get(), "timed-out child stdout must close")
            assertTrue(child.stderrClosed.get(), "timed-out child stderr must close")
        }

    @Test
    fun `enclosing timeout stays cancellation and still reaps the child`() =
        runBlocking {
            val starter = SwitchableProcessStarter { error("no process expected yet") }
            val adbHooks = TestAdbHooks()
            val adb = testAdb(starter, adbHooks)
            val child = FakeProcess(stdout = "", exitDelayMs = FakeProcess.NEVER)
            starter.delegate = ProcessStarter { child }
            assertFailsWith<CancellationException> {
                withTimeout(200) { adb.devices(timeoutMs = 30_000) }
            }
            assertTrue(
                child.destroyed.get() || child.destroyedForcibly.get(),
                "cancelled child must be destroyed",
            )
            withTimeout(2_000) { while (child.isAlive) delay(10) }
            assertTrue(child.stdinClosed.get(), "cancelled child stdin must close")
            assertTrue(child.stdoutClosed.get(), "cancelled child stdout must close")
            assertTrue(child.stderrClosed.get(), "cancelled child stderr must close")
        }

    @Test
    fun `cancellation during process start cannot discard the created child`() =
        runBlocking {
            val starter = SwitchableProcessStarter { error("no process expected yet") }
            val adbHooks = TestAdbHooks()
            val adb = testAdb(starter, adbHooks)
            val child = FakeProcess(exitDelayMs = FakeProcess.NEVER)
            val published = CountDownLatch(1)
            val releaseReturn = CountDownLatch(1)
            starter.delegate =
                ProcessStarter {
                    published.countDown()
                    check(releaseReturn.await(5, TimeUnit.SECONDS))
                    child
                }
            val running = async(Dispatchers.IO) { adb.devices(timeoutMs = 30_000) }
            assertTrue(published.await(2, TimeUnit.SECONDS), "starter did not publish child")
            val original = CancellationException("original cancellation")
            running.cancel(original)
            assertFalse(running.isCompleted, "ownership installation must finish before cancellation")
            releaseReturn.countDown()
            val thrown = assertFailsWith<CancellationException> { running.await() }
            assertEquals(original.message, thrown.message)
            assertTrue(child.destroyed.get() || child.destroyedForcibly.get())
            assertFalse(child.isAlive)
            assertTrue(child.stdinClosed.get())
            assertTrue(child.stdoutClosed.get())
            assertTrue(child.stderrClosed.get())
        }

    @Test
    @OptIn(RawAdb::class)
    fun `successful process returns trimmed output through the real path`() =
        runBlocking {
            val starter = SwitchableProcessStarter { error("no process expected yet") }
            val adbHooks = TestAdbHooks()
            val adb = testAdb(starter, adbHooks)
            starter.delegate =
                ProcessStarter { FakeProcess(stdout = "  hello\n", exitCode = 0) }
            assertEquals("hello", adb.run(serial, "shell", "echo", "hello"))
        }

    @Test
    fun `cancelled call with a blocking drain returns within the aggregate reap bound as uncertain`() =
        runBlocking {
            val starter = SwitchableProcessStarter { error("no process expected yet") }
            val adbHooks = TestAdbHooks()
            val adb = testAdb(starter, adbHooks)
            // Aggregate-bound coverage: a drain that ignores close plus a child that survives
            // destroy plus a timed wait that actually consumes its supplied timeout exercises the
            // drain join AND the process wait/destroy under the single ADB_REAP_TIMEOUT_MS
            // deadline. Separate per-step budgets would consume the timeout twice (drain budget
            // plus process budget); the upper bound below fails that old behavior while the lower
            // bound proves the reap actually shares one deadline instead of returning instantly.
            val child =
                FakeProcess(
                    stdout = "",
                    exitDelayMs = FakeProcess.NEVER,
                    survivesDestroy = true,
                    blockingStdout = true,
                    timedWaitAlwaysFalse = true,
                    timedWaitConsumesTimeout = true,
                )
            starter.delegate = ProcessStarter { child }
            try {
                val started = System.nanoTime()
                val failure =
                    assertFailsWith<AdbReapUncertainException> {
                        withTimeout(200) { adb.devices(timeoutMs = 30_000) }
                    }
                val elapsedMs = (System.nanoTime() - started) / 1_000_000L
                // Aggregate bound: one ADB_REAP_TIMEOUT_MS deadline shared by drain join plus
                // process wait/destroy, plus the 200ms outer timeout. A two-budget implementation
                // would take ~200 + 2*ADB_REAP_TIMEOUT_MS; CI scheduling slack stays in the margin.
                assertTrue(
                    elapsedMs >= 200 + ADB_REAP_TIMEOUT_MS - 1_000,
                    "cancelled call took only ${elapsedMs}ms; the shared reap deadline must be consumed",
                )
                assertTrue(
                    elapsedMs < 200 + ADB_REAP_TIMEOUT_MS + 1_500,
                    "cancelled call took ${elapsedMs}ms; the aggregate reap deadline is " +
                        "${ADB_REAP_TIMEOUT_MS}ms plus the 200ms outer timeout",
                )
                assertTrue(
                    "survived bounded reap" in failure.message.orEmpty(),
                    failure.message.orEmpty(),
                )
                // The original cancellation rides in the message: cancellation machinery may drop
                // the suppressed chain on delivery, but the report must still name it.
                assertTrue(
                    "Timed out waiting for 200 ms" in failure.message.orEmpty(),
                    failure.message.orEmpty(),
                )
                assertTrue(child.destroyed.get() || child.destroyedForcibly.get())
                assertTrue(child.stdinClosed.get())
                assertTrue(child.stdoutClosed.get())
                assertTrue(child.stderrClosed.get())
            } finally {
                child.releaseStdout()
                child.forceExit()
            }
            // The owned drain executor is shut down: the released child still answers a later call.
            // Poll for recovery: the released drain completes asynchronously, so the first admit
            // after release may still prune-and-reject before the completion lands.
            starter.delegate = ProcessStarter { FakeProcess(stdout = "List of devices attached\n", exitCode = 0) }
            val recovered =
                withTimeout(10_000) {
                    while (true) {
                        val attempt = runCatching { adb.devices(timeoutMs = 5_000) }
                        if (attempt.isSuccess) break
                        check(attempt.exceptionOrNull() is AdbRunnerGatedException)
                        delay(10)
                    }
                }
            assertEquals(emptyList(), adb.devices(timeoutMs = 30_000))
        }

    @Test
    fun `unreaped drain gates further starts until it completes then recovers`() =
        runBlocking {
            val starter = SwitchableProcessStarter { error("no process expected yet") }
            val adbHooks = TestAdbHooks()
            val adb = testAdb(starter, adbHooks)
            val starts =
                java.util.concurrent.atomic
                    .AtomicInteger(0)
            val blocked = FakeProcess(stdout = "", exitDelayMs = FakeProcess.NEVER, blockingStdout = true)
            starter.delegate =
                ProcessStarter {
                    if (starts.incrementAndGet() == 1) {
                        blocked
                    } else {
                        FakeProcess(stdout = "List of devices attached\n", exitCode = 0)
                    }
                }

            fun drainThreads(): Int = Thread.getAllStackTraces().keys.count { it.name == "adb-output-drain" && it.isAlive }
            // First call leaves its drain blocked: typed reap uncertainty, gate installed.
            assertFailsWith<AdbReapUncertainException> {
                withTimeout(200) { adb.devices(timeoutMs = 30_000) }
            }
            assertEquals(1, starts.get())
            assertTrue(adb.isReapGated(), "unreaped drain must gate the runner")
            // Repeated calls are rejected before start: no additional processes or drains.
            // Temporary gate, never uncertainty: the gated command never started, so it must not
            // poison — it carries the attempted serial and the blocking residual for diagnostics.
            repeat(3) {
                val gated =
                    assertFailsWith<AdbRunnerGatedException> {
                        adb.devices(timeoutMs = 5_000)
                    }
                assertTrue("gated" in gated.message.orEmpty(), gated.message.orEmpty())
            }
            assertEquals(1, starts.get(), "gated calls must not start new processes")
            // Release the residual: the gate clears, the old executor terminates, reuse succeeds.
            // The released drain completes asynchronously, so the first calls after release may
            // still be gated: poll until one is admitted.
            blocked.releaseStdout()
            blocked.forceExit()
            val reused =
                withTimeout(10_000) {
                    var attempt = runCatching { adb.devices(timeoutMs = 5_000) }
                    while (attempt.isFailure) {
                        check(attempt.exceptionOrNull() is AdbRunnerGatedException)
                        delay(10)
                        attempt = runCatching { adb.devices(timeoutMs = 5_000) }
                    }
                    attempt.getOrThrow()
                }
            assertEquals(emptyList(), reused)
            assertEquals(2, starts.get())
            assertFalse(adb.isReapGated(), "gate must clear once the residual completes")
            withTimeout(5_000) {
                while (drainThreads() > 0) delay(10)
            }
        }

    @Test
    fun `concurrent starters never exceed the fixed residual cap and permits recover without ABA`() =
        runBlocking {
            val starter = SwitchableProcessStarter { error("no process expected yet") }
            val adbHooks = TestAdbHooks()
            val adb = testAdb(starter, adbHooks)
            val starts =
                java.util.concurrent.atomic
                    .AtomicInteger(0)
            val enteredStart = java.util.concurrent.CountDownLatch(1)
            val releaseStart = java.util.concurrent.CountDownLatch(1)
            val residuals = java.util.concurrent.CopyOnWriteArrayList<FakeProcess>()
            starter.delegate =
                ProcessStarter {
                    val n = starts.incrementAndGet()
                    if (n == 1) {
                        enteredStart.countDown()
                        check(releaseStart.await(5, TimeUnit.SECONDS))
                    }
                    // Every admitted starter leaves an unreapable residual: a blocked drain plus a
                    // child that survives destroy, so admission — not cleanup — bounds the count.
                    FakeProcess(
                        stdout = "",
                        exitDelayMs = FakeProcess.NEVER,
                        survivesDestroy = true,
                        blockingStdout = true,
                        timedWaitAlwaysFalse = true,
                    ).also(residuals::add)
                }
            try {
                val callerCount = 4
                val outcomes = java.util.concurrent.CopyOnWriteArrayList<Result<List<String>>>()
                val jobs =
                    (1..callerCount).map {
                        async(Dispatchers.IO) {
                            // Short local deadline: the admitted holder times out fast and leaves its
                            // residual; waiters observe the residual and reject instead of timing out.
                            outcomes.add(runCatching { withTimeout(15_000) { adb.devices(timeoutMs = 300) } })
                        }
                    }
                assertTrue(enteredStart.await(5, TimeUnit.SECONDS), "first starter never ran")
                releaseStart.countDown()
                withTimeout(20_000) { jobs.forEach { it.join() } }
                assertEquals(callerCount, outcomes.size)
                // Fixed cap: one lane (serial-less `devices`), so process starts and residual
                // workers never exceed ADB_PERMITS_PER_SERIAL.
                assertTrue(starts.get() <= ADB_PERMITS_PER_SERIAL, "started ${starts.get()} with cap $ADB_PERMITS_PER_SERIAL")
                assertTrue(adb.admittedTokenCount() <= ADB_PERMITS_PER_SERIAL)
                val uncertain = outcomes.count { it.exceptionOrNull() is AdbReapUncertainException }
                val gated = outcomes.count { it.exceptionOrNull() is AdbRunnerGatedException }
                assertEquals(callerCount, uncertain + gated, "every caller is uncertain-started or temporarily gated: $outcomes")
                assertTrue(uncertain >= 1, "at least one starter must hold the residual")
                // Gated diagnostics: attempted serial preserved (devices has none) and the blocker named.
                outcomes.mapNotNull { it.exceptionOrNull() as? AdbRunnerGatedException }.forEach { gate ->
                    assertTrue(gate.blockingCommand.isNotEmpty())
                }
                // Release every residual: each token frees only its own permit (no ABA) and the
                // runner recovers for a proven call.
                residuals.forEach {
                    it.releaseStdout()
                    it.forceExit()
                }
                starter.delegate = ProcessStarter { FakeProcess(stdout = "List of devices attached\n", exitCode = 0) }
                withTimeout(10_000) {
                    while (true) {
                        val attempt = runCatching { adb.devices(timeoutMs = 5_000) }
                        if (attempt.isSuccess) break
                        check(attempt.exceptionOrNull() is AdbRunnerGatedException)
                        delay(10)
                    }
                }
                assertFalse(adb.isReapGated())
            } finally {
                releaseStart.countDown()
                residuals.forEach {
                    runCatching { it.releaseStdout() }
                    runCatching { it.forceExit() }
                }
            }
        }

    @Test
    fun `queued admission is cancellable before process start`() =
        runBlocking {
            val starter = SwitchableProcessStarter { error("no process expected yet") }
            val adbHooks = TestAdbHooks()
            val adb = testAdb(starter, adbHooks)
            val starts =
                java.util.concurrent.atomic
                    .AtomicInteger(0)
            val enteredStart = java.util.concurrent.CountDownLatch(1)
            val releaseStart = java.util.concurrent.CountDownLatch(1)
            starter.delegate =
                ProcessStarter {
                    starts.incrementAndGet()
                    enteredStart.countDown()
                    check(releaseStart.await(5, TimeUnit.SECONDS))
                    FakeProcess(stdout = "List of devices attached\n", exitCode = 0)
                }
            val first = async(Dispatchers.IO) { adb.devices(timeoutMs = 30_000) }
            assertTrue(enteredStart.await(5, TimeUnit.SECONDS))
            // Explicit barrier: the probe proves the second coroutine entered the admission wait
            // before it is cancelled, so the test never races the wait loop.
            val enteredAdmissionWait = CompletableDeferred<Unit>()
            adbHooks.onAdmissionWaitCall = { enteredAdmissionWait.complete(Unit) }
            // A second caller queued on admission/Mutex never starts when cancelled first.
            val second = async(Dispatchers.IO) { adb.devices(timeoutMs = 30_000) }
            withTimeout(2_000) { enteredAdmissionWait.await() }
            second.cancel(CancellationException("queued admission cancelled"))
            assertFailsWith<CancellationException> { second.await() }
            releaseStart.countDown()
            assertEquals(emptyList(), withTimeout(5_000) { first.await() })
            assertEquals(1, starts.get(), "cancelled queued caller must never start a process")
        }

    @Test
    @OptIn(RawAdb::class)
    fun `a residual gates only its own serial and another serial keeps running`() =
        runBlocking {
            val serialA = "serial-A"
            val serialB = "serial-B"
            val starter = SwitchableProcessStarter { error("no process expected yet") }
            val adbHooks = TestAdbHooks()
            val adb = testAdb(starter, adbHooks)
            val starts =
                java.util.concurrent.atomic
                    .AtomicInteger(0)
            val residual = FakeProcess(stdout = "", exitDelayMs = FakeProcess.NEVER, blockingStdout = true)
            starter.delegate =
                ProcessStarter { command ->
                    starts.incrementAndGet()
                    if (serialA in command) residual else FakeProcess(stdout = "4242\n", exitCode = 0)
                }
            try {
                // Poison A: this started command leaves an unresolved residual in A's lane.
                assertFailsWith<AdbReapUncertainException> {
                    withTimeout(5_000) { adb.run(serialA, "shell", "pidof", "com.example", timeoutMs = 30_000) }
                }
                assertEquals(1, starts.get())
                // A is gated without a process start: typed temporary rejection, never uncertainty.
                val gated =
                    assertFailsWith<AdbRunnerGatedException> {
                        adb.run(serialA, "shell", "pidof", "com.example", timeoutMs = 5_000)
                    }
                assertEquals(serialA, gated.attemptSerial)
                assertEquals(serialA, gated.blockingSerial)
                assertTrue(gated.blockingCommand.joinToString(" ").contains("pidof"))
                assertEquals(1, starts.get(), "gated serial must not start a process")
                // B's lane is independent: it runs while A's residual is unresolved.
                assertEquals("4242", adb.run(serialB, "shell", "pidof", "com.example", timeoutMs = 5_000))
                assertEquals(2, starts.get())
                // Release A: the residual's own token frees its own permit (no ABA) and A recovers.
                residual.releaseStdout()
                residual.forceExit()
                withTimeout(10_000) {
                    while (true) {
                        val attempt = runCatching { adb.run(serialA, "shell", "pidof", "com.example", timeoutMs = 5_000) }
                        if (attempt.isSuccess) break
                        check(attempt.exceptionOrNull() is AdbRunnerGatedException)
                        delay(10)
                    }
                }
                assertFalse(adb.isReapGated())
            } finally {
                runCatching { residual.releaseStdout() }
                runCatching { residual.forceExit() }
            }
        }

    @Test
    @OptIn(RawAdb::class)
    fun `different serials run concurrently while one serial stays single-flight`() =
        runBlocking {
            val starter = SwitchableProcessStarter { error("no process expected yet") }
            val adbHooks = TestAdbHooks()
            val adb = testAdb(starter, adbHooks)
            val running = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()
            val peakPerSerial = java.util.concurrent.ConcurrentHashMap<String, Int>()
            val bothStarted = CountDownLatch(2)
            val release = CountDownLatch(1)
            starter.delegate =
                ProcessStarter { command ->
                    val serial = command[2]
                    val now = running.computeIfAbsent(serial) { java.util.concurrent.atomic.AtomicInteger() }.incrementAndGet()
                    peakPerSerial.merge(serial, now, ::maxOf)
                    bothStarted.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    running.getValue(serial).decrementAndGet()
                    FakeProcess(stdout = "ok\n", exitCode = 0)
                }
            val calls =
                listOf("serial-A", "serial-B", "serial-A").map { serial ->
                    async(Dispatchers.IO) { adb.run(serial, "shell", "true", timeoutMs = 10_000) }
                }
            // A and B are both inside process start at once: no global serialization.
            assertTrue(bothStarted.await(5, TimeUnit.SECONDS), "two serials must run concurrently")
            release.countDown()
            calls.forEach { assertEquals("ok", withTimeout(10_000) { it.await() }) }
            assertEquals(1, peakPerSerial["serial-A"], "one serial never runs two commands at once")
            assertEquals(0, adb.admittedTokenCount())
        }

    @Test
    @OptIn(RawAdb::class)
    fun `a global cap held only by residuals rejects further serials`() =
        runBlocking {
            val starter = SwitchableProcessStarter { error("no process expected yet") }
            val adbHooks = TestAdbHooks()
            val adb = testAdb(starter, adbHooks)
            val residuals = java.util.concurrent.CopyOnWriteArrayList<FakeProcess>()
            starter.delegate =
                ProcessStarter {
                    FakeProcess(stdout = "", exitDelayMs = FakeProcess.NEVER, blockingStdout = true).also(residuals::add)
                }
            try {
                repeat(ADB_GLOBAL_PERMITS) { index ->
                    assertFailsWith<AdbReapUncertainException> {
                        withTimeout(5_000) { adb.run("serial-$index", "shell", "true", timeoutMs = 30_000) }
                    }
                }
                assertEquals(ADB_GLOBAL_PERMITS, adb.admittedTokenCount())
                val gated =
                    assertFailsWith<AdbRunnerGatedException> {
                        adb.run("serial-fresh", "shell", "true", timeoutMs = 5_000)
                    }
                assertEquals("serial-fresh", gated.attemptSerial)
                assertEquals(ADB_GLOBAL_PERMITS, residuals.size, "a gated call must not start a process")
            } finally {
                residuals.forEach {
                    runCatching { it.releaseStdout() }
                    runCatching { it.forceExit() }
                }
            }
        }

    @Test
    fun `cancelled proven reap still releases its exact token`() =
        runBlocking {
            val starter = SwitchableProcessStarter { error("no process expected yet") }
            val adbHooks = TestAdbHooks()
            val adb = testAdb(starter, adbHooks)
            // Suspend-friendly barriers: the test thread is the op's event loop, so nothing here
            // may block it (no latch await on this thread); the starter itself runs on Dispatchers.IO.
            val enteredStart = AtomicBoolean(false)
            val releaseStart = CountDownLatch(1)
            starter.delegate =
                ProcessStarter {
                    enteredStart.set(true)
                    check(releaseStart.await(5, TimeUnit.SECONDS))
                    FakeProcess(stdout = "List of devices attached\n", exitCode = 0)
                }
            // Deterministic barrier: the operation admits first and parks in the starter; it is
            // cancelled there, and its proven release then parks in the bookkeeping hook, so the
            // release must survive a suspension under an already-cancelled owner (a cancellable
            // bookkeeping step would leak the token and mask the cancellation). The outcome
            // travels through a separate deferred because awaiting a coroutine cancelled by the
            // test itself always rethrows the cancellation instead of the block's result.
            val holderRelease = CompletableDeferred<Unit>()
            val bookkeepingAttempted = CompletableDeferred<Unit>()
            adbHooks.onBeforeAdmissionBookkeeping = {
                bookkeepingAttempted.complete(Unit)
                holderRelease.await()
            }
            val outcome = CompletableDeferred<Result<List<String>>>()
            val op = async { outcome.complete(runCatching { adb.devices(timeoutMs = 30_000) }) }
            withTimeout(5_000) {
                while (!enteredStart.get()) delay(10)
            }
            val original = CancellationException("proven reap cancelled")
            op.cancel(original)
            releaseStart.countDown()
            withTimeout(5_000) { bookkeepingAttempted.await() }
            assertFalse(op.isCompleted, "proven release must pend on the barrier")
            holderRelease.complete(Unit)
            val result = withTimeout(5_000) { outcome.await() }
            val thrown = assertFailsWith<CancellationException> { result.getOrThrow() }
            assertEquals(original.message, thrown.message, "original cancellation must survive bookkeeping")
            assertEquals(0, adb.admittedTokenCount(), "proven token must return to zero")
            assertFalse(adb.isReapGated())
            // The runner is reusable: a later proven call succeeds with no gate.
            starter.delegate = ProcessStarter { FakeProcess(stdout = "List of devices attached\n", exitCode = 0) }
            assertEquals(emptyList(), adb.devices(timeoutMs = 5_000))
        }

    @Test
    fun `cancelled uncertain reap transfers its exact token to the residual gate`() =
        runBlocking {
            val starter = SwitchableProcessStarter { error("no process expected yet") }
            val adbHooks = TestAdbHooks()
            val adb = testAdb(starter, adbHooks)
            val residual =
                FakeProcess(
                    stdout = "",
                    exitDelayMs = FakeProcess.NEVER,
                    survivesDestroy = true,
                    blockingStdout = true,
                    timedWaitAlwaysFalse = true,
                )
            var starts = 0
            starter.delegate =
                ProcessStarter {
                    starts++
                    residual
                }
            val holderRelease = CompletableDeferred<Unit>()
            val bookkeepingAttempted = CompletableDeferred<Unit>()
            adbHooks.onBeforeAdmissionBookkeeping = {
                bookkeepingAttempted.complete(Unit)
                holderRelease.await()
            }
            val outcome = CompletableDeferred<Result<List<String>>>()
            val op = async { outcome.complete(runCatching { adb.devices(timeoutMs = 30_000) }) }
            // The operation admitted; once cancelled, the reap's residual transfer parks in the
            // bookkeeping hook, so it must land under cancellation.
            withTimeout(2_000) {
                while (adb.admittedTokenCount() != 1) delay(10)
            }
            op.cancel(CancellationException("uncertain reap cancelled"))
            withTimeout(10_000) { bookkeepingAttempted.await() }
            assertFalse(op.isCompleted, "uncertain transfer must pend on the barrier")
            holderRelease.complete(Unit)
            val result = withTimeout(10_000) { outcome.await() }
            val failure = assertFailsWith<AdbReapUncertainException> { result.getOrThrow() }
            assertTrue("survived bounded reap" in failure.message.orEmpty(), failure.message.orEmpty())
            // The exact token stays gated until its own process/drain resolves: a second start is
            // rejected and never starts a process.
            assertTrue(adb.isReapGated(), "uncertain token must gate the runner")
            assertEquals(1, adb.admittedTokenCount())
            assertFailsWith<AdbRunnerGatedException> { adb.devices(timeoutMs = 5_000) }
            assertEquals(1, starts, "no second process starts while the residual is unresolved")
            // Resolving the exact process/drain clears the gate and the runner recovers.
            residual.releaseStdout()
            residual.forceExit()
            starter.delegate = ProcessStarter { FakeProcess(stdout = "List of devices attached\n", exitCode = 0) }
            withTimeout(10_000) {
                while (true) {
                    val attempt = runCatching { adb.devices(timeoutMs = 5_000) }
                    if (attempt.isSuccess) break
                    check(attempt.exceptionOrNull() is AdbRunnerGatedException)
                    delay(10)
                }
            }
            assertFalse(adb.isReapGated())
        }
}

// `dumpsys package` layouts (trimmed to the sections the parser must survive), following the
// shape API 29 (SM-J810G) and API 34 (emulator) print for the driver packages.
private val DUMPSYS_API_29 =
    """
    Activity Resolver Table:
      Non-Data Actions:
          android.intent.action.MAIN:
            4f1c2d7 io.github.noamcohen48.tap.driver/.MainActivity filter 9e3a0b1
              Action: "android.intent.action.MAIN"
              Category: "android.intent.category.LAUNCHER"

    Key Set Manager:
      [io.github.noamcohen48.tap.driver]
          Signing KeySets: 61

    Packages:
      Package [io.github.noamcohen48.tap.driver] (3e5b1c2):
        userId=10245
        pkg=Package{9a8b7c6 io.github.noamcohen48.tap.driver}
        codePath=/data/app/io.github.noamcohen48.tap.driver-AbCdEf==
        resourcePath=/data/app/io.github.noamcohen48.tap.driver-AbCdEf==
        legacyNativeLibraryDir=/data/app/io.github.noamcohen48.tap.driver-AbCdEf==/lib
        primaryCpuAbi=null
        secondaryCpuAbi=null
        versionCode=100 minSdk=26 targetSdk=36
        versionName=0.1.0
        splits=[base]
        apkSigningVersion=2
        applicationInfo=ApplicationInfo{1d2e3f4 io.github.noamcohen48.tap.driver}
        flags=[ DEBUGGABLE HAS_CODE ALLOW_CLEAR_USER_DATA TEST_ONLY ALLOW_BACKUP ]
        timeStamp=2026-09-20 10:11:12
        firstInstallTime=2026-09-01 09:00:00
        lastUpdateTime=2026-09-20 10:11:13
        signatures=PackageSignatures{5a6b7c8 version:2, signatures:[1f2e3d4c], past signatures:[]}
        installPermissionsFixed=true
        pkgFlags=[ DEBUGGABLE HAS_CODE ALLOW_CLEAR_USER_DATA TEST_ONLY ALLOW_BACKUP ]
        User 0: ceDataInode=409731 installed=true hidden=false suspended=false stopped=false notLaunched=false enabled=0 instant=false virtual=false
          gids=[3003]
          runtime permissions:

    Dexopt state:
      [io.github.noamcohen48.tap.driver]
        path: /data/app/io.github.noamcohen48.tap.driver-AbCdEf==/base.apk
          arm64: [status=quicken] [reason=install]
    """.trimIndent()

private val DUMPSYS_API_34 =
    """
    Activity Resolver Table:
      Non-Data Actions:
          android.intent.action.MAIN:
            b41e9d2 io.github.noamcohen48.tap.driver/.MainActivity filter 07a3c55
              Action: "android.intent.action.MAIN"
              Category: "android.intent.category.LAUNCHER"

    Key Set Manager:
      [io.github.noamcohen48.tap.driver]
          Signing KeySets: 57

    Packages:
      Package [io.github.noamcohen48.tap.driver] (8f3c2a1):
        appId=10187
        pkg=Package{5d1e0b7 io.github.noamcohen48.tap.driver}
        codePath=/data/app/~~Xy12Ab==/io.github.noamcohen48.tap.driver-Cd34Ef==
        resourcePath=/data/app/~~Xy12Ab==/io.github.noamcohen48.tap.driver-Cd34Ef==
        legacyNativeLibraryDir=/data/app/~~Xy12Ab==/io.github.noamcohen48.tap.driver-Cd34Ef==/lib
        extractNativeLibs=false
        primaryCpuAbi=null
        secondaryCpuAbi=null
        cpuAbiOverride=null
        versionCode=100 minSdk=26 targetSdk=36
        minExtensionVersions=[]
        versionName=0.1.0
        usesNonSdkApi=false
        splits=[base]
        apkSigningVersion=2
        flags=[ DEBUGGABLE HAS_CODE ALLOW_CLEAR_USER_DATA TEST_ONLY ALLOW_BACKUP ]
        privateFlags=[ PRIVATE_FLAG_ACTIVITIES_RESIZE_MODE_RESIZEABLE_VIA_SDK_VERSION ALLOW_AUDIO_PLAYBACK_CAPTURE PRIVATE_FLAG_ALLOW_NATIVE_HEAP_POINTER_TAGGING ]
        forceQueryable=false
        dataDir=/data/user/0/io.github.noamcohen48.tap.driver
        timeStamp=2026-09-20 10:11:12.345
        lastUpdateTime=2026-09-20 10:11:13.012
        installerPackageName=null
        installerPackageUid=-1
        User 0: ceDataInode=16302 deDataInode=0 installed=true hidden=false suspended=false distractionFlags=0 stopped=false notLaunched=false enabled=0 instant=false virtual=false quarantined=false
          installReason=0
          dataDir=/data/user/0/io.github.noamcohen48.tap.driver
          gids=[3003]
          runtime permissions:

    Queries:
      system apps queryable: false

    Dexopt state:
      [io.github.noamcohen48.tap.driver]
        path: /data/app/~~Xy12Ab==/io.github.noamcohen48.tap.driver-Cd34Ef==/base.apk
          arm64: [status=verify] [reason=install] [primary-abi]
    """.trimIndent()

/** The instrumentation APK: AGP stamps no version on it. */
private val DUMPSYS_TEST_APK =
    """
    Key Set Manager:
      [io.github.noamcohen48.tap.driver.test]
          Signing KeySets: 58

    Packages:
      Package [io.github.noamcohen48.tap.driver.test] (2c7d9e0):
        appId=10188
        pkg=Package{6e2f1a8 io.github.noamcohen48.tap.driver.test}
        codePath=/data/app/~~Gh56Ij==/io.github.noamcohen48.tap.driver.test-Kl78Mn==
        versionCode=0 minSdk=26 targetSdk=36
        minExtensionVersions=[]
        versionName=null
        flags=[ DEBUGGABLE HAS_CODE ALLOW_CLEAR_USER_DATA TEST_ONLY ALLOW_BACKUP ]
        User 0: ceDataInode=16310 installed=true hidden=false suspended=false stopped=true notLaunched=true enabled=0 instant=false virtual=false

    Dexopt state:
      [io.github.noamcohen48.tap.driver.test]
        path: /data/app/~~Gh56Ij==/io.github.noamcohen48.tap.driver.test-Kl78Mn==/base.apk
    """.trimIndent()

private val DUMPSYS_UPDATED_SYSTEM_APP =
    """
    Packages:
      Package [com.android.chrome] (a1b2c3d):
        appId=10123
        versionCode=609923033 minSdk=29 targetSdk=34
        versionName=120.0.6099.230
        User 0: installed=true hidden=false

    Hidden system packages:
      Package [com.android.chrome] (e4f5a6b):
        appId=10123
        versionCode=559807533 minSdk=29 targetSdk=33
        versionName=110.0.5481.153
    """.trimIndent()

/** A package that is not installed: the resolver tables print, no `Packages:` section. */
private val DUMPSYS_NOT_INSTALLED =
    """
    Activity Resolver Table:

    Permissions:

    Key Set Manager:

    Dexopt state:
    """.trimIndent()
