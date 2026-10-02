package io.github.noamcohen48.tap.driver

import android.os.Build
import androidx.test.uiautomator.UiDevice
import io.github.noamcohen48.tap.api.v1.Bounds
import io.github.noamcohen48.tap.api.v1.DeviceInfo
import io.github.noamcohen48.tap.api.v1.ElementSnapshot
import io.github.noamcohen48.tap.driver.engine.CommandContext

/** Read-only element and device queries (`exists`, `count`, `snapshot`, `device_info`). */
internal class QueryCommands(
    private val device: UiDevice,
    private val objects: UiObjectAccess,
    private val screen: ScreenCommands,
    private val keyboard: KeyboardCommands,
    private val rotation: RotationCommands,
    private val conditions: DeviceConditionsReader,
) {
    fun exists(
        context: CommandContext,
        target: CompiledSelector,
    ): Boolean = retryWhileStale(context) { objects.presence(target) }

    fun count(
        context: CommandContext,
        target: CompiledSelector,
    ): Int = retryWhileStale(context) { objects.count(target) }

    /**
     * Reads one element's state at this instant; the object is recycled before returning.
     * `text` is the raw accessibility text (the hint, while one is shown on API 26+), with
     * `showing_hint` saying so, so the snapshot agrees with what text selectors match.
     */
    fun snapshot(target: CompiledSelector): ElementSnapshot {
        val element = objects.resolveTarget(target)
        return try {
            val node = element.accessibilityNodeInfo
            val bounds = element.visibleBounds
            ElementSnapshot
                .newBuilder()
                .apply {
                    element.className?.let(::setClassName)
                    element.applicationPackage?.let(::setPackageName)
                    element.resourceName?.let(::setResourceName)
                    node.text?.toString()?.let(::setText)
                    element.contentDescription?.let(::setContentDescription)
                    element.hint?.let(::setHint)
                }.setBounds(
                    Bounds
                        .newBuilder()
                        .setLeft(bounds.left)
                        .setTop(bounds.top)
                        .setRight(bounds.right)
                        .setBottom(bounds.bottom),
                ).setCheckable(element.isCheckable)
                .setChecked(element.isChecked)
                .setClickable(element.isClickable)
                .setEnabled(element.isEnabled)
                .setFocusable(element.isFocusable)
                .setFocused(element.isFocused)
                .setLongClickable(element.isLongClickable)
                .setScrollable(element.isScrollable)
                .setSelected(element.isSelected)
                .setChildCount(element.childCount)
                .setShowingHint(node.isShowingHintText)
                .build()
        } finally {
            element.recycle()
        }
    }

    fun deviceInfo(): DeviceInfo =
        DeviceInfo
            .newBuilder()
            .setApiLevel(Build.VERSION.SDK_INT)
            .setManufacturer(Build.MANUFACTURER)
            .setModel(Build.MODEL)
            .setProduct(Build.PRODUCT)
            .setDisplayWidth(device.displayWidth)
            .setDisplayHeight(device.displayHeight)
            .setDisplayRotation(device.displayRotation)
            .apply { device.currentPackageName?.let(::setCurrentPackage) }
            .setScreenOn(screen.screenOn)
            .setKeyguardLocked(screen.keyguardLocked)
            .setKeyguardSecure(screen.keyguardSecure)
            .setKeyboardShown(keyboard.shown)
            .setAutoRotate(rotation.autoRotate)
            .setAnimationsEnabled(conditions.animationsEnabled)
            .setDarkMode(conditions.darkMode)
            .setFontScale(conditions.fontScale)
            .setDensityDpi(conditions.densityDpi)
            .setAirplaneMode(conditions.airplaneMode)
            .setWifiEnabled(conditions.wifiEnabled)
            .setMobileDataEnabled(conditions.mobileDataEnabled)
            .build()

    /**
     * A one-shot query whose read was stale (null) is read again rather than answered as
     * absent; the command's own deadline (or a cancel) bounds the retries.
     */
    private inline fun <T : Any> retryWhileStale(
        context: CommandContext,
        read: () -> T?,
    ): T {
        while (true) {
            context.checkpoint()
            read()?.let { return it }
            context.sleep(STALE_RETRY_MS)
        }
    }

    private companion object {
        const val STALE_RETRY_MS = 25L
    }
}
