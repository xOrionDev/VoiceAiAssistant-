package tj.orion.recorder

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import kotlin.concurrent.thread
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var db: Db
    private lateinit var list: ListView
    private lateinit var search: EditText
    private lateinit var btnRecord: Button
    private lateinit var btnThought: Button
    private lateinit var btnStop: Button
    private val adapter = EntryAdapter()
    private val fmt = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())
    private var loginShown = false

    companion object { @Volatile private var modelWarmStarted = false }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        db = Db(this)

        list = findViewById(R.id.list)
        search = findViewById(R.id.search)
        btnRecord = findViewById(R.id.btnRecord)
        btnThought = findViewById(R.id.btnThought)
        btnStop = findViewById(R.id.btnStop)
        list.adapter = adapter

        btnRecord.setOnClickListener { start(Entry.TYPE_RECORDING) }
        btnThought.setOnClickListener { start(Entry.TYPE_THOUGHT) }
        btnStop.setOnClickListener { stop() }

        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = refresh()
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        ensurePermissions()
        showDiagIfCrash()
    }

    private fun showDiagIfCrash() {
        val log = Diag.read(this)
        if (log.contains("CRASH") || log.contains("EXC")) {
            AlertDialog.Builder(this)
                .setTitle("Диагностика")
                .setMessage(log)
                .setPositiveButton("OK", null)
                .setNegativeButton("Очистить") { _, _ -> Diag.clear(this) }
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        try {
            if (!Auth.isLoggedIn(this)) {
                if (!loginShown) {
                    loginShown = true
                    startActivity(Intent(this, LoginActivity::class.java))
                }
                return
            }
            loginShown = false
            SyncScheduler.schedulePeriodic(this)
            prewarmModel()
            refresh()
        } catch (t: Throwable) {
            Diag.log(this, "onResume EXC $t")
        }
    }

    private fun prewarmModel() {
        if (modelWarmStarted) return
        modelWarmStarted = true
        thread {
            try {
                val v = VoskStt(this)
                if (!v.isModelReady()) Diag.log(this, "prewarm: downloading model…")
                v.ensureModel()
                v.close()
                Diag.log(this, "prewarm: model ready")
            } catch (t: Throwable) {
                Diag.log(this, "prewarm EXC $t")
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, "Диагностика")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == 1) {
            val log = Diag.read(this)
            AlertDialog.Builder(this)
                .setTitle("Диагностика")
                .setMessage(if (log.isEmpty()) "пусто" else log)
                .setPositiveButton("OK", null)
                .setNegativeButton("Очистить") { _, _ -> Diag.clear(this) }
                .show()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun start(type: String) {
        if (!hasMic()) { ensurePermissions(); return }
        val i = Intent(this, RecordingService::class.java).putExtra(RecordingService.EXTRA_TYPE, type)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i) else startService(i)
        setRecording(true)
    }

    private fun stop() {
        startService(Intent(this, RecordingService::class.java).setAction(RecordingService.ACTION_STOP))
        setRecording(false)
        // give the service a moment to flush the final transcript, then upload
        list.postDelayed({ refresh(); SyncScheduler.kickNow(this) }, 800)
    }

    private fun setRecording(on: Boolean) {
        btnRecord.isEnabled = !on
        btnThought.isEnabled = !on
        btnStop.isEnabled = on
    }

    private fun refresh() {
        val q = search.text.toString().trim()
        adapter.setItems(if (q.isEmpty()) db.recent() else db.search(q))
    }

    private fun hasMic() =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun ensurePermissions() {
        val need = ArrayList<String>()
        if (!hasMic()) need.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) need.add(Manifest.permission.POST_NOTIFICATIONS)
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), 1)
    }

    private inner class EntryAdapter : BaseAdapter() {
        private var items: List<Entry> = emptyList()
        fun setItems(v: List<Entry>) { items = v; notifyDataSetChanged() }
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = items[position].id
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val v = convertView ?: layoutInflater.inflate(R.layout.item_entry, parent, false)
            val e = items[position]
            val label = when (e.type) {
                Entry.TYPE_THOUGHT -> "мысль"
                Entry.TYPE_COMMAND -> "команда"
                else -> "запись"
            }
            v.findViewById<TextView>(R.id.meta).text = "${fmt.format(Date(e.capturedAt))} · $label"
            v.findViewById<TextView>(R.id.text).text =
                if (e.text.isNullOrBlank()) "…распознаётся" else e.text
            return v
        }
    }
}
