package io.github.noamcohen48.tap.host.validation

import io.github.noamcohen48.tap.protocol.Commands
import io.github.noamcohen48.tap.protocol.Nodes
import io.github.noamcohen48.tap.protocol.Selectors
import io.github.noamcohen48.tap.protocol.and
import io.github.noamcohen48.tap.protocol.ok
import io.github.noamcohen48.tap.protocol.toSelector

/**
 * A runtime permission dialog: a selector bound to the AUT's package (or another package) does
 * not see it, the permission controller's package does, and a selector with no package
 * predicate finds its allow button (per API level) and grants it.
 */
@DeviceTest
class PermissionTest {
    @OnEachDevice
    fun `permission dialog is reachable by its package or screen-wide`(serial: String) =
        deviceTest(serial) { device ->
            device.shell("pm", "revoke", FIXTURE_PACKAGE, "android.permission.CAMERA")
            device.withSession { session ->
                val client = session.client
                device.wakeAndDismissKeyguard()
                device.launchFixture("PermissionActivity")
                val requestPermission = Selectors.androidResource(FIXTURE_PACKAGE, "request_camera_permission")
                check(client.send(Commands.waitVisible(requestPermission), timeoutMs = 10_000).ok) {
                    "Permission activity did not appear"
                }
                val requestTap = client.send(Commands.tap(requestPermission))
                check(requestTap.ok) { "Permission request tap failed: $requestTap" }
                val permissionChoice = Nodes.text(if (device.apiLevel >= 30) "While using the app" else "Allow")
                val allowPermission =
                    Nodes.androidResource(
                        PERMISSION_RESOURCE_PACKAGE,
                        if (device.apiLevel >= 30) "permission_allow_foreground_only_button" else "permission_allow_button",
                    )
                check(client.send(Commands.waitVisible(allowPermission.toSelector()), timeoutMs = 10_000).ok) {
                    "Allow button did not appear on the screen"
                }
                val inAut = client.send(Commands.exists((permissionChoice and Nodes.packageName(FIXTURE_PACKAGE)).toSelector()))
                check(inAut.ok && !inAut.result.bool) { "The AUT's package should not see the dialog: $inAut" }
                val inSettings = client.send(Commands.exists((permissionChoice and Nodes.packageName("com.android.settings")).toSelector()))
                check(inSettings.ok && !inSettings.result.bool) { "Another package should not see the dialog: $inSettings" }
                val inController = client.send(Commands.exists((allowPermission and Nodes.packageName(PERMISSION_CONTROLLER_PACKAGE)).toSelector()))
                check(inController.ok && inController.result.bool) { "The controller's package should see the dialog: $inController" }
                check(client.send(Commands.tap(allowPermission.toSelector())).ok)
                check(client.send(Commands.waitVisible(Selectors.text("Camera granted")), timeoutMs = 10_000).ok)
            }
        }
}

private const val PERMISSION_CONTROLLER_PACKAGE = "com.google.android.permissioncontroller"
