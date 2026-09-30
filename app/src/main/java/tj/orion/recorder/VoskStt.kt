package tj.orion.recorder

import android.content.Context
import android.util.Log
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Offline Russian STT via Vosk. Concrete on purpose: a second engine
 * (e.g. Whisper) would be introduced behind an interface only when it
 * actually exists — not speculatively for one caller.
 *
 * M1 ships the small RU model (~45 MB, downloaded on first use). A larger,
 * more accurate model can be added later as a user-selectable download.
 */
class VoskStt(private val context: Context) {

    val sampleRate = 16000.0f
    private var model: Model? = null

    fun isModelReady(): Boolean = modelDir().resolve("am").exists() || modelDir().resolve("conf").exists()

    /** Blocking: download + unzip the model if missing, then load it. Call off the main thread. */
    @Synchronized
    fun ensureModel(progress: (String) -> Unit = {}) {
        if (model != null) return
        if (!isModelReady()) {
            progress("Скачиваю модель…")
            downloadAndUnzip(MODEL_URL, context.filesDir, progress)
        }
        progress("Загружаю модель…")
        model = Model(modelDir().absolutePath)
    }

    fun newRecognizer(): Recognizer {
        val m = model ?: throw IllegalStateException("model not loaded; call ensureModel() first")
        return Recognizer(m, sampleRate)
    }

    fun close() {
        model?.close()
        model = null
    }

    private fun modelDir(): File = File(context.filesDir, MODEL_DIR)

    private fun downloadAndUnzip(url: String, target: File, progress: (String) -> Unit) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 30000
        conn.readTimeout = 60000
        conn.inputStream.use { input ->
            ZipInputStream(input.buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val out = File(target, entry.name)
                    if (entry.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile?.mkdirs()
                        FileOutputStream(out).use { fos -> zip.copyTo(fos) }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }
        conn.disconnect()
        Log.i(TAG, "model unpacked to ${target.absolutePath}")
    }

    companion object {
        private const val TAG = "VoskStt"
        // Small Russian model (~45 MB). Unzips to a top-level folder of this name.
        private const val MODEL_DIR = "vosk-model-small-ru-0.22"
        private const val MODEL_URL =
            "https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip"
    }
}
