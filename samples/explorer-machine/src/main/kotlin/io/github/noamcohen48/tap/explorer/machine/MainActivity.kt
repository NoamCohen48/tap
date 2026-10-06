package io.github.noamcohen48.tap.explorer.machine

import android.app.Activity
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Fictional, memory-only wizard. Ordinary UI exposes business values, never an oracle state ID. */
class MainActivity : Activity() {
    private var page = "home"
    private var name = ""
    private var pro = false
    private var accepted = false
    private var error = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        show()
    }

    private fun reset() {
        name = ""
        pro = false
        accepted = false
        error = ""
    }

    private fun show() {
        val layout =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(24), dp(24), dp(24), dp(24))
            }

        fun label(
            value: String,
            resource: Int,
            size: Float = 18f,
        ): TextView =
            TextView(this).apply {
                id = resource
                text = value
                textSize = size
                setPadding(0, dp(6), 0, dp(8))
                layout.addView(this)
            }

        fun button(
            value: String,
            resource: Int,
            action: () -> Unit,
        ) {
            layout.addView(
                Button(this).apply {
                    id = resource
                    text = value
                    isAllCaps = false
                    setOnClickListener {
                        action()
                        show()
                    }
                },
            )
        }

        fun home(value: String = "Cancel request") =
            button(value, R.id.home) {
                reset()
                page = "home"
            }
        val title =
            when (page) {
                "home" -> "Local requests"
                "about" -> "About requests"
                "identity" -> "Your name"
                "options" -> "Choose a plan"
                "explain" -> "Plan details"
                "review" -> "Review request"
                else -> "Request saved"
            }
        label(title, R.id.title, 26f)
        when (page) {
            "home" -> {
                button("New request", R.id.wizard) {
                    reset()
                    page = "identity"
                }
                button("About", R.id.about) { page = "about" }
            }

            "about" -> {
                label("Fictional requests. No network, permissions or saved files.", R.id.summary)
                button("Close", R.id.close) { page = "home" }
            }

            "identity" -> {
                val input =
                    EditText(this).apply {
                        id = R.id.name
                        hint = "Fictional name"
                        inputType = InputType.TYPE_CLASS_TEXT
                        showSoftInputOnFocus = false
                        setText(name)
                    }
                layout.addView(input)
                val validation = label(error, R.id.validation)
                label("Plan: ${if (pro) "Pro" else "Basic"}; terms: $accepted", R.id.summary)
                input.addTextChangedListener(
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
                            name = value.toString()
                            error = ""
                            validation.text = ""
                        }
                    },
                )
                button("Continue", R.id.next) {
                    if (name.isEmpty()) {
                        error = "A name is required."
                    } else {
                        page = "options"
                        error = ""
                    }
                }
                home()
            }

            "options", "explain" -> {
                label("Name: $name", R.id.summary)
                layout.addView(
                    CheckBox(this).apply {
                        id = R.id.tier
                        text = "Pro plan"
                        isChecked = pro
                        setOnClickListener {
                            pro = isChecked
                            this@MainActivity.error = ""
                            show()
                        }
                    },
                )
                layout.addView(
                    CheckBox(this).apply {
                        id = R.id.terms
                        text = "Accept fictional terms"
                        isChecked = accepted
                        setOnClickListener {
                            accepted = isChecked
                            this@MainActivity.error = ""
                            show()
                        }
                    },
                )
                label(error, R.id.validation)
                if (page == "options") {
                    button("Review", R.id.next) {
                        if (pro && !accepted) {
                            error = "Pro requires accepting the fictional terms."
                        } else {
                            page = "review"
                            error = ""
                        }
                    }
                    button("Plan details", R.id.explain) { page = "explain" }
                    button("Edit name", R.id.edit) {
                        page = "identity"
                        error = ""
                    }
                    home()
                } else {
                    button("Close details", R.id.close) { page = "options" }
                }
            }

            else -> {
                label("Name: $name; plan: ${if (pro) "Pro" else "Basic"}; terms: $accepted", R.id.summary)
                if (page == "review") {
                    button("Save fictional request", R.id.confirm) { page = "receipt" }
                    button("Edit name", R.id.edit) {
                        page = "identity"
                        error = ""
                    }
                }
                home("Return home")
            }
        }
        setContentView(ScrollView(this).apply { addView(layout) })
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
