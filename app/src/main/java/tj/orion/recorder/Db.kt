package tj.orion.recorder

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Plain-framework local store (no Room / no AndroidX).
 * Holds capture entries; a later SyncClient pushes pending rows to Supabase.
 */
class Db(context: Context) : SQLiteOpenHelper(context.applicationContext, NAME, null, VERSION) {

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
                audio_ref     TEXT,
                sync_state    TEXT NOT NULL DEFAULT 'pending'
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_entries_captured ON entries(captured_at DESC)")
        db.execSQL("CREATE INDEX idx_entries_sync ON entries(sync_state)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1 only for now
    }

    fun insert(e: Entry): Long {
        val cv = ContentValues().apply {
            put("client_id", e.clientId)
            put("captured_at", e.capturedAt)
            put("type", e.type)
            put("source", e.source)
            put("text", e.text)
            put("lang", e.lang)
            put("tags", e.tags.joinToString(","))
            put("audio_ref", e.audioLocalRef)
            put("sync_state", e.syncState)
        }
        return writableDatabase.insert("entries", null, cv)
    }

    /** Set transcript text after STT and mark it back to pending for sync. */
    fun setText(id: Long, text: String) {
        val cv = ContentValues().apply {
            put("text", text)
            put("sync_state", Entry.SYNC_PENDING)
        }
        writableDatabase.update("entries", cv, "id = ?", arrayOf(id.toString()))
    }

    fun markSynced(id: Long) {
        val cv = ContentValues().apply { put("sync_state", Entry.SYNC_SYNCED) }
        writableDatabase.update("entries", cv, "id = ?", arrayOf(id.toString()))
    }

    fun recent(limit: Int = 200): List<Entry> =
        query("SELECT * FROM entries ORDER BY captured_at DESC LIMIT ?", arrayOf(limit.toString()))

    fun search(q: String, limit: Int = 200): List<Entry> =
        query(
            "SELECT * FROM entries WHERE text LIKE ? ORDER BY captured_at DESC LIMIT ?",
            arrayOf("%$q%", limit.toString())
        )

    fun pending(): List<Entry> =
        query("SELECT * FROM entries WHERE sync_state = 'pending' AND text IS NOT NULL", null)

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
                        audioLocalRef = c.getString(c.getColumnIndexOrThrow("audio_ref")),
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
