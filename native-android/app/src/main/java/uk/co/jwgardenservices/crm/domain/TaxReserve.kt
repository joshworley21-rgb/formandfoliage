package uk.co.jwgardenservices.crm.domain

import java.time.LocalDate
import java.time.Month
import uk.co.jwgardenservices.crm.data.model.Invoice

data class UkTaxYear(val start: LocalDate, val end: LocalDate)

/** The UK self-assessment year containing [date]: 6 April to 5 April. */
fun ukTaxYearContaining(date: LocalDate): UkTaxYear {
    val startYear = if (date.month == Month.APRIL && date.dayOfMonth >= 6 || date.month > Month.APRIL) {
        date.year
    } else {
        date.year - 1
    }
    return UkTaxYear(
        start = LocalDate.of(startYear, Month.APRIL, 6),
        end = LocalDate.of(startYear + 1, Month.APRIL, 5),
    )
}

fun ukTaxYearLabel(year: UkTaxYear): String =
    "${year.start.year}/${String.format("%02d", year.end.year % 100)}"

/** What to put aside: [ratePercent] of the net (ex-VAT) amount. */
fun taxReserveAmount(netAmount: Double, ratePercent: Double): Double =
    round2(netAmount.coerceAtLeast(0.0) * ratePercent / 100.0)

/**
 * Running total to set aside for tax: non-draft invoices issued in the
 * current UK tax year, at [ratePercent] of each net subtotal. The percentage
 * is the user's own knob — tax is charged on profit, so it absorbs their own
 * running costs.
 */
fun taxSetAside(
    invoices: List<Invoice>,
    ratePercent: Double,
    today: LocalDate = LocalDate.now(),
): Double {
    if (ratePercent <= 0.0) return 0.0
    val year = ukTaxYearContaining(today)
    return round2(
        invoices
            .filter { it.status != "draft" }
            .filter { parseDate(it.issueDate)?.let { d -> !d.isBefore(year.start) && !d.isAfter(year.end) } == true }
            .sumOf { taxReserveAmount(it.subtotal, ratePercent) },
    )
}
