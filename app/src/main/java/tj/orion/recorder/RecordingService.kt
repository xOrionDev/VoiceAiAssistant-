package tj.orion.recorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.util.Log
import org.json.JSONObject
import org.vosk.Recognizer
import java.util.UUID
import kotlin.concurrent.thread

/**
 * Foreground service: captures mic PCM (16 kHz mono) and feeds it to Vosk live.
 * No audio file is written — the transcript is the asset, downstream works on
 * text, so nothing is kept on disk beyond the text. Transcript is saved to the
 * DB incrementally, so a crash never loses more than the last few seconds.
 */
class RecordingService : Service() {

    @Volatile private var running = false
    private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopCapture(); return START_NOT_STICKY }
            else -> {
                val type = intent?.getStringExtra(EXTRA_TYPE) ?: Entry.TYPE_RECORDING
                startForegroundNotice()
                startCapture(type)
            }
        }
        return START_STICKY
    }

    private fun startCapture(type: String) {
        if (running) return
        running = true
        worker = thread(name = "capture") { captureLoop(type) }
    }

    private fun stopCapture() {
        running = false
        worker?.join(4000)
        worker = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun captureLoop(type: String) {
        val db = Db(this)
        val vosk = VoskStt(this)
        try {
            vosk.ensureModel { Log.i(TAG, it) }
        } catch (e: Exception) {
            Log.e(TAG, "model load failed", e)
            running = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        val clientId = UUID.randomUUID().toString()
        val entryId = db.insert(
            Entry(
                clientId = clientId,
                capturedAt = System.currentTimeMillis(),
                type = type,
                source = Build.MODEL ?: "android",
                text = ""
            )
        )

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufSize = maxOf(minBuf, SAMPLE_RATE) // >= ~0.5s
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize
        )
        val recognizer: Recognizer = vosk.newRecognizer()
        val transcript = StringBuilder()
        val buffer = ByteArray(bufSize)

        try {
            recorder.startRecording()
            while (running) {
                val n = recorder.read(buffer, 0, buffer.size)
                if (n <= 0) continue
                if (recognizer.acceptWaveForm(buffer, n)) {
                    val text = JSONObject(recognizer.result).optString("text").trim()
                    if (text.isNotEmpty()) {
                        transcript.append(text).append(' ')
                        db.setText(entryId, transcript.toString().trim())
                    }
                }
            }
            val tail = JSONObject(recognizer.finalResult).optString("text").trim()
            if (tail.isNotEmpty()) transcript.append(tail)
            db.setText(entryId, transcript.toString().trim())
        } catch (e: Exception) {
            Log.e(TAG, "capture error", e)
        } finally {
            try { recorder.stop() } catch (_: Exception) {}
            recorder.release()
            recognizer.close()
            vosk.close()
            db.close()
            Log.i(TAG, "session $clientId done")
        }
    }

    private fun startForegroundNotice() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.rec_channel), NotificationManager.IMPORTANCE_LOW)
            )
        }
        val n: Notification = Notification.Builder(this, CHANNEL)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Запись идёт…")
            .setSmallIcon(android.R.drawable.presence_audio_online)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTE_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTE_ID, n)
        }
    }

    companion object {
        private const val TAG = "RecordingService"
        private const val CHANNEL = "recording"
        private const val NOTE_ID = 1
        private const val SAMPLE_RATE = 16000
        const val ACTION_STOP = "tj.orion.recorder.STOP"
        const val EXTRA_TYPE = "type"
    }
}
