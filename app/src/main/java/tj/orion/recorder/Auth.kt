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
            Diag.log(ctx, "login: POST token for '$email'")
            val body = JSONObject().put("email", email.trim()).put("password", password)
            val (code, text) = post(ctx, "/auth/v1/token?grant_type=password", body.toString())
            Diag.log(ctx, "login: HTTP $code")
            if (code !in 200..299) {
                return shortError(code, text)
            }
            store(ctx, JSONObject(text))
            Diag.log(ctx, "login: stored, loggedIn=${isLoggedIn(ctx)}")
            null
        } catch (t: Throwable) {
            Diag.log(ctx, "login: EXC ${t}")
            t.message ?: "login failed"
        }
    }

    fun logout(ctx: Context) = prefs(ctx).edit().clear().commit()

    /** Valid access token (refreshing if needed), or null if not logged in. Blocking. */
    @Synchronized
    fun accessToken(ctx: Context): String? {
        val p = prefs(ctx)
        val refresh = p.getString("refresh_token", null) ?: return null
        if (System.currentTimeMillis() < p.getLong("expires_at", 0L)) {
            return p.getString("access_token", null)
        }
        return try {
            val (code, text) = post(
                ctx, "/auth/v1/token?grant_type=refresh_token",
                JSONObject().put("refresh_token", refresh).toString()
            )
            if (code !in 200..299) {
                Diag.log(ctx, "refresh: HTTP $code")
                return null
            }
            store(ctx, JSONObject(text))
            prefs(ctx).getString("access_token", null)
        } catch (t: Throwable) {
            Diag.log(ctx, "refresh: EXC $t")
            null
        }
    }

    private fun store(ctx: Context, o: JSONObject) {
        val expiresIn = o.optLong("expires_in", 3600L)
        prefs(ctx).edit()
            .putString("access_token", o.getString("access_token"))
            .putString("refresh_token", o.getString("refresh_token"))
            .putLong("expires_at", System.currentTimeMillis() + (expiresIn - 60) * 1000)
            .commit()
    }

    private fun shortError(code: Int, body: String): String {
        val msg = try { JSONObject(body).let { it.optString("msg", it.optString("error_description", it.optString("error", ""))) } } catch (_: Throwable) { "" }
        return "HTTP $code${if (msg.isNotEmpty()) " · $msg" else ""}"
    }

    /** Returns (httpCode, bodyText). */
    private fun post(ctx: Context, path: String, json: String): Pair<Int, String> {
        val conn = URL(Config.SUPABASE_URL + path).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 20000
        conn.readTimeout = 20000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("apikey", Config.SUPABASE_KEY)
        conn.outputStream.use { os: OutputStream -> os.write(json.toByteArray()) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        conn.disconnect()
        return code to text
    }

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
}
