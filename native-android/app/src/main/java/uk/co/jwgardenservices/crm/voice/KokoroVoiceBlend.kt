package uk.co.jwgardenservices.crm.voice

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A prepared voice bank for the installed Kokoro model.
 *
 * The model metadata declares 54 speaker slots and validates that
 * `voices.bin` contains exactly 54 × 510 × 256 floats. We cannot append a
 * 55th speaker, so the cab blend replaces the last slot (53) and a custom
 * single-speaker vector replaces slot 52. Both are unused by the offered
 * British voices.
 */
object KokoroVoiceBlend {
    const val STYLE_COUNT = 510
    const val STYLE_DIM = 256
    const val SPEAKER_COUNT = 54

    const val FABLE_SLOT = 25
    const val GEORGE_SLOT = 26
    const val BLEND_SLOT = 53
    const val CUSTOM_SLOT = 52

    const val FABLE_WEIGHT = 0.70f
    const val GEORGE_WEIGHT = 0.30f

    const val STYLE_FLOATS = STYLE_COUNT * STYLE_DIM
    const val STYLE_BYTES = STYLE_FLOATS * 4
    private const val FILE_BYTES = SPEAKER_COUNT * STYLE_BYTES

    data class Prepared(
        val file: File,
        val blend: Boolean,
        val custom: Boolean,
    )

    @Volatile
    private var cachedKey: String? = null

    @Volatile
    private var cached: Prepared? = null

    /** The 510 × 256 cab style vector, kept for the life of the process. */
    @Volatile
    private var cachedBlend: FloatArray? = null

    val blendVector: FloatArray?
        get() = cachedBlend

    /**
     * Returns a bank with the cab blend and optional custom speaker, or null
     * when the installed bank is not the expected format. The caller falls
     * back to the original file and maps the blend to `bm_fable`.
     */
    @Synchronized
    fun prepare(dir: File, customVoice: File? = null): Prepared? {
        val source = File(dir, "voices.bin")
        if (!source.isFile || source.length() != FILE_BYTES.toLong()) return null
        val custom = customVoice
            ?.takeIf { it.isFile && it.length() == STYLE_BYTES.toLong() }
        val key = "${source.length()}:${source.lastModified()}:${custom?.name ?: "none"}:${custom?.lastModified() ?: 0}"
        cached?.let { if (cachedKey == key && it.file.isFile) return it }

        val target = File(
            dir,
            if (custom != null) "voices-custom-${custom.nameWithoutExtension}.bin"
            else "voices-cab.bin",
        )
        if (target.isFile && target.length() == source.length() &&
            target.lastModified() >= source.lastModified() &&
            (custom == null || target.lastModified() >= custom.lastModified())
        ) {
            return Prepared(
                file = target,
                blend = true,
                custom = custom != null,
            ).also {
                cachedKey = key
                cached = it
            }
        }
        return runCatching {
            val bytes = source.readBytes()
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val blendOffset = BLEND_SLOT * STYLE_BYTES
            val fableOffset = FABLE_SLOT * STYLE_BYTES
            val georgeOffset = GEORGE_SLOT * STYLE_BYTES
            val blended = FloatArray(STYLE_FLOATS)
            for (index in 0 until STYLE_FLOATS) {
                val offset = index * 4
                val fable = buffer.getFloat(fableOffset + offset)
                val george = buffer.getFloat(georgeOffset + offset)
                val value = fable * FABLE_WEIGHT + george * GEORGE_WEIGHT
                blended[index] = value
                buffer.putFloat(blendOffset + offset, value)
            }
            cachedBlend = blended
            if (custom != null) {
                val customBytes = custom.readBytes()
                System.arraycopy(
                    customBytes,
                    0,
                    bytes,
                    CUSTOM_SLOT * STYLE_BYTES,
                    STYLE_BYTES,
                )
            }
            target.writeBytes(bytes)
            Prepared(file = target, blend = true, custom = custom != null).also {
                cachedKey = key
                cached = it
            }
        }.getOrNull()
    }

    /** Exposed for tests and diagnostics. */
    fun blend(fable: FloatArray, george: FloatArray): FloatArray {
        require(fable.size == george.size)
        return FloatArray(fable.size) { index ->
            fable[index] * FABLE_WEIGHT + george[index] * GEORGE_WEIGHT
        }
    }
}
