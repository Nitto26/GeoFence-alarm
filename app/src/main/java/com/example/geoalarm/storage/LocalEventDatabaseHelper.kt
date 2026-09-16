package com.example.geoalarm.storage

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LocalEventRecord(
    val id: Long,
    val jobId: String?,
    val eventType: String,
    val latitude: Double,
    val longitude: Double,
    val timestamp: String,
    val createdAtEpoch: Long,
    val isSynced: Boolean,
    val syncedAt: String?
)

class LocalEventDatabaseHelper private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val TAG = "LocalEventDb"
        private const val DATABASE_NAME = "GeoAlarmLocalCache.db"
        private const val DATABASE_VERSION = 1

        const val TABLE_EVENTS = "local_events"
        const val COL_ID = "id"
        const val COL_JOB_ID = "job_id"
        const val COL_EVENT_TYPE = "event_type"
        const val COL_LATITUDE = "latitude"
        const val COL_LONGITUDE = "longitude"
        const val COL_TIMESTAMP = "timestamp"
        const val COL_CREATED_AT_EPOCH = "created_at_epoch"
        const val COL_IS_SYNCED = "is_synced"
        const val COL_SYNCED_AT = "synced_at"

        // 3 Days in milliseconds: 3 * 24 * 60 * 60 * 1000 = 259,200,000 ms
        const val THREE_DAYS_MILLIS = 3 * 24 * 60 * 60 * 1000L

        @Volatile
        private var instance: LocalEventDatabaseHelper? = null

        fun getInstance(context: Context): LocalEventDatabaseHelper {
            return instance ?: synchronized(this) {
                instance ?: LocalEventDatabaseHelper(context).also { instance = it }
            }
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        val createTableQuery = """
            CREATE TABLE $TABLE_EVENTS (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_JOB_ID TEXT,
                $COL_EVENT_TYPE TEXT NOT NULL,
                $COL_LATITUDE REAL NOT NULL,
                $COL_LONGITUDE REAL NOT NULL,
                $COL_TIMESTAMP TEXT NOT NULL,
                $COL_CREATED_AT_EPOCH INTEGER NOT NULL,
                $COL_IS_SYNCED INTEGER NOT NULL DEFAULT 0,
                $COL_SYNCED_AT TEXT
            )
        """.trimIndent()
        db.execSQL(createTableQuery)
        db.execSQL("CREATE INDEX idx_is_synced ON $TABLE_EVENTS ($COL_IS_SYNCED)")
        db.execSQL("CREATE INDEX idx_created_at ON $TABLE_EVENTS ($COL_CREATED_AT_EPOCH)")
        Log.d(TAG, "Initialized Local SQLite Event Database table.")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_EVENTS")
        onCreate(db)
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        try {
            db.execSQL("DELETE FROM $TABLE_EVENTS WHERE (abs($COL_LATITUDE - 10.5276) < 0.001 AND abs($COL_LONGITUDE - 76.2144) < 0.001) OR ($COL_LATITUDE = 0.0 AND $COL_LONGITUDE = 0.0)")
        } catch (_: Exception) {}
    }

    /**
     * Inserts an event record and automatically purges events older than 3 days.
     */
    fun insertEvent(
        eventType: String,
        jobId: String? = null,
        latitude: Double = 0.0,
        longitude: Double = 0.0,
        timestamp: String,
        isSynced: Boolean = false
    ): Long {
        val db = writableDatabase
        val now = System.currentTimeMillis()

        // 1. Auto-purge records older than 3 days
        purgeExpiredRecords(db, now)

        // 2. Insert new record
        val values = ContentValues().apply {
            put(COL_JOB_ID, jobId)
            put(COL_EVENT_TYPE, eventType)
            put(COL_LATITUDE, latitude)
            put(COL_LONGITUDE, longitude)
            put(COL_TIMESTAMP, timestamp)
            put(COL_CREATED_AT_EPOCH, now)
            put(COL_IS_SYNCED, if (isSynced) 1 else 0)
            put(COL_SYNCED_AT, if (isSynced) getFormattedDate(now) else null)
        }

        val id = db.insert(TABLE_EVENTS, null, values)
        Log.d(TAG, "Stored local event id=$id ($eventType, synced=$isSynced)")
        return id
    }

    /**
     * Retrieves up to [limit] unsynced events (is_synced = 0)
     */
    fun getUnsyncedEvents(limit: Int = 100): List<LocalEventRecord> {
        val list = mutableListOf<LocalEventRecord>()
        val db = readableDatabase
        val query = """
            SELECT * FROM $TABLE_EVENTS 
            WHERE $COL_IS_SYNCED = 0 
            ORDER BY $COL_ID ASC 
            LIMIT $limit
        """.trimIndent()

        val cursor = db.rawQuery(query, null)
        cursor.use {
            while (it.moveToNext()) {
                list.add(cursorToRecord(it))
            }
        }
        return list
    }

    /**
     * Marks a list of record IDs as successfully synced
     */
    fun markEventsAsSynced(ids: List<Long>) {
        if (ids.isEmpty()) return
        val db = writableDatabase
        val nowStr = getFormattedDate(System.currentTimeMillis())

        val inClause = ids.joinToString(",")
        val query = "UPDATE $TABLE_EVENTS SET $COL_IS_SYNCED = 1, $COL_SYNCED_AT = '$nowStr' WHERE $COL_ID IN ($inClause)"
        db.execSQL(query)
        Log.d(TAG, "Marked ${ids.size} events as synced in local DB.")
    }

    /**
     * Returns statistics: Triple(Total Records, Synced Count, Unsynced/Pending Count)
     */
    fun getStats(): Triple<Int, Int, Int> {
        val db = readableDatabase
        var total = 0
        var synced = 0
        var unsynced = 0

        val cursor = db.rawQuery("SELECT $COL_IS_SYNCED, COUNT(*) FROM $TABLE_EVENTS GROUP BY $COL_IS_SYNCED", null)
        cursor.use {
            while (it.moveToNext()) {
                val isSyn = it.getInt(0)
                val count = it.getInt(1)
                total += count
                if (isSyn == 1) synced += count else unsynced += count
            }
        }
        return Triple(total, synced, unsynced)
    }

    /**
     * Retrieves all events from the past 3 days for the secret audit log dialog
     */
    fun getAllRecordsForAudit(limit: Int = 150): List<LocalEventRecord> {
        val list = mutableListOf<LocalEventRecord>()
        val db = readableDatabase
        val query = """
            SELECT * FROM $TABLE_EVENTS 
            ORDER BY $COL_ID DESC 
            LIMIT $limit
        """.trimIndent()

        val cursor = db.rawQuery(query, null)
        cursor.use {
            while (it.moveToNext()) {
                list.add(cursorToRecord(it))
            }
        }
        return list
    }

    /**
     * Deletes records older than 3 days
     */
    fun purgeExpiredRecords(db: SQLiteDatabase = writableDatabase, now: Long = System.currentTimeMillis()): Int {
        val cutoff = now - THREE_DAYS_MILLIS
        val deletedRows = db.delete(TABLE_EVENTS, "$COL_CREATED_AT_EPOCH < ?", arrayOf(cutoff.toString()))
        if (deletedRows > 0) {
            Log.d(TAG, "Auto-purged $deletedRows expired event records (> 3 days old).")
        }
        return deletedRows
    }

    private fun cursorToRecord(c: android.database.Cursor): LocalEventRecord {
        return LocalEventRecord(
            id = c.getLong(c.getColumnIndexOrThrow(COL_ID)),
            jobId = c.getString(c.getColumnIndexOrThrow(COL_JOB_ID)),
            eventType = c.getString(c.getColumnIndexOrThrow(COL_EVENT_TYPE)),
            latitude = c.getDouble(c.getColumnIndexOrThrow(COL_LATITUDE)),
            longitude = c.getDouble(c.getColumnIndexOrThrow(COL_LONGITUDE)),
            timestamp = c.getString(c.getColumnIndexOrThrow(COL_TIMESTAMP)),
            createdAtEpoch = c.getLong(c.getColumnIndexOrThrow(COL_CREATED_AT_EPOCH)),
            isSynced = c.getInt(c.getColumnIndexOrThrow(COL_IS_SYNCED)) == 1,
            syncedAt = c.getString(c.getColumnIndexOrThrow(COL_SYNCED_AT))
        )
    }

    private fun getFormattedDate(epoch: Long): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(epoch))
    }
}
