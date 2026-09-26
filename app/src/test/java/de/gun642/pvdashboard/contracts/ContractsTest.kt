package de.gun642.pvdashboard.contracts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ContractsTest {
    private val today = LocalDate.of(2026, 9, 26)

    @Test
    fun deadlineBeforeTermEnd() {
        val c = Contract(name = "Hausrat", termEnd = LocalDate.of(2026, 12, 31), noticeValue = 3, noticeUnit = NoticeUnit.MONTHS)
        assertEquals(LocalDate.of(2026, 9, 30), c.nextDeadline(today))
        assertEquals(4L, c.daysUntilDeadline(today))
        assertTrue(c.reminderDue(today))
    }

    @Test
    fun rollsOverWhenDeadlineMissed() {
        // Laufzeit endete 30.11.2025 und hat sich um 12 Monate verlängert
        val c = Contract(name = "Strom", termEnd = LocalDate.of(2025, 11, 30), renewalMonths = 12, noticeValue = 6, noticeUnit = NoticeUnit.WEEKS)
        // 30.11.2025 → 30.11.2026, Frist 6 Wochen vorher = 19.10.2026
        assertEquals(LocalDate.of(2026, 11, 30), c.currentTermEnd(today))
        assertEquals(LocalDate.of(2026, 10, 19), c.nextDeadline(today))
        assertTrue(c.reminderDue(today)) // 23 Tage vorher, Erinnerung ab 30 Tagen
    }

    @Test
    fun noRenewalMeansNoFutureDeadline() {
        val c = Contract(name = "Kredit", termEnd = LocalDate.of(2026, 10, 1), renewalMonths = 0, noticeValue = 3, noticeUnit = NoticeUnit.MONTHS)
        assertNull(c.nextDeadline(today))
        assertEquals(LocalDate.of(2026, 10, 1), c.currentTermEnd(today))
    }

    @Test
    fun costsPerMonthAndYear() {
        val c = Contract(name = "Kfz", amount = 360.0, interval = BillingInterval.YEARLY)
        assertEquals(30.0, c.monthlyCost, 1e-9)
        assertEquals(360.0, c.yearlyCost, 1e-9)
        assertNull(c.nextDeadline(today)) // unbefristet
    }

    @Test
    fun jsonRoundTrip() {
        val list = listOf(
            Contract(name = "Hausrat", category = ContractCategory.INSURANCE, provider = "Muster AG", amount = 12.5, termEnd = LocalDate.of(2027, 1, 1), notes = "Nr. 123"),
            Contract(name = "Netflix", category = ContractCategory.STREAMING, amount = 13.99),
        )
        assertEquals(list, ContractStore.fromJson(ContractStore.toJson(list)))
    }
}

class ContractSortTest {
    private val today = LocalDate.of(2026, 9, 26)
    private val kfz = Contract(name = "Kfz", amount = 480.0, interval = BillingInterval.YEARLY, termEnd = LocalDate.of(2027, 1, 1), noticeValue = 1)
    private val internet = Contract(name = "Internet", amount = 45.0, termEnd = LocalDate.of(2026, 12, 31), noticeValue = 1)
    private val abo = Contract(name = "abo", amount = 9.99)

    @Test
    fun sortsByName() {
        assertEquals(listOf("abo", "Internet", "Kfz"), ContractSort.NAME.apply(listOf(kfz, internet, abo), today).map { it.name })
    }

    @Test
    fun sortsByMonthlyAmount() {
        // Kfz 480 €/Jahr = 40 €/Monat, Internet 45 €/Monat
        assertEquals(listOf("Internet", "Kfz", "abo"), ContractSort.AMOUNT_DESC.apply(listOf(kfz, abo, internet), today).map { it.name })
        assertEquals(listOf("abo", "Kfz", "Internet"), ContractSort.AMOUNT_ASC.apply(listOf(internet, kfz, abo), today).map { it.name })
    }

    @Test
    fun sortsByDeadlineWithUnlimitedLast() {
        assertEquals(listOf("Internet", "Kfz", "abo"), ContractSort.DEADLINE.apply(listOf(abo, kfz, internet), today).map { it.name })
    }
}
