package io.github.noamcohen48.tap.fixture

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.activity.ComponentActivity

/** The deep-link target (`tapfixture://link/...`): shows the URI it was opened with. */
class LinkActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_link)
        show(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        show(intent)
    }

    private fun show(intent: Intent) {
        findViewById<TextView>(R.id.link_status).text = "Link: ${intent.dataString}"
    }
}
