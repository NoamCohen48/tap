package io.github.noamcohen48.tap.fixture

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.ListView
import androidx.activity.ComponentActivity

class ViewListActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_view_list)
        findViewById<ListView>(R.id.view_list).adapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_1,
            (1..100).map { "View item $it" },
        )
    }
}
