package io.github.noamcohen48.tap.host.validation

import io.github.noamcohen48.tap.api.v1.DisplayRotation
import io.github.noamcohen48.tap.api.v1.ErrorCode
import io.github.noamcohen48.tap.host.Adb
import io.github.noamcohen48.tap.host.DefaultProcessStarter
import io.github.noamcohen48.tap.host.DeviceSession
import io.github.noamcohen48.tap.host.DeviceSessionConfig
import io.github.noamcohen48.tap.host.DriverClient
import io.github.noamcohen48.tap.host.DriverRuntimeCache
import io.github.noamcohen48.tap.host.DriverStart
import io.github.noamcohen48.tap.host.JournalState
import io.github.noamcohen48.tap.host.ProcessStarter
import io.github.noamcohen48.tap.host.SavedState
import io.github.noamcohen48.tap.host.SessionJournalStore
import io.github.noamcohen48.tap.host.StateKey
import io.github.noamcohen48.tap.host.ValidationApi
import io.github.noamcohen48.tap.protocol.Commands
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.Base64

/** Real sessions and the real serial lock; no fixture app or synchronization is required. */
@DeviceTest
@OptIn(ValidationApi::class)
class RuntimeReuseTest {
    private class Setup(
        val serial: String,
        idleMs: Long,
    ) {
        val adb = Adb()
        val store = SessionJournalStore(Devices.journalRoot, serial)
        val cache = DriverRuntimeCache(adb, Devices.journalRoot, idleMs)
        val secrets = mutableListOf<ByteArray>()
        private var installed = false
        val config =
            DeviceSessionConfig(
                serial = serial,
                adb = adb,
                journalRoot = Devices.journalRoot,
                driverApk = Devices.driverApk,
                driverTestApk = Devices.driverTestApk,
                runtimeCache = cache,
                leaseTimeoutMs = 60_000,
                installDriver = { (!installed).also { installed = true } },
                processStarter =
                    ProcessStarter { command ->
                        val position = command.indexOf("tapSecret")
                        secrets += Base64.getUrlDecoder().decode(command[position + 1])
                        DefaultProcessStarter.start(command)
                    },
            )

        suspend fun rotation() =
            store.acquireLease(60_000).use {
                StateKey.ROTATION.map { SavedState(it, adb.readState(serial, it)) }
            }

        suspend fun <T> session(
            config: DeviceSessionConfig = this.config,
            block: suspend (DeviceSession) -> T,
        ): T {
            val device = DeviceSession.open(config)
            try {
                return block(device)
            } finally {
                device.close()
            }
        }

        suspend fun restoreRotation(saved: List<SavedState>) {
            store.acquireLease(60_000).use { adb.restoreState(serial, saved) }
        }
    }

