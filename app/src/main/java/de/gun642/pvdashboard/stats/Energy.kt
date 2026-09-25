package de.gun642.pvdashboard.stats

/** Energiemengen eines Zeitraums in kWh. */
data class EnergyTotals(
    val pv: Double = 0.0,
    val consumption: Double = 0.0,
    val gridImport: Double = 0.0,
    val gridExport: Double = 0.0,
    val batteryCharge: Double = 0.0,
    val batteryDischarge: Double = 0.0,
    val wallbox: Double = 0.0,
) {
    operator fun plus(o: EnergyTotals) = EnergyTotals(
        pv + o.pv,
        consumption + o.consumption,
        gridImport + o.gridImport,
        gridExport + o.gridExport,
        batteryCharge + o.batteryCharge,
        batteryDischarge + o.batteryDischarge,
        wallbox + o.wallbox,
    )

    /** Anteil des Verbrauchs, der nicht aus dem Netz kam (0..1). */
    val autarky: Double?
        get() = if (consumption > 0) ((consumption - gridImport) / consumption).coerceIn(0.0, 1.0) else null

    /** Anteil der PV-Erzeugung, der nicht eingespeist wurde (0..1). */
    val selfConsumption: Double?
        get() = if (pv > 0) ((pv - gridExport) / pv).coerceIn(0.0, 1.0) else null

    companion object {
        /** Namen der Messreihen der SENEC-App-API. */
        fun fromMeasurements(values: Map<String, Double>) = EnergyTotals(
            pv = values["POWER_GENERATION"] ?: 0.0,
            consumption = values["POWER_CONSUMPTION"] ?: 0.0,
            gridImport = values["GRID_IMPORT"] ?: 0.0,
            gridExport = values["GRID_EXPORT"] ?: 0.0,
            batteryCharge = values["BATTERY_IMPORT"] ?: 0.0,
            batteryDischarge = values["BATTERY_EXPORT"] ?: 0.0,
            wallbox = values["WALLBOX_CONSUMPTION"] ?: 0.0,
        )
    }
}

data class Tariff(
    val provider: String,
    val baseFeePerMonth: Double,
    val pricePerKwhCent: Double,
    val feedInCent: Double,
)

/** Kosten eines Zeitraums in Euro. */
data class CostSummary(
    /** Netzbezug × Arbeitspreis */
    val gridCost: Double,
    /** Anteilige Grundgebühr */
    val baseFee: Double,
    /** Einspeisung × Vergütung */
    val feedInRevenue: Double,
    /** Was der gesamte Verbrauch ohne PV-Anlage gekostet hätte */
    val costWithoutPv: Double,
) {
    /** Tatsächliche Stromrechnung abzüglich Einspeisevergütung */
    val netCost: Double get() = gridCost + baseFee - feedInRevenue

    /** Vorteil durch PV und Speicher gegenüber reinem Netzbezug */
    val savings: Double get() = costWithoutPv - netCost

    companion object {
        fun of(totals: EnergyTotals, tariff: Tariff, months: Double): CostSummary {
            val price = tariff.pricePerKwhCent / 100
            val baseFee = tariff.baseFeePerMonth * months
            return CostSummary(
                gridCost = totals.gridImport * price,
                baseFee = baseFee,
                feedInRevenue = totals.gridExport * tariff.feedInCent / 100,
                costWithoutPv = totals.consumption * price + baseFee,
            )
        }
    }
}
