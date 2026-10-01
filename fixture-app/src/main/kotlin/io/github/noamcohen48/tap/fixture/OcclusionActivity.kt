package io.github.noamcohen48.tap.fixture

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.PopupWindow
import android.widget.TextView
import androidx.activity.ComponentActivity

/**
 * Targets that another window covers, for the driver's `OBSCURED` check. "Show cover" lays a
 * non-focusable popup window (a second window of this app) over the middle half of "Covered
 * target": the button stays partly visible, so Android still reports it, but its centre (where a
 * tap goes) is under the cover. A node another window covers completely is reported invisible
 * and not found at all. The activity never resizes or pans for the keyboard (`adjustNothing`),
 * so with the keyboard open it covers "Bottom target". Every click is counted on screen, the cover's included, so the host can
 * prove a refused tap reached neither the target nor the window on top of it.
 */
class OcclusionActivity : ComponentActivity() {
    private var covered = 0
    private var bottom = 0
    private var coverTaps = 0
    private var cover: PopupWindow? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_occlusion)
        val status = findViewById<TextView>(R.id.occlusion_status)
        fun update() {
            status.text = "Taps: covered=$covered bottom=$bottom cover=$coverTaps"
        }
        val target = findViewById<Button>(R.id.covered_target)
        target.setOnClickListener {
            covered++
            update()
        }
        findViewById<Button>(R.id.bottom_target).setOnClickListener {
            bottom++
            update()
        }
        val toggle = findViewById<Button>(R.id.cover_toggle)
        toggle.setOnClickListener {
            val shown = cover
            if (shown != null) {
                shown.dismiss()
                cover = null
                toggle.text = "Show cover"
                return@setOnClickListener
            }
            val view =
                TextView(this).apply {
                    text = "Cover"
                    gravity = Gravity.CENTER
                    setBackgroundColor(Color.rgb(255, 210, 120))
                    setOnClickListener {
                        coverTaps++
                        update()
                    }
                }
            val location = IntArray(2).also(target::getLocationOnScreen)
            cover =
                PopupWindow(view, target.width, target.height / 2, false).apply {
                    isTouchable = true
                    showAtLocation(target, Gravity.NO_GRAVITY, location[0], location[1] + target.height / 4)
                }
            toggle.text = "Hide cover"
        }
    }

    override fun onDestroy() {
        cover?.dismiss()
        super.onDestroy()
    }
}
