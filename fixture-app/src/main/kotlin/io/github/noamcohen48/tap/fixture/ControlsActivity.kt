package io.github.noamcohen48.tap.fixture

import android.os.Bundle
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.ComponentActivity

/**
 * Targets for accessibility actions, each answered as a screen reader user would get it: a
 * `SeekBar` (`ACTION_SET_PROGRESS`, 0..100), a card offering the custom actions "Archive" and
 * "Mark unread" and no click, and a details header offering `ACTION_EXPAND` / `ACTION_COLLAPSE`.
 * Every result is shown as text.
 */
class ControlsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_controls)

        val volume = findViewById<TextView>(R.id.volume_status)
        findViewById<SeekBar>(R.id.volume_slider).setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    bar: SeekBar,
                    progress: Int,
                    fromUser: Boolean,
                ) {
                    volume.text = "Volume: $progress"
                }

                override fun onStartTrackingTouch(bar: SeekBar) = Unit

                override fun onStopTrackingTouch(bar: SeekBar) = Unit
            },
        )

        val cardStatus = findViewById<TextView>(R.id.card_status)
        val custom = mapOf(R.id.action_archive to "Archive", R.id.action_mark_unread to "Mark unread")
        findViewById<View>(R.id.message_card).accessibilityDelegate =
            object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(
                    host: View,
                    info: AccessibilityNodeInfo,
                ) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    custom.forEach { (id, label) -> info.addAction(AccessibilityAction(id, label)) }
                }

                override fun performAccessibilityAction(
                    host: View,
                    action: Int,
                    args: Bundle?,
                ): Boolean {
                    val label = custom[action] ?: return super.performAccessibilityAction(host, action, args)
                    cardStatus.text = if (label == "Archive") "Archived" else "Marked unread"
                    return true
                }
            }

        val body = findViewById<View>(R.id.details_body)
        val header = findViewById<TextView>(R.id.details_header)
        header.accessibilityDelegate =
            object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(
                    host: View,
                    info: AccessibilityNodeInfo,
                ) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.addAction(if (body.visibility == View.VISIBLE) AccessibilityAction.ACTION_COLLAPSE else AccessibilityAction.ACTION_EXPAND)
                }

                override fun performAccessibilityAction(
                    host: View,
                    action: Int,
                    args: Bundle?,
                ): Boolean {
                    val expand =
                        when (action) {
                            AccessibilityAction.ACTION_EXPAND.id -> true
                            AccessibilityAction.ACTION_COLLAPSE.id -> false
                            else -> return super.performAccessibilityAction(host, action, args)
                        }
                    body.visibility = if (expand) View.VISIBLE else View.GONE
                    header.text = if (expand) "Details (expanded)" else "Details"
                    return true
                }
            }
    }
}
