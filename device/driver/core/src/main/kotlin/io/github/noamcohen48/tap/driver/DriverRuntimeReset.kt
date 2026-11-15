package io.github.noamcohen48.tap.driver

import android.annotation.SuppressLint
import android.app.Instrumentation
import android.app.UiAutomation
import android.os.Build
import androidx.test.uiautomator.UiDevice

/**
 * Narrow instrumentation-only compatibility bridge, verified on API 29/34 and AndroidX 2.4.0.
 * The public SDK has no disconnect API. A flags toggle reconnects immediately and retains a
 * platform rotation snapshot, which is NOT a safe idle state. Destroy instead; Instrumentation
 * then creates a new UiAutomation at the next session and does not disconnect again at finish.
 * Failure disqualifies reuse: the host stops/verifies the process before restoring rotation.
 * No Android hidden-API checks are disabled. No upstream code is copied.
 */
internal object DriverRuntimeReset {
    // Optional, version-dependent bridge: blocked access throws and forces verified cold cleanup.
    // This suppresses the static compatibility warning, not Android's hidden-API enforcement.
    @SuppressLint("BlockedPrivateApi")
    fun disconnect(instrumentation: Instrumentation) {
        val automation = requireNotNull(instrumentation.uiAutomation)
        automation.setOnAccessibilityEventListener(null)
        if (Build.VERSION.SDK_INT >= 29) automation.dropShellPermissionIdentity()
        UiAutomation::class.java
            .getDeclaredMethod("destroy")
            .apply { isAccessible = true }
            .invoke(automation)
        // UiDevice keeps query/event/cache state even when the platform automation is replaced.
        // The pinned 2.4.0 source has sInstance; use a fresh instance for every new session.
        UiDevice::class.java
            .getDeclaredField("sInstance")
            .apply { isAccessible = true }
            .set(null, null)
    }
}
