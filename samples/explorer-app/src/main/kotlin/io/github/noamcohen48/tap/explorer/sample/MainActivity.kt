package io.github.noamcohen48.tap.explorer.sample

import android.app.Activity
import android.app.Dialog
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** A disposable, local-only AUT: eight observable states, all with explicit return controls. */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showHome()
    }

    private fun page(
        title: String,
        state: String,
    ): LinearLayout {
        val layout =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(24), dp(36), dp(24), dp(24))
            }
        layout.addView(label(title, size = 28f))
        layout.addView(label(state, R.id.state, 14f))
        return layout
    }

    private fun display(layout: LinearLayout) {
        setContentView(ScrollView(this).apply { addView(layout) })
    }

    private fun label(
        value: String,
        resource: Int = View.NO_ID,
        size: Float = 18f,
    ) = TextView(this).apply {
        id = resource
        text = value
        textSize = size
        setPadding(0, dp(8), 0, dp(12))
    }

    private fun LinearLayout.button(
        value: String,
        resource: Int,
        action: () -> Unit,
    ) {
        addView(
            Button(this@MainActivity).apply {
                id = resource
                text = value
                isAllCaps = false
                setOnClickListener { action() }
            },
        )
    }

    private fun showHome() {
        val layout = page("App Explorer sample", "home")
        layout.addView(label("A safe playground for discovering navigation, forms and dialogs."))
        layout.button("Create a profile", R.id.open_profile) { showProfile() }
        layout.button("Preferences", R.id.open_preferences) { showPreferences() }
        layout.button("About this sample", R.id.open_about) { showAbout() }
        display(layout)
    }

    private fun showProfile() {
        val layout = page("Create a profile", "profile-empty")
        val state = layout.findViewById<TextView>(R.id.state)
        val validation = label("", R.id.validation)
        val input =
            EditText(this).apply {
                id = R.id.name_input
                hint = "Your sample name"
                inputType = InputType.TYPE_CLASS_TEXT
                // Accessibility SetText still works; no system keyboard is needed for this demo.
                showSoftInputOnFocus = false
                addTextChangedListener(
                    object : TextWatcher {
                        override fun beforeTextChanged(
                            s: CharSequence?,
                            start: Int,
                            count: Int,
                            after: Int,
                        ) = Unit

                        override fun onTextChanged(
                            s: CharSequence?,
                            start: Int,
                            before: Int,
                            count: Int,
                        ) = Unit

                        override fun afterTextChanged(value: Editable?) {
                            state.text = if (value.toString().isBlank()) "profile-empty" else "profile-ready"
                            validation.text = ""
                        }
                    },
                )
            }
        layout.addView(input)
        layout.addView(validation)
        layout.button("Continue", R.id.submit) {
            val name = input.text.toString().trim()
            if (name.isEmpty()) {
                validation.text = "Please enter a name."
                state.text = "profile-error"
            } else {
                showWelcome(name)
            }
        }
        layout.button("Return home", R.id.home) { showHome() }
        display(layout)
    }

    private fun showWelcome(name: String) {
        val layout = page("Profile created", "welcome")
        layout.addView(label("Hello, $name!", R.id.greeting))
        layout.addView(label("This profile is fictional and is never saved or sent anywhere."))
        layout.button("Return home", R.id.home) { showHome() }
        display(layout)
    }

    private fun showPreferences() {
        // Deliberately resets on every entry: Home has no hidden persistent option state.
        var enabled = false
        val layout = page("Preferences", "preferences-off")
        val state = layout.findViewById<TextView>(R.id.state)
        val value = label("Option: off", R.id.option_value)
        layout.addView(label("This temporary option resets when you leave this page."))
        layout.addView(value)
        layout.button("Toggle option", R.id.toggle_option) {
            enabled = !enabled
            state.text = if (enabled) "preferences-on" else "preferences-off"
            value.text = if (enabled) "Option: on" else "Option: off"
        }
        layout.button("Return home", R.id.home) { showHome() }
        display(layout)
    }

    private fun showAbout() {
        val dialog = Dialog(this)
        val layout = page("About this sample", "about")
        layout.addView(label("No permissions. No network. No real account. No saved data."))
        layout.button("Close and return home", R.id.close_about) { dialog.dismiss() }
        dialog.setContentView(layout)
        dialog.setCancelable(false)
        dialog.show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
