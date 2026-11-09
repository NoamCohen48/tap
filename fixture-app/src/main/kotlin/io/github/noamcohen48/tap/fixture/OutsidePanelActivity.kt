package io.github.noamcohen48.tap.fixture

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog

/**
 * Panels to touch outside of (`tap_outside_panel`), Views edition: a dialog (with a popup menu
 * of its own, a popup over a dialog), a popup menu, a popup that does not take focus and lets
 * touches through to this screen, a bottom sheet tall enough to cover its scrim's centre (fully
 * expanded, eight rows), a bottom sheet that cannot be cancelled, and navigation drawers at the
 * start and the end (the activity's root is a `DrawerLayout`).
 *
 * The status line counts every click a stray touch could cause (`under`: the large button that
 * fills the rest of the screen, under every panel; `rows`: the sheet's rows; `menu`: the menus'
 * items) and lists each panel as it closes, so a test reads afterwards whether the touch closed
 * the panel and nothing else.
 */
class OutsidePanelActivity : ComponentActivity() {
    private var under = 0
    private var rows = 0
    private var menu = 0
    private val closed = mutableListOf<String>()
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        status = TextView(this).apply { id = R.id.outside_status }
        update()
        val drawerLayout = DrawerLayout(this)
        val content =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.WHITE)
                setPadding(dp(16), dp(16), dp(16), dp(16))
                addView(status)
                addView(button("Open dialog") { showDialog() })
                addView(button("Open menu") { anchor -> showMenu(anchor) })
                addView(button("Open sheet") { showSheet(dp(64)) })
                addView(button("Open fixed sheet") { showFixedSheet() })
                addView(button("Open drawer") { drawerLayout.openDrawer(GravityCompat.START) })
                addView(button("Open end drawer") { drawerLayout.openDrawer(GravityCompat.END) })
                // Last opener: the pass-through popup drops below it, over the empty space.
                addView(button("Open pass-through") { anchor -> showPassThrough(anchor, dp(240), dp(96)) })
                addView(
                    Button(context).apply {
                        text = "Under"
                        isAllCaps = false
                        setOnClickListener {
                            under++
                            update()
                        }
                    },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
                )
            }
        // Scrolls where the screen is short (landscape); otherwise "Under" fills the rest.
        val scroll =
            ScrollView(this).apply {
                isFillViewport = true
                addView(content)
            }
        drawerLayout.addView(scroll, DrawerLayout.LayoutParams(DrawerLayout.LayoutParams.MATCH_PARENT, DrawerLayout.LayoutParams.MATCH_PARENT))
        drawerLayout.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.rgb(230, 230, 250))
                setPadding(dp(16), dp(16), dp(16), dp(16))
                addView(TextView(context).apply { text = "Drawer body" })
            },
            DrawerLayout.LayoutParams(dp(240), DrawerLayout.LayoutParams.MATCH_PARENT, Gravity.START),
        )
        drawerLayout.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.rgb(220, 245, 230))
                setPadding(dp(16), dp(16), dp(16), dp(16))
                addView(TextView(context).apply { text = "End drawer body" })
            },
            DrawerLayout.LayoutParams(dp(240), DrawerLayout.LayoutParams.MATCH_PARENT, Gravity.END),
        )
        drawerLayout.addDrawerListener(
            object : DrawerLayout.SimpleDrawerListener() {
                override fun onDrawerClosed(drawerView: View) = closed(if ((drawerView.layoutParams as DrawerLayout.LayoutParams).gravity == Gravity.END) "end drawer" else "drawer")
            },
        )
        setContentView(drawerLayout)
    }

    private fun button(
        label: String,
        onClick: (View) -> Unit,
    ) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener(onClick)
    }

    private fun update() {
        status.text = "Outside: under=$under rows=$rows menu=$menu closed=${closed.joinToString(",")}"
    }

    private fun closed(panel: String) {
        closed += panel
        update()
    }

    private fun showDialog() {
        val nested =
            Button(this).apply {
                text = "Open nested menu"
                isAllCaps = false
                setOnClickListener { anchor -> showMenu(anchor, "nested") }
            }
        val dialog = AlertDialog.Builder(this).setTitle("Dialog title").setMessage("Dialog body").setView(nested).setPositiveButton("OK", null).create()
        dialog.setCanceledOnTouchOutside(true)
        dialog.setOnDismissListener { closed("dialog") }
        dialog.show()
    }

    private fun showMenu(
        anchor: View,
        name: String = "menu",
    ) {
        PopupMenu(anchor.context, anchor).apply {
            menu.add(if (name == "menu") "Menu one" else "Nested one")
            menu.add(if (name == "menu") "Menu two" else "Nested two")
            setOnMenuItemClickListener {
                this@OutsidePanelActivity.menu++
                update()
                true
            }
            setOnDismissListener { closed(name) }
            show()
        }
    }

    /** Not focusable and touchable outside: an outside touch closes it and also reaches this screen. */
    private fun showPassThrough(
        anchor: View,
        width: Int,
        height: Int,
    ) {
        val body =
            TextView(this).apply {
                text = "Pass-through body"
                gravity = Gravity.CENTER
                setBackgroundColor(Color.rgb(255, 245, 200))
            }
        PopupWindow(body, width, height, false).apply {
            isOutsideTouchable = true
            setBackgroundDrawable(ColorDrawable(Color.rgb(255, 245, 200)))
            setOnDismissListener { closed("pass-through") }
            showAsDropDown(anchor)
        }
    }

    /** A sheet the user cannot dismiss: no dismiss action, and its window fills the screen. */
    private fun showFixedSheet() {
        val themed = ContextThemeWrapper(this, com.google.android.material.R.style.Theme_Material3_Light_NoActionBar)
        val dialog = BottomSheetDialog(themed)
        dialog.setContentView(
            LinearLayout(themed).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(context).apply { text = "Fixed sheet body" })
                addView(
                    Button(context).apply {
                        text = "Close fixed sheet"
                        isAllCaps = false
                        setOnClickListener { dialog.dismiss() }
                    },
                )
            },
        )
        dialog.setCancelable(false)
        dialog.setOnDismissListener { closed("fixed sheet") }
        dialog.show()
    }

    private fun showSheet(rowHeight: Int) {
        val themed = ContextThemeWrapper(this, com.google.android.material.R.style.Theme_Material3_Light_NoActionBar)
        val dialog = BottomSheetDialog(themed)
        dialog.setContentView(
            LinearLayout(themed).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(context).apply { text = "Sheet body" })
                for (i in 1..8) {
                    addView(
                        Button(context).apply {
                            text = "Sheet row $i"
                            isAllCaps = false
                            setOnClickListener {
                                rows++
                                update()
                            }
                        },
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowHeight),
                    )
                }
            },
        )
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        dialog.setOnDismissListener { closed("sheet") }
        dialog.show()
    }
}
