package io.github.noamcohen48.tap.host.validation

import io.github.noamcohen48.tap.api.v1.ErrorCode
import io.github.noamcohen48.tap.api.v1.PermissionChoice
import io.github.noamcohen48.tap.protocol.Commands
import io.github.noamcohen48.tap.protocol.ErrorDetail
import io.github.noamcohen48.tap.protocol.Nodes
import io.github.noamcohen48.tap.protocol.Selectors
import io.github.noamcohen48.tap.protocol.and
import io.github.noamcohen48.tap.protocol.detail
import io.github.noamcohen48.tap.protocol.errorCode
import io.github.noamcohen48.tap.protocol.ok
import io.github.noamcohen48.tap.protocol.toSelector

/**
 * A runtime permission dialog: a selector bound to the AUT's package (or another package) does
 * not see it, the permission controller's package does, and a selector with no package
 * predicate finds its allow button (per API level) and grants it. The dialog commands report the
 * offered choices by resource id, refuse a choice the dialog does not offer before any input,
 * and press the one that is.
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

    @OnEachDevice
    fun `permission prompt reports its choices and presses one`(serial: String) =
        deviceTest(serial) { device ->
            // pm clear resets the permission and its "don't ask again" state, so every run sees the dialog.
            device.shell("pm", "clear", FIXTURE_PACKAGE)
            device.withSession { session ->
                val client = session.client
                device.wakeAndDismissKeyguard()
                val none = client.send(Commands.waitPermissionPrompt(), timeoutMs = 500)
                check(!none.ok && none.errorCode == ErrorCode.ERR_WAIT_TIMEOUT && none.detail == ErrorDetail.NO_PERMISSION_PROMPT) {
                    "No dialog should be a NO_PERMISSION_PROMPT timeout: $none"
                }
                device.launchFixture("PermissionActivity")
                val requestPermission = Selectors.androidResource(FIXTURE_PACKAGE, "request_camera_permission")
                check(client.send(Commands.waitVisible(requestPermission), timeoutMs = 10_000).ok) { "Permission activity did not appear" }
                check(client.send(Commands.tap(requestPermission)).ok)

                val prompt = client.execute(Commands.waitPermissionPrompt(), timeoutMs = 10_000).permissionPrompt
                val allow =
                    if (device.apiLevel >= 30) PermissionChoice.PERMISSION_ALLOW_FOREGROUND_ONLY else PermissionChoice.PERMISSION_ALLOW
                check(allow in prompt.choicesList && PermissionChoice.PERMISSION_DENY in prompt.choicesList) {
                    "Camera dialog should offer $allow and DENY: $prompt"
                }
                check(prompt.packageName == PERMISSION_CONTROLLER_PACKAGE) { "Dialog package: $prompt" }

                val notOffered = client.send(Commands.choosePermission(PermissionChoice.PERMISSION_ALLOW_ALL))
                check(!notOffered.ok && notOffered.errorCode == ErrorCode.ERR_NOT_FOUND) { "A choice not offered must be NOT_FOUND: $notOffered" }
                check(client.send(Commands.choosePermission(allow)).ok)
                check(client.send(Commands.waitVisible(Selectors.text("Camera granted")), timeoutMs = 10_000).ok) {
                    "Choosing $allow did not grant the permission"
                }
                report("permission_prompt", serial, "choices" to prompt.choicesList.joinToString(",") { it.name }, "package" to prompt.packageName)
            }
        }
}

private const val PERMISSION_CONTROLLER_PACKAGE = "com.google.android.permissioncontroller"
