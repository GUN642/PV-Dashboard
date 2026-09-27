package de.gun642.pvdashboard.wallbox

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import androidx.core.content.res.ResourcesCompat
import de.gun642.pvdashboard.R
import java.io.OutputStream
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A4-Monatsbericht der Ladevorgänge, z. B. für die Abrechnung eines Dienstwagens. */
object ChargePdf {
    private const val PAGE_W = 595
    private const val PAGE_H = 842
    private const val MARGIN = 42f
    private val TEXT = Color.parseColor("#111111")
    private val MUTED = Color.parseColor("#6B6B6B")
    private val LINE = Color.parseColor("#DDDDDD")
    private val RED = Color.parseColor("#D71921")
    private val de = Locale.GERMANY

    private fun euro(v: Double) = String.format(de, "%,.2f €", v)
    private fun kwh(v: Double) = String.format(de, "%,.2f kWh", v)
    private fun pct(v: Double) = String.format(de, "%.0f %%", v * 100)

    fun write(
        context: Context, out: OutputStream, month: YearMonth, log: ChargeMonth,
        priceCent: Double, feedInCent: Double, today: LocalDate = LocalDate.now(),
    ) {
        val grotesk = ResourcesCompat.getFont(context, R.font.space_grotesk) ?: Typeface.SANS_SERIF
        val mono = ResourcesCompat.getFont(context, R.font.space_mono) ?: Typeface.MONOSPACE
        val doc = PdfDocument()
        try {
            var pageNo = 0
            var page: PdfDocument.Page? = null
            var y = 0f
            val monthName = month.format(DateTimeFormatter.ofPattern("MMMM yyyy", de))

            fun paint(size: Float, color: Int = TEXT, font: Typeface = grotesk, bold: Boolean = false, align: Paint.Align = Paint.Align.LEFT) =
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    textSize = size; this.color = color; textAlign = align
                    typeface = if (bold) Typeface.create(font, Typeface.BOLD) else font
                }

            fun newPage() {
                page?.let { p ->
                    p.canvas.drawText("Ladebericht $monthName · Seite $pageNo", PAGE_W / 2f, PAGE_H - 24f, paint(8f, MUTED, mono, align = Paint.Align.CENTER))
                    doc.finishPage(p)
                }
                pageNo++
                page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNo).create())
                y = MARGIN
            }

            fun ensure(space: Float) {
                if (page == null || y + space > PAGE_H - MARGIN - 20) newPage()
            }

            newPage()
            val c = { page!!.canvas }
            c().drawText("Ladebericht Wallbox", MARGIN, y + 22, paint(22f, bold = true))
            c().drawText(monthName, MARGIN, y + 42, paint(12f, MUTED))
            c().drawText("Erstellt am ${today.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))}", PAGE_W - MARGIN, y + 42, paint(9f, MUTED, mono, align = Paint.Align.RIGHT))
            y += 70

            // Zusammenfassung
            val summary = listOf(
                "Ladevorgänge" to log.sessions.size.toString(),
                "Geladen" to kwh(log.kwh),
                "davon eigener Strom" to "${kwh(log.solarKwh)} (${pct(log.solarShare)})",
                "davon Netzstrom" to kwh(log.gridKwh),
                "Netzstrom × ${String.format(de, "%.2f", priceCent)} ct" to euro(log.sessions.sumOf { it.gridCost(priceCent) }),
                "Eigener Strom × ${String.format(de, "%.2f", feedInCent)} ct (entgangene Einspeisung)" to euro(log.sessions.sumOf { it.lostFeedIn(feedInCent) }),
                "Kosten gesamt" to euro(log.cost(priceCent, feedInCent)),
            )
            summary.forEachIndexed { i, (label, value) ->
                val last = i == summary.lastIndex
                c().drawText(label, MARGIN, y, paint(10.5f, if (last) TEXT else MUTED, bold = last))
                c().drawText(value, PAGE_W - MARGIN, y, paint(10.5f, if (last) RED else TEXT, bold = last, align = Paint.Align.RIGHT))
                y += 17
            }
            y += 18

            // Tabelle
            val cols = floatArrayOf(MARGIN, MARGIN + 120, MARGIN + 250, MARGIN + 330, MARGIN + 410, PAGE_W - MARGIN)
            fun header() {
                val h = paint(8.5f, MUTED, mono)
                c().drawText("DATUM", cols[0], y, h)
                c().drawText("ZEIT", cols[1], y, h)
                c().drawText("KWH", cols[3] - 8, y, paint(8.5f, MUTED, mono, align = Paint.Align.RIGHT))
                c().drawText("EIGEN", cols[4] - 8, y, paint(8.5f, MUTED, mono, align = Paint.Align.RIGHT))
                c().drawText("NETZ", cols[4] + 40, y, paint(8.5f, MUTED, mono, align = Paint.Align.RIGHT))
                c().drawText("KOSTEN", cols[5], y, paint(8.5f, MUTED, mono, align = Paint.Align.RIGHT))
                y += 6
                c().drawLine(MARGIN, y, PAGE_W - MARGIN, y, Paint().apply { color = LINE })
                y += 14
            }
            ensure(40f)
            header()
            val dateF = DateTimeFormatter.ofPattern("EE dd.MM.", de)
            val timeF = DateTimeFormatter.ofPattern("HH:mm")
            if (log.sessions.isEmpty()) {
                c().drawText("Keine Ladevorgänge in diesem Monat.", MARGIN, y, paint(10f, MUTED))
            }
            log.sessions.forEach { s ->
                if (page == null || y + 16 > PAGE_H - MARGIN - 20) {
                    newPage()
                    header()
                }
                val row = paint(10f)
                c().drawText(s.start.format(dateF), cols[0], y, row)
                c().drawText("${s.start.format(timeF)}–${s.end.format(timeF)}", cols[1], y, paint(10f, font = mono))
                c().drawText(String.format(de, "%.2f", s.kwh), cols[3] - 8, y, paint(10f, align = Paint.Align.RIGHT))
                c().drawText(pct(s.solarShare), cols[4] - 8, y, paint(10f, align = Paint.Align.RIGHT))
                c().drawText(String.format(de, "%.2f", s.gridKwh), cols[4] + 40, y, paint(10f, align = Paint.Align.RIGHT))
                c().drawText(euro(s.cost(priceCent, feedInCent)), cols[5], y, paint(10f, align = Paint.Align.RIGHT))
                y += 16
            }
            y += 16
            ensure(40f)
            val note = paint(8f, MUTED)
            c().drawText("Ladevorgänge aus stündlichen Messwerten der SENEC-Cloud; Zeiten auf die Stunde genau.", MARGIN, y, note)
            y += 11
            c().drawText("Eigener Strom = Anteil ohne Netzbezug (PV direkt oder aus dem Speicher), wie bei der Autarkie.", MARGIN, y, note)

            page?.let { p ->
                p.canvas.drawText("Ladebericht $monthName · Seite $pageNo", PAGE_W / 2f, PAGE_H - 24f, paint(8f, MUTED, mono, align = Paint.Align.CENTER))
                doc.finishPage(p)
            }
            doc.writeTo(out)
        } finally {
            doc.close()
        }
    }
}
