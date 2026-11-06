package io.github.noamcohen48.tap.fixture

import android.app.Activity
import android.app.Service
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.os.Bundle
import android.os.IBinder
import android.system.Os
import android.widget.TextView

/** Keeps a WAL connection open so force-stop, rather than a clean close/checkpoint, ends it. */
class PrivateDataActivity : Activity() {
    private lateinit var database: SQLiteDatabase

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        database = openOrCreateDatabase("tap-debug.db", MODE_PRIVATE, null)
        database.enableWriteAheadLogging()
        database.rawQuery("PRAGMA wal_autocheckpoint=0", null).use { it.moveToFirst() }
        if (intent.getBooleanExtra("seed", false)) {
            database.execSQL("CREATE TABLE IF NOT EXISTS state (value INTEGER NOT NULL)")
            database.execSQL("DELETE FROM state")
            database.execSQL("INSERT INTO state VALUES (41)")
            filesDir.resolve("binary.bin").writeBytes(ByteArray(1024) { it.toByte() })
            cacheDir.resolve("link-target").writeText("must not follow")
            filesDir.resolve("link").delete()
            Os.symlink(cacheDir.resolve("link-target").path, filesDir.resolve("link").path)
            startService(Intent(this, PrivateDataProcessService::class.java))
        }
        val value =
            try {
                database.rawQuery("SELECT value FROM state", null).use { cursor ->
                    if (cursor.moveToFirst()) cursor.getInt(0).toString() else "empty"
                }
            } catch (_: SQLiteException) {
                "missing"
            }
        setContentView(
            TextView(this).apply {
                id = R.id.private_data_value
                text = "database value: $value"
                textSize = 24f
            },
        )
        // Deliberately do not close on pause/destroy: the test must prove WAL survival at kill.
    }
}

/** A second process under the same UID; private-data access must refuse it too. */
class PrivateDataProcessService : Service() {
    override fun onBind(intent: Intent): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int = START_NOT_STICKY
}
