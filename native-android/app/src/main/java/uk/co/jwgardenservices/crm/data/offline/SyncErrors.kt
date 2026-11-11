package uk.co.jwgardenservices.crm.data.offline

import io.github.jan.supabase.exceptions.RestException
import java.io.IOException

/** Decides whether a failed write is worth retrying later or needs a person. */
object SyncErrors {
    /**
     * True when the same request could succeed later without any change: no
     * connection, a timeout, a server fault, rate limiting or an expired
     * session that will be refreshed.
     */
    fun isRetryable(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            if (current is IOException) return true
            if (current is RestException && isRetryableStatus(current.statusCode)) return true
            current = current.cause
        }
        return false
    }

    fun isRetryableStatus(status: Int): Boolean =
        status == 401 || status == 408 || status == 425 || status == 429 || status >= 500
}
