package io.github.noamcohen48.tap.fixture

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.activity.ComponentActivity

/**
 * Motion for the `WAIT_SCREEN_STABLE` checks. Both effects are driven by a `Handler`, not the
 * animator framework, so they behave the same on devices with animations disabled:
 * "Animate" moves the box in fixed steps for [ANIMATION_MS] and then reports completion;
 * the ticker changes text every [TICK_MS] until toggled off (a screen that never settles).
 */
class MotionActivity : ComponentActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private var tickerRunning = false
    private var ticks = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_motion)

        val status = findViewById<TextView>(R.id.motion_status)
        val target = findViewById<View>(R.id.motion_target)
        findViewById<Button>(R.id.motion_button).setOnClickListener {
            status.text = "Animating"
            target.translationX = 0f
            val step = resources.displayMetrics.density * 4
            var frame = 0
            val frames = (ANIMATION_MS / FRAME_MS).toInt()
            lateinit var advance: Runnable
            advance = Runnable {
                frame++
                target.translationX = step * frame
                if (frame < frames) handler.postDelayed(advance, FRAME_MS) else status.text = "Animation done"
            }
            handler.postDelayed(advance, FRAME_MS)
        }

        val tickerStatus = findViewById<TextView>(R.id.ticker_status)
        lateinit var tick: Runnable
        tick = Runnable {
            if (!tickerRunning) return@Runnable
            tickerStatus.text = "Tick ${++ticks}"
            handler.postDelayed(tick, TICK_MS)
        }
        findViewById<Button>(R.id.ticker_button).setOnClickListener {
            tickerRunning = !tickerRunning
            if (tickerRunning) handler.post(tick) else tickerStatus.text = "Ticker stopped"
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private companion object {
        const val ANIMATION_MS = 2_000L
        const val FRAME_MS = 50L
        const val TICK_MS = 100L
    }
}