    @OnEachDevice
    fun `reuse fences generations and credentials and restores each session`(serial: String) =
        runBlocking(Dispatchers.IO) {
            val setup = Setup(serial, 60_000)
            val original = setup.rotation()
            val font = setup.adb.readState(serial, StateKey.FONT_SCALE)
            val providers = setup.adb.readState(serial, StateKey.MockLocationProviders.id)
            try {
                var firstId = ""
                var firstGeneration = 0L
                var instance = ""
                setup.session { first ->
                    check(!first.driverReused)
                    firstId = first.sessionId
                    firstGeneration = first.generation
                    instance = first.client.driverInstanceId
                    check(
                        first.client
                            .execute(Commands.deviceInfo())
                            .requestId == 2L,
                    )
                }
                val idle = checkNotNull(setup.store.read())
                check(idle.runtimeIdle && idle.state == JournalState.CLOSED && idle.savedState.isEmpty())
                setup.store.acquireLease(0).close() // idle instrumentation reserves no device
                val oldAuth =
                    runCatching {
                        DriverClient.connect(
                            idle.hostPort!!,
                            firstId,
                            firstGeneration,
                            setup.secrets.first(),
                            overallDeadlineNanos = System.nanoTime() + 2_000_000_000L,
                            heartbeatIntervalMs = 0,
                        )
                    }
                oldAuth.getOrNull()?.close()
                check(oldAuth.isFailure) { "Old session credentials authenticated an idle runtime" }
                setup.session { second ->
                    check(second.driverReused)
                    check(second.generation == firstGeneration + 1 && second.sessionId != firstId)
                    check(second.client.driverInstanceId == instance)
                    val info = second.client.execute(Commands.deviceInfo())
                    check(info.requestId == 2L && info.sessionGeneration == second.generation)
                    val stale = second.client.validationTransport().executeHealth(100, generation = firstGeneration)
                    check(stale.result.error.code == ErrorCode.ERR_SESSION_MISMATCH)
                    val wrongId = second.client.validationTransport().executeHealth(101, sessionId = firstId)
                    check(wrongId.result.error.code == ErrorCode.ERR_SESSION_MISMATCH)
                    second.conditions.setFontScale(1.2f)
                    second.conditions.setLocation(37.0, -122.0, null, null)
                    second.client.execute(Commands.setDisplayRotation(DisplayRotation.DISPLAY_ROTATION_LEFT))
                }
                check(setup.rotation() == original) { "Warm detach did not restore rotation" }
                check(setup.adb.readState(serial, StateKey.FONT_SCALE) == font) { "Warm detach did not restore font scale" }
                delay(1_300) // no old mock emitter may survive into the next generation
                check(setup.adb.readState(serial, StateKey.MockLocationProviders.id) == providers) { "Mock providers survived warm detach" }
                val threads = setup.adb.run(serial, "shell", "ps", "-T", "-p", checkNotNull(setup.store.read()?.driverPid).toString())
                check("tap-mock" !in threads && "mock-location" !in threads) { "Mock location emitter survived warm detach" }
                setup.session { third ->
                    check(third.driverReused && third.client.driverInstanceId == instance)
                    check(
                        third.client
                            .execute(Commands.deviceInfo())
                            .deviceInfo.screenOn,
                    )
                }
                setup.session(setup.config.copy(driverStart = DriverStart.COLD)) { cold ->
                    check(!cold.driverReused && cold.client.driverInstanceId != instance)
                }
                check(setup.store.read()?.runtimeIdle == false) { "COLD detach retained instrumentation" }
                report(
                    "runtime_reuse",
                    serial,
                    "fresh_credentials" to "ok",
                    "generation_fence" to "ok",
                    "rotation_restore" to "ok",
                    "mock_emitter" to "ok",
                    "cold" to "ok",
                )
            } finally {
                setup.cache.close()
                setup.restoreRotation(original)
            }
        }

    @OnEachDevice
    fun `an idle expiry cannot kill a foreign active session and poison is never reused`(serial: String) =
        runBlocking(Dispatchers.IO) {
            val setup = Setup(serial, 1_000)
            try {
                var oldInstance = ""
                setup.session { oldInstance = it.client.driverInstanceId }
                // This direct core user has no cache, exactly like another host process. It takes
                // the same journal lock and cold-recovers the daemon's idle process and forward.
                setup.session(setup.config.copy(runtimeCache = null)) { foreign ->
                    check(!foreign.driverReused && foreign.client.driverInstanceId != oldInstance)
                    delay(1_300)
                    check(
                        foreign.client
                            .execute(Commands.deviceInfo())
                            .deviceInfo.screenOn,
                    )
                    check(setup.store.read()?.state == JournalState.READY)
                }
                val poisoned = DeviceSession.open(setup.config)
                try {
                    check(!poisoned.driverReused)
                    poisoned.client.validationTransport().disconnect()
                } finally {
                    poisoned.close()
                }
                check(setup.store.read()?.state == JournalState.BROKEN)
                setup.session { check(!it.driverReused) }
                report("runtime_reuse", serial, "foreign_owner" to "ok", "poison" to "ok")
            } finally {
                setup.cache.close()
            }
        }

