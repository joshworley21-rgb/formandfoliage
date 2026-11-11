package uk.co.jwgardenservices.crm.domain

import java.util.Locale
import kotlin.math.round

/** Round to pennies, the way money is stored in Postgres. */
fun round2(value: Double): Double = round(value * 100.0) / 100.0

fun money(value: Double?): String = String.format(Locale.UK, "£%,.2f", value ?: 0.0)

fun hoursLabel(value: Double?): String {
    val hours = value ?: 0.0
    return if (hours % 1.0 == 0.0) {
        String.format(Locale.UK, "%.0f h", hours)
    } else {
        String.format(Locale.UK, "%.2f h", hours)
    }
}
