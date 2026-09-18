package com.company.tap.fixture

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock

class TapSynchronizationProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        return when (method) {
            "state" -> {
                val state = TapSynchronization.snapshot()
                Bundle().apply {
                    putBoolean("initialized", state.initialized)
                    putInt("processId", Process.myPid())
                    putString("processStartUuid", state.processStartUuid)
                    putString("sessionIdentity", state.sessionIdentity)
                    putLong("generation", state.generation)
                    putInt("busyCount", state.busyCount)
                    putLong("lastTransitionElapsedMs", state.lastTransitionElapsedMs)
                    putString("error", state.error)
                }
            }
            "scheduleFaultMutation" -> {
                val delayMs = requireNotNull(arg).toLong()
                require(delayMs in 1_000..60_000)
                val dueElapsedMs = SystemClock.elapsedRealtime() + delayMs
                Handler(Looper.getMainLooper()).postDelayed({ FaultTapCounter.increment() }, delayMs)
                Bundle().apply { putLong("dueElapsedMs", dueElapsedMs) }
            }
            else -> error("Unsupported method")
        }
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
