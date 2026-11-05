package io.github.noamcohen48.tap.fixture

import android.os.Bundle
import android.view.MotionEvent
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity

/** Observes raw releases and press durations, independently of Android's multi-click heuristics. */
class TimedTouchActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        fun label(
            viewId: Int,
            initial: String,
        ): TextView =
            TextView(this).apply {
                id = viewId
                text = initial
                textSize = 20f
                setPadding(24, 24, 24, 24)
                layout.addView(this, LinearLayout.LayoutParams(-1, -2))
            }
        val status = label(R.id.touch_count, "Taps: 0")
        val duration = label(R.id.touch_duration, "Held: 0 ms")
        val target = label(R.id.timed_touch_target, "Touch me")
        label(R.id.timed_touch_duplicate, "Touch me")
        var count = 0
        var down = 0L
        target.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    down = event.eventTime
                }

                MotionEvent.ACTION_UP -> {
                    status.text = "Taps: ${++count}"
                    duration.text = "Held: ${event.eventTime - down} ms"
                    view.performClick()
                }

                MotionEvent.ACTION_CANCEL -> {
                    duration.text = "Cancelled"
                }
            }
            true
        }
        setContentView(layout)
    }
}