    @OnEachDevice
    fun `trickled unauthenticated bytes cannot extend the absolute idle deadline`(serial: String) =
        runBlocking(Dispatchers.IO) {
            val setup = Setup(serial, 5_000)
            try {
                setup.session { }
                val idle = checkNotNull(setup.store.read())
                // Block host eviction: this must prove the device's deadline, not its cache timer.
                setup.store.acquireLease(0).use {
                    java.net.Socket("127.0.0.1", idle.hostPort!!).use { socket ->
                        socket.soTimeout = 6_500
                        val header =
                            java.nio.ByteBuffer
                                .allocate(20)
                                .putInt(0x54415031)
                                .put(1)
                                .put(1)
                                .putShort(0)
                                .putLong(0)
                                .putInt(100)
                                .array()
                        val started = System.nanoTime()
                        val dripper =
                            launch(Dispatchers.IO) {
                                try {
                                    for (byte in header + ByteArray(100)) {
                                        socket.getOutputStream().write(byte.toInt())
                                        socket.getOutputStream().flush()
                                        delay(200)
                                    }
                                } catch (_: java.io.IOException) {
                                }
                            }
                        try {
                            check(socket.getInputStream().read() == -1) { "Unauthenticated idle socket produced data" }
                            check((System.nanoTime() - started) / 1_000_000 < 6_000) { "Trickled bytes extended idle lifetime" }
                        } finally {
                            socket.close()
                            dripper.cancel()
                            dripper.join()
                        }
                    }
                }
                setup.session { check(!it.driverReused) }
                report("runtime_reuse", serial, "absolute_idle_deadline" to "ok")
            } finally {
                setup.cache.close()
            }
        }

    @OnEachDevice
    fun `rotation baseline changes force cold and final runtime exit preserves a new lock`(serial: String) =
        runBlocking(Dispatchers.IO) {
            val setup = Setup(serial, 60_000)
            val original = setup.rotation()

            fun state(
                auto: Boolean,
                userRotation: String,
            ) = original.map { saved ->
                when (saved.key) {
                    StateKey.ACCELEROMETER_ROTATION -> {
                        saved.copy(value = if (auto) "1" else "0")
                    }

                    StateKey.USER_ROTATION -> {
                        saved.copy(value = userRotation)
                    }

                    StateKey.DEVICE_STATE_ROTATION_LOCK -> {
                        saved.copy(
                            value =
                                saved.value
                                    ?.split(':')
                                    ?.mapIndexed { index, value ->
                                        // Android's per-posture map: 0 inherits, 1 is locked, 2 is unlocked.
                                        if (index % 2 == 1 && value in listOf("1", "2")) (if (auto) "2" else "1") else value
                                    }?.joinToString(":"),
                        )
                    }

                    else -> {
                        saved
                    }
                }
            }
            try {
                val frozen = state(false, "0")
                setup.restoreRotation(frozen)
                var firstInstance = ""
                setup.session {
                    check(!it.driverReused)
                    firstInstance = it.client.driverInstanceId
                }
                val first = checkNotNull(setup.store.read())
                check(first.runtimeIdle && setup.adb.processStat(serial, first.driverPid!!) is Adb.ProcessStat.Live)
                val changed = state(true, "1")
                setup.restoreRotation(changed)
                // The runtime is still live: cold must result from the changed baseline, not TTL.
                check(setup.adb.processStat(serial, first.driverPid!!) is Adb.ProcessStat.Live)
                setup.session {
                    check(!it.driverReused && it.client.driverInstanceId != firstInstance)
                }
                check(setup.rotation() == changed) { "Cold recovery restored the old frozen snapshot" }
                val second = checkNotNull(setup.store.read())
                setup.restoreRotation(frozen)
                check(setup.adb.processStat(serial, second.driverPid!!) is Adb.ProcessStat.Live)
                setup.cache.close()
                check(setup.adb.processStat(serial, second.driverPid!!) == Adb.ProcessStat.Gone)
                check(setup.store.read()?.let { it.state == JournalState.CLOSED && !it.runtimeIdle } == true)
                delay(1_300)
                check(setup.rotation() == frozen) { "Final runtime exit restored an earlier rotation snapshot" }
                report("runtime_reuse", serial, "idle_rotation" to "ok", "final_exit" to "ok")
            } finally {
                setup.cache.close()
                setup.restoreRotation(original)
            }
        }
}
