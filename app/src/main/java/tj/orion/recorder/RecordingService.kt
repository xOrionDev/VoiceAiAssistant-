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
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import kotlin.concurrent.thread

/**
 * Foreground service: captures mic PCM (16 kHz mono), feeds it to Vosk live,
 * and writes the audio to a WAV file. Transcript is saved to the DB
 * incrementally as final segments arrive, so a crash never loses more than
 * the last few seconds of text.
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
        val startedAt = System.currentTimeMillis()
        val wavFile = File(getExternalFilesDir(null), "$clientId.wav")
        val entryId = db.insert(
            Entry(
                clientId = clientId,
                capturedAt = startedAt,
                type = type,
                source = Build.MODEL ?: "android",
                audioLocalRef = wavFile.absolutePath,
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

        val wav = RandomAccessFile(wavFile, "rw")
        writeWavHeaderPlaceholder(wav)
        var pcmBytes = 0L

        try {
            recorder.startRecording()
            while (running) {
                val n = recorder.read(buffer, 0, buffer.size)
                if (n <= 0) continue
                wav.write(buffer, 0, n)
                pcmBytes += n
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
            finalizeWavHeader(wav, pcmBytes)
            wav.close()
            db.close()
            Log.i(TAG, "session $clientId done, ${pcmBytes / 1024} KB pcm")
        }
    }

    // --- WAV (PCM 16-bit mono @ 16 kHz) ---

    private fun writeWavHeaderPlaceholder(wav: RandomAccessFile) {
        wav.seek(0)
        wav.write(ByteArray(44)) // filled on finalize
    }

    private fun finalizeWavHeader(wav: RandomAccessFile, dataLen: Long) {
        val byteRate = SAMPLE_RATE * 2 // mono, 16-bit
        val header = ByteArray(44)
        fun putStr(off: Int, s: String) { for (i in s.indices) header[off + i] = s[i].code.toByte() }
        fun putInt(off: Int, v: Int) {
            header[off] = (v and 0xff).toByte()
            header[off + 1] = ((v shr 8) and 0xff).toByte()
            header[off + 2] = ((v shr 16) and 0xff).toByte()
            header[off + 3] = ((v shr 24) and 0xff).toByte()
        }
        fun putShort(off: Int, v: Int) {
            header[off] = (v and 0xff).toByte()
            header[off + 1] = ((v shr 8) and 0xff).toByte()
        }
        putStr(0, "RIFF"); putInt(4, (36 + dataLen).toInt()); putStr(8, "WAVE")
        putStr(12, "fmt "); putInt(16, 16); putShort(20, 1); putShort(22, 1)
        putInt(24, SAMPLE_RATE); putInt(28, byteRate); putShort(32, 2); putShort(34, 16)
        putStr(36, "data"); putInt(40, dataLen.toInt())
        wav.seek(0); wav.write(header)
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
