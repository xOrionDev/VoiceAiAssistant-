package tj.orion.recorder

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

/**
 * Pushes transcribed entries (text + meta, no audio) to Supabase, then deletes
 * them locally so the app keeps almost nothing. user_id is filled server-side
 * from the JWT (column default auth.uid()), so it is never sent.
 */
object SyncClient {

    /** Blocking. Returns true if nothing is left pending (success or empty). */
    fun syncNow(ctx: Context): Boolean {
        val pending = Db(ctx).use { it.pendingForUpload() }
        if (pending.isEmpty()) return true
        val token = Auth.accessToken(ctx) ?: return false // not logged in / refresh failed

        val arr = JSONArray()
        for (e in pending) {
            arr.put(JSONObject().apply {
                put("client_id", e.clientId)
                put("captured_at", Instant.ofEpochMilli(e.capturedAt).toString())
                put("type", e.type)
                put("source", e.source)
                put("text", e.text)
                put("lang", e.lang)
                put("tags", JSONArray(e.tags))
            })
        }

        val ok = post("/rest/v1/entries?on_conflict=user_id,client_id", arr.toString(), token)
        if (!ok) return false

        Db(ctx).use { db -> pending.forEach { db.deleteByClientId(it.clientId) } }
        Log.i(TAG, "synced ${pending.size} entries")
        return true
    }

    private fun post(path: String, json: String, token: String): Boolean {
        return try {
            val conn = URL(Config.SUPABASE_URL + path).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 20000
            conn.readTimeout = 30000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("apikey", Config.SUPABASE_KEY)
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Prefer", "resolution=merge-duplicates,return=minimal")
            conn.outputStream.use { it.write(json.toByteArray()) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                Log.e(TAG, "upload HTTP $code: $err")
            }
            conn.disconnect()
            code in 200..299
        } catch (e: Exception) {
            Log.e(TAG, "upload failed", e)
            false
        }
    }

    private const val TAG = "SyncClient"
}
