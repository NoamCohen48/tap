package com.company.tap.fixture

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Fixture-only hook the driver's late-mutation fault uses to schedule a delayed tap-counter
 * increment inside the AUT process. Kept out of the synchronization SDK: real apps never need
 * it. Guarded by the same signature permission as synchronization state.
 */
class FixtureFaultProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        require(method == "scheduleFaultMutation") { "Unsupported method" }
        val delayMs = requireNotNull(arg).toLong()
        require(delayMs in 1_000..60_000)
        val dueElapsedMs = SystemClock.elapsedRealtime() + delayMs
        Handler(Looper.getMainLooper()).postDelayed({ FaultTapCounter.increment() }, delayMs)
        return Bundle().apply { putLong("dueElapsedMs", dueElapsedMs) }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}
