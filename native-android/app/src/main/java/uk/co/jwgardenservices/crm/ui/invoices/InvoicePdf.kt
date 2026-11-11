package uk.co.jwgardenservices.crm.ui.invoices

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import uk.co.jwgardenservices.crm.data.model.Client
import uk.co.jwgardenservices.crm.data.model.Invoice
import uk.co.jwgardenservices.crm.data.model.InvoiceItem
import uk.co.jwgardenservices.crm.data.model.Settings
import uk.co.jwgardenservices.crm.domain.formatDayMonth
import uk.co.jwgardenservices.crm.domain.formatDayMonthYear
import uk.co.jwgardenservices.crm.domain.money
import uk.co.jwgardenservices.crm.domain.parseDate

/**
 * The native counterpart of the web app's jsPDF invoice.
 *
 * A4 at 72 points per inch, drawn with the platform PdfDocument so no third
 * party renderer or server is involved. Figures use a monospace face and are
 * right-aligned so a £280 can never hide beside a £28.
 */
object InvoicePdf {
    private const val PAGE_WIDTH = 595
    private const val PAGE_HEIGHT = 842
    private const val MARGIN = 44f
    private const val FOOTER_SPACE = 34f

    private val primary = Color.rgb(47, 107, 65)
    private val muted = Color.rgb(98, 114, 106)
    private val dark = Color.rgb(20, 35, 27)
    private val ruleColor = Color.rgb(226, 231, 221)
    private val bandColor = Color.rgb(238, 242, 234)

    fun build(
        context: Context,
        invoice: Invoice,
        client: Client,
        items: List<InvoiceItem>,
        settings: Settings?,
    ): File {
        val document = PdfDocument()
        var pageNumber = 1
        var page = document.startPage(pageInfo(pageNumber))
        var canvas = page.canvas
        var y = MARGIN

        val title = paint(20f, Typeface.BOLD, dark)
        val heading = paint(13f, Typeface.BOLD, dark)
        val label = paint(8.5f, Typeface.BOLD, primary)
        val body = paint(9.5f, Typeface.NORMAL, dark)
        val small = paint(8.5f, Typeface.NORMAL, muted)
        val mono = paint(9.5f, Typeface.NORMAL, dark, mono = true)
        val rule = Paint().apply {
            color = ruleColor
            strokeWidth = 1f
        }
        val band = Paint().apply { color = bandColor }

        val businessName = settings?.businessName ?: "JW Garden Services"
        val right = PAGE_WIDTH - MARGIN

        fun drawFooter() {
            val footerY = PAGE_HEIGHT - MARGIN
            canvas.drawLine(MARGIN, footerY - 12f, right, footerY - 12f, rule)
            canvas.drawText(businessName, MARGIN, footerY, small)
            drawRight(canvas, "Page $pageNumber", right, footerY, small)
        }

        fun newPage() {
            drawFooter()
            document.finishPage(page)
            pageNumber += 1
            page = document.startPage(pageInfo(pageNumber))
            canvas = page.canvas
            y = MARGIN
        }

        fun ensure(space: Float) {
            if (y + space > PAGE_HEIGHT - MARGIN - FOOTER_SPACE) newPage()
        }

        // ---- Business header -------------------------------------------------
        canvas.drawText(businessName, MARGIN, y + 16f, title)
        y += 30f
        val businessLines = buildList {
            settings?.address?.split("\n")?.forEach { add(it) }
            settings?.phone?.takeIf { it.isNotBlank() }?.let { add(it) }
            settings?.email?.takeIf { it.isNotBlank() }?.let { add(it) }
        }
        businessLines.forEach { line ->
            canvas.drawText(line, MARGIN, y, small)
            y += 12f
        }

        drawRight(canvas, "INVOICE", right, MARGIN + 10f, label)
        drawRight(canvas, invoice.number, right, MARGIN + 31f, heading)
        drawRight(canvas, "Issued ${dateText(invoice.issueDate)}", right, MARGIN + 47f, small)
        invoice.dueDate?.let {
            drawRight(canvas, "Due ${dateText(it)}", right, MARGIN + 60f, small)
        }

        y = maxOf(y, MARGIN + 74f)
        canvas.drawLine(MARGIN, y, right, y, rule)
        y += 20f

        // ---- Who it is for ---------------------------------------------------
        canvas.drawText("INVOICE TO", MARGIN, y, label)
        y += 15f
        canvas.drawText(client.name, MARGIN, y, heading)
        y += 15f
        client.address?.split("\n")?.forEach { line ->
            canvas.drawText(line, MARGIN, y, body)
            y += 12f
        }
        y += 14f

        // ---- Lines -----------------------------------------------------------
        val dateX = MARGIN + 6f
        val descX = MARGIN + 66f
        val qtyRight = MARGIN + 310f
        val rateRight = MARGIN + 390f
        val amountRight = right - 6f
        val descWidth = qtyRight - descX - 74f

        ensure(40f)
        canvas.drawRect(MARGIN, y - 11f, right, y + 8f, band)
        canvas.drawText("DATE", dateX, y, label)
        canvas.drawText("WORK", descX, y, label)
        drawRight(canvas, "QTY", qtyRight, y, label)
        drawRight(canvas, "RATE", rateRight, y, label)
        drawRight(canvas, "AMOUNT", amountRight, y, label)
        y += 19f

        items.forEach { item ->
            ensure(54f)
            val rowTop = y
            val dateLabel = if (item.grouped) "" else {
                parseDate(item.itemDate)?.formatDayMonth() ?: ""
            }
            canvas.drawText(dateLabel, dateX, rowTop, small)
            drawRight(canvas, quantityText(item.quantity), qtyRight, rowTop, mono)
            drawRight(canvas, money(item.unitPrice), rateRight, rowTop, mono)
            drawRight(canvas, money(item.amount), amountRight, rowTop, mono)
            val descEnd = drawParagraph(
                canvas = canvas,
                text = item.description,
                x = descX,
                startY = rowTop,
                maxWidth = descWidth,
                textPaint = body,
                lineHeight = 12f,
            )
            y = maxOf(descEnd, rowTop + 14f) + 7f
            canvas.drawLine(MARGIN, y - 5f, right, y - 5f, rule)
        }

        // ---- Totals ----------------------------------------------------------
        y += 12f
        ensure(100f)
        drawRight(canvas, "Subtotal", right - 100f, y, small)
        drawRight(canvas, money(invoice.subtotal), right, y, mono)
        y += 15f
        if (settings?.vatRegistered == true) {
            drawRight(canvas, "VAT", right - 100f, y, small)
            drawRight(canvas, money(invoice.vatAmount), right, y, mono)
            y += 15f
        }
        canvas.drawLine(right - 190f, y, right, y, rule)
        y += 17f
        drawRight(canvas, "Total", right - 100f, y, heading)
        drawRight(canvas, money(invoice.total), right, y, heading)
        y += 30f

        // ---- Notes, terms and bank details ----------------------------------
        if (!invoice.notes.isNullOrBlank()) {
            ensure(50f)
            canvas.drawText("NOTES", MARGIN, y, label)
            y += 14f
            y = drawParagraph(canvas, invoice.notes, MARGIN, y, right - MARGIN, body, 12f) + 12f
        }

        settings?.paymentTerms?.takeIf { it.isNotBlank() }?.let { terms ->
            ensure(50f)
            canvas.drawText("PAYMENT", MARGIN, y, label)
            y += 14f
            y = drawParagraph(canvas, terms, MARGIN, y, right - MARGIN, body, 12f) + 12f
        }

        settings?.bankDetails?.takeIf { it.isNotBlank() }?.let { bank ->
            ensure(50f)
            canvas.drawText("BANK DETAILS", MARGIN, y, label)
            y += 14f
            bank.split("\n").forEach { line ->
                canvas.drawText(line, MARGIN, y, body)
                y += 12f
            }
        }

        // ---- Footer ----------------------------------------------------------
        drawFooter()
        document.finishPage(page)
        val dir = File(context.cacheDir, "invoices").apply { mkdirs() }
        val safeNumber = invoice.number.replace(Regex("[^A-Za-z0-9._-]"), "-")
        val file = File(dir, "$safeNumber.pdf")
        FileOutputStream(file).use { document.writeTo(it) }
        document.close()
        return file
    }

