package io.github.noamcohen48.tap.fixture

import android.Manifest
import android.animation.ValueAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity

/**
 * A search field whose keyboard action is Search, clipboard copy and paste buttons, a toast
 * button, and the launch intent's typed extras, the camera permission's state and the
 * configuration the activity runs in (night mode, first locale, font scale, density, animators),
 * each shown in a status line so a test can assert what the app saw.
 */
class FormActivity : ComponentActivity() {
    private var toasts = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_form)
        val field = findViewById<EditText>(R.id.search_field)
        field.setOnEditorActionListener { view, actionId, _ ->
            // Only the Search action counts: Enter (IME_NULL) or another action does not.
            if (actionId != EditorInfo.IME_ACTION_SEARCH) return@setOnEditorActionListener false
            status(R.id.search_status, "Searched: ${view.text}")
            true
        }
        val clipboard = getSystemService(ClipboardManager::class.java)
        findViewById<Button>(R.id.copy_button).setOnClickListener {
            clipboard.setPrimaryClip(ClipData.newPlainText("fixture", field.text.toString()))
            status(R.id.clipboard_status, "Copied: ${field.text}")
        }
        findViewById<Button>(R.id.paste_button).setOnClickListener {
            val text = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)
            status(R.id.clipboard_status, "Pasted: ${text ?: "nothing"}")
        }
        findViewById<Button>(R.id.toast_button).setOnClickListener {
            toasts += 1
            Toast.makeText(this, "Saved $toasts", Toast.LENGTH_SHORT).show()
        }
        val extras = intent
        status(
            R.id.extras_status,
            "Extras: query=${extras.getStringExtra("query")} flag=${extras.getBooleanExtra("flag", false)} " +
                "count=${extras.getIntExtra("count", 0)} id=${extras.getLongExtra("id", 0)} ratio=${extras.getFloatExtra("ratio", 0f)}",
        )
    }

    override fun onResume() {
        super.onResume()
        val granted = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        status(R.id.camera_status, if (granted) "Camera: granted" else "Camera: denied")
        val config = resources.configuration
        val night = config.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        status(
            R.id.config_status,
            "Config: night=$night locale=${config.locales[0].toLanguageTag()} fontScale=${config.fontScale} " +
                "density=${config.densityDpi} animators=${ValueAnimator.areAnimatorsEnabled()}",
        )
    }

    private fun status(
        id: Int,
        text: String,
    ) {
        findViewById<TextView>(id).text = text
    }
}
