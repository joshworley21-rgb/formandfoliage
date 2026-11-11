package uk.co.jwgardenservices.crm.data.offline

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import uk.co.jwgardenservices.crm.data.local.QueuedOpDto
import uk.co.jwgardenservices.crm.data.model.Client

class OfflineQueueTest {
    @Test
    fun `client payload uses server column names and keeps defaults`() {
        val client = Client(id = "c1", userId = "u1", name = "Mrs Smith", hourlyRate = 30.0)
        val payload = OfflineJson.encodeToJsonElement(Client.serializer(), client).jsonObject

        assertEquals("u1", payload["user_id"]?.jsonPrimitive?.content)
        assertEquals("hourly", payload["billing_mode"]?.jsonPrimitive?.content)
        assertEquals("false", payload["payment_reminders"]?.jsonPrimitive?.content)
        assertFalse(payload.containsKey("phone"))
        assertFalse(payload.containsKey("created_at"))
    }

    @Test
    fun `an update can set a value back to its default`() {
        val client = Client(
            id = "c1",
            userId = "u1",
            name = "A",
            fixedPrice = 0.0,
            paymentReminders = false,
        )
        val payload = OfflineJson.encodeToJsonElement(Client.serializer(), client).jsonObject

        assertEquals("0.0", payload["fixed_price"]?.jsonPrimitive?.content)
        assertEquals("false", payload["payment_reminders"]?.jsonPrimitive?.content)
    }

    @Test
    fun `queued operations survive a round trip through the outbox column`() {
        val ops = listOf(
            QueuedOpDto(
                kind = QueuedOpDto.INSERT,
                table = "clients",
                payload = buildJsonObject {
                    put("id", "c1")
                    put("name", "Mrs Smith")
                },
            ),
            QueuedOpDto(
                kind = QueuedOpDto.UPDATE,
                table = "clients",
                payload = buildJsonObject { put("name", "Mrs Smyth") },
                match = buildJsonObject { put("id", "c1") },
            ),
            QueuedOpDto(
                kind = QueuedOpDto.RPC,
                fn = "staff_complete_job",
                args = buildJsonObject {
                    put("_job_id", "j1")
                    put("_hours", 2.0)
                },
            ),
        )

        val encoded = OfflineJson.encodeToString(ops)
        val decoded = OfflineJson.decodeFromString<List<QueuedOpDto>>(encoded)

        assertEquals(ops, decoded)
    }
}
