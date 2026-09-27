package de.gun642.pvdashboard.contracts

/** Anzeige-Filter der Finanzübersicht. */
enum class FinanceFilter(val label: String) { ALL("Alle"), INCOME("Einnahmen"), EXPENSE("Ausgaben") }

/** Einnahmen und Ausgaben eines Jahres, aus den Zahlungsplänen aller Posten. */
data class FinanceSummary(
    val year: Int,
    /** Einnahmen je Monat (Index 0 = Januar) */
    val income: List<Double>,
    val expense: List<Double>,
    /** Summe je Kategorie im Jahr, absteigend */
    val incomeByCategory: List<Pair<ContractCategory, Double>>,
    val expenseByCategory: List<Pair<ContractCategory, Double>>,
    /** Betrag je Posten (id) im Jahr */
    val perItem: Map<String, Double>,
    /** Betrag je Posten (id) und Monat (Index 0 = Januar) */
    val perItemMonthly: Map<String, List<Double>> = emptyMap(),
    /** Kategorie je Posten (id) */
    val itemCategory: Map<String, ContractCategory> = emptyMap(),
) {
    val totalIncome: Double get() = income.sum()
    val totalExpense: Double get() = expense.sum()
    val balance: Double get() = totalIncome - totalExpense
    val balanceByMonth: List<Double> get() = income.indices.map { income[it] - expense[it] }

    /** Anteil der Einnahmen, der übrig bleibt (null ohne Einnahmen) */
    val savingsRate: Double? get() = if (totalIncome > 0) balance / totalIncome else null

    /** Summen eines Monats [month] (1–12). */
    fun month(month: Int): MonthFigures {
        val i = month - 1
        fun byCategory(flow: FlowType) = perItemMonthly
            .mapNotNull { (id, values) -> itemCategory[id]?.takeIf { it.flow == flow }?.let { it to values[i] } }
            .filter { it.second > 0 }
            .groupBy({ it.first }, { it.second })
            .map { (k, v) -> k to v.sum() }
            .sortedByDescending { it.second }
        return MonthFigures(
            income = income[i],
            expense = expense[i],
            incomeByCategory = byCategory(FlowType.INCOME),
            expenseByCategory = byCategory(FlowType.EXPENSE),
            perItem = perItemMonthly.mapValues { it.value[i] },
        )
    }

    companion object {
        fun of(items: List<Contract>, year: Int): FinanceSummary {
            val income = MutableList(12) { 0.0 }
            val expense = MutableList(12) { 0.0 }
            val perItem = mutableMapOf<String, Double>()
            val perItemMonthly = mutableMapOf<String, List<Double>>()
            val byCategory = mutableMapOf<ContractCategory, Double>()
            items.forEach { item ->
                val payments = item.paymentsIn(year)
                val target = if (item.flow == FlowType.INCOME) income else expense
                payments.forEachIndexed { m, v -> target[m] += v }
                val sum = payments.sum()
                perItem[item.id] = sum
                perItemMonthly[item.id] = payments
                if (sum > 0) byCategory.merge(item.category, sum, Double::plus)
            }
            fun sorted(flow: FlowType) = byCategory.filterKeys { it.flow == flow }.toList().sortedByDescending { it.second }
            return FinanceSummary(
                year, income, expense, sorted(FlowType.INCOME), sorted(FlowType.EXPENSE), perItem,
                perItemMonthly, items.associate { it.id to it.category },
            )
        }
    }
}

/** Einnahmen und Ausgaben eines einzelnen Monats (tatsächliche Zahlungstermine). */
data class MonthFigures(
    val income: Double,
    val expense: Double,
    val incomeByCategory: List<Pair<ContractCategory, Double>>,
    val expenseByCategory: List<Pair<ContractCategory, Double>>,
    val perItem: Map<String, Double>,
) {
    val balance: Double get() = income - expense
    val savingsRate: Double? get() = if (income > 0) balance / income else null
}
