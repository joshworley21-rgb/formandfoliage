package uk.co.jwgardenservices.crm.domain

import java.time.LocalDate
import uk.co.jwgardenservices.crm.data.model.Invoice
import uk.co.jwgardenservices.crm.data.model.Visit

/**
 * Rough self-assessment estimate for a sole trader, assuming no other income,
 * the standard personal allowance and England/Wales/NI rates. The 2025/26
 * thresholds are frozen through 2028, so they are held as constants.
 *
 * Profit is estimated from the app's own data: net invoiced minus materials
 * logged on visits in the tax year. Recharged materials land in both, so
 * they cancel out.
 */
object SelfAssessment {
    const val PERSONAL_ALLOWANCE = 12570.0
    const val BASIC_RATE_UPPER = 50270.0
    const val ADDITIONAL_RATE_UPPER = 125140.0
    const val INCOME_TAX_BASIC = 0.20
    const val INCOME_TAX_HIGHER = 0.40
    const val INCOME_TAX_ADDITIONAL = 0.45
    const val CLASS4_MAIN = 0.06
    const val CLASS4_UPPER = 0.02
}

data class SelfAssessmentEstimate(
    val profit: Double,
    val incomeTax: Double,
    val class4: Double,
) {
    val total: Double get() = round2(incomeTax + class4)

    /** Effective % of profit owed, for sanity checks. */
    val effectiveRate: Double
        get() = if (profit > 0) round2(total / profit * 100) else 0.0
}

/** Income tax above the allowance plus Class 4 NICs on [profit]. */
fun estimateSelfAssessmentTax(profit: Double): SelfAssessmentEstimate {
    val p = profit.coerceAtLeast(0.0)

    val taxable = (p - SelfAssessment.PERSONAL_ALLOWANCE).coerceAtLeast(0.0)
    val basicTaxable = taxable.coerceAtMost(SelfAssessment.BASIC_RATE_UPPER - SelfAssessment.PERSONAL_ALLOWANCE)
    val higherTaxable = (taxable - basicTaxable)
        .coerceAtMost(SelfAssessment.ADDITIONAL_RATE_UPPER - SelfAssessment.BASIC_RATE_UPPER)
    val additionalTaxable = (taxable - basicTaxable - higherTaxable).coerceAtLeast(0.0)
    val incomeTax = round2(
        basicTaxable * SelfAssessment.INCOME_TAX_BASIC +
            higherTaxable * SelfAssessment.INCOME_TAX_HIGHER +
            additionalTaxable * SelfAssessment.INCOME_TAX_ADDITIONAL,
    )

    // Class 4: 6% between the allowance and the upper limit, 2% above it.
    val class4Main = (
        p.coerceAtMost(SelfAssessment.BASIC_RATE_UPPER) -
            SelfAssessment.PERSONAL_ALLOWANCE
        ).coerceAtLeast(0.0)
    val class4Upper = (p - SelfAssessment.BASIC_RATE_UPPER).coerceAtLeast(0.0)
    val class4 = round2(
        class4Main * SelfAssessment.CLASS4_MAIN + class4Upper * SelfAssessment.CLASS4_UPPER,
    )

    return SelfAssessmentEstimate(profit = round2(p), incomeTax = incomeTax, class4 = class4)
}

/**
 * Estimated profit for the UK tax year containing [today]: non-draft invoiced
 * (net) minus materials logged on that year's visits.
 */
fun estimatedTaxYearProfit(
    invoices: List<Invoice>,
    visits: List<Visit>,
    today: LocalDate = LocalDate.now(),
): Double {
    val year = ukTaxYearContaining(today)
    val inYear = { date: String? ->
        parseDate(date)?.let { d -> !d.isBefore(year.start) && !d.isAfter(year.end) } == true
    }
    val invoiced = invoices
        .filter { it.status != "draft" && inYear(it.issueDate) }
        .sumOf { it.subtotal.coerceAtLeast(0.0) }
    val materials = visits
        .filter { !it.missed && inYear(it.visitDate) }
        .sumOf { it.materialsCost.coerceAtLeast(0.0) }
    return round2(invoiced - materials)
}
