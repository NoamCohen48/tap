package io.github.noamcohen48.tap.fixture

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp

/** `InWindowCoverActivity`'s Compose counterpart: a later sibling in a `Box` is drawn on top. */
class ComposeInWindowCoverActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var target by remember { mutableIntStateOf(0) }
            var cover by remember { mutableIntStateOf(0) }
            var badged by remember { mutableIntStateOf(0) }
            Column(Modifier.padding(16.dp).semantics { testTagsAsResourceId = true }) {
                Text("Compose covers: target=$target cover=$cover badged=$badged", Modifier.testTag("composeCoverStatus"))
                Box(Modifier.fillMaxWidth().height(120.dp)) {
                    Button(onClick = { target++ }, Modifier.fillMaxWidth().height(120.dp).testTag("composeCoverTarget")) { Text("Compose covered target") }
                    Button(onClick = { cover++ }, Modifier.size(140.dp, 56.dp).align(Alignment.Center).testTag("composeCoverButton")) { Text("Compose cover") }
                }
                Box(Modifier.fillMaxWidth().height(120.dp)) {
                    Button(onClick = { badged++ }, Modifier.fillMaxWidth().height(120.dp).testTag("composeBadgeTarget")) { Text("Compose badged target") }
                    Box(Modifier.size(140.dp, 56.dp).align(Alignment.Center).background(Color(0xFFFFD278))) { Text("Compose badge", Modifier.align(Alignment.Center)) }
                }
            }
        }
    }
}
