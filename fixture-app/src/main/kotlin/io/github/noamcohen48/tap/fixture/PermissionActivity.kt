package io.github.noamcohen48.tap.fixture

import android.Manifest
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Requests `CAMERA` on a tap and shows the answer, so a test can drive the system permission
 * dialog (a window outside the AUT) or pre-grant the permission and see it reported.
 */
class PermissionActivity : ComponentActivity() {
    private val requestCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        findViewById<TextView>(R.id.permission_status).text =
            if (granted) "Camera granted" else "Camera denied"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_permission)
        findViewById<Button>(R.id.request_camera_permission).setOnClickListener {
            requestCamera.launch(Manifest.permission.CAMERA)
        }
    }
}
