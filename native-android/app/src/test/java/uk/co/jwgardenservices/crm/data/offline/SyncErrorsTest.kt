package uk.co.jwgardenservices.crm.data.offline

import java.io.IOException
import java.net.UnknownHostException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncErrorsTest {
    @Test
    fun `connection problems are retryable even when wrapped`() {
        assertTrue(SyncErrors.isRetryable(UnknownHostException("no host")))
        assertTrue(SyncErrors.isRetryable(IOException("reset")))
        assertTrue(SyncErrors.isRetryable(IllegalStateException("outer", IOException("inner"))))
    }

    @Test
    fun `server faults rate limits and expired sessions are retryable`() {
        listOf(401, 408, 425, 429, 500, 502, 503, 504).forEach {
            assertTrue("status $it", SyncErrors.isRetryableStatus(it))
        }
    }

    @Test
    fun `validation and permission failures need a person`() {
        listOf(400, 403, 404, 409, 422).forEach {
            assertFalse("status $it", SyncErrors.isRetryableStatus(it))
        }
        assertFalse(SyncErrors.isRetryable(IllegalStateException("Unknown queue operation")))
        assertFalse(SyncErrors.isRetryable(IllegalArgumentException("a message mentioning network")))
    }
}
