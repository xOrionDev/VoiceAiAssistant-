package tj.orion.recorder

import android.content.Context
import org.json.JSONObject
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal Supabase (GoTrue) auth: one-time email/password login, tokens kept in
 * SharedPreferences, silent refresh when the access token is near expiry.
 */
object Auth {
    private const val PREF = "auth"

    fun isLoggedIn(ctx: Context): Boolean =
        prefs(ctx).getString("refresh_token", null) != null

    /** Blocking. Returns null on success, or an error message. Call off the main thread. */
    fun login(ctx: Context, email: String, password: String): String? {
        return try {
            val body = JSONObject().put("email", email.trim()).put("password", password)
            val resp = post("/auth/v1/token?grant_type=password", body.toString(), null)
            store(ctx, JSONObject(resp))
            null
        } catch (e: Exception) {
            e.message ?: "login failed"
        }
    }

    fun logout(ctx: Context) = prefs(ctx).edit().clear().apply()

    /** Valid access token (refreshing if needed), or null if not logged in. Blocking. */
    @Synchronized
    fun accessToken(ctx: Context): String? {
        val p = prefs(ctx)
        val refresh = p.getString("refresh_token", null) ?: return null
        if (System.currentTimeMillis() < p.getLong("expires_at", 0L)) {
            return p.getString("access_token", null)
        }
        return try {
            val resp = post(
                "/auth/v1/token?grant_type=refresh_token",
                JSONObject().put("refresh_token", refresh).toString(), null
            )
            store(ctx, JSONObject(resp))
            prefs(ctx).getString("access_token", null)
        } catch (e: Exception) {
            null
        }
    }

    private fun store(ctx: Context, o: JSONObject) {
        val expiresIn = o.optLong("expires_in", 3600L)
        prefs(ctx).edit()
            .putString("access_token", o.getString("access_token"))
            .putString("refresh_token", o.getString("refresh_token"))
            .putLong("expires_at", System.currentTimeMillis() + (expiresIn - 60) * 1000)
            .apply()
    }

    private fun post(path: String, json: String, bearer: String?): String {
        val conn = URL(Config.SUPABASE_URL + path).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 20000
        conn.readTimeout = 20000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("apikey", Config.SUPABASE_KEY)
        if (bearer != null) conn.setRequestProperty("Authorization", "Bearer $bearer")
        conn.outputStream.use { os: OutputStream -> os.write(json.toByteArray()) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        conn.disconnect()
        if (code !in 200..299) throw RuntimeException("HTTP $code: $text")
        return text
    }

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
}
