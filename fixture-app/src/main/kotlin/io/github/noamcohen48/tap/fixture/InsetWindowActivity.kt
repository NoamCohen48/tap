package io.github.noamcohen48.tap.fixture

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.activity.ComponentActivity

/**
 * Windows with surface insets (the shadow margin of a window with an elevation), whose
 * accessibility bounds some devices report moved up and left by that margin.
 *
 * "Show dialog" opens a dialog whose last pixels at the right and bottom hold "Corner"; the dialog
 * writes the button's real screen bounds next to it ("Corner at l,t,r,b"), so a test can compare
 * them with what it is told. "Show cover" lays an elevated popup window, which does not take
 * focus, beside "Beside" (8 dp to its right) and over the top of "Half under" (its centre 12 dp
 * inside the popup's bottom edge). Every click is counted on screen, the cover's included.
 */
class InsetWindowActivity : ComponentActivity() {
    private var beside = 0
    private var halfUnder = 0
    private var cover = 0
    private var corner = 0
    private var popup: PopupWindow? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val status = TextView(this).apply { id = R.id.inset_status }
        fun update() {
            status.text = "Taps: beside=$beside halfUnder=$halfUnder cover=$cover corner=$corner"
        }
        val besideButton =
            Button(this).apply {
                id = R.id.inset_beside
                text = "B"
                contentDescription = "Beside"
                setPadding(0, 0, 0, 0)
                minWidth = 0
                minimumWidth = 0
                setOnClickListener {
                    beside++
                    update()
                }
            }
        val halfUnderButton =
            Button(this).apply {
                id = R.id.inset_half_under
                text = "Half under"
                isAllCaps = false
                setOnClickListener {
                    halfUnder++
                    update()
                }
            }
        val root =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(16), dp(16), dp(16))
                addView(status)
                addView(
                    Button(context).apply {
                        id = R.id.inset_show_dialog
                        text = "Show dialog"
                        isAllCaps = false
                        setOnClickListener { showDialog(::update) }
                    },
                )
                addView(
                    Button(context).apply {
                        id = R.id.inset_show_cover
                        text = "Show cover"
                        isAllCaps = false
                        setOnClickListener { showCover(besideButton, halfUnderButton, ::update) }
                    },
                )
                addView(View(context), LinearLayout.LayoutParams(1, dp(48)))
                addView(besideButton, LinearLayout.LayoutParams(dp(40), dp(48)))
                addView(View(context), LinearLayout.LayoutParams(1, dp(24)))
                addView(
                    halfUnderButton,
                    LinearLayout.LayoutParams(dp(120), dp(48)).apply { leftMargin = dp(80) },
                )
            }
        update()
        setContentView(root)
    }

    private fun showDialog(update: () -> Unit) {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val location = TextView(this).apply { id = R.id.inset_corner_location }
        val content = FrameLayout(this)
        content.addView(location, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START))
        val cornerButton =
            Button(this).apply {
                id = R.id.inset_corner
                text = "Corner"
                isAllCaps = false
                setOnClickListener {
                    corner++
                    update()
                }
            }
        content.addView(cornerButton, FrameLayout.LayoutParams(dp(96), dp(48), Gravity.BOTTOM or Gravity.END))
        cornerButton.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            val xy = IntArray(2).also(view::getLocationOnScreen)
            location.text = "Corner at ${xy[0]},${xy[1]},${xy[0] + view.width},${xy[1] + view.height}"
        }
        // No padding, title or buttons: "Corner" ends where the dialog's content does.
        AlertDialog.Builder(this).create().apply {
            setView(content, 0, 0, 0, 0)
            show()
            window?.setLayout(dp(280), dp(160))
        }
    }

    private fun showCover(
        besideButton: View,
        halfUnderButton: View,
        update: () -> Unit,
    ) {
        val shown = popup
        if (shown != null) {
            shown.dismiss()
            popup = null
            return
        }
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val beside = IntArray(2).also(besideButton::getLocationOnScreen)
        val under = IntArray(2).also(halfUnderButton::getLocationOnScreen)
        val left = beside[0] + besideButton.width + dp(8)
        val top = beside[1] - dp(16)
        val bottom = under[1] + halfUnderButton.height / 2 + dp(12)
        val view =
            TextView(this).apply {
                text = "Elevated cover"
                gravity = Gravity.CENTER
                setOnClickListener {
                    cover++
                    update()
                }
            }
        popup =
            PopupWindow(view, dp(200), bottom - top, false).apply {
                setBackgroundDrawable(ColorDrawable(Color.rgb(255, 210, 120)))
                elevation = dp(8).toFloat()
                isTouchable = true
                showAtLocation(besideButton, Gravity.NO_GRAVITY, left, top)
            }
    }

    override fun onDestroy() {
        popup?.dismiss()
        super.onDestroy()
    }
}
