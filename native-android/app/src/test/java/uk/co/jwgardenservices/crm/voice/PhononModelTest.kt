package uk.co.jwgardenservices.crm.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhononModelTest {
    @Test
    fun `the standard model is the default and keeps its size`() {
        assertEquals(PhononModel.Phonon31, PhononModel.fromId(null))
        assertEquals(PhononModel.Phonon31, PhononModel.fromId("nonsense"))
        assertEquals(65_051_089L, PhononModel.Phonon31.totalBytes)
    }

    @Test
    fun `the optional model is separate and says its licence`() {
        assertEquals(PhononModel.Phonon40, PhononModel.fromId("40m"))
        assertEquals(75_092_305L, PhononModel.Phonon40.totalBytes)
        assertTrue(PhononModel.Phonon40.licence.contains("non-commercial", ignoreCase = true))
        assertTrue(PhononModel.Phonon40.base.contains("phonon-40m-en"))
        assertTrue(PhononModel.Phonon31.base.contains("phonon-31m-en"))
        assertTrue(PhononModel.Phonon40.dirName != PhononModel.Phonon31.dirName)
    }
}
