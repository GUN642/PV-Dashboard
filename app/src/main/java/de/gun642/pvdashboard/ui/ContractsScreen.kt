package de.gun642.pvdashboard.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import de.gun642.pvdashboard.MainViewModel
import de.gun642.pvdashboard.contracts.BillingInterval
import de.gun642.pvdashboard.contracts.Contract
import de.gun642.pvdashboard.contracts.ContractCategory
import de.gun642.pvdashboard.contracts.ContractSort
import androidx.compose.foundation.horizontalScroll
import androidx.compose.runtime.collectAsState
import de.gun642.pvdashboard.contracts.NoticeUnit
import de.gun642.pvdashboard.notify.Notifier
import de.gun642.pvdashboard.ui.theme.EnergyColors
import de.gun642.pvdashboard.ui.theme.VoidTheme
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val contractDate = DateTimeFormatter.ofPattern("dd.MM.yyyy")

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

/** Übersicht der Verträge mit Kosten und Kündigungsfristen. */
@Composable
fun ContractsSection(vm: MainViewModel, onEdit: (Contract?) -> Unit) {
    val c = VoidTheme.colors
    val today = LocalDate.now()
    val sort = vm.settings.collectAsState().value.contractSort
    val contracts = sort.apply(vm.contracts, today)

    if (contracts.isEmpty()) {
        Tile(Modifier.fillMaxWidth()) {
            Label("Noch keine Verträge")
            Spacer(Modifier.height(6.dp))
            Text(
                "Lege Strom, Wasser, Versicherungen, Internet, Abos usw. an. Die App zeigt die Kosten und erinnert rechtzeitig vor der Kündigungsfrist.",
                color = c.text, style = MaterialTheme.typography.bodyMedium,
            )
        }
    } else {
        // Kostenübersicht
        Tile(Modifier.fillMaxWidth()) {
            Label("Kosten aller Verträge")
            val monthly = contracts.sumOf { it.monthlyCost }
            BigValue(String.format(Locale.GERMANY, "%.2f", monthly), "€ / Monat", size = 44)
            Label(String.format(Locale.GERMANY, "%.2f € pro Jahr", monthly * 12))
            Spacer(Modifier.height(8.dp))
            contracts.groupBy { it.category }.toList()
                .sortedByDescending { (_, list) -> list.sumOf { it.monthlyCost } }
                .forEach { (category, list) ->
                    ValueRow(category.label, String.format(Locale.GERMANY, "%.2f €/Monat", list.sumOf { it.monthlyCost }))
                }
        }

        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ContractSort.entries.forEach { option ->
                Pill(option.label, sort == option, { vm.updateSettings { copy(contractSort = option) } })
            }
        }
        contracts.forEach { contract -> ContractTile(contract, today) { onEdit(contract) } }
    }
}

