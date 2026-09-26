package de.gun642.pvdashboard.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import de.gun642.pvdashboard.MainViewModel
import de.gun642.pvdashboard.contracts.BillingInterval
import de.gun642.pvdashboard.contracts.Contract
import de.gun642.pvdashboard.contracts.ContractCategory
import de.gun642.pvdashboard.contracts.ContractSort
import de.gun642.pvdashboard.contracts.FinanceFilter
import de.gun642.pvdashboard.contracts.FinanceSummary
import de.gun642.pvdashboard.contracts.FlowType
import de.gun642.pvdashboard.contracts.NoticeUnit
import de.gun642.pvdashboard.notify.Notifier
import de.gun642.pvdashboard.ui.theme.EnergyColors
import de.gun642.pvdashboard.ui.theme.VoidTheme
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

private val contractDate = DateTimeFormatter.ofPattern("dd.MM.yyyy")
private val deLocale = Locale.GERMANY

/** Farbe für Einnahmen (grün) bzw. Ausgaben (Akzentfarbe). */
@Composable
private fun flowColor(flow: FlowType): Color = if (flow == FlowType.INCOME) EnergyColors.gridExport else VoidTheme.colors.accent

private fun signedEuro(flow: FlowType, v: Double) = (if (flow == FlowType.INCOME) "+" else "−") + formatEuro(v)

