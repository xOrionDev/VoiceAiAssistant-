package tj.orion.recorder

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

/** Captures any uncaught crash into the Diag log and the clipboard so it's recoverable. */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            Diag.log(this, "CRASH on ${t.name}: $e\n${e.stackTrace.take(6).joinToString("\n")}")
            try {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("diag", Diag.read(this)))
            } catch (_: Throwable) {}
            prev?.uncaughtException(t, e)
        }
    }
}
