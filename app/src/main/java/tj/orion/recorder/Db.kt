package tj.orion.recorder

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.Closeable

/**
 * Plain-framework local store (no Room / no AndroidX). Holds capture entries
 * until SyncClient pushes them to Supabase; synced rows are deleted locally.
 */
class Db(context: Context) : SQLiteOpenHelper(context.applicationContext, NAME, null, VERSION), Closeable {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE entries (
                id            INTEGER PRIMARY KEY AUTOINCREMENT,
                client_id     TEXT NOT NULL UNIQUE,
                captured_at   INTEGER NOT NULL,
                type          TEXT NOT NULL,
                source        TEXT NOT NULL,
                text          TEXT,
                lang          TEXT NOT NULL DEFAULT 'ru',
                tags          TEXT NOT NULL DEFAULT '',
                sync_state    TEXT NOT NULL DEFAULT 'pending'
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_entries_captured ON entries(captured_at DESC)")
        db.execSQL("CREATE INDEX idx_entries_sync ON entries(sync_state)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    fun insert(e: Entry): Long {
        val cv = ContentValues().apply {
            put("client_id", e.clientId)
            put("captured_at", e.capturedAt)
            put("type", e.type)
            put("source", e.source)
            put("text", e.text)
            put("lang", e.lang)
            put("tags", e.tags.joinToString(","))
            put("sync_state", e.syncState)
        }
        return writableDatabase.insert("entries", null, cv)
    }

    fun setText(id: Long, text: String) {
        val cv = ContentValues().apply { put("text", text) }
        writableDatabase.update("entries", cv, "id = ?", arrayOf(id.toString()))
    }

    fun deleteByClientId(clientId: String) {
        writableDatabase.delete("entries", "client_id = ?", arrayOf(clientId))
    }

    /** Safety net: drop anything older than N days regardless of sync state. */
    fun purgeOlderThan(days: Int) {
        val cutoff = System.currentTimeMillis() - days.toLong() * 24 * 60 * 60 * 1000
        writableDatabase.delete("entries", "captured_at < ?", arrayOf(cutoff.toString()))
    }

    fun recent(limit: Int = 200): List<Entry> =
        query("SELECT * FROM entries ORDER BY captured_at DESC LIMIT ?", arrayOf(limit.toString()))

    fun search(q: String, limit: Int = 200): List<Entry> =
        query(
            "SELECT * FROM entries WHERE text LIKE ? ORDER BY captured_at DESC LIMIT ?",
            arrayOf("%$q%", limit.toString())
        )

    /** Entries ready to upload: have transcribed text and not yet synced. */
    fun pendingForUpload(): List<Entry> =
        query("SELECT * FROM entries WHERE sync_state = 'pending' AND text IS NOT NULL AND text != ''", null)

    /** Total characters of pending text — used to trigger an early upload. */
    fun pendingCharCount(): Int {
        readableDatabase.rawQuery(
            "SELECT COALESCE(SUM(LENGTH(text)),0) FROM entries WHERE sync_state='pending' AND text IS NOT NULL", null
        ).use { c -> return if (c.moveToFirst()) c.getInt(0) else 0 }
    }

    private fun query(sql: String, args: Array<String>?): List<Entry> {
        val out = ArrayList<Entry>()
        readableDatabase.rawQuery(sql, args).use { c ->
            while (c.moveToNext()) {
                val tags = c.getString(c.getColumnIndexOrThrow("tags"))
                out.add(
                    Entry(
                        id = c.getLong(c.getColumnIndexOrThrow("id")),
                        clientId = c.getString(c.getColumnIndexOrThrow("client_id")),
                        capturedAt = c.getLong(c.getColumnIndexOrThrow("captured_at")),
                        type = c.getString(c.getColumnIndexOrThrow("type")),
                        source = c.getString(c.getColumnIndexOrThrow("source")),
                        text = c.getString(c.getColumnIndexOrThrow("text")),
                        lang = c.getString(c.getColumnIndexOrThrow("lang")),
                        tags = if (tags.isEmpty()) emptyList() else tags.split(","),
                        syncState = c.getString(c.getColumnIndexOrThrow("sync_state"))
                    )
                )
            }
        }
        return out
    }

    companion object {
        private const val NAME = "recorder.db"
        private const val VERSION = 1
    }
}
