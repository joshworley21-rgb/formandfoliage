package uk.co.jwgardenservices.crm.domain

import java.time.LocalDate

data class QuoteLine(val quantity: Double, val unitPrice: Double)

data class QuoteTotals(
    val subtotal: Double,
    val vatAmount: Double,
    val total: Double,
)

fun lineAmount(item: QuoteLine): Double = round2(item.quantity * item.unitPrice)

/** Price a whole quote. VAT only applies when the business is VAT registered. */
fun quoteTotals(
    items: List<QuoteLine>,
    vatRegistered: Boolean?,
    vatRate: Double?,
): QuoteTotals {
    val subtotal = round2(items.sumOf { lineAmount(it) })
    val rate = if (vatRegistered == true) (vatRate ?: 0.0) else 0.0
    val vatAmount = round2(subtotal * (rate / 100.0))
    return QuoteTotals(
        subtotal = subtotal,
        vatAmount = vatAmount,
        total = round2(subtotal + vatAmount),
    )
}

/**
 * A quote is past its date when valid_until has gone by. The stored status is
 * left alone: this is what the UI shows, so an untouched quote still reads as
 * expired without a background job having to sweep the table.
 */
fun isQuoteExpired(
    status: String,
    validUntil: String?,
    today: LocalDate = LocalDate.now(),
): Boolean {
    if (status != "sent" || validUntil == null) return false
    return validUntil < today.toString()
}

fun displayQuoteStatus(
    status: String,
    validUntil: String?,
    today: LocalDate = LocalDate.now(),
): String = if (isQuoteExpired(status, validUntil, today)) "expired" else status

/** Where a customer goes to accept or decline. */
fun quoteLink(publicKey: String, origin: String): String =
    "${origin.trimEnd('/')}/quote/$publicKey"
