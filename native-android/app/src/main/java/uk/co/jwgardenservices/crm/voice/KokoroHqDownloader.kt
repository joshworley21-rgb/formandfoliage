package uk.co.jwgardenservices.crm.voice

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import uk.co.jwgardenservices.crm.core.CrashLog
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Optional high-quality Kokoro voice.
 *
 * The bundled model is the int8 quantised one (109 MB). The full fp32 model
 * sounds noticeably more natural but is 310 MB, so it is fetched on demand
 * from the official sherpa-onnx release and stored in the app's files
 * directory. The .tar.bz2 is streamed and only `model.onnx` is written out.
 */
object KokoroHqDownloader {
    private const val TAG = "KokoroHq"

    const val MIN_BYTES = 250L * 1024 * 1024
    private const val URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-multi-lang-v1_0.tar.bz2"

    fun modelFile(context: Context): File =
        File(context.filesDir, "kokoro-hq/model.onnx")

    fun isInstalled(context: Context): Boolean {
        val file = modelFile(context)
        return file.isFile && file.length() > MIN_BYTES
    }

    /**
     * Stream the archive, extract `model.onnx` into filesDir/kokoro-hq and
     * report progress as 0..100. Safe to call repeatedly; leaves the existing
     * model untouched until the new one is complete.
     */
    suspend fun download(context: Context, onProgress: (Int) -> Unit): File =
        withContext(Dispatchers.IO) {
            val target = modelFile(context)
            target.parentFile?.mkdirs()
            val partial = File(target.parentFile, "model.onnx.part")
            partial.delete()

            val client = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(300, TimeUnit.SECONDS)
                .build()
            val request = Request.Builder().url(URL).build()

            var lastPercent = -1
            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("Kokoro download failed: HTTP ${response.code}")
                    }
                    val body = response.body ?: throw IOException("Empty Kokoro download")
                    val total = body.contentLength()
                    val counting = object : FilterInputStream(body.byteStream()) {
                        var seen = 0L
                        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                            val read = super.read(buffer, offset, length)
                            if (read > 0) {
                                seen += read
                                if (total > 0) {
                                    val percent = (seen * 100 / total).toInt().coerceIn(0, 100)
                                    if (percent != lastPercent) {
                                        lastPercent = percent
                                        onProgress(percent)
                                    }
                                }
                            }
                            return read
                        }
                    }

                    var found = false
                    BZip2CompressorInputStream(counting, true).use { bzip ->
                        TarArchiveInputStream(bzip).use { tar ->
                            var entry = tar.nextEntry
                            while (entry != null) {
                                if (!entry.isDirectory && entry.name.endsWith("/model.onnx")) {
                                    partial.outputStream().use { output ->
                                        tar.copyTo(output, 256 * 1024)
                                    }
                                    found = true
                                    break
                                }
                                entry = tar.nextEntry
                            }
                        }
                    }
                    if (!found) throw IOException("model.onnx was not in the Kokoro archive")
                }
            } catch (error: Exception) {
                partial.delete()
                CrashLog.note(context, TAG, "download failed: ${error.message}")
                throw error
            }

            if (partial.length() < MIN_BYTES) {
                partial.delete()
                throw IOException("Downloaded Kokoro model was incomplete")
            }
            if (!partial.renameTo(target)) {
                partial.copyTo(target, overwrite = true)
                partial.delete()
            }
            CrashLog.note(context, TAG, "fp32 model installed (${target.length() / 1048576} MB)")
            Log.i(TAG, "Kokoro fp32 installed at $target")
            target
        }
}
