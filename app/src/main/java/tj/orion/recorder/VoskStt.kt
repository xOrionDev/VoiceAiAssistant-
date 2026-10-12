package tj.orion.recorder

import android.content.Context
import android.util.Log
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Offline Russian STT via Vosk.
 *
 * The model (~45 MB) is downloaded on first use. Download/unpack is made
 * crash-proof: it unpacks to a temp dir, verifies the required files exist,
 * then atomically swaps it into place and writes a `.ready` marker. A partial
 * or corrupt download can never be mistaken for a ready model, and a failed
 * native load self-heals by wiping and re-downloading once.
 */
class VoskStt(private val context: Context) {

    val sampleRate = 16000.0f
    private var model: Model? = null

    private fun modelDir(): File = File(context.filesDir, MODEL_DIR)
    private fun readyMarker(): File = File(modelDir(), ".ready")

    /** Ready only when the full model is present and verified (marker written). */
    fun isModelReady(): Boolean = readyMarker().exists()

    /** Blocking: ensure the model is downloaded + loaded. Call off the main thread. */
    @Synchronized
    fun ensureModel(progress: (String) -> Unit = {}) {
        if (model != null) return
        if (!isModelReady()) {
            progress("Скачиваю модель…")
            acquireModel(progress)
        }
        progress("Загружаю модель…")
        model = try {
            Model(modelDir().absolutePath)
        } catch (t: Throwable) {
            // Dir exists but native load failed → treat as corrupt, re-acquire once.
            Log.w(TAG, "model load failed, re-acquiring", t)
            deleteDir(modelDir())
            acquireModel(progress)
            Model(modelDir().absolutePath)
        }
    }

    fun newRecognizer(): Recognizer {
        val m = model ?: throw IllegalStateException("model not loaded; call ensureModel() first")
        return Recognizer(m, sampleRate)
    }

    fun close() {
        model?.close()
        model = null
    }

    // --- model acquisition ---------------------------------------------------

    private fun acquireModel(progress: (String) -> Unit) {
        val dir = modelDir()
        deleteDir(dir) // never trust leftovers

        val tmp = File(context.filesDir, "$MODEL_DIR.tmp")
        deleteDir(tmp)
        if (!tmp.mkdirs()) throw IOException("cannot create temp dir")

        downloadAndUnzip(MODEL_URL, tmp, progress)

        // Archive contains a single top-level folder; locate the real model root.
        val root = findModelRoot(tmp)
            ?: throw IOException("model archive invalid (no am/conf inside)")

        if (!root.renameTo(dir)) root.copyRecursively(dir, overwrite = true)
        deleteDir(tmp)

        if (!File(dir, "am/final.mdl").exists() || !File(dir, "conf/mfcc.conf").exists())
            throw IOException("model incomplete after unpack")

        readyMarker().writeText("ok")
        Log.i(TAG, "model ready at ${dir.absolutePath}")
    }

    private fun findModelRoot(base: File): File? {
        if (isModelFolder(base)) return base
        base.listFiles()?.forEach { if (it.isDirectory && isModelFolder(it)) return it }
        return null
    }

    private fun isModelFolder(f: File) =
        File(f, "am").isDirectory && File(f, "conf").isDirectory

    private fun downloadAndUnzip(url: String, target: File, progress: (String) -> Unit) {
        var conn = open(url)
        var hops = 0
        // Follow cross-protocol redirects (http<->https) that the JDK won't auto-follow.
        while (conn.responseCode in 300..399 && hops < 5) {
            val loc = conn.getHeaderField("Location") ?: break
            conn.disconnect()
            conn = open(loc)
            hops++
        }
        if (conn.responseCode !in 200..299) {
            val code = conn.responseCode
            conn.disconnect()
            throw IOException("download HTTP $code")
        }

        try {
            ZipInputStream(conn.inputStream.buffered()).use { zip ->
                var entry = zip.nextEntry
                var n = 0
                val targetPath = target.canonicalPath + File.separator
                while (entry != null) {
                    val out = File(target, entry.name)
                    if (!out.canonicalPath.startsWith(targetPath))
                        throw IOException("unsafe zip entry: ${entry.name}")
                    if (entry.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile?.mkdirs()
                        FileOutputStream(out).use { fos -> zip.copyTo(fos) }
                    }
                    zip.closeEntry()
                    if (++n % 40 == 0) progress("Распаковка модели… $n файлов")
                    entry = zip.nextEntry
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun open(u: String): HttpURLConnection =
        (URL(u).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30000
            readTimeout = 120000
            instanceFollowRedirects = true
        }

    private fun deleteDir(f: File) { if (f.exists()) f.deleteRecursively() }

    companion object {
        private const val TAG = "VoskStt"
        private const val MODEL_DIR = "vosk-model-small-ru-0.22"
        private const val MODEL_URL =
            "https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip"
    }
}
