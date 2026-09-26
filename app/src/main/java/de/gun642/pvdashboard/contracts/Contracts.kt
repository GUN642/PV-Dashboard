package de.gun642.pvdashboard.contracts

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

enum class ContractCategory(val label: String) {
    POWER("Strom"),
    WATER("Wasser"),
    GAS("Gas/Heizung"),
    INSURANCE("Versicherung"),
    INTERNET("Internet/Telefon"),
    MOBILE("Mobilfunk"),
    STREAMING("Streaming/Abo"),
    LOAN("Kredit/Finanzierung"),
    OTHER("Sonstiges"),
}

enum class BillingInterval(val label: String, val months: Int) {
    MONTHLY("monatlich", 1),
    QUARTERLY("vierteljährlich", 3),
    HALF_YEARLY("halbjährlich", 6),
    YEARLY("jährlich", 12),
}

enum class NoticeUnit(val label: String) { DAYS("Tage"), WEEKS("Wochen"), MONTHS("Monate") }

data class Contract(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val category: ContractCategory = ContractCategory.OTHER,
    val provider: String = "",
    val contractNumber: String = "",
    /** Betrag je Abrechnungszeitraum in € */
    val amount: Double = 0.0,
    val interval: BillingInterval = BillingInterval.MONTHLY,
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
    val monthlyCost: Double get() = amount / interval.months
    val yearlyCost: Double get() = monthlyCost * 12

    /** Ende der aktuellen Laufzeit ab [today] (inklusive Verlängerungen); null = unbefristet oder abgelaufen. */
    fun currentTermEnd(today: LocalDate = LocalDate.now()): LocalDate? {
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
        .put("id", id).put("name", name).put("category", category.name).put("provider", provider)
        .put("contractNumber", contractNumber).put("amount", amount).put("interval", interval.name)
        .put("termEnd", termEnd?.toString() ?: JSONObject.NULL).put("renewalMonths", renewalMonths)
        .put("noticeValue", noticeValue).put("noticeUnit", noticeUnit.name)
        .put("remindDaysBefore", remindDaysBefore).put("notes", notes)

    companion object {
        private inline fun <reified T : Enum<T>> enumOr(value: String?, default: T): T =
            enumValues<T>().firstOrNull { it.name == value } ?: default

        fun fromJson(o: JSONObject) = Contract(
            id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
            name = o.optString("name"),
            category = enumOr(o.optString("category"), ContractCategory.OTHER),
            provider = o.optString("provider"),
            contractNumber = o.optString("contractNumber"),
            amount = o.optDouble("amount", 0.0),
            interval = enumOr(o.optString("interval"), BillingInterval.MONTHLY),
            termEnd = o.optString("termEnd").takeIf { it.isNotBlank() && it != "null" }?.let(LocalDate::parse),
            renewalMonths = o.optInt("renewalMonths", 12),
            noticeValue = o.optInt("noticeValue", 3),
            noticeUnit = enumOr(o.optString("noticeUnit"), NoticeUnit.MONTHS),
            remindDaysBefore = o.optInt("remindDaysBefore", 30),
            notes = o.optString("notes"),
        )
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
