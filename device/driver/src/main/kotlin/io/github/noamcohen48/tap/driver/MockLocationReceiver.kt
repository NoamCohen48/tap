package io.github.noamcohen48.tap.driver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.LocationManager

/**
 * Removes LocationManager test providers, ending a mock location: they outlive the driver
 * instrumentation that added them and the mock-location app-op (seen on API 29), so detach
 * removes them here. The host sends `am broadcast -n <driver>/.MockLocationReceiver --es remove
 * gps,network` after `appops set <driver> android:mock_location allow` (without the op Android
 * ignores the call). Only a sender holding CHANGE_CONFIGURATION (the shell, the system) may send
 * it (manifest), and it runs without the instrumentation, so it works while no driver runs.
 *
 * A name that is not a test provider is skipped. The result is [RESULT_REMOVED], or
 * [RESULT_FAILED] with the reason as its data; whether the providers are gone is the host's
 * read-back (`dumpsys location`), not this receiver's claim.
 */
class MockLocationReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val names = intent.getStringExtra(EXTRA_REMOVE)?.split(',')?.filter(String::isNotBlank).orEmpty()
        if (names.isEmpty()) {
            setResult(RESULT_FAILED, "No providers given", null)
            return
        }
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        try {
            for (name in names) {
                try {
                    manager.removeTestProvider(name)
                } catch (_: IllegalArgumentException) {
                    // Not a test provider (any more).
                }
            }
            setResult(RESULT_REMOVED, names.joinToString(","), null)
        } catch (error: SecurityException) {
            setResult(RESULT_FAILED, "${error.javaClass.name}: ${error.message}", null)
        }
    }

    companion object {
        const val EXTRA_REMOVE = "remove"
        const val RESULT_REMOVED = 1
        const val RESULT_FAILED = 2
    }
}