@Composable
private fun ContractTile(contract: Contract, today: LocalDate, onClick: () -> Unit) {
    val c = VoidTheme.colors
    val deadline = contract.nextDeadline(today)
    val days = contract.daysUntilDeadline(today)
    val due = contract.reminderDue(today)
    Tile(Modifier.fillMaxWidth(), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(contract.name, color = c.text, style = MaterialTheme.typography.titleMedium)
                Label(listOf(contract.category.label, contract.provider).filter { it.isNotBlank() }.joinToString(" · "))
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(formatEuro(contract.amount), color = c.text, style = MaterialTheme.typography.titleMedium)
                Label(contract.interval.label)
            }
        }
        Spacer(Modifier.height(8.dp))
        val status = when {
            deadline != null -> "Kündigen bis ${deadline.format(contractDate)} · in $days Tagen"
            contract.termEnd == null -> "Unbefristet – Frist ${contract.noticeValue} ${contract.noticeUnit.label}"
            else -> contract.currentTermEnd(today)?.let { "Kündigungsfrist verpasst – läuft bis ${it.format(contractDate)}" } ?: "Beendet"
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dot(if (due) c.accent else EnergyColors.gridExport, 7.dp)
            Spacer(Modifier.width(8.dp))
            Label(status, color = if (due) c.accent else c.textMuted)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ContractDialog(
    initial: Contract?,
    onDismiss: () -> Unit,
    onSave: (Contract) -> Unit,
    onDelete: (Contract) -> Unit,
) {
    val c = VoidTheme.colors
    val base = initial ?: Contract(name = "")
    var name by rememberSaveable { mutableStateOf(base.name) }
    var category by rememberSaveable { mutableStateOf(base.category) }
    var provider by rememberSaveable { mutableStateOf(base.provider) }
    var number by rememberSaveable { mutableStateOf(base.contractNumber) }
    var amount by rememberSaveable { mutableStateOf(base.amount) }
    var interval by rememberSaveable { mutableStateOf(base.interval) }
    var termEndText by rememberSaveable { mutableStateOf(base.termEnd?.format(contractDate).orEmpty()) }
    var renewal by rememberSaveable { mutableStateOf(base.renewalMonths.toDouble()) }
    var noticeValue by rememberSaveable { mutableStateOf(base.noticeValue.toDouble()) }
    var noticeUnit by rememberSaveable { mutableStateOf(base.noticeUnit) }
    var remind by rememberSaveable { mutableStateOf(base.remindDaysBefore.toDouble()) }
    var notes by rememberSaveable { mutableStateOf(base.notes) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    val termEnd = termEndText.trim().takeIf { it.isNotEmpty() }?.let { runCatching { LocalDate.parse(it, contractDate) }.getOrNull() }
    val termEndInvalid = termEndText.isNotBlank() && termEnd == null
    val result = base.copy(
        name = name.trim(), category = category, provider = provider.trim(), contractNumber = number.trim(),
        amount = amount, interval = interval, termEnd = termEnd, renewalMonths = renewal.toInt(),
        noticeValue = noticeValue.toInt(), noticeUnit = noticeUnit, remindDaysBefore = remind.toInt(), notes = notes.trim(),
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        title = { Text(if (initial == null) "Neuer Vertrag" else "Vertrag bearbeiten", color = c.text) },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                VoidTextField(name, { name = it }, "Bezeichnung", placeholder = "z. B. Hausratversicherung")
                Label("Kategorie")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ContractCategory.entries.forEach { k -> Pill(k.label, category == k, { category = k }) }
                }
                VoidTextField(provider, { provider = it }, "Anbieter")
                VoidTextField(number, { number = it }, "Vertragsnummer (optional)")
                NumberField(amount, { amount = it }, "Betrag", "€")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    BillingInterval.entries.forEach { i -> Pill(i.label, interval == i, { interval = i }) }
                }
                Spacer(Modifier.height(4.dp))
                Label("Laufzeit & Kündigung")
                VoidTextField(termEndText, { termEndText = it }, "Laufzeit bis (TT.MM.JJJJ)", placeholder = "leer = unbefristet", keyboardType = KeyboardType.Number)
                if (termEndInvalid) Text("Datum im Format TT.MM.JJJJ", color = c.accent, style = MaterialTheme.typography.bodySmall)
                NumberField(renewal, { renewal = it }, "Verlängerung danach", "Monate")
                NumberField(noticeValue, { noticeValue = it }, "Kündigungsfrist", noticeUnit.label)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    NoticeUnit.entries.forEach { u -> Pill(u.label, noticeUnit == u, { noticeUnit = u }) }
                }
                NumberField(remind, { remind = it }, "Erinnern vor Fristende", "Tage")
                result.nextDeadline()?.let { Label("Nächste Frist: ${it.format(contractDate)}", color = c.text) }
                VoidTextField(notes, { notes = it }, "Notizen (optional)")
                if (initial != null) {
                    TextButton(onClick = { confirmDelete = true }) {
                        Text("VERTRAG LÖSCHEN", style = MaterialTheme.typography.labelLarge, color = c.accent)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank() && !termEndInvalid, onClick = { onSave(result) }) {
                Text("SPEICHERN", style = MaterialTheme.typography.labelLarge, color = c.accent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("ABBRECHEN", style = MaterialTheme.typography.labelLarge, color = c.textMuted) }
        },
    )

    if (confirmDelete && initial != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = c.surface,
            title = { Text("Vertrag löschen", color = c.text) },
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
