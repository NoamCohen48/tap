package io.github.noamcohen48.tap.fixture

import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity

/**
 * Duplicate targets for cardinality validation plus a long-press target and a prefilled
 * editor for the gesture operations. Every reaction is visible as text so the host can prove
 * whether a rejected command touched anything.
 */
class AmbiguityActivity : ComponentActivity() {
    private var duplicateTaps = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ambiguity)
        val duplicateStatus = findViewById<TextView>(R.id.duplicate_status)
        listOf(R.id.left_half, R.id.right_half).forEach { half ->
            findViewById<LinearLayout>(half).findViewById<Button>(R.id.duplicate_button).setOnClickListener {
                duplicateTaps += 1
                duplicateStatus.text = "Duplicate taps: $duplicateTaps"
            }
        }
        val gestureStatus = findViewById<TextView>(R.id.gesture_status)
        findViewById<Button>(R.id.gesture_target).apply {
            setOnClickListener { gestureStatus.text = "Gesture: tap" }
            setOnLongClickListener {
                gestureStatus.text = "Gesture: long press"
                true
            }
        }
    }
}
