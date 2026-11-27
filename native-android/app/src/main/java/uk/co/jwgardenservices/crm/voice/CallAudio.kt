package uk.co.jwgardenservices.crm.voice

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** A 16-bit mono PCM WAV file. */
object WavFile {
    fun encode(samples: FloatArray, sampleRate: Int): ByteArray {
        val dataBytes = samples.size * 2
        val out = ByteArrayOutputStream(44 + dataBytes)
        fun int(value: Int) = repeat(4) { out.write((value shr (8 * it)) and 0xFF) }
        fun short(value: Int) = repeat(2) { out.write((value shr (8 * it)) and 0xFF) }
        out.write("RIFF".toByteArray())
        int(36 + dataBytes)
        out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray())
        int(16)
        short(1)
        short(1)
        int(sampleRate)
        int(sampleRate * 2)
        short(2)
        short(16)
        out.write("data".toByteArray())
        int(dataBytes)
        for (sample in samples) short((sample.coerceIn(-1f, 1f) * 32767f).roundToInt())
        return out.toByteArray()
    }
}

/**
 * Optional local recordings of what the recogniser was given, with what each
 * recogniser made of it, so the engines can be compared on the owner's own
 * voice in the van. Nothing leaves the phone unless the owner shares the zip.
 */
object CallAudio {
    private const val DIR = "call-audio"
    const val KEEP = 60
    private const val SAMPLE_RATE = 16_000

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun dir(context: Context): File = File(context.filesDir, DIR)

    fun count(context: Context): Int = dir(context).listFiles { f -> f.extension == "wav" }?.size ?: 0

    /** Save one utterance and a note about it, off the audio thread. */
    fun save(context: Context, samples: FloatArray, note: String) {
        if (samples.isEmpty()) return
        val app = context.applicationContext
        val stamp = System.currentTimeMillis()
        scope.launch {
            runCatching {
                val folder = dir(app).apply { mkdirs() }
                val name = stem(stamp)
                File(folder, "$name.wav").writeBytes(WavFile.encode(samples, SAMPLE_RATE))
                File(folder, "$name.txt").writeText(note)
                prune(folder, KEEP)
            }
        }
    }

    fun stem(millis: Long): String =
        String.format(Locale.ROOT, "%tY%<tm%<td-%<tH%<tM%<tS-%<tL", millis)

    /** Keep the newest [keep] recordings, with their notes, and delete the rest. */
    fun prune(folder: File, keep: Int) {
        val wavs = folder.listFiles { f -> f.extension == "wav" }?.sortedByDescending { it.name } ?: return
        wavs.drop(keep).forEach { wav ->
            wav.delete()
            File(folder, wav.nameWithoutExtension + ".txt").delete()
        }
    }

    fun clear(context: Context) {
        dir(context).listFiles()?.forEach { it.delete() }
    }

    /** One zip of everything saved, ready to share; null when there is nothing. */
    fun zipForShare(context: Context): File? {
        val folder = dir(context)
        val files = folder.listFiles()?.sortedBy { it.name } ?: return null
        if (files.none { it.extension == "wav" }) return null
        val target = File(File(context.cacheDir, "exports").apply { mkdirs() }, "call-audio.zip")
        zip(files, target)
        return target
    }

    fun zip(files: List<File>, target: File) {
        ZipOutputStream(target.outputStream().buffered()).use { zip ->
            files.forEach { file ->
                zip.putNextEntry(ZipEntry(file.name))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }
}