/** Fragt (ab Android 13) nach der Erlaubnis für Benachrichtigungen. */
@Composable
fun rememberNotificationPermissionRequest(): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    return {
        if (Build.VERSION.SDK_INT >= 33 && !Notifier.permitted(context)) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

/** Neuer Posten mit sinnvollen Vorgaben. */
fun newItem(flow: FlowType) = Contract(
    name = "",
    flow = flow,
    category = ContractCategory.default(flow),
    hasContract = false,
    startDate = LocalDate.now().withDayOfMonth(1),
)

/** Finanzübersicht: Einnahmen, Ausgaben, Bilanz, Kategorien, Posten und PDF-Export. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ContractsSection(vm: MainViewModel, onEdit: (Contract, Boolean) -> Unit) {
    val c = VoidTheme.colors
    val today = LocalDate.now()
    val settings = vm.settings.collectAsState().value
    val year = vm.financeYear
    val summary = remember(vm.contracts, year) { vm.financeSummary(year) }
    val pdfSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) vm.exportFinancePdf(uri)
    }

    // Jahr wählen
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { vm.financeYear = year - 1 }) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Vorjahr", tint = c.text)
        }
        Text(year.toString(), style = MaterialTheme.typography.titleLarge, color = c.text, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
        IconButton(onClick = { vm.financeYear = year + 1 }) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Folgejahr", tint = c.text)
        }
    }

    if (vm.contracts.isEmpty()) {
        Tile(Modifier.fillMaxWidth()) {
            Label("Noch keine Einnahmen oder Ausgaben")
            Spacer(Modifier.height(6.dp))
            Text(
                "Lege Einnahmen (Gehalt, Einspeisevergütung, Mieteinnahmen …) und Ausgaben (Miete, Strom, Versicherungen, Abos, Lebensmittel …) an – " +
                    "monatlich, jährlich oder einmalig. Bei Verträgen erinnert die App vor der Kündigungsfrist.",
                color = c.text, style = MaterialTheme.typography.bodyMedium,
            )
        }
    } else {
        BalanceTile(summary)
        CashflowTile(summary)
        CategoryTile("Einnahmen nach Kategorie", summary.incomeByCategory, summary.totalIncome, flowColor(FlowType.INCOME))
        CategoryTile("Ausgaben nach Kategorie", summary.expenseByCategory, summary.totalExpense, flowColor(FlowType.EXPENSE))

        // Filter und Sortierung
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FinanceFilter.entries.forEach { f -> Pill(f.label, vm.financeFilter == f, { vm.financeFilter = f }) }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ContractSort.entries.forEach { option ->
                Pill(option.label, settings.contractSort == option, { vm.updateSettings { copy(contractSort = option) } })
            }
        }
        val visible = settings.contractSort.apply(vm.contracts, today).filter {
            when (vm.financeFilter) {
                FinanceFilter.ALL -> true
                FinanceFilter.INCOME -> it.flow == FlowType.INCOME
                FinanceFilter.EXPENSE -> it.flow == FlowType.EXPENSE
            }
        }
        visible.forEach { item -> ItemTile(item, summary.perItem[item.id] ?: 0.0, year, today) { onEdit(item, false) } }
    }

    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Pill("+ Einnahme", selected = true, onClick = { onEdit(newItem(FlowType.INCOME), true) })
        Pill("+ Ausgabe", selected = true, onClick = { onEdit(newItem(FlowType.EXPENSE), true) })
        if (vm.contracts.isNotEmpty()) {
            Pill("PDF speichern", selected = false, onClick = { pdfSaver.launch(vm.financePdfName()) })
            Pill("PDF teilen", selected = false, onClick = { vm.shareFinancePdf() })
        }
    }
}

@Composable
private fun BalanceTile(s: FinanceSummary) {
    val c = VoidTheme.colors
    val positive = s.balance >= 0
    Tile(Modifier.fillMaxWidth()) {
        Label(if (positive) "Überschuss ${s.year}" else "Fehlbetrag ${s.year}")
        BigValue(String.format(deLocale, "%,.2f", s.balance), "€", color = if (positive) EnergyColors.gridExport else c.accent, size = 44)
        Label(String.format(deLocale, "Ø %,.2f € pro Monat", s.balance / 12))
        Spacer(Modifier.height(10.dp))
        ValueRow("Einnahmen", "+" + formatEuro(s.totalIncome), dot = flowColor(FlowType.INCOME))
        ValueRow("Ausgaben", "−" + formatEuro(s.totalExpense), dot = flowColor(FlowType.EXPENSE))
        Hairline()
        ValueRow("Ø Einnahmen / Monat", formatEuro(s.totalIncome / 12))
        ValueRow("Ø Ausgaben / Monat", formatEuro(s.totalExpense / 12))
        s.savingsRate?.let { ValueRow("Sparquote", formatPercent(it.coerceAtLeast(-9.99)), emphasize = true) }
    }
}

@Composable
private fun CashflowTile(s: FinanceSummary) {
    Tile(Modifier.fillMaxWidth()) {
        Label("Je Monat")
        Spacer(Modifier.height(10.dp))
        BarChart(
            labels = (1..12).map { java.time.Month.of(it).getDisplayName(java.time.format.TextStyle.SHORT, deLocale).take(3) },
            series = listOf(flowColor(FlowType.INCOME) to s.income, flowColor(FlowType.EXPENSE) to s.expense),
            seriesNames = listOf("Einnahmen", "Ausgaben"),
            unit = "€",
            minScale = 100.0,
            detailLabels = (1..12).map { m ->
                YearMonth.of(s.year, m).format(DateTimeFormatter.ofPattern("MMMM yyyy", deLocale)) +
                    " · Bilanz " + String.format(deLocale, "%+,.2f €", s.balanceByMonth[m - 1])
            },
        )
        Spacer(Modifier.height(8.dp))
        Legend(listOf("Einnahmen" to flowColor(FlowType.INCOME), "Ausgaben" to flowColor(FlowType.EXPENSE)))
    }
}

@Composable
private fun CategoryTile(title: String, data: List<Pair<ContractCategory, Double>>, total: Double, color: Color) {
    if (data.isEmpty()) return
    Tile(Modifier.fillMaxWidth()) {
        Label(title)
        Spacer(Modifier.height(6.dp))
        data.forEach { (category, sum) ->
            val share = if (total > 0) sum / total else 0.0
            ValueRow(category.label, String.format(deLocale, "%s · %.0f %%", formatEuro(sum), share * 100))
            DotBar(share.toFloat(), Modifier.fillMaxWidth().height(6.dp), dots = 40, color = color)
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun ItemTile(item: Contract, inYear: Double, year: Int, today: LocalDate, onClick: () -> Unit) {
    val c = VoidTheme.colors
    Tile(Modifier.fillMaxWidth(), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(item.name, color = c.text, style = MaterialTheme.typography.titleMedium)
                Label(listOf(item.category.label, item.provider).filter { it.isNotBlank() }.joinToString(" · "))
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(signedEuro(item.flow, item.amount), color = flowColor(item.flow), style = MaterialTheme.typography.titleMedium)
                Label(
                    if (item.interval == BillingInterval.ONCE) item.startDate?.format(contractDate) ?: "einmalig"
                    else item.interval.label
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Label("$year: ${formatEuro(inYear)}")
        if (item.hasContract) {
            val deadline = item.nextDeadline(today)
            val due = item.reminderDue(today)
            val status = when {
                deadline != null -> "Kündigen bis ${deadline.format(contractDate)} · in ${item.daysUntilDeadline(today)} Tagen"
                item.termEnd == null -> "Unbefristet – Frist ${item.noticeValue} ${item.noticeUnit.label}"
                else -> item.currentTermEnd(today)?.let { "Kündigungsfrist verpasst – läuft bis ${it.format(contractDate)}" } ?: "Beendet"
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Dot(if (due) c.accent else EnergyColors.gridExport, 7.dp)
                Spacer(Modifier.width(8.dp))
                Label(status, color = if (due) c.accent else c.textMuted)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ContractDialog(
    initial: Contract,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: (Contract) -> Unit,
    onDelete: (Contract) -> Unit,
) {
    val c = VoidTheme.colors
    val base = initial
    var flow by rememberSaveable { mutableStateOf(base.flow) }
    var name by rememberSaveable { mutableStateOf(base.name) }
    var category by rememberSaveable { mutableStateOf(base.category) }
    var provider by rememberSaveable { mutableStateOf(base.provider) }
    var number by rememberSaveable { mutableStateOf(base.contractNumber) }
    var amount by rememberSaveable { mutableStateOf(base.amount) }
    var interval by rememberSaveable { mutableStateOf(base.interval) }
    var startText by rememberSaveable { mutableStateOf(base.startDate?.format(contractDate).orEmpty()) }
    var hasContract by rememberSaveable { mutableStateOf(base.hasContract) }
    var termEndText by rememberSaveable { mutableStateOf(base.termEnd?.format(contractDate).orEmpty()) }
    var renewal by rememberSaveable { mutableStateOf(base.renewalMonths.toDouble()) }
    var noticeValue by rememberSaveable { mutableStateOf(base.noticeValue.toDouble()) }
    var noticeUnit by rememberSaveable { mutableStateOf(base.noticeUnit) }
    var remind by rememberSaveable { mutableStateOf(base.remindDaysBefore.toDouble()) }
    var notes by rememberSaveable { mutableStateOf(base.notes) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    fun parse(text: String) = text.trim().takeIf { it.isNotEmpty() }?.let { runCatching { LocalDate.parse(it, contractDate) }.getOrNull() }
    val startDate = parse(startText)
    val startInvalid = startText.isNotBlank() && startDate == null
    val startMissing = interval == BillingInterval.ONCE && startDate == null
    val termEnd = if (hasContract) parse(termEndText) else null
    val termEndInvalid = hasContract && termEndText.isNotBlank() && termEnd == null
    val result = base.copy(
        flow = flow, name = name.trim(), category = category, provider = provider.trim(), contractNumber = number.trim(),
        amount = amount, interval = interval, startDate = startDate, hasContract = hasContract, termEnd = termEnd,
        renewalMonths = renewal.toInt(), noticeValue = noticeValue.toInt(), noticeUnit = noticeUnit,
        remindDaysBefore = remind.toInt(), notes = notes.trim(),
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        title = {
            Text(
                (if (isNew) "Neue " else "") + flow.label + (if (isNew) "" else " bearbeiten"),
                color = c.text,
            )
        },
        text = {
            Column(Modifier.heightIn(max = 540.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FlowType.entries.forEach { f ->
                        Pill(f.label, flow == f, {
                            if (flow != f) {
                                flow = f
                                if (category.flow != f) category = ContractCategory.default(f)
                            }
                        })
                    }
                }
                VoidTextField(
                    name, { name = it }, "Bezeichnung",
                    placeholder = if (flow == FlowType.INCOME) "z. B. Gehalt" else "z. B. Hausratversicherung",
                )
                Label("Kategorie")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ContractCategory.of(flow).forEach { k -> Pill(k.label, category == k, { category = k }) }
                }
                VoidTextField(provider, { provider = it }, if (flow == FlowType.INCOME) "Von (optional)" else "Anbieter / Empfänger (optional)")
                NumberField(amount, { amount = it }, "Betrag", "€")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    BillingInterval.entries.forEach { i -> Pill(i.label, interval == i, { interval = i }) }
                }
                VoidTextField(
                    startText, { startText = it },
                    if (interval == BillingInterval.ONCE) "Datum (TT.MM.JJJJ)" else "Erste Zahlung / fällig ab (TT.MM.JJJJ)",
                    placeholder = if (interval == BillingInterval.ONCE) "Pflichtfeld" else "leer = ab Januar",
                    keyboardType = KeyboardType.Number,
                )
                if (startInvalid) Text("Datum im Format TT.MM.JJJJ", color = c.accent, style = MaterialTheme.typography.bodySmall)
                if (startMissing && !startInvalid) Text("Bei einmaligen Posten bitte das Datum angeben", color = c.accent, style = MaterialTheme.typography.bodySmall)

                Toggle("Vertrag mit Kündigungsfrist", "Laufzeit, Verlängerung und Erinnerung vor Fristende", hasContract) { hasContract = it }
                if (hasContract) {
                    VoidTextField(number, { number = it }, "Vertragsnummer (optional)")
                    VoidTextField(termEndText, { termEndText = it }, "Laufzeit bis (TT.MM.JJJJ)", placeholder = "leer = unbefristet", keyboardType = KeyboardType.Number)
                    if (termEndInvalid) Text("Datum im Format TT.MM.JJJJ", color = c.accent, style = MaterialTheme.typography.bodySmall)
                    NumberField(renewal, { renewal = it }, "Verlängerung danach", "Monate")
                    NumberField(noticeValue, { noticeValue = it }, "Kündigungsfrist", noticeUnit.label)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        NoticeUnit.entries.forEach { u -> Pill(u.label, noticeUnit == u, { noticeUnit = u }) }
                    }
                    NumberField(remind, { remind = it }, "Erinnern vor Fristende", "Tage")
                    result.nextDeadline()?.let { Label("Nächste Frist: ${it.format(contractDate)}", color = c.text) }
                }
                VoidTextField(notes, { notes = it }, "Notizen (optional)")
                if (!isNew) {
                    TextButton(onClick = { confirmDelete = true }) {
                        Text("LÖSCHEN", style = MaterialTheme.typography.labelLarge, color = c.accent)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && !termEndInvalid && !startInvalid && !startMissing,
                onClick = { onSave(result) },
            ) { Text("SPEICHERN", style = MaterialTheme.typography.labelLarge, color = c.accent) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("ABBRECHEN", style = MaterialTheme.typography.labelLarge, color = c.textMuted) }
        },
    )

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = c.surface,
            title = { Text("Löschen", color = c.text) },
            text = { Text("„${initial.name}“ wirklich löschen?", color = c.textMuted) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete(initial) }) {
                    Text("LÖSCHEN", style = MaterialTheme.typography.labelLarge, color = c.accent)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text("ABBRECHEN", style = MaterialTheme.typography.labelLarge, color = c.textMuted)
                }
            },
        )
    }
}
