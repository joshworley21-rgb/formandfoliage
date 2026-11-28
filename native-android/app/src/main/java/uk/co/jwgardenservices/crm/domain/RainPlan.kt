package uk.co.jwgardenservices.crm.domain

import java.time.LocalDate

/** One day of the forecast: the chance of rain and how much is expected. */
data class DayRain(val chancePercent: Int, val millimetres: Double)

/**
 * When a day is too wet for garden work, and where its jobs could go.
 * Plain logic so it can be tested without a network.
 */
object RainPlan {
    /** A wet day: rain is likely and there is enough of it to stop work. */
    fun isWet(day: DayRain?): Boolean =
        day != null && day.chancePercent >= 60 && day.millimetres >= 2.0

    /** The first day after [from] in the forecast that is not wet, or null if none is known. */
    fun nextDryDay(from: LocalDate, forecast: Map<LocalDate, DayRain>): LocalDate? =
        forecast.keys.sorted().firstOrNull { it.isAfter(from) && !isWet(forecast[it]) }

    /** A short phrase for a wet day: "80% chance, about 6 mm". */
    fun describe(day: DayRain): String =
        "${day.chancePercent}% chance, about ${"%.0f".format(day.millimetres)} mm"
}
