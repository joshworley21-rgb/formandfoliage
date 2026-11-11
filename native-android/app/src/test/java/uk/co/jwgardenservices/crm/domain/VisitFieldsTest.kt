package uk.co.jwgardenservices.crm.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VisitFieldsTest {
    private fun form(
        locked: Boolean = false,
        missed: Boolean = false,
        payMode: PayMode = PayMode.HOURLY,
        hours: String = "2",
        materials: String = "0",
        rate: String = "30",
        days: String = "1",
    ) = VisitMoneyInput(locked, missed, payMode, hours, materials, rate, days)

    @Test
    fun `hourly work is the rate times the hours`() {
        assertEquals(60.0, visitAmount(form()), 0.0001)
    }

    @Test
    fun `materials are added on top`() {
        assertEquals(72.5, visitAmount(form(materials = "12.50")), 0.0001)
    }

    @Test
    fun `a day rate ignores the hours entirely`() {
        assertEquals(
            90.0,
            visitAmount(form(payMode = PayMode.DAY, rate = "180", days = "0.5", hours = "99")),
            0.0001,
        )
    }

    @Test
    fun `a missed visit is worth nothing however the form is filled in`() {
        assertEquals(
            0.0,
            visitAmount(form(missed = true, rate = "200", hours = "8", materials = "40")),
            0.0001,
        )
    }

    @Test
    fun `empty and nonsense figures count as zero rather than NaN`() {
        assertEquals(0.0, visitAmount(form(hours = "", rate = "")), 0.0001)
        assertEquals(0.0, visitAmount(form(hours = "abc", materials = "oops")), 0.0001)
    }

    @Test
    fun `an ordinary save writes every money column`() {
        val fields = visitMoneyFields(form(hours = "3", materials = "5"))
        assertEquals(3.0, fields.hours!!, 0.0001)
        assertEquals(5.0, fields.materialsCost!!, 0.0001)
        assertEquals(95.0, fields.amount!!, 0.0001)
        assertEquals(false, fields.missed)
    }

    @Test
    fun `a missed save zeroes the hours and materials, not just the charge`() {
        val fields = visitMoneyFields(form(missed = true, hours = "3", materials = "5"))
        assertEquals(0.0, fields.hours!!, 0.0001)
        assertEquals(0.0, fields.materialsCost!!, 0.0001)
        assertEquals(0.0, fields.amount!!, 0.0001)
        assertEquals(true, fields.missed)
    }

    @Test
    fun `an invoiced visit writes no money at all`() {
        val fields = visitMoneyFields(form(locked = true, hours = "999", materials = "999"))
        assertNull(fields.hours)
        assertNull(fields.materialsCost)
        assertNull(fields.amount)
        assertNull(fields.missed)
    }

    @Test
    fun `an invoiced visit stays locked even when the form says missed`() {
        val fields = visitMoneyFields(form(locked = true, missed = true))
        assertNull(fields.hours)
        assertNull(fields.missed)
    }
}
