package io.github.noamcohen48.tap.driver

import android.app.Instrumentation
import android.app.UiAutomation
import android.os.SystemClock
import android.provider.Settings
import android.view.Surface
import androidx.test.uiautomator.UiDevice
import io.github.noamcohen48.tap.api.v1.DisplayRotation
import io.github.noamcohen48.tap.api.v1.ErrorCode
import io.github.noamcohen48.tap.api.v1.Orientation
import io.github.noamcohen48.tap.api.v1.SetDisplayRotation
import io.github.noamcohen48.tap.api.v1.SetOrientation
import io.github.noamcohen48.tap.driver.engine.CommandContext
import io.github.noamcohen48.tap.protocol.CommandFailure

/**
 * Default-display rotation through `UiAutomation.setRotation`, as AndroidX's `UiDevice` rotation
 * helpers do. The result is only whether Android accepted the request. After that the command
 * waits, bounded and without failing, for the display to report the requested rotation (again as
 * AndroidX does), so the next command usually sees the new geometry. The foreground app may pin
 * its orientation and keep the display where it wants it: that is for the test to observe
 * (`deviceInfo`), never a driver failure.
 */
internal class RotationCommands(
    private val instrumentation: Instrumentation,
    private val device: UiDevice,
) {
    /** Auto-rotate is on (`ACCELEROMETER_ROTATION`): the sensor, not a frozen rotation, turns the display. */
    val autoRotate: Boolean
        get() = Settings.System.getInt(instrumentation.context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0) == 1

    /** Portrait or landscape geometry, whatever the device's natural orientation; frozen. */
    fun setOrientation(
        context: CommandContext,
        command: SetOrientation,
    ) {
        context.checkpoint()
        val portrait =
            when (command.orientation) {
                Orientation.ORIENTATION_PORTRAIT -> true
                Orientation.ORIENTATION_LANDSCAPE -> false
                else -> throw CommandFailure(ErrorCode.ERR_INVALID_REQUEST, message = "A known orientation is required")
            }
        val alreadyThere = (device.displayHeight >= device.displayWidth) == portrait
        // Same choice as UiDevice.setOrientationPortrait/Landscape: the quarter turn away from
        // the natural rotation has the other geometry, whether the device is a phone or a tablet.
        val target =
            when {
                alreadyThere -> null
                device.isNaturalOrientation -> Surface.ROTATION_90
                else -> Surface.ROTATION_0
            }
        rotate(context, target?.let(::freezeTo) ?: UiAutomation.ROTATION_FREEZE_CURRENT, "orientation ${command.orientation.name}")
        target?.let(::awaitDisplayRotation)
    }

    /** An exact clockwise rotation relative to the natural orientation; frozen. */
    fun setDisplayRotation(
        context: CommandContext,
        command: SetDisplayRotation,
    ) {
        context.checkpoint()
        val target =
            when (command.rotation) {
                DisplayRotation.DISPLAY_ROTATION_NATURAL -> Surface.ROTATION_0
                DisplayRotation.DISPLAY_ROTATION_LEFT -> Surface.ROTATION_90
                DisplayRotation.DISPLAY_ROTATION_UPSIDE_DOWN -> Surface.ROTATION_180
                DisplayRotation.DISPLAY_ROTATION_RIGHT -> Surface.ROTATION_270
                else -> throw CommandFailure(ErrorCode.ERR_INVALID_REQUEST, message = "A known display rotation is required")
            }
        rotate(context, freezeTo(target), "display rotation ${command.rotation.name}")
        awaitDisplayRotation(target)
    }

    /** Hands rotation back to the sensor; nothing to wait for. */
    fun unfreezeRotation(context: CommandContext) {
        context.checkpoint()
        rotate(context, UiAutomation.ROTATION_UNFREEZE, "rotation unfreeze")
    }

    private fun rotate(
        context: CommandContext,
        uiAutomationRotation: Int,
        what: String,
    ) {
        context.markMutationStarted()
        if (!instrumentation.uiAutomation.setRotation(uiAutomationRotation)) {
            throw CommandFailure(ErrorCode.ERR_ACTION_REJECTED, message = "Android refused the $what")
        }
    }

    /** Polls until the display reports [rotation] or [SETTLE_TIMEOUT_MS] passes; never fails. */
    private fun awaitDisplayRotation(rotation: Int) {
        val deadline = SystemClock.elapsedRealtime() + SETTLE_TIMEOUT_MS
        while (device.displayRotation != rotation && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
    }

    private fun freezeTo(surfaceRotation: Int): Int =
        when (surfaceRotation) {
            Surface.ROTATION_0 -> UiAutomation.ROTATION_FREEZE_0
            Surface.ROTATION_90 -> UiAutomation.ROTATION_FREEZE_90
            Surface.ROTATION_180 -> UiAutomation.ROTATION_FREEZE_180
            else -> UiAutomation.ROTATION_FREEZE_270
        }

    private companion object {
        /** AndroidX `UiDevice.ROTATION_TIMEOUT`. */
        const val SETTLE_TIMEOUT_MS = 2_000L
        const val POLL_INTERVAL_MS = 50L
    }
}
