package uk.co.jwgardenservices.crm.domain

import uk.co.jwgardenservices.crm.data.model.RunningCost

/**
 * How often a cost is paid. The monthly equivalent is the only figure the
 * rest of the app uses, so a weekly insurance premium and an annual one can
 * sit in the same breakdown without anyone doing sums in their head.
 */
enum class CostFrequency(
    val key: String,
    val label: String,
    /** How many times this is paid in a year. */
    val perYear: Double,
) {
    Weekly("weekly", "Weekly", 52.0),
    Fortnightly("fortnightly", "Fortnightly", 26.0),
    Monthly("monthly", "Monthly", 12.0),
    Quarterly("quarterly", "Quarterly", 4.0),
    Annual("annual", "Annual", 1.0),
    ;

    companion object {
        fun from(key: String?): CostFrequency =
            entries.firstOrNull { it.key.equals(key, ignoreCase = true) } ?: Monthly
    }
}

enum class CostCategory(val key: String, val label: String) {
    Vehicle("vehicle", "Vehicle"),
    Fuel("fuel", "Fuel"),
    Insurance("insurance", "Insurance"),
    Tools("tools", "Tools"),
    Software("software", "Software"),
    Premises("premises", "Premises"),
    Other("other", "Other"),
    ;

    companion object {
        fun from(key: String?): CostCategory =
            entries.firstOrNull { it.key.equals(key, ignoreCase = true) } ?: Other
    }
}

fun RunningCost.monthlyAmount(): Double {
    val frequency = CostFrequency.from(frequency)
    return round2(amount * frequency.perYear / 12.0)
}

fun List<RunningCost>.monthlyRunningCosts(): Double = round2(sumOf { it.monthlyAmount() })

fun List<RunningCost>.annualRunningCosts(): Double = round2(monthlyRunningCosts() * 12.0)

fun List<RunningCost>.dailyRunningCosts(): Double = round2(annualRunningCosts() / 365.0)

data class CostBreakdown(
    val category: CostCategory,
    val monthly: Double,
    /** 0..1 of the monthly total. */
    val share: Double,
)

/** Categories ranked by what they cost each month. Empty categories are dropped. */
fun costBreakdown(costs: List<RunningCost>): List<CostBreakdown> {
    val byCategory = costs.groupBy { CostCategory.from(it.category) }
    val total = costs.monthlyRunningCosts()
    return byCategory
        .map { (category, rows) ->
            val monthly = round2(rows.sumOf { it.monthlyAmount() })
            CostBreakdown(
                category = category,
                monthly = monthly,
                share = if (total > 0.0) monthly / total else 0.0,
            )
        }
        .filter { it.monthly > 0.0 }
        .sortedWith(compareByDescending<CostBreakdown> { it.monthly }.thenBy { it.category.label })
}

/** £1.5k rather than £1,500.00 — for the one number that has to fit a card. */
fun moneyCompact(value: Double?): String {
    val amount = value ?: 0.0
    val abs = kotlin.math.abs(amount)
    return when {
        abs >= 1_000_000 -> String.format(java.util.Locale.UK, "£%.1fm", amount / 1_000_000)
        abs >= 10_000 -> String.format(java.util.Locale.UK, "£%.0fk", amount / 1_000)
        abs >= 1_000 -> String.format(java.util.Locale.UK, "£%.1fk", amount / 1_000)
        else -> String.format(java.util.Locale.UK, "£%.0f", amount)
    }
}
