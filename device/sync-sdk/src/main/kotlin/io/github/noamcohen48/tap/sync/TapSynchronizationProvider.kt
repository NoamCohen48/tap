package io.github.noamcohen48.tap.sync

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Process

/**
 * Exposes [TapSynchronization] state to the driver over `ContentResolver.call`, guarded by the
 * signature permission declared in this module's manifest. Method `state` returns the bundle
 * contract the driver's `SyncProviderClient` validates field by field; nothing else is served.
 */
class TapSynchronizationProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        TapSynchronization.initialize()
        return true
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        require(method == "state") { "Unsupported method" }
        val state = TapSynchronization.snapshot()
        return Bundle().apply {
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
