package tj.orion.recorder

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

/**
 * Pushes transcribed entries (text + meta, no audio) to Supabase, then deletes
 * them locally. Shows a Toast with the outcome so failures are visible, not silent.
 */
object SyncClient {

    /** Blocking. Returns true if nothing is left pending. */
    fun syncNow(ctx: Context): Boolean {
        val pending = Db(ctx).use { it.pendingForUpload() }
        if (pending.isEmpty()) return true

        val token = Auth.accessToken(ctx)
        if (token == null) {
            toast(ctx, "Синхронизация: нужен вход в аккаунт")
            return false
        }

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

        val err = post("/rest/v1/entries?on_conflict=user_id,client_id", arr.toString(), token)
        if (err != null) {
            toast(ctx, "Заливка не удалась: $err")
            return false
        }

        Db(ctx).use { db -> pending.forEach { db.deleteByClientId(it.clientId) } }
        toast(ctx, "Синхронизировано: ${pending.size}")
        Log.i(TAG, "synced ${pending.size} entries")
        return true
    }

    /** Returns null on success, or a short error string. */
    private fun post(path: String, json: String, token: String): String? {
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
            if (code in 200..299) {
                conn.disconnect()
                null
            } else {
                val body = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                conn.disconnect()
                Log.e(TAG, "upload HTTP $code: $body")
                "HTTP $code ${body.take(140)}"
            }
        } catch (e: Exception) {
            Log.e(TAG, "upload failed", e)
            e.message ?: "network error"
        }
    }

    private fun toast(ctx: Context, msg: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(ctx.applicationContext, msg, Toast.LENGTH_LONG).show()
        }
    }

    private const val TAG = "SyncClient"
}
