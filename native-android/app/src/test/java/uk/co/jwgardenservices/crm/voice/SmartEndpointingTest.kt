package uk.co.jwgardenservices.crm.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class SmartEndpointingTest {
    @Test
    fun `complete questions close quickly`() {
        assertEquals(SmartEndpointing.FAST_MS, SmartEndpointing.waitMs("what's on tomorrow"))
        assertEquals(SmartEndpointing.FAST_MS, SmartEndpointing.waitMs("what have I got on today"))
        assertEquals(SmartEndpointing.FAST_MS, SmartEndpointing.waitMs("who owes me"))
    }

    @Test
    fun `crisp commands close quickly`() {
        assertEquals(SmartEndpointing.FAST_MS, SmartEndpointing.waitMs("log that"))
        assertEquals(SmartEndpointing.FAST_MS, SmartEndpointing.waitMs("yes"))
        assertEquals(SmartEndpointing.FAST_MS, SmartEndpointing.waitMs("cancel"))
        assertEquals(SmartEndpointing.FAST_MS, SmartEndpointing.waitMs("mark Smiths done"))
    }

    @Test
    fun `a trailing conjunction or hesitation earns a beat`() {
        assertEquals(SmartEndpointing.PATIENT_MS, SmartEndpointing.waitMs("log two hours and"))
        assertEquals(SmartEndpointing.PATIENT_MS, SmartEndpointing.waitMs("what's on tomorrow and"))
        assertEquals(SmartEndpointing.PATIENT_MS, SmartEndpointing.waitMs("put it down for the"))
        assertEquals(SmartEndpointing.PATIENT_MS, SmartEndpointing.waitMs("um"))
    }

    @Test
    fun `ordinary speech keeps the standard wait`() {
        assertEquals(SmartEndpointing.NORMAL_MS, SmartEndpointing.waitMs("the garden needs a cut"))
        assertEquals(SmartEndpointing.NORMAL_MS, SmartEndpointing.waitMs(""))
    }

    @Test
    fun `bluetooth adds exactly one extra beat`() {
        assertEquals(
            SmartEndpointing.FAST_MS + SmartEndpointing.BLUETOOTH_EXTRA_MS,
            SmartEndpointing.waitMs("log that", onBluetooth = true),
        )
        assertEquals(
            SmartEndpointing.NORMAL_MS + SmartEndpointing.BLUETOOTH_EXTRA_MS,
            SmartEndpointing.waitMs("the garden needs a cut", onBluetooth = true),
        )
    }
}
