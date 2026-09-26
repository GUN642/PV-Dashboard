package de.gun642.pvdashboard.contracts

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import androidx.core.content.res.ResourcesCompat
import de.gun642.pvdashboard.R
import de.gun642.pvdashboard.ui.ChartScale
import java.io.OutputStream
import java.time.LocalDate
import java.time.Month
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** A4-Bericht „Einnahmen & Ausgaben“ als PDF. */
object FinancePdf {
    private const val PAGE_W = 595 // A4 in Punkt
    private const val PAGE_H = 842
    private const val MARGIN = 42f

    private val RED = Color.parseColor("#D71921")
    private val GREEN = Color.parseColor("#1E8E3E")
    private val TEXT = Color.parseColor("#111111")
    private val MUTED = Color.parseColor("#6B6B6B")
    private val LINE = Color.parseColor("#DDDDDD")
    private val de = Locale.GERMANY

    private fun euro(v: Double) = String.format(de, "%,.2f €", v)

    fun write(context: Context, out: OutputStream, summary: FinanceSummary, items: List<Contract>, today: LocalDate = LocalDate.now()) {
        val grotesk = ResourcesCompat.getFont(context, R.font.space_grotesk) ?: Typeface.SANS_SERIF
        val mono = ResourcesCompat.getFont(context, R.font.space_mono) ?: Typeface.MONOSPACE
        val w = Writer(PdfDocument(), grotesk, mono, "Einnahmen & Ausgaben ${summary.year}")
        try {
            w.title("Einnahmen & Ausgaben ${summary.year}", "Erstellt am ${today.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))}")
            w.summaryBoxes(summary)
            w.chart(summary)
            w.monthTable(summary)
            w.categoryTable("Einnahmen nach Kategorie", summary.incomeByCategory, summary.totalIncome, GREEN)
            w.categoryTable("Ausgaben nach Kategorie", summary.expenseByCategory, summary.totalExpense, RED)
            w.itemTable(summary, items, today)
            w.finish(out)
        } finally {
            w.close()
        }
    }

    private class Writer(val doc: PdfDocument, val grotesk: Typeface, val mono: Typeface, val footerTitle: String) {
        private var page: PdfDocument.Page? = null
        private var pageNo = 0
        private var y = 0f
        private val canvas: Canvas get() = page!!.canvas
        private val contentW = PAGE_W - 2 * MARGIN

        fun paint(size: Float, color: Int = TEXT, bold: Boolean = false, monoFont: Boolean = false, align: Paint.Align = Paint.Align.LEFT) =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = size
                this.color = color
                typeface = Typeface.create(if (monoFont) mono else grotesk, if (bold) Typeface.BOLD else Typeface.NORMAL)
                textAlign = align
            }

        init {
            newPage()
        }

