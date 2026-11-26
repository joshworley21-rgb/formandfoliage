package uk.co.jwgardenservices.crm.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class KokoroVoiceBlendTest {
    @Test
    fun `cab blend is seventy fable and thirty george`() {
        val blended = KokoroVoiceBlend.blend(
            floatArrayOf(1f, 0f, 0.5f),
            floatArrayOf(0f, 1f, 0.5f),
        )
        assertEquals(0.70f, blended[0], 0.0001f)
        assertEquals(0.30f, blended[1], 0.0001f)
        assertEquals(0.50f, blended[2], 0.0001f)
    }
}
