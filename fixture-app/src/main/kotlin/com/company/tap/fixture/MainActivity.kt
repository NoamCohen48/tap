package com.company.tap.fixture

import android.os.Bundle
import com.company.tap.sync.TapSynchronization
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button as ComposeButton
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.platform.testTag

class MainActivity : ComponentActivity() {
    private var composeStatus by mutableStateOf("Compose idle")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val viewStatus = findViewById<TextView>(R.id.view_status)
        findViewById<Button>(R.id.view_button).setOnClickListener {
            viewStatus.text = "View tapped"
        }
        var ambiguousLeft = 0
        var ambiguousRight = 0
        val ambiguousStatus = findViewById<TextView>(R.id.ambiguous_status)
        fun updateAmbiguousStatus() {
            ambiguousStatus.text = "Ambiguous taps: left=$ambiguousLeft right=$ambiguousRight"
        }
        findViewById<Button>(R.id.ambiguous_button_left).setOnClickListener {
            ambiguousLeft++
            updateAmbiguousStatus()
        }
        findViewById<Button>(R.id.ambiguous_button_right).setOnClickListener {
            ambiguousRight++
            updateAmbiguousStatus()
        }
        val faultStatus = findViewById<TextView>(R.id.fault_status)
        faultStatus.text = "Fault taps: ${FaultTapCounter.value()}"
        findViewById<Button>(R.id.fault_button).setOnClickListener {
            faultStatus.text = "Fault taps: ${FaultTapCounter.increment()}"
        }
        findViewById<Button>(R.id.sync_button).setOnClickListener {
            val busy = TapSynchronization.busy()
            viewStatus.text = "Synchronized work running"
            Handler(Looper.getMainLooper()).postDelayed({
                busy.close()
                viewStatus.text = "Synchronized work complete"
            }, 5_000)
        }
        val keyboardStatus = findViewById<TextView>(R.id.keyboard_status)
        findViewById<EditText>(R.id.keyboard_input).setOnKeyListener { _, _, _ ->
            keyboardStatus.text = "Keyboard event received"
            false
        }

        findViewById<ComposeView>(R.id.compose_content).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                Column(
                    Modifier
                        .fillMaxSize()
                        .semantics { testTagsAsResourceId = true }
                        .padding(16.dp)
                ) {
                    ComposeButton(
                        onClick = { composeStatus = "Compose tapped" },
                        modifier = Modifier.testTag("composeButton"),
                    ) {
                        Text("Tap Compose")
                    }
                    Text(composeStatus, Modifier.testTag("composeStatus"))
                    LazyColumn(Modifier.testTag("composeList")) {
                        items((1..100).toList()) { item ->
                            Text("Item $item", Modifier.testTag("item-$item"))
                        }
                    }
                }
            }
        }
    }
}
