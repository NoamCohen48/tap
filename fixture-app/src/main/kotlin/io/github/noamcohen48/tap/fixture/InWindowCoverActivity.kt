package io.github.noamcohen48.tap.fixture

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity

/**
 * Views drawn over a target inside its own window, for the driver's in-window `OBSCURED` check:
 * "Covered target" has a clickable "Cover" button drawn over its centre (a tap there presses the
 * cover), "Badged target" a label that takes no touches (a tap there reaches the target). Every
 * click is counted on screen, so the host proves a refused tap reached nothing.
 */
class InWindowCoverActivity : ComponentActivity() {
    private var target = 0
    private var cover = 0
    private var badged = 0
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        status = TextView(this).apply { id = R.id.cover_status }
        update()
        fun stack(
            under: View,
            over: View,
        ) = FrameLayout(this).apply {
            addView(under, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(120)))
            // Added last and raised: drawn over the middle of the button under it.
            over.elevation = dp(8).toFloat()
            addView(over, FrameLayout.LayoutParams(dp(140), dp(56), Gravity.CENTER))
        }
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.WHITE)
                setPadding(dp(16), dp(16), dp(16), dp(16))
                addView(status)
                addView(
                    stack(
                        button(R.id.cover_target, "Covered target") { target++ },
                        button(R.id.cover_button, "Cover") { cover++ },
                    ),
                )
                addView(
                    stack(
                        button(R.id.badge_target, "Badged target") { badged++ },
                        TextView(context).apply {
                            text = "Badge"
                            gravity = Gravity.CENTER
                            setBackgroundColor(Color.rgb(255, 210, 120))
                        },
                    ),
                )
            },
        )
    }

    private fun button(
        id: Int,
        label: String,
        onClick: () -> Unit,
    ) = Button(this).apply {
        this.id = id
        text = label
        isAllCaps = false
        setOnClickListener {
            onClick()
            update()
        }
    }

    private fun update() {
        status.text = "Covers: target=$target cover=$cover badged=$badged"
    }
}
