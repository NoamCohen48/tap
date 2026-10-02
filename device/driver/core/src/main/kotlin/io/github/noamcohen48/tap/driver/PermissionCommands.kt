package io.github.noamcohen48.tap.driver

import androidx.test.uiautomator.By
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import io.github.noamcohen48.tap.api.v1.ErrorCode
import io.github.noamcohen48.tap.api.v1.PermissionChoice
import io.github.noamcohen48.tap.api.v1.PermissionPrompt
import io.github.noamcohen48.tap.driver.engine.CommandContext
import io.github.noamcohen48.tap.protocol.CommandFailure
import io.github.noamcohen48.tap.protocol.ErrorDetail
import java.util.regex.Pattern

/**
 * The runtime-permission dialog (`wait_permission_prompt`, `choose_permission`). Buttons are
 * found only by the permission controller's own resource ids, as AndroidX's `PermissionDialog`
 * and Appium's alert handling prefer: never by label (it is localized) and never by position
 * (the order differs between versions and OEMs). A dialog button with an id not in the table is
 * not reported, so a new Android version shows up as a missing choice rather than a wrong tap.
 */
internal class PermissionCommands(
    private val device: UiDevice,
) {
    /** Polls until a permission dialog offers at least one known choice; `WAIT_TIMEOUT` otherwise. */
    fun waitPrompt(context: CommandContext): PermissionPrompt {
        while (true) {
            context.checkCancelled()
            read()?.let { return it }
            if (context.remainingMs() <= 0) {
                throw CommandFailure(ErrorCode.ERR_WAIT_TIMEOUT, ErrorDetail.NO_PERMISSION_PROMPT, "No permission dialog is showing")
            }
            context.sleep(POLL_MS)
        }
    }

    /**
     * Clicks the one button offering [choice]. `NOT_FOUND` when the dialog (or this choice in it)
     * is not showing, `AMBIGUOUS` when several windows offer it; both before any input.
     */
    fun choose(
        context: CommandContext,
        choice: PermissionChoice,
    ) {
        context.checkpoint()
        val name =
            BUTTONS[choice]
                ?: throw CommandFailure(ErrorCode.ERR_INVALID_REQUEST, message = "A known permission choice is required")
        val matches = device.findObjects(By.res(Pattern.compile("$CONTROLLERS:id/${Pattern.quote(name)}")))
        val button =
            when (matches.size) {
                1 -> matches.single()
                0 -> throw CommandFailure(ErrorCode.ERR_NOT_FOUND, message = "No permission dialog offers $choice")
                else -> {
                    matches.forEach(::recycleQuietly)
                    throw CommandFailure(ErrorCode.ERR_AMBIGUOUS, message = "${matches.size} permission dialog buttons offer $choice")
                }
            }
        try {
            context.markMutationStarted()
            button.click()
        } finally {
            button.recycle()
        }
    }

    /** The dialog's known choices, or null when none is showing (or the tree changed mid-read). */
    private fun read(): PermissionPrompt? {
        val buttons = device.findObjects(ANY_BUTTON)
        try {
            val found =
                buttons.mapNotNull { button ->
                    val match = BUTTON_ID.matcher(button.resourceName ?: return@mapNotNull null)
                    if (!match.matches()) return@mapNotNull null
                    val choice = CHOICES[match.group(2)] ?: return@mapNotNull null
                    // The window's package, which Google builds rename; resource ids keep the AOSP one.
                    button.applicationPackage to choice
                }
            if (found.isEmpty()) return null
            return PermissionPrompt
                .newBuilder()
                .setPackageName(found.first().first)
                .addAllChoices(found.map { it.second }.distinct().sortedBy { it.number })
                .build()
        } catch (_: StaleObjectException) {
            return null
        } finally {
            buttons.forEach(UiObject2::recycle)
        }
    }

    private companion object {
        const val POLL_MS = 100L

        /** API 29+ permission controller (AOSP and Google builds); API 26–28 package installer. */
        const val CONTROLLERS =
            "(com\\.android\\.permissioncontroller|com\\.google\\.android\\.permissioncontroller" +
                "|com\\.android\\.packageinstaller|com\\.google\\.android\\.packageinstaller)"

        /** Resource entry names of `GrantPermissionsActivity`'s buttons. */
        val BUTTONS =
            mapOf(
                PermissionChoice.PERMISSION_ALLOW to "permission_allow_button",
                PermissionChoice.PERMISSION_ALLOW_FOREGROUND_ONLY to "permission_allow_foreground_only_button",
                PermissionChoice.PERMISSION_ALLOW_ONE_TIME to "permission_allow_one_time_button",
                PermissionChoice.PERMISSION_ALLOW_ALWAYS to "permission_allow_always_button",
                PermissionChoice.PERMISSION_ALLOW_SELECTED to "permission_allow_selected_button",
                PermissionChoice.PERMISSION_ALLOW_ALL to "permission_allow_all_button",
                PermissionChoice.PERMISSION_DENY to "permission_deny_button",
                PermissionChoice.PERMISSION_DENY_AND_DONT_ASK_AGAIN to "permission_deny_and_dont_ask_again_button",
                PermissionChoice.PERMISSION_KEEP_FOREGROUND_ONLY to "permission_no_upgrade_button",
                PermissionChoice.PERMISSION_KEEP_ONE_TIME to "permission_no_upgrade_one_time_button",
            )
        val CHOICES = BUTTONS.entries.associate { (choice, name) -> name to choice }

        val BUTTON_ID: Pattern = Pattern.compile("$CONTROLLERS:id/(permission_\\w+_button)")
        val ANY_BUTTON = By.res(BUTTON_ID)
    }
}
