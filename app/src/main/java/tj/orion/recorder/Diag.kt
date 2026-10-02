package tj.orion.recorder

import android.content.Context

/** Crash/flow log that survives activity recreation and crashes (kept in prefs). */
object Diag {
    private const val PREF = "diag"
    private const val KEY = "log"

    fun log(ctx: Context, msg: String) {
        try {
            val p = ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            val now = android.text.format.DateFormat.format("HH:mm:ss", System.currentTimeMillis())
            val prev = p.getString(KEY, "") ?: ""
            val merged = ("[$now] $msg\n$prev").take(3000)
            p.edit().putString(KEY, merged).commit()
        } catch (_: Throwable) {}
    }

    fun read(ctx: Context): String =
        ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, "") ?: ""

    fun clear(ctx: Context) {
        ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().clear().commit()
    }
}
