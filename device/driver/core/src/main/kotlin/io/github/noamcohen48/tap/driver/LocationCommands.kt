package io.github.noamcohen48.tap.driver

import android.app.Instrumentation
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.Build
import android.os.SystemClock
import io.github.noamcohen48.tap.api.v1.ErrorCode
import io.github.noamcohen48.tap.api.v1.SetLocation
import io.github.noamcohen48.tap.driver.engine.CommandContext
import io.github.noamcohen48.tap.protocol.CommandFailure
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Mock location (`set_location`), as Maestro's driver does it: the gps and network providers (and
 * fused, API 31+) are replaced by LocationManager test providers this process owns, and the fix
 * is sent to all of them again every [RESEND_MS], fresh each time, so an app that starts
 * listening later still gets one. The test providers outlive this process and the app-op, so
 * the host removes them on detach (the driver app's `MockLocationReceiver`); a provider already a
 * test provider (left by a crashed run, or removed under this process) is replaced. Whether the
 * app reads the fix is the test's business: nothing here checks it.
 */
internal class LocationCommands(
    private val instrumentation: Instrumentation,
) {
    private val resender =
        Executors.newSingleThreadScheduledExecutor { runnable -> Thread(runnable, "tap-mock-location").apply { isDaemon = true } }
    private val providers = mutableSetOf<String>()
    private var resending: ScheduledFuture<*>? = null

    @Synchronized
    fun set(
        context: CommandContext,
        command: SetLocation,
    ) {
        context.checkpoint()
        val manager = manager()
        context.markMutationStarted()
        try {
            try {
                addMissing(manager)
                emit(manager, command)
            } catch (_: IllegalArgumentException) {
                // The host removed this process's test providers on a detach since: add them again.
                providers.clear()
                addMissing(manager)
                emit(manager, command)
            }
        } catch (denied: SecurityException) {
            throw CommandFailure(ErrorCode.ERR_ACTION_REJECTED, message = "Android refused the mock location (the driver lacks the mock-location app-op): ${denied.message}")
        } catch (refused: IllegalArgumentException) {
            throw CommandFailure(ErrorCode.ERR_ACTION_REJECTED, message = "Android refused the mock location: ${refused.message}")
        }
        resending?.cancel(false)
        resending =
            resender.scheduleWithFixedDelay(
                { runCatching { synchronized(this) { emit(manager, command) } } },
                RESEND_MS,
                RESEND_MS,
                TimeUnit.MILLISECONDS,
            )
    }

    private fun addMissing(manager: LocationManager) {
        for (name in PROVIDERS) {
            if (name in providers) continue
            // A test provider left by another run makes addTestProvider throw on older APIs.
            try {
                manager.removeTestProvider(name)
            } catch (_: IllegalArgumentException) {
                // Not a test provider: the usual case.
            }
            add(manager, name)
        }
    }

    private fun add(
        manager: LocationManager,
        name: String,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val properties =
                ProviderProperties
                    .Builder()
                    .setHasAltitudeSupport(true)
                    .setHasSpeedSupport(true)
                    .setHasBearingSupport(true)
                    .setPowerUsage(ProviderProperties.POWER_USAGE_LOW)
                    .setAccuracy(ProviderProperties.ACCURACY_FINE)
                    .build()
            manager.addTestProvider(name, properties)
        } else {
            @Suppress("DEPRECATION")
            manager.addTestProvider(name, false, false, false, false, true, true, true, ProviderProperties.POWER_USAGE_LOW, ProviderProperties.ACCURACY_FINE)
        }
        manager.setTestProviderEnabled(name, true)
        providers += name
    }

    private fun emit(
        manager: LocationManager,
        command: SetLocation,
    ) {
        for (name in providers) {
            val fix =
                Location(name).apply {
                    latitude = command.latitude
                    longitude = command.longitude
                    accuracy = if (command.hasAccuracyM()) command.accuracyM else DEFAULT_ACCURACY_M
                    if (command.hasAltitudeM()) altitude = command.altitudeM
                    time = System.currentTimeMillis()
                    elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                }
            manager.setTestProviderLocation(name, fix)
        }
    }

    // The driver app's own context: the mock-location app-op is granted to its package.
    private fun manager() = instrumentation.targetContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private companion object {
        const val RESEND_MS = 1_000L
        const val DEFAULT_ACCURACY_M = 5f
        val PROVIDERS =
            buildList {
                add(LocationManager.GPS_PROVIDER)
                add(LocationManager.NETWORK_PROVIDER)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
            }
    }
}
