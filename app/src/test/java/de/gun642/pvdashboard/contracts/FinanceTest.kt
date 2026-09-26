package de.gun642.pvdashboard.contracts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class FinanceTest {
    private val salary = Contract(name = "Gehalt", flow = FlowType.INCOME, category = ContractCategory.SALARY, amount = 3000.0, hasContract = false)
    private val rent = Contract(name = "Miete", category = ContractCategory.HOUSING, amount = 1000.0, hasContract = false)
    private val car = Contract(
        name = "Kfz-Versicherung", category = ContractCategory.INSURANCE, amount = 480.0,
        interval = BillingInterval.YEARLY, startDate = LocalDate.of(2024, 1, 1),
    )
    private val water = Contract(
        name = "Wasser", category = ContractCategory.WATER, amount = 90.0,
        interval = BillingInterval.QUARTERLY, startDate = LocalDate.of(2025, 2, 15), hasContract = false,
    )
    private val tv = Contract(name = "Fernseher", category = ContractCategory.HOUSEHOLD, amount = 800.0, interval = BillingInterval.ONCE, startDate = LocalDate.of(2026, 11, 20), hasContract = false)

    @Test
    fun paymentScheduleFollowsInterval() {
        assertEquals(List(12) { 3000.0 }, salary.paymentsIn(2026))
        // jährlich im Januar
        assertEquals(480.0, car.paymentsIn(2026)[0], 0.0)
        assertEquals(480.0, car.paymentsIn(2026).sum(), 0.0)
        // vierteljährlich ab Februar: Feb, Mai, Aug, Nov
        val w = water.paymentsIn(2026)
        assertEquals(listOf(1, 4, 7, 10), w.indices.filter { w[it] > 0 })
        // einmalig im November, nicht im Folgejahr
        assertEquals(800.0, tv.paymentsIn(2026)[10], 0.0)
        assertEquals(0.0, tv.paymentsIn(2027).sum(), 0.0)
    }

    @Test
    fun noPaymentsBeforeStartOrAfterEnd() {
        val loan = Contract(
            name = "Kredit", category = ContractCategory.LOAN, amount = 200.0,
            startDate = LocalDate.of(2026, 3, 1), termEnd = LocalDate.of(2026, 9, 1), renewalMonths = 0,
        )
        val p = loan.paymentsIn(2026)
        assertEquals(listOf(2, 3, 4, 5, 6, 7), p.indices.filter { p[it] > 0 }) // März bis August
    }

    @Test
    fun yearlySummary() {
        val s = FinanceSummary.of(listOf(salary, rent, car, water, tv), 2026)
        assertEquals(36000.0, s.totalIncome, 1e-9)
        assertEquals(12000.0 + 480.0 + 360.0 + 800.0, s.totalExpense, 1e-9)
        assertEquals(36000.0 - 13640.0, s.balance, 1e-9)
        assertEquals((36000.0 - 13640.0) / 36000.0, s.savingsRate!!, 1e-9)
        assertEquals(ContractCategory.HOUSING, s.expenseByCategory.first().first)
        assertEquals(listOf(ContractCategory.SALARY to 36000.0), s.incomeByCategory)
        // Januar: Gehalt − Miete − Kfz
        assertEquals(3000.0 - 1000.0 - 480.0, s.balanceByMonth[0], 1e-9)
    }

    @Test
    fun itemsWithoutContractHaveNoDeadline() {
        val item = rent.copy(termEnd = LocalDate.of(2026, 12, 31))
        assertNull(item.nextDeadline(LocalDate.of(2026, 9, 26)))
        assertFalse(item.reminderDue(LocalDate.of(2026, 9, 26)))
    }

    @Test
    fun oldJsonBecomesExpenseContract() {
        val old = org.json.JSONObject("""{"id":"1","name":"Hausrat","category":"INSURANCE","amount":12.5,"interval":"MONTHLY","termEnd":"2027-01-01"}""")
        val c = Contract.fromJson(old)
        assertEquals(FlowType.EXPENSE, c.flow)
        assertEquals(true, c.hasContract)
        assertNull(c.startDate)
        assertEquals(c, Contract.fromJson(c.toJson()))
    }
}
