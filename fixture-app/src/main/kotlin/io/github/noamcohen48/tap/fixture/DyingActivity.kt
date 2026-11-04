package io.github.noamcohen48.tap.fixture

import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import androidx.activity.ComponentActivity

/** An app that dies on demand: a button that crashes it and one that hangs its main thread with input waiting. */
class DyingActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val crash =
            Button(this).apply {
                id = View.generateViewId()
                text = "Crash"
                contentDescription = "crash_button"
                setOnClickListener { throw IllegalStateException("Tap fixture crashed on purpose") }
            }
        val hang =
            Button(this).apply {
                id = View.generateViewId()
                text = "Hang"
                contentDescription = "hang_button"
                setOnTouchListener { _, event ->
                    if (event.action == MotionEvent.ACTION_DOWN) Thread.sleep(60_000)
                    true
                }
            }
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(crash)
                addView(hang)
            },
        )
    }
}
