package io.github.noamcohen48.tap.fixture

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import androidx.activity.ComponentActivity

/** A stable screen for diagnostic dump parity and hinted/invalid field snapshots. */
class FieldStateActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_field_state)
        val field = findViewById<EditText>(R.id.field_state_input)
        findViewById<Button>(R.id.validate_field).setOnClickListener {
            field.error = if (field.text.isEmpty()) "Cannot be blank" else null
        }
    }
}
