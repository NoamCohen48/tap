package io.github.noamcohen48.tap.fixture

import android.Manifest
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Requests `CAMERA`, or fine and coarse location, on a tap and shows the answer, so a test can
 * drive the system permission dialog (a window outside the AUT; on API 31+ the location one
 * offers Precise / Approximate) or pre-grant the permission and see it reported.
 */
class PermissionActivity : ComponentActivity() {
    private val requestCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        findViewById<TextView>(R.id.permission_status).text =
            if (granted) "Camera granted" else "Camera denied"
    }

    private val requestLocation =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            findViewById<TextView>(R.id.location_permission_status).text =
                when {
                    granted[Manifest.permission.ACCESS_FINE_LOCATION] == true -> "Location precise"
                    granted[Manifest.permission.ACCESS_COARSE_LOCATION] == true -> "Location approximate"
                    else -> "Location denied"
                }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_permission)
        findViewById<Button>(R.id.request_camera_permission).setOnClickListener {
            requestCamera.launch(Manifest.permission.CAMERA)
        }
        findViewById<Button>(R.id.request_location_permission).setOnClickListener {
            requestLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }
}
