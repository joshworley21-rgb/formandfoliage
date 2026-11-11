package uk.co.jwgardenservices.crm.domain

/*
 * What a visit's money fields become when the form is saved.
 *
 * Two quiet rules: a missed visit is worth nothing whatever is left in the rate
 * boxes, and an invoiced visit's figures are not writable at all. The invoice
 * keeps its own copy of every line, so a visit edited after billing would leave
 * the two disagreeing with nothing to say so.
 */

enum class PayMode { HOURLY, DAY }

data class VisitMoneyInput(
    val locked: Boolean,
    val missed: Boolean,
    val payMode: PayMode,
    val hours: String,
    val materials: String,
    val rate: String,
    val days: String,
)

/** Null means "do not write this column"; see [visitMoneyFields]. */
data class VisitMoneyFields(
    val hours: Double? = null,
    val materialsCost: Double? = null,
    val amount: Double? = null,
    val missed: Boolean? = null,
)

private fun num(value: String): Double = value.toDoubleOrNull() ?: 0.0

/** Labour plus materials, or nothing at all when the visit did not happen. */
fun visitAmount(input: VisitMoneyInput): Double {
    if (input.missed) return 0.0
    val labour = if (input.payMode == PayMode.DAY) {
        num(input.rate) * num(input.days)
    } else {
        num(input.rate) * num(input.hours)
    }
    return round2(labour + num(input.materials))
}

/**
 * The columns a save should write. An invoiced visit contributes none of them,
 * so the update leaves the billed figures exactly as the invoice has them.
 */
fun visitMoneyFields(input: VisitMoneyInput): VisitMoneyFields {
    if (input.locked) return VisitMoneyFields()
    return VisitMoneyFields(
        hours = if (input.missed) 0.0 else num(input.hours),
        materialsCost = if (input.missed) 0.0 else num(input.materials),
        amount = visitAmount(input),
        missed = input.missed,
    )
}
