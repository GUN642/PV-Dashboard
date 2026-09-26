package de.gun642.pvdashboard.contracts

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.util.UUID

enum class FlowType(val label: String) { INCOME("Einnahme"), EXPENSE("Ausgabe") }

enum class ContractCategory(val label: String, val flow: FlowType = FlowType.EXPENSE) {
    // Ausgaben
    HOUSING("Wohnen/Miete"),
    POWER("Strom"),
    WATER("Wasser"),
    GAS("Gas/Heizung"),
    INSURANCE("Versicherung"),
    INTERNET("Internet/Telefon"),
    MOBILE("Mobilfunk"),
    STREAMING("Streaming/Abo"),
    MOBILITY("Auto/Mobilität"),
    HOUSEHOLD("Haushalt/Lebensmittel"),
    LEISURE("Freizeit/Hobby"),
    SAVINGS("Sparen/Vorsorge"),
    LOAN("Kredit/Finanzierung"),
    OTHER("Sonstiges"),
    // Einnahmen
    SALARY("Gehalt/Lohn", FlowType.INCOME),
    FEED_IN("Einspeisevergütung", FlowType.INCOME),
    RENTAL("Mieteinnahmen", FlowType.INCOME),
    CHILD_BENEFIT("Kindergeld", FlowType.INCOME),
    CAPITAL("Zinsen/Dividenden", FlowType.INCOME),
    SIDE_INCOME("Nebeneinkünfte", FlowType.INCOME),
    INCOME_OTHER("Sonstige Einnahmen", FlowType.INCOME);

    companion object {
        fun of(flow: FlowType) = entries.filter { it.flow == flow }
        fun default(flow: FlowType) = if (flow == FlowType.INCOME) INCOME_OTHER else OTHER
    }
}

enum class BillingInterval(val label: String, val months: Int) {
    MONTHLY("monatlich", 1),
    QUARTERLY("vierteljährlich", 3),
    HALF_YEARLY("halbjährlich", 6),
    YEARLY("jährlich", 12),
    ONCE("einmalig", 0),
}

enum class NoticeUnit(val label: String) { DAYS("Tage"), WEEKS("Wochen"), MONTHS("Monate") }

/**
 * Ein Posten der Finanzübersicht: Einnahme oder Ausgabe, wiederkehrend oder einmalig,
 * optional mit Vertrag (Laufzeit, Kündigungsfrist).
 */
