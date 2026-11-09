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
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout

/**
 * A covering view inside the target's own window, for the driver's `OBSCURED` check: an open
 * `DrawerLayout` drawer. Its scrim is drawn by the layout over the content, and a touch on the
 * content closes the drawer instead of reaching it; the window list shows nothing above the
 * content. "Under drawer" sits at the right edge, where the scrim (not the drawer) covers it.
 * "Decorative" is a clickable view the app marks not important for accessibility itself, so the
 * check can be seen not to refuse it while the drawer is closed. Every click and the drawer's
 * state are counted on screen.
 */
class DrawerActivity : ComponentActivity() {
    private var under = 0
    private var decorative = 0
    private var item = 0
    private var open = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val drawer = DrawerLayout(this)
        val status = TextView(this).apply { id = R.id.drawer_status }
        fun update() {
            status.text = "Drawer: open=$open under=$under decorative=$decorative item=$item"
        }
        val content =
            FrameLayout(this).apply {
                addView(
                    LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(16), dp(16), dp(16), dp(16))
                        addView(
                            Button(context).apply {
                                id = R.id.drawer_open
                                text = "Open drawer"
                                isAllCaps = false
                                setOnClickListener { drawer.openDrawer(GravityCompat.START) }
                            },
                        )
                        addView(
                            Button(context).apply {
                                id = R.id.drawer_decorative
                                text = "Decorative"
                                isAllCaps = false
                                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                                setOnClickListener {
                                    decorative++
                                    update()
                                }
                            },
                        )
                        addView(status)
                    },
                    FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT),
                )
                addView(
                    Button(context).apply {
                        id = R.id.drawer_under
                        text = "Under drawer"
                        isAllCaps = false
                        setOnClickListener {
                            under++
                            update()
                        }
                    },
                    FrameLayout.LayoutParams(dp(96), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL or Gravity.END),
                )
            }
        drawer.addView(content, DrawerLayout.LayoutParams(DrawerLayout.LayoutParams.MATCH_PARENT, DrawerLayout.LayoutParams.MATCH_PARENT))
        val sheet =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.WHITE)
                setPadding(dp(16), dp(48), dp(16), dp(16))
                addView(
                    Button(context).apply {
                        id = R.id.drawer_close
                        text = "Close drawer"
                        isAllCaps = false
                        setOnClickListener { drawer.closeDrawers() }
                    },
                )
                addView(
                    Button(context).apply {
                        id = R.id.drawer_item
                        text = "Drawer item"
                        isAllCaps = false
                        setOnClickListener {
                            item++
                            update()
                        }
                    },
                )
            }
        // Narrow enough to leave scrim beside it on a 360dp-wide screen.
        drawer.addView(sheet, DrawerLayout.LayoutParams(dp(200), DrawerLayout.LayoutParams.MATCH_PARENT, Gravity.START))
        drawer.addDrawerListener(
            object : DrawerLayout.SimpleDrawerListener() {
                override fun onDrawerOpened(drawerView: View) {
                    open = true
                    update()
                }

                override fun onDrawerClosed(drawerView: View) {
                    open = false
                    update()
                }
            },
        )
        update()
        setContentView(drawer)
    }
}