    private fun pageInfo(number: Int): PdfDocument.PageInfo =
        PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, number).create()

    private fun paint(
        size: Float,
        style: Int,
        color: Int,
        mono: Boolean = false,
    ): Paint = Paint().apply {
        isAntiAlias = true
        textSize = size
        typeface = (if (mono) Typeface.MONOSPACE else Typeface.SANS_SERIF).let {
            Typeface.create(it, style)
        }
        this.color = color
    }

    private fun drawRight(canvas: Canvas, text: String, rightX: Float, y: Float, paint: Paint) {
        canvas.drawText(text, rightX - paint.measureText(text), y, paint)
    }

    /** Draw word-wrapped text and return the y after the last line. */
    private fun drawParagraph(
        canvas: Canvas,
        text: String,
        x: Float,
        startY: Float,
        maxWidth: Float,
        textPaint: Paint,
        lineHeight: Float,
    ): Float {
        var y = startY
        text.split("\n").forEach { paragraph ->
            val words = paragraph.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (words.isEmpty()) {
                y += lineHeight
                return@forEach
            }
            var line = StringBuilder()
            words.forEach { word ->
                val candidate = if (line.isEmpty()) word else "$line $word"
                if (textPaint.measureText(candidate) <= maxWidth || line.isEmpty()) {
                    line = StringBuilder(candidate)
                } else {
                    canvas.drawText(line.toString(), x, y, textPaint)
                    y += lineHeight
                    line = StringBuilder(word)
                }
            }
            if (line.isNotEmpty()) {
                canvas.drawText(line.toString(), x, y, textPaint)
                y += lineHeight
            }
        }
        return y
    }

    private fun quantityText(quantity: Double): String =
        if (quantity % 1.0 == 0.0) {
            quantity.toInt().toString()
        } else {
            String.format(Locale.UK, "%.2f", quantity)
        }

    private fun dateText(value: String?): String =
        parseDate(value)?.formatDayMonthYear() ?: (value ?: "")
}
