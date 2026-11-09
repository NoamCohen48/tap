package io.github.noamcohen48.tap.fixture

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * Panels to touch outside of, Compose edition: a `ModalNavigationDrawer`, a `ModalBottomSheet` and
 * an `AlertDialog`. The status line (test tag `compose_outside_status`) counts clicks on the
 * screen under every panel (`under`: the whole content area is clickable) and on the sheet's row,
 * and lists each panel as it closes.
 */
class ComposeOutsidePanelActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val drawer = rememberDrawerState(DrawerValue.Closed)
            val scope = rememberCoroutineScope()
            var sheet by remember { mutableStateOf(false) }
            var rows by remember { mutableIntStateOf(0) }
            var under by remember { mutableIntStateOf(0) }
            var dialog by remember { mutableStateOf(false) }
            val closed = remember { mutableStateListOf<String>() }
            LaunchedEffect(drawer) {
                snapshotFlow { drawer.currentValue }.drop(1).collect { if (it == DrawerValue.Closed) closed += "drawer" }
            }
            ModalNavigationDrawer(
                drawerState = drawer,
                drawerContent = { ModalDrawerSheet(Modifier.width(240.dp)) { Text("Compose drawer body", Modifier.padding(24.dp)) } },
            ) {
                Box(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
                    // Behind the content, a separate node: a stray touch anywhere on the screen counts.
                    Box(Modifier.matchParentSize().clickable { under++ }.testTag("compose_outside_under"))
                    Column(Modifier.padding(16.dp)) {
                        Text("Compose: under=$under rows=$rows closed=${closed.joinToString(",")}", Modifier.testTag("compose_outside_status"))
                        Button(onClick = { scope.launch { drawer.open() } }) { Text("Open compose drawer") }
                        Button(onClick = { sheet = true }) { Text("Open compose sheet") }
                        Button(onClick = { dialog = true }) { Text("Open compose dialog") }
                    }
                }
            }
            if (dialog) {
                AlertDialog(
                    onDismissRequest = {
                        dialog = false
                        closed += "dialog"
                    },
                    title = { Text("Compose dialog title") },
                    text = { Text("Compose dialog body") },
                    confirmButton = { TextButton(onClick = { dialog = false }) { Text("OK") } },
                )
            }
            if (sheet) {
                ModalBottomSheet(onDismissRequest = {
                    sheet = false
                    closed += "sheet"
                }) {
                    Text("Compose sheet body", Modifier.padding(24.dp))
                    TextButton(onClick = { rows++ }, Modifier.padding(24.dp)) { Text("Compose sheet row") }
                }
            }
        }
    }
}
