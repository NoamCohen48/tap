package io.github.noamcohen48.tap.driver

import android.app.Instrumentation
import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo

/** Fresh metadata reads, without UiAutomator's implicit accessibility-idle waits. */
internal class DeviceMetadataReader(
    private val readRotation: () -> Int,
    private val activeRoot: () -> Root?,
) {
    /** A root acquired for this read only; never retained across commands. */
    internal interface Root {
        val packageName: CharSequence?

        fun recycle()
    }

    val displayRotation: Int get() = readRotation()

    val currentPackage: String?
        get() {
            val root = activeRoot() ?: return null
            try {
                return root.packageName?.toString()
            } finally {
                root.recycle()
            }
        }

    companion object {
        fun from(instrumentation: Instrumentation): DeviceMetadataReader {
            // Same display source as UiDevice.getDisplayRotation(), but no waitForIdle().
            val displays = instrumentation.context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            return DeviceMetadataReader(
                readRotation = {
                    requireNotNull(displays.getDisplay(Display.DEFAULT_DISPLAY)) {
                        "Display ${Display.DEFAULT_DISPLAY} is not accessible"
                    }.rotation
                },
                activeRoot = {
                    // QueryController.getCurrentPackageName() uses this root too, after idling
                    // and retrying. DeviceInfo is a snapshot: no current root means unknown.
                    instrumentation.uiAutomation.rootInActiveWindow?.let(::AndroidRoot)
                },
            )
        }
    }

    private class AndroidRoot(
        private val node: AccessibilityNodeInfo,
    ) : Root {
        override val packageName: CharSequence? get() = node.packageName

        @Suppress("DEPRECATION")
        override fun recycle() = node.recycle()
    }
}
