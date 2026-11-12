package uk.co.jwgardenservices.crm.voice

import java.io.ByteArrayOutputStream

/**
 * Hand-rolled protobuf codec for Pipecat's `Frame` message (frames.proto),
 * matching `ProtobufFrameSerializer` on the server. Written by hand rather
 * than pulling in a protobuf runtime — the schema is four small messages and
 * this keeps the app lean.
 *
 * frames.proto:
 *
 *     message Frame {
 *       oneof frame {
 *         TextFrame text = 1;          { uint64 id; string name; string text }
 *         AudioRawFrame audio = 2;     { bytes audio; uint32 sample_rate; uint32 num_channels; ... }
 *         TranscriptionFrame transcription = 3; { string text; string user_id; string timestamp }
 *         MessageFrame message = 4;
 *         InterruptionFrame interruption = 5;
 *       }
 *     }
 */
object PipecatFrames {

    private const val TAG_TEXT = 0x0A          // field 1, wire type 2
    private const val TAG_AUDIO = 0x12         // field 2, wire type 2
    private const val TAG_TRANSCRIPTION = 0x1A // field 3, wire type 2
    private const val TAG_INTERRUPTION = 0x2A  // field 5, wire type 2

    private const val TAG_AUDIO_DATA = 0x1A    // AudioRawFrame.audio (field 3)
    private const val TAG_SAMPLE_RATE = 0x20   // AudioRawFrame.sample_rate (field 4)
    private const val TAG_NUM_CHANNELS = 0x28  // AudioRawFrame.num_channels (field 5)

    private const val TAG_TEXT_TEXT = 0x1A     // TextFrame.text (field 3)
    private const val TAG_TRANS_TEXT = 0x1A    // TranscriptionFrame.text (field 3)
    private const val TAG_TRANS_USER = 0x22    // TranscriptionFrame.user_id (field 4)
    private const val TAG_TRANS_TIME = 0x2A    // TranscriptionFrame.timestamp (field 5)

    sealed class Frame {
        data class Audio(val audio: ByteArray, val sampleRate: Int, val numChannels: Int) : Frame()
        data class Text(val text: String) : Frame()
        data class Transcription(val text: String, val userId: String?, val timestamp: String?) : Frame()
        data object Interruption : Frame()
    }

    /** Encode 16-bit mono PCM as a server-bound audio frame. */
    fun encodeAudio(pcm: ByteArray, sampleRate: Int = 16_000, numChannels: Int = 1): ByteArray {
        val inner = ByteArrayOutputStream()
        inner.write(byteFrame(TAG_AUDIO_DATA, pcm))
        inner.write(varint(TAG_SAMPLE_RATE, sampleRate.toLong()))
        inner.write(varint(TAG_NUM_CHANNELS, numChannels.toLong()))
        return messageFrame(TAG_AUDIO, inner.toByteArray())
    }

    /** Decode one server frame, or null when it carries nothing we display. */
    fun decode(bytes: ByteArray): Frame? {
        if (bytes.isEmpty()) return null
        val reader = Reader(bytes)
        while (reader.remaining() > 0) {
            val tag = reader.readVarint()
            when (tag.toInt()) {
                TAG_AUDIO -> {
                    val payload = reader.readLengthDelimited() ?: return null
                    var audio = ByteArray(0)
                    var rate = 0
                    var channels = 1
                    val inner = Reader(payload)
                    while (inner.remaining() > 0) {
                        when (inner.readVarint().toInt()) {
                            TAG_AUDIO_DATA -> audio = inner.readLengthDelimited() ?: ByteArray(0)
                            TAG_SAMPLE_RATE -> rate = inner.readVarint().toInt()
                            TAG_NUM_CHANNELS -> channels = inner.readVarint().toInt()
                            else -> inner.skip()
                        }
                    }
                    return Frame.Audio(audio, rate, channels)
                }
                TAG_TEXT -> {
                    val payload = reader.readLengthDelimited() ?: return null
                    val inner = Reader(payload)
                    var text = ""
                    while (inner.remaining() > 0) {
                        when (inner.readVarint().toInt()) {
                            TAG_TEXT_TEXT -> text = String(inner.readLengthDelimited() ?: ByteArray(0), Charsets.UTF_8)
                            else -> inner.skip()
                        }
                    }
                    return Frame.Text(text)
                }
                TAG_TRANSCRIPTION -> {
                    val payload = reader.readLengthDelimited() ?: return null
                    val inner = Reader(payload)
                    var text = ""
                    var user: String? = null
                    var time: String? = null
                    while (inner.remaining() > 0) {
                        when (inner.readVarint().toInt()) {
                            TAG_TRANS_TEXT -> text = String(inner.readLengthDelimited() ?: ByteArray(0), Charsets.UTF_8)
                            TAG_TRANS_USER -> user = String(inner.readLengthDelimited() ?: ByteArray(0), Charsets.UTF_8)
                            TAG_TRANS_TIME -> time = String(inner.readLengthDelimited() ?: ByteArray(0), Charsets.UTF_8)
                            else -> inner.skip()
                        }
                    }
                    return Frame.Transcription(text, user, time)
                }
                TAG_INTERRUPTION -> return Frame.Interruption
                else -> reader.skip()
            }
        }
        return null
    }

    internal fun messageFrame(tag: Int, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag)
        out.write(varintBytes(payload.size.toLong()))
        out.write(payload)
        return out.toByteArray()
    }

    internal fun byteFrame(tag: Int, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag)
        out.write(varintBytes(payload.size.toLong()))
        out.write(payload)
        return out.toByteArray()
    }

    private fun varint(tag: Int, value: Long): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag)
        out.write(varintBytes(value))
        return out.toByteArray()
    }

    private fun varintBytes(value: Long): ByteArray {
        var v = value
        val out = ByteArrayOutputStream()
        while (v and 0x7FL != v) {
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v shr 7
        }
        out.write(v.toInt())
        return out.toByteArray()
    }

    private class Reader(private val data: ByteArray) {
        var pos = 0
        fun remaining(): Int = data.size - pos
        fun readVarint(): Long {
            var shift = 0
            var result = 0L
            while (remaining() > 0) {
                val raw = data[pos++].toInt()
                result = result or ((raw and 0x7F).toLong() shl shift)
                if (raw and 0x80 == 0) break
                shift += 7
            }
            return result
        }

        fun readBytes(len: Int): ByteArray {
            val out = data.copyOfRange(pos, (pos + len).coerceAtMost(data.size))
            pos = (pos + len).coerceAtMost(data.size)
            return out
        }

        fun readLengthDelimited(): ByteArray? {
            val len = readVarint().toInt()
            if (len < 0 || pos + len > data.size) return null
            return readBytes(len)
        }

        fun skip() {
            val tag = readVarint()
            when ((tag and 0x7).toInt()) {
                0 -> readVarint()
                1 -> readBytes(8)
                2 -> readLengthDelimited()
                5 -> readBytes(4)
                else -> pos = data.size
            }
        }
    }
}
