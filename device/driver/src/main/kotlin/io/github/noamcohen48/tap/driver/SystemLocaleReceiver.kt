package io.github.noamcohen48.tap.driver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.LocaleList

/**
 * Sets the device-wide locale list, as Settings' language picker does: Android has no shell
 * command for it, so the host sends `am broadcast -n <driver>/.SystemLocaleReceiver --es locales
 * <tags>` (comma-separated BCP 47 tags) after `pm grant <driver> CHANGE_CONFIGURATION` and
 * `appops set <driver> WRITE_SETTINGS allow` (Android checks both). Only a
 * sender holding CHANGE_CONFIGURATION (the shell, the system) may send it (manifest), and it
 * runs without the instrumentation, so the host can restore a locale while no driver is running.
 *
 * The result is the broadcast's: [RESULT_SET], or [RESULT_FAILED] with the reason as its data.
 * Whether the device took the locale is the host's read-back, not this receiver's claim.
 */
class SystemLocaleReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val tags = intent.getStringExtra(EXTRA_LOCALES)
        if (tags.isNullOrBlank()) {
            setResult(RESULT_FAILED, "No locales given", null)
            return
        }
        try {
            // ActivityManager.getService() / IActivityManager: the calls LocalePicker makes. Looked
            // up on the interface: the hidden-API lists allow it there and deny it on the proxy.
            val activityManager = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null)
            val service = Class.forName("android.app.IActivityManager")
            val configuration = Configuration(service.getMethod("getConfiguration").invoke(activityManager) as Configuration)
            configuration.setLocales(LocaleList.forLanguageTags(tags))
            Configuration::class.java.getField("userSetLocale").setBoolean(configuration, true)
            service.getMethod("updatePersistentConfiguration", Configuration::class.java).invoke(activityManager, configuration)
            setResult(RESULT_SET, tags, null)
        } catch (error: Throwable) {
            val cause = (error as? java.lang.reflect.InvocationTargetException)?.targetException ?: error
            setResult(RESULT_FAILED, "${cause.javaClass.name}: ${cause.message}", null)
        }
    }

    companion object {
        const val EXTRA_LOCALES = "locales"
        const val RESULT_SET = 1
        const val RESULT_FAILED = 2
    }
}
