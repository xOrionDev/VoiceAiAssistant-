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
 * Pushes entries (text + meta) to Supabase, then deletes them locally ONLY for
 * rows the server actually returned (return=representation). A 2xx with no rows
 * never deletes anything — so data is never lost on a silent reject.
 */
object SyncClient {

    fun syncNow(ctx: Context): Boolean {
        val pending = Db(ctx).use { it.pendingForUpload() }
        Diag.log(ctx, "sync: ${pending.size} pending")
        if (pending.isEmpty()) return true

        val token = Auth.accessToken(ctx)
        if (token == null) {
            Diag.log(ctx, "sync: no token")
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

        val (code, body) = post("/rest/v1/entries?on_conflict=user_id,client_id", arr.toString(), token)
        if (code !in 200..299) {
            Diag.log(ctx, "sync: FAIL HTTP $code ${body.take(140)}")
            toast(ctx, "Заливка не удалась: HTTP $code")
            return false
        }

        val confirmed = parseClientIds(body)
        if (confirmed.isEmpty()) {
            Diag.log(ctx, "sync: 2xx but 0 rows returned — оставляю локально")
            toast(ctx, "Сервер не подтвердил запись")
            return false
        }

        Db(ctx).use { db -> confirmed.forEach { db.deleteByClientId(it) } }
        Diag.log(ctx, "sync: OK, uploaded ${confirmed.size}")
        toast(ctx, "Синхронизировано: ${confirmed.size}")
        Log.i(TAG, "synced ${confirmed.size}")
        return true
    }

    private fun parseClientIds(body: String): List<String> {
        return try {
            val arr = JSONArray(body)
            (0 until arr.length()).mapNotNull { arr.getJSONObject(it).optString("client_id").ifEmpty { null } }
        } catch (_: Throwable) { emptyList() }
    }

    /** Returns (httpCode, bodyText). */
    private fun post(path: String, json: String, token: String): Pair<Int, String> {
        return try {
            val conn = URL(Config.SUPABASE_URL + path).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 20000
            conn.readTimeout = 30000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("apikey", Config.SUPABASE_KEY)
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Prefer", "resolution=merge-duplicates,return=representation")
            conn.outputStream.use { it.write(json.toByteArray()) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            conn.disconnect()
            code to text
        } catch (e: Exception) {
            Log.e(TAG, "upload failed", e)
            -1 to (e.message ?: "network error")
        }
    }

    private fun toast(ctx: Context, msg: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(ctx.applicationContext, msg, Toast.LENGTH_LONG).show()
        }
    }

    private const val TAG = "SyncClient"
}
