package io.github.noamcohen48.tap.host.validation

import io.github.noamcohen48.tap.api.v1.ErrorCode
import io.github.noamcohen48.tap.host.DriverClient
import io.github.noamcohen48.tap.host.RemoteCommandException
import io.github.noamcohen48.tap.host.observeProcess
import io.github.noamcohen48.tap.protocol.Commands
import io.github.noamcohen48.tap.protocol.ErrorDetail
import io.github.noamcohen48.tap.protocol.Requests
import io.github.noamcohen48.tap.protocol.Selectors
import io.github.noamcohen48.tap.protocol.ok
import io.github.noamcohen48.tap.wire.v1.Request
import io.github.noamcohen48.tap.wire.v1.SyncState
import kotlinx.coroutines.delay

/**
 * The driver is a separate package from the AUT: Compose and View taps land, the sync SDK
 * reports busy then idle without disturbing the AUT process, the driver survives the AUT's
 * force-stop and clear-data, and a restarted AUT process is detected by its sync identity.
 */
@DeviceTest
class AutLifecycleTest {
    @OnEachDevice
    fun `driver survives AUT force-stop and clear-data and detects the restart`(serial: String) =
        deviceTest(serial) { device ->
            device.withSession { session ->
                val client = session.client
                device.wakeAndDismissKeyguard()
                device.shell("input", "keyevent", "KEYCODE_BACK")
                device.launchFixture("MainActivity")

                val composeButton = Selectors.resource("composeButton")
                check(client.send(Commands.waitVisible(composeButton), timeoutMs = 10_000).ok)
                val processBeforeBootstrap = observeProcess(device.adb, serial, FIXTURE_PACKAGE)
                val firstSyncIdentity = callSync(device, client) { pid, token -> Requests.syncBootstrap(pid, token, FIXTURE_PACKAGE, SYNC_AUTHORITY) }.getOrThrow()
                check(client.send(Commands.tap(composeButton)).ok)
                check(client.send(Commands.waitVisible(Selectors.text("Compose tapped")), timeoutMs = 5_000).ok)

                val viewButton = Selectors.androidResource(FIXTURE_PACKAGE, "view_button")
                check(client.send(Commands.tap(viewButton)).ok)
                check(client.send(Commands.waitVisible(Selectors.text("View tapped"))).ok)
                val syncButton = Selectors.androidResource(FIXTURE_PACKAGE, "sync_button")
                check(client.send(Commands.tap(syncButton)).ok)
                val busyState =
                    callSync(device, client) { pid, token ->
                        Requests.syncPoll(pid, token, firstSyncIdentity.processStartUuid, firstSyncIdentity.sessionIdentity, FIXTURE_PACKAGE, SYNC_AUTHORITY)
                    }.getOrThrow()
                check(busyState.busyCount != 0) { "Busy state was not observed: $busyState" }
                awaitSyncIdle(device, client, firstSyncIdentity, timeoutMs = 10_000)
                check(client.send(Commands.waitVisible(Selectors.text("Synchronized work complete")), timeoutMs = 5_000).ok)

                device.forceStopFixture()
                check(client.send(Requests.health()).ok) { "Driver died with the AUT" }
                check(device.shell("pm", "clear", FIXTURE_PACKAGE) == "Success") { "AUT data clearing did not report success" }
                device.forceStopFixture()
                device.awaitProcessAbsent(FIXTURE_PACKAGE)
                check(client.send(Requests.health()).ok) { "Driver died after AUT data clearing" }
                device.shell("am", "start", "-n", "$FIXTURE_PACKAGE/.MainActivity", timeoutMs = 10_000)
                val relaunched = client.send(Commands.waitVisible(composeButton), timeoutMs = 10_000)
                check(relaunched.ok) { "Fixture did not come back after clear-data: $relaunched" }
                val restartedProcess = observeProcess(device.adb, serial, FIXTURE_PACKAGE)
                check(restartedProcess != processBeforeBootstrap) { "AUT process identity did not change" }
                val staleSync =
                    callSync(device, client) { pid, token ->
                        Requests.syncPoll(pid, token, firstSyncIdentity.processStartUuid, firstSyncIdentity.sessionIdentity, FIXTURE_PACKAGE, SYNC_AUTHORITY)
                    }.exceptionOrNull()
                check(
                    staleSync is RemoteCommandException && staleSync.code == ErrorCode.ERR_AUT_MISMATCH &&
                        staleSync.detail == ErrorDetail.PROCESS_RESTARTED,
                ) { "Synchronization restart was not detected: $staleSync" }
                val restartedBootstrap = callSync(device, client) { pid, token -> Requests.syncBootstrap(pid, token, FIXTURE_PACKAGE, SYNC_AUTHORITY) }.getOrThrow()
                check(restartedBootstrap.processStartUuid != firstSyncIdentity.processStartUuid)
            }
        }

    /** Runs one synchronization command built from the observed AUT process and proves the call left that process alone. */
    private suspend inline fun callSync(
        device: DeviceHarness,
        client: DriverClient,
        timeoutMs: Long = 5_000,
        request: (observedPid: Int, observedStartToken: String) -> Request,
    ): Result<SyncState> {
        val before = observeProcess(device.adb, device.serial, FIXTURE_PACKAGE)
        val outcome = runCatching { client.execute(request(before.pid, before.startToken), timeoutMs).sync }
        val after = observeProcess(device.adb, device.serial, FIXTURE_PACKAGE)
        check(after == before) { "Synchronization call changed the AUT process: $before -> $after" }
        return outcome
    }

    private suspend fun awaitSyncIdle(
        device: DeviceHarness,
        client: DriverClient,
        expectedIdentity: SyncState,
        timeoutMs: Long,
    ) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        var zeroGeneration: Long? = null
        var zeroObservedAt = 0L
        while (true) {
            val remainingMs = (deadline - System.nanoTime()) / 1_000_000
            check(remainingMs > 0) { "Synchronization idle wait timed out" }
            val state =
                callSync(device, client, timeoutMs = minOf(5_000, remainingMs)) { pid, token ->
                    Requests.syncPoll(pid, token, expectedIdentity.processStartUuid, expectedIdentity.sessionIdentity, FIXTURE_PACKAGE, SYNC_AUTHORITY)
                }.getOrThrow()
            val now = System.nanoTime()
            check(now < deadline) { "Synchronization idle wait timed out" }
            if (state.busyCount == 0) {
                if (zeroGeneration == state.generation && now - zeroObservedAt >= 200_000_000) return
                if (zeroGeneration != state.generation) {
                    zeroGeneration = state.generation
                    zeroObservedAt = now
                }
            } else {
                zeroGeneration = null
            }
            delay(50)
        }
    }
}
