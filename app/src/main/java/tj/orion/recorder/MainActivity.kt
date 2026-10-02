package tj.orion.recorder

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.widget.CheckBox
import android.widget.TextView
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private enum class S { IDLE, REC, BUSY }
    private var state = S.IDLE

    private lateinit var orb: OrbView
    private lateinit var status: TextView
    private lateinit var timer: TextView
    private lateinit var dot: TextView
    private lateinit var remember: CheckBox

    private val ui = Handler(Looper.getMainLooper())
    private var seconds = 0
    private val tick = object : Runnable {
        override fun run() {
            seconds++
            timer.text = fmt(seconds)
            ui.postDelayed(this, 1000)
        }
    }

    private var loginShown = false
    companion object {
        @Volatile private var modelWarmStarted = false
        @Volatile private var batteryAsked = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        orb = findViewById(R.id.orb)
        status = findViewById(R.id.status)
        timer = findViewById(R.id.timer)
        dot = findViewById(R.id.dot)
        remember = findViewById(R.id.remember)

        orb.setOnClickListener { onOrbTap() }
        orb.setOnLongClickListener { showDiag(); true }

        showDiagIfCrash()
    }

    override fun onResume() {
        super.onResume()
        try {
            if (!Auth.isLoggedIn(this)) {
                if (!loginShown) { loginShown = true; startActivity(Intent(this, LoginActivity::class.java)) }
                return
            }
            loginShown = false
            prewarmModel()
            requestBatteryExemption()
            SyncScheduler.schedulePeriodic(this) // backstop retry for anything left pending
        } catch (t: Throwable) { Diag.log(this, "onResume EXC $t") }
    }

    // --- orb state machine ---

    private fun onOrbTap() {
        when (state) {
            S.IDLE -> startRecording()
            S.REC -> stopAndProcess()
            S.BUSY -> {}
        }
    }

    private fun startRecording() {
        val type = if (remember.isChecked) Entry.TYPE_THOUGHT else Entry.TYPE_RECORDING
        val i = Intent(this, RecordingService::class.java).putExtra(RecordingService.EXTRA_TYPE, type)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i) else startService(i)

        state = S.REC
        orb.state = OrbView.State.RECORDING
        setStatus("Идёт запись")
        dot.visibility = TextView.VISIBLE
        remember.visibility = CheckBox.INVISIBLE
        seconds = 0; timer.text = "00:00"
        ui.postDelayed(tick, 1000)
    }

    private fun stopAndProcess() {
        startService(Intent(this, RecordingService::class.java).setAction(RecordingService.ACTION_STOP))
        ui.removeCallbacks(tick)
        timer.text = ""
        dot.visibility = TextView.GONE
        state = S.BUSY
        orb.state = OrbView.State.BUSY
        val thought = remember.isChecked

        thread {
            // 1) processing (transcript finalizes live; give it a readable beat)
            setStatusUi("Обрабатываю ваши мысли")
            sleep(2000)
            // 2) creating task = upload to Supabase, min 2s shown
            setStatusUi(if (thought) "Сохраняю мысль" else "Создаю задачу")
            val t0 = System.currentTimeMillis()
            val ok = try { SyncClient.syncNow(this) } catch (t: Throwable) { Diag.log(this, "sync EXC $t"); false }
            val left = 2000 - (System.currentTimeMillis() - t0)
            if (left > 0) sleep(left)
            // 3) result
            setStatusUi(if (ok) "Задача создана" else "Сохранено, отправлю позже")
            sleep(2000)
            ui.post { toIdle() }
        }
    }

    private fun toIdle() {
        state = S.IDLE
        orb.state = OrbView.State.IDLE
        setStatus("Нажмите, чтобы записать")
        remember.visibility = CheckBox.VISIBLE
        remember.isChecked = false
    }

    // --- helpers ---

    private fun setStatus(text: String) {
        status.text = text
        status.alpha = 0f
        status.animate().alpha(1f).setDuration(280).start()
    }

    private fun setStatusUi(text: String) = ui.post { setStatus(text) }

    private fun fmt(s: Int) = "%02d:%02d".format(s / 60, s % 60)

    private fun sleep(ms: Long) { try { Thread.sleep(ms) } catch (_: InterruptedException) {} }

    private fun prewarmModel() {
        if (modelWarmStarted) return
        modelWarmStarted = true
        thread {
            try {
                val v = VoskStt(this)
                if (!v.isModelReady()) Diag.log(this, "prewarm: downloading model…")
                v.ensureModel(); v.close()
                Diag.log(this, "prewarm: model ready")
            } catch (t: Throwable) { Diag.log(this, "prewarm EXC $t") }
        }
    }

    private fun requestBatteryExemption() {
        if (batteryAsked) return
        batteryAsked = true
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                )
            }
        } catch (_: Exception) {}
    }

    private fun showDiag() {
        val log = Diag.read(this)
        AlertDialog.Builder(this)
            .setTitle("Диагностика")
            .setMessage(if (log.isEmpty()) "пусто" else log)
            .setPositiveButton("OK", null)
            .setNegativeButton("Очистить") { _, _ -> Diag.clear(this) }
            .show()
    }

    private fun showDiagIfCrash() {
        val log = Diag.read(this)
        if (log.contains("CRASH")) showDiag()
    }
}
