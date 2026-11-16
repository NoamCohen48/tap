package io.github.noamcohen48.tap.fixture

import android.os.Bundle
import androidx.activity.ComponentActivity

/** A short, fully exposed product collection with duplicate resource IDs and checkbox state. */
class CollectionActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_collection)
    }
}
