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
) {
    val totalIncome: Double get() = income.sum()
    val totalExpense: Double get() = expense.sum()
    val balance: Double get() = totalIncome - totalExpense
    val balanceByMonth: List<Double> get() = income.indices.map { income[it] - expense[it] }

    /** Anteil der Einnahmen, der übrig bleibt (null ohne Einnahmen) */
    val savingsRate: Double? get() = if (totalIncome > 0) balance / totalIncome else null

    companion object {
        fun of(items: List<Contract>, year: Int): FinanceSummary {
            val income = MutableList(12) { 0.0 }
            val expense = MutableList(12) { 0.0 }
            val perItem = mutableMapOf<String, Double>()
            val byCategory = mutableMapOf<ContractCategory, Double>()
            items.forEach { item ->
                val payments = item.paymentsIn(year)
                val target = if (item.flow == FlowType.INCOME) income else expense
                payments.forEachIndexed { m, v -> target[m] += v }
                val sum = payments.sum()
                perItem[item.id] = sum
                if (sum > 0) byCategory.merge(item.category, sum, Double::plus)
            }
            fun sorted(flow: FlowType) = byCategory.filterKeys { it.flow == flow }.toList().sortedByDescending { it.second }
            return FinanceSummary(year, income, expense, sorted(FlowType.INCOME), sorted(FlowType.EXPENSE), perItem)
        }
    }
}
