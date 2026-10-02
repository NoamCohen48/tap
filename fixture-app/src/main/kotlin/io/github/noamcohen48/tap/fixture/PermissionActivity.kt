package io.github.noamcohen48.tap.fixture

import android.Manifest
import android.content.pm.PackageManager
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Button
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Requests `CAMERA`, or fine and coarse location, on a tap and shows the answer, so a test can
 * drive the system permission dialog (a window outside the AUT; on API 31+ the location one
 * offers Precise / Approximate) or pre-grant the permission and see it reported. "Read location"
 * shows the next gps fix (`At <lat>, <lon>`, five decimals), for the mock-location tests. "Read
 * gallery" lists the photos and videos MediaStore has in `Pictures/Tap` and `Movies/Tap`
 * (`Gallery: a.png, b.mp4`, sorted), as a gallery app sees them, for the add-media tests.
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
        findViewById<Button>(R.id.read_location).setOnClickListener { readLocation() }
        findViewById<Button>(R.id.read_gallery).setOnClickListener { readGallery() }
    }

    private fun readGallery() {
        val shown = findViewById<TextView>(R.id.gallery_value)
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
        if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            shown.text = "Gallery not permitted"
            return
        }
        val names = sortedSetOf<String>()
        for (uri in listOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)) {
            @Suppress("DEPRECATION")
            val data = MediaStore.MediaColumns.DATA
            contentResolver
                .query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), "$data LIKE ? OR $data LIKE ?", arrayOf("%/Pictures/Tap/%", "%/Movies/Tap/%"), null)
                ?.use { cursor -> while (cursor.moveToNext()) names += cursor.getString(0) }
        }
        shown.text = if (names.isEmpty()) "Gallery empty" else "Gallery: " + names.joinToString(", ")
    }

    private fun readLocation() {
        val shown = findViewById<TextView>(R.id.location_value)
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            shown.text = "Location not permitted"
            return
        }
        shown.text = "Waiting for a fix"
        val manager = getSystemService(LocationManager::class.java)
        val listener =
            object : LocationListener {
                override fun onLocationChanged(location: android.location.Location) {
                    shown.text = String.format(java.util.Locale.ROOT, "At %.5f, %.5f", location.latitude, location.longitude)
                    manager.removeUpdates(this)
                }

                @Deprecated("Deprecated in Java")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

                override fun onProviderEnabled(provider: String) = Unit

                override fun onProviderDisabled(provider: String) = Unit
            }
        manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0L, 0f, listener, mainLooper)
    }
}
