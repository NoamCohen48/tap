package io.github.noamcohen48.tap.fixture

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * `DrawerActivity`'s Compose counterpart: a `ModalNavigationDrawer`, which leaves the content
 * out of the accessibility tree while the drawer is open, so a lookup of "Under drawer" is
 * `NOT_FOUND` rather than `OBSCURED`. Its clicks are counted on screen.
 */
class ComposeDrawerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val drawer = rememberDrawerState(DrawerValue.Closed)
            val scope = rememberCoroutineScope()
            var under by remember { mutableIntStateOf(0) }
            ModalNavigationDrawer(
                drawerState = drawer,
                modifier = Modifier.semantics { testTagsAsResourceId = true },
                drawerContent = {
                    ModalDrawerSheet(Modifier.width(200.dp)) {
                        Text("Compose drawer item", Modifier.padding(24.dp).testTag("composeDrawerItem"))
                        Button(onClick = { scope.launch { drawer.close() } }, Modifier.padding(16.dp).testTag("composeDrawerClose")) { Text("Close") }
                    }
                },
            ) {
                Box(Modifier.fillMaxSize()) {
                    Column(Modifier.align(Alignment.TopStart).padding(16.dp)) {
                        Button(onClick = { scope.launch { drawer.open() } }, Modifier.testTag("composeDrawerOpen")) { Text("Open drawer") }
                        Text("Compose drawer: under=$under", Modifier.testTag("composeDrawerStatus"))
                    }
                    Button(onClick = { under++ }, Modifier.align(Alignment.CenterEnd).width(96.dp).testTag("composeDrawerUnder")) {
                        Text("Under drawer")
                    }
                }
            }
        }
    }
}