data class Contract(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val flow: FlowType = FlowType.EXPENSE,
    val category: ContractCategory = ContractCategory.default(flow),
    val provider: String = "",
    val contractNumber: String = "",
    /** Betrag je Abrechnungszeitraum in € */
    val amount: Double = 0.0,
    val interval: BillingInterval = BillingInterval.MONTHLY,
    /** Erste Zahlung (bei „einmalig“: Zahlungsdatum); null = ab Januar */
    val startDate: LocalDate? = null,
    /** Vertrag mit Laufzeit und Kündigungsfrist (sonst reiner Einnahme-/Ausgabeposten) */
    val hasContract: Boolean = true,
    /** Ende der (Mindest-)Laufzeit; null = unbefristet/jederzeit kündbar */
    val termEnd: LocalDate? = null,
    /** Automatische Verlängerung in Monaten nach Laufzeitende (0 = keine) */
    val renewalMonths: Int = 12,
    val noticeValue: Int = 3,
    val noticeUnit: NoticeUnit = NoticeUnit.MONTHS,
    /** Erinnerung so viele Tage vor Ablauf der Kündigungsfrist */
    val remindDaysBefore: Int = 30,
    val notes: String = "",
) {
    /** Durchschnitt pro Monat (einmalige Posten: 0) */
    val monthlyCost: Double get() = if (interval == BillingInterval.ONCE) 0.0 else amount / interval.months

    /** Betrag pro Jahr (einmalige Posten: der Betrag selbst) */
    val yearlyCost: Double get() = if (interval == BillingInterval.ONCE) amount else monthlyCost * 12

    /** Letzter Monat mit Zahlung: nur wenn der Vertrag ohne Verlängerung endet. */
    private val lastPaymentMonth: YearMonth?
        get() = if (hasContract && renewalMonths <= 0 && termEnd != null) YearMonth.from(termEnd.minusDays(1)) else null

    /** Zahlungen im Jahr [year] je Monat (Index 0 = Januar). */
    fun paymentsIn(year: Int): List<Double> {
        val result = MutableList(12) { 0.0 }
        if (interval == BillingInterval.ONCE) {
            val date = startDate ?: return result
            if (date.year == year) result[date.monthValue - 1] = amount
            return result
        }
        val anchor = startDate?.let(YearMonth::from) ?: YearMonth.of(2000, 1)
        val last = lastPaymentMonth
        for (m in 1..12) {
            val month = YearMonth.of(year, m)
            val diff = ChronoUnit.MONTHS.between(anchor, month)
            if (diff < 0 || diff % interval.months != 0L) continue
            if (last != null && month.isAfter(last)) continue
            result[m - 1] = amount
        }
        return result
    }

    /** Ende der aktuellen Laufzeit ab [today] (inklusive Verlängerungen); null = unbefristet oder abgelaufen. */
    fun currentTermEnd(today: LocalDate = LocalDate.now()): LocalDate? {
        if (!hasContract) return null
        var end = termEnd ?: return null
        // Frist für dieses Laufzeitende schon vorbei? Dann zählt die nächste Verlängerung.
        while (deadlineFor(end).isBefore(today)) {
            if (renewalMonths <= 0) return if (end.isBefore(today)) null else end
            end = end.plusMonths(renewalMonths.toLong())
        }
        return end
    }

    /** Letzter Tag, an dem die Kündigung beim Anbieter sein muss. */
    fun deadlineFor(end: LocalDate): LocalDate = when (noticeUnit) {
        NoticeUnit.DAYS -> end.minusDays(noticeValue.toLong())
        NoticeUnit.WEEKS -> end.minusWeeks(noticeValue.toLong())
        NoticeUnit.MONTHS -> end.minusMonths(noticeValue.toLong())
    }

    /** Nächste Kündigungsfrist, die noch nicht verstrichen ist. */
    fun nextDeadline(today: LocalDate = LocalDate.now()): LocalDate? =
        currentTermEnd(today)?.let(::deadlineFor)?.takeIf { !it.isBefore(today) }

    fun daysUntilDeadline(today: LocalDate = LocalDate.now()): Long? =
        nextDeadline(today)?.let { ChronoUnit.DAYS.between(today, it) }

    /** Soll jetzt erinnert werden? */
    fun reminderDue(today: LocalDate = LocalDate.now()): Boolean =
        daysUntilDeadline(today)?.let { it <= remindDaysBefore } ?: false

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("flow", flow.name).put("category", category.name).put("provider", provider)
        .put("startDate", startDate?.toString() ?: JSONObject.NULL).put("hasContract", hasContract)
        .put("contractNumber", contractNumber).put("amount", amount).put("interval", interval.name)
        .put("termEnd", termEnd?.toString() ?: JSONObject.NULL).put("renewalMonths", renewalMonths)
        .put("noticeValue", noticeValue).put("noticeUnit", noticeUnit.name)
        .put("remindDaysBefore", remindDaysBefore).put("notes", notes)

    companion object {
        private inline fun <reified T : Enum<T>> enumOr(value: String?, default: T): T =
            enumValues<T>().firstOrNull { it.name == value } ?: default

        private fun date(o: JSONObject, key: String): LocalDate? =
            o.optString(key).takeIf { it.isNotBlank() && it != "null" }?.let(LocalDate::parse)

        fun fromJson(o: JSONObject): Contract {
            val flow = enumOr(o.optString("flow"), FlowType.EXPENSE)
            return Contract(
            id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
            name = o.optString("name"),
            flow = flow,
            category = enumOr(o.optString("category"), ContractCategory.default(flow)),
            startDate = date(o, "startDate"),
            // Ältere Einträge waren alle Verträge
            hasContract = o.optBoolean("hasContract", true),
            provider = o.optString("provider"),
            contractNumber = o.optString("contractNumber"),
            amount = o.optDouble("amount", 0.0),
            interval = enumOr(o.optString("interval"), BillingInterval.MONTHLY),
            termEnd = date(o, "termEnd"),
            renewalMonths = o.optInt("renewalMonths", 12),
            noticeValue = o.optInt("noticeValue", 3),
            noticeUnit = enumOr(o.optString("noticeUnit"), NoticeUnit.MONTHS),
            remindDaysBefore = o.optInt("remindDaysBefore", 30),
            notes = o.optString("notes"),
            )
        }
    }
}

enum class ContractSort(val label: String) {
    DEADLINE("Frist"),
    NAME("Name"),
    AMOUNT_DESC("Betrag ↓"),
    AMOUNT_ASC("Betrag ↑");

    /** Beim Betrag zählt der Betrag pro Jahr, damit jährliche, monatliche und einmalige Posten vergleichbar sind. */
    fun apply(list: List<Contract>, today: LocalDate = LocalDate.now()): List<Contract> {
        val byName = compareBy<Contract> { it.name.lowercase() }
        return when (this) {
            DEADLINE -> list.sortedWith(compareBy<Contract> { it.nextDeadline(today) ?: LocalDate.MAX }.then(byName))
            NAME -> list.sortedWith(byName)
            AMOUNT_DESC -> list.sortedWith(compareByDescending<Contract> { it.yearlyCost }.then(byName))
            AMOUNT_ASC -> list.sortedWith(compareBy<Contract> { it.yearlyCost }.then(byName))
        }
    }
}

/** Verträge als JSON im App-Speicher. */
class ContractStore(context: Context) {
    private val file = File(context.filesDir, "contracts.json")

    fun load(): List<Contract> =
        if (!file.exists()) emptyList() else runCatching { fromJson(file.readText()) }.getOrDefault(emptyList())

    fun save(list: List<Contract>) {
        val tmp = File(file.parentFile, "contracts.json.tmp")
        tmp.writeText(toJson(list))
        tmp.renameTo(file)
    }

    companion object {
        fun toJson(list: List<Contract>): String = JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()

        fun fromJson(text: String): List<Contract> {
            val arr = JSONArray(text)
            return (0 until arr.length()).map { Contract.fromJson(arr.getJSONObject(it)) }
        }
    }
}