        private fun newPage() {
            page?.let { finishPage(it) }
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNo).create())
            y = MARGIN
        }

        private fun finishPage(p: PdfDocument.Page) {
            val c = p.canvas
            val f = paint(8f, MUTED, monoFont = true)
            c.drawText("VOID HOME DASHBOARD · ${footerTitle.uppercase(de)}", MARGIN, PAGE_H - 24f, f)
            f.textAlign = Paint.Align.RIGHT
            c.drawText("SEITE $pageNo", PAGE_W - MARGIN, PAGE_H - 24f, f)
            doc.finishPage(p)
        }

        /** Platz reservieren, sonst neue Seite. */
        private fun ensure(height: Float) {
            if (y + height > PAGE_H - 48f) newPage()
        }

        private fun fit(text: String, p: Paint, width: Float): String {
            if (p.measureText(text) <= width) return text
            var t = text
            while (t.isNotEmpty() && p.measureText("$t…") > width) t = t.dropLast(1)
            return "$t…"
        }

        fun title(title: String, subtitle: String) {
            canvas.drawText("VOID HOME DASHBOARD", MARGIN, y + 8f, paint(8f, MUTED, monoFont = true))
            y += 34f
            canvas.drawText(title, MARGIN, y, paint(24f, bold = true))
            y += 16f
            canvas.drawText(subtitle, MARGIN, y, paint(9f, MUTED))
            y += 10f
            canvas.drawRect(MARGIN, y, MARGIN + 36f, y + 3f, Paint().apply { color = RED })
            y += 22f
        }

        private fun section(title: String, height: Float) {
            ensure(height + 26f)
            canvas.drawText(title.uppercase(de), MARGIN, y + 8f, paint(8.5f, MUTED, monoFont = true))
            y += 18f
        }

        fun summaryBoxes(s: FinanceSummary) {
            section("Überblick", 92f)
            val boxW = (contentW - 2 * 10f) / 3
            val boxes = listOf(
                Triple("Einnahmen", s.totalIncome, GREEN),
                Triple("Ausgaben", s.totalExpense, RED),
                Triple(if (s.balance >= 0) "Überschuss" else "Fehlbetrag", s.balance, if (s.balance >= 0) GREEN else RED),
            )
            val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = LINE; strokeWidth = 1f }
            boxes.forEachIndexed { i, (label, value, color) ->
                val x = MARGIN + i * (boxW + 10f)
                canvas.drawRoundRect(RectF(x, y, x + boxW, y + 70f), 10f, 10f, border)
                canvas.drawText(label.uppercase(de), x + 12f, y + 18f, paint(7.5f, MUTED, monoFont = true))
                canvas.drawText(euro(value), x + 12f, y + 42f, paint(15f, color, bold = true))
                canvas.drawText("Ø ${euro(value / 12)} / Monat", x + 12f, y + 58f, paint(8f, MUTED))
            }
            y += 80f
            s.savingsRate?.let {
                canvas.drawText(String.format(de, "Sparquote: %.0f %% der Einnahmen bleiben übrig", it * 100), MARGIN, y + 4f, paint(9f, MUTED))
                y += 16f
            }
            y += 8f
        }

        fun chart(s: FinanceSummary) {
            val h = 150f
            section("Einnahmen und Ausgaben je Monat", h + 30f)
            val scale = ChartScale.of((s.income + s.expense).maxOrNull() ?: 0.0, 100.0)
            val left = MARGIN + 44f
            val plotW = PAGE_W - MARGIN - left
            val top = y
            val bottom = y + h
            val tick = paint(7f, MUTED, monoFont = true, align = Paint.Align.RIGHT)
            val grid = Paint().apply { color = LINE; strokeWidth = 0.6f }
            scale.ticks.forEach { t ->
                val yy = bottom - (t / scale.max * h).toFloat()
                canvas.drawLine(left, yy, PAGE_W - MARGIN, yy, grid)
                canvas.drawText(String.format(de, "%,.0f €", t), left - 5f, yy + 2.5f, tick)
            }
            val slot = plotW / 12
            val barW = slot * 0.32f
            val inc = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GREEN }
            val exp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = RED }
            val label = paint(7f, MUTED, monoFont = true, align = Paint.Align.CENTER)
            for (m in 0 until 12) {
                val x = left + slot * m + slot * 0.14f
                val hi = (s.income[m] / scale.max * h).toFloat()
                val he = (s.expense[m] / scale.max * h).toFloat()
                if (hi > 0) canvas.drawRoundRect(RectF(x, bottom - hi, x + barW, bottom), 2f, 2f, inc)
                if (he > 0) canvas.drawRoundRect(RectF(x + barW + 2f, bottom - he, x + 2 * barW + 2f, bottom), 2f, 2f, exp)
                canvas.drawText(Month.of(m + 1).getDisplayName(TextStyle.SHORT, de).take(3), left + slot * m + slot / 2, bottom + 12f, label)
            }
            y = bottom + 20f
            // Legende
            canvas.drawRect(left, y, left + 8f, y + 8f, inc)
            canvas.drawText("Einnahmen", left + 12f, y + 7.5f, paint(8f, MUTED))
            canvas.drawRect(left + 80f, y, left + 88f, y + 8f, exp)
            canvas.drawText("Ausgaben", left + 92f, y + 7.5f, paint(8f, MUTED))
            y = top + h + 44f
        }

        /** Tabelle mit rechtsbündigen Zahlenspalten. [cols] = Anteil der Breite je Spalte. */
        private fun table(
            header: List<String>,
            rows: List<List<String>>,
            cols: List<Float>,
            rowColors: List<Int?> = emptyList(),
            boldLast: Boolean = false,
            right: Set<Int> = cols.indices.drop(1).toSet(),
        ) {
            val rowH = 16f
            val head = paint(7.5f, MUTED, monoFont = true)
            val line = Paint().apply { color = LINE; strokeWidth = 0.6f }
            fun drawHeader() {
                var x = MARGIN
                header.forEachIndexed { i, h ->
                    val w = contentW * cols[i]
                    if (i !in right) canvas.drawText(h.uppercase(de), x, y + 10f, head)
                    else canvas.drawText(h.uppercase(de), x + w - 2f, y + 10f, Paint(head).apply { textAlign = Paint.Align.RIGHT })
                    x += w
                }
                y += 14f
                canvas.drawLine(MARGIN, y, PAGE_W - MARGIN, y, line)
            }
            ensure(rowH * 3)
            drawHeader()
            rows.forEachIndexed { r, row ->
                if (y + rowH > PAGE_H - 48f) {
                    newPage()
                    drawHeader()
                }
                val bold = boldLast && r == rows.lastIndex
                var x = MARGIN
                row.forEachIndexed { i, cell ->
                    val w = contentW * cols[i]
                    val isRight = i in right
                    val color = if (isRight) rowColors.getOrNull(r) ?: TEXT else TEXT
                    val p = paint(9f, color, bold = bold, align = if (isRight) Paint.Align.RIGHT else Paint.Align.LEFT)
                    val text = fit(cell, p, w - 6f)
                    canvas.drawText(text, if (isRight) x + w - 2f else x, y + 12f, p)
                    x += w
                }
                y += rowH
                canvas.drawLine(MARGIN, y, PAGE_W - MARGIN, y, line)
            }
            y += 18f
        }

        fun monthTable(s: FinanceSummary) {
            section("Monatsübersicht", 16f * 15)
            val rows = (0 until 12).map { m ->
                listOf(
                    Month.of(m + 1).getDisplayName(TextStyle.FULL_STANDALONE, de),
                    euro(s.income[m]), euro(s.expense[m]), euro(s.income[m] - s.expense[m]),
                )
            } + listOf(listOf("Summe ${s.year}", euro(s.totalIncome), euro(s.totalExpense), euro(s.balance)))
            val colors = (0 until 12).map { m -> if (s.income[m] - s.expense[m] < 0) RED else null } + (if (s.balance < 0) RED else null)
            table(listOf("Monat", "Einnahmen", "Ausgaben", "Bilanz"), rows, listOf(0.34f, 0.22f, 0.22f, 0.22f), colors, boldLast = true)
        }

        fun categoryTable(title: String, data: List<Pair<ContractCategory, Double>>, total: Double, color: Int) {
            if (data.isEmpty()) return
            section(title, 16f * (data.size + 2))
            val rows = data.map { (cat, sum) ->
                listOf(cat.label, euro(sum / 12), euro(sum), String.format(de, "%.0f %%", if (total > 0) sum / total * 100 else 0.0))
            } + listOf(listOf("Summe", euro(total / 12), euro(total), "100 %"))
            table(listOf("Kategorie", "Ø pro Monat", "pro Jahr", "Anteil"), rows, listOf(0.40f, 0.20f, 0.22f, 0.18f), boldLast = true)
        }

        fun itemTable(s: FinanceSummary, items: List<Contract>, today: LocalDate) {
            if (items.isEmpty()) return
            section("Alle Posten", 16f * 4)
            val format = DateTimeFormatter.ofPattern("dd.MM.yyyy")
            val sorted = items.sortedWith(compareBy<Contract> { it.flow != FlowType.INCOME }.thenByDescending { s.perItem[it.id] ?: 0.0 })
            val rows = sorted.map { c ->
                val name = if (c.provider.isNotBlank()) "${c.name} · ${c.provider}" else c.name
                val interval = if (c.interval == BillingInterval.ONCE) c.startDate?.format(format) ?: "einmalig" else c.interval.label
                val deadline = c.nextDeadline(today)?.let { "bis ${it.format(format)}" } ?: if (c.hasContract) "—" else ""
                listOf(name, c.category.label, (if (c.flow == FlowType.INCOME) "+" else "−") + euro(c.amount), interval, euro(s.perItem[c.id] ?: 0.0), deadline)
            }
            val colors = sorted.map { if (it.flow == FlowType.INCOME) GREEN else null }
            table(
                listOf("Posten", "Kategorie", "Betrag", "Intervall", "im Jahr", "Kündigen"), rows,
                listOf(0.27f, 0.18f, 0.14f, 0.13f, 0.14f, 0.14f), colors, right = setOf(2, 4),
            )
        }

        fun finish(out: OutputStream) {
            page?.let { finishPage(it) }
            page = null
            doc.writeTo(out)
        }

        fun close() {
            doc.close()
        }
    }
}
