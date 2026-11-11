package uk.co.jwgardenservices.crm.domain

import java.time.LocalDate
import uk.co.jwgardenservices.crm.data.model.Invoice
import uk.co.jwgardenservices.crm.data.model.Visit

/** RFC 4180 quoting, plus a leading apostrophe on formula-looking text so spreadsheets do not run it. */
fun csvField(value: String?): String {
    var text = value.orEmpty()
    if (text.isNotEmpty() && text[0] in "=+-@\t\r" && text.toDoubleOrNull() == null) {
        text = "'$text"
    }
    return if (text.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
        "\"" + text.replace("\"", "\"\"") + "\""
    } else {
        text
    }
}

private fun csv(header: List<String>, rows: List<List<String?>>): String =
    (listOf(header) + rows).joinToString("\r\n", postfix = "\r\n") { row ->
        row.joinToString(",") { csvField(it) }
    }

private fun number(value: Double): String = String.format(java.util.Locale.UK, "%.2f", value)

fun invoicesCsv(invoices: List<Invoice>, clientName: (String) -> String): String = csv(
    listOf("Number", "Client", "Issue date", "Due date", "Status", "Subtotal", "VAT", "Total", "Paid on"),
    invoices.map {
        listOf(
            it.number,
            clientName(it.clientId),
            it.issueDate,
            it.dueDate,
            it.status,
            number(it.subtotal),
            number(it.vatAmount),
            number(it.total),
            it.paidAt?.take(10),
        )
    },
)

fun visitsCsv(visits: List<Visit>, clientName: (String) -> String): String = csv(
    listOf("Date", "Client", "Hours", "Materials", "Amount", "Billed", "Missed", "Description"),
    visits.map {
        listOf(
            it.visitDate,
            clientName(it.clientId),
            number(it.hours),
            number(it.materialsCost),
            number(it.amount),
            if (it.billed) "Yes" else "No",
            if (it.missed) "Yes" else "No",
            it.description,
        )
    },
)

/**
 * One row per invoice in the current UK tax year, with what to set aside
 * for tax at [ratePercent] and what remains after it. The summary row at the
 * end is what the accountant wants at a glance.
 */
fun taxYearCsv(
    invoices: List<Invoice>,
    clientName: (String) -> String,
    ratePercent: Double,
    today: LocalDate = LocalDate.now(),
): String {
    val year = ukTaxYearContaining(today)
    val inYear = invoices
        .filter { it.status != "draft" }
        .filter { parseDate(it.issueDate)?.let { d -> !d.isBefore(year.start) && !d.isAfter(year.end) } == true }
        .sortedBy { it.issueDate }
    val body = inYear.map {
        val pot = taxReserveAmount(it.subtotal, ratePercent)
        listOf(
            it.number,
            clientName(it.clientId),
            it.issueDate,
            it.status,
            number(it.subtotal),
            number(pot),
            number(it.subtotal - pot),
        )
    }
    val net = round2(inYear.sumOf { it.subtotal })
    val pot = round2(inYear.sumOf { taxReserveAmount(it.subtotal, ratePercent) })
    val rows = body + listOf(
        listOf(
            "Tax year ${ukTaxYearLabel(year)}",
            "${inYear.size} invoices",
            "",
            "",
            number(net),
            number(pot),
            number(net - pot),
        ),
    )
    return csv(
        listOf("Number", "Client", "Issue date", "Status", "Net", "Tax pot", "Drawn"),
        rows,
    )
}
