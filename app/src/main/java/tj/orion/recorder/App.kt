package tj.orion.recorder

import android.app.Application

/** Captures any uncaught crash into the Diag log so it is visible after restart. */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            Diag.log(this, "CRASH on ${t.name}: ${e}\n${e.stackTrace.take(4).joinToString("\n")}")
            prev?.uncaughtException(t, e)
        }
    }
}
