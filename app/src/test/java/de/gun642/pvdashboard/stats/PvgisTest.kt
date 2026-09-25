package de.gun642.pvdashboard.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class PvgisTest {
    // Werte aus dem PVGIS-Bericht (9,6 kWp, 24°, Azimut 155°)
    private val monthly = listOf(360.5, 577.6, 970.8, 1166.7, 1235.6, 1347.7, 1354.0, 1228.6, 1012.1, 639.9, 370.3, 312.1)
    private val ref = PvgisReference(monthly)

    @Test
    fun parsesManualInputFromReport() {
        val parsed = PvgisReference.parseMonthly("360,5 577,6 970,8 1166,7 1235,6 1347,7 1354,0 1228,6 1012,1 639,9 370,3 312,1")
        assertEquals(monthly, parsed)
        // Summe der gerundeten Monatswerte; der Bericht nennt gerundet 10576 kWh/Jahr
        assertEquals(10575.9, ref.yearly, 1e-6)
        assertEquals(10576.0, ref.yearly, 0.5)
        assertNull(PvgisReference.parseMonthly("1 2 3"))
        assertNull(PvgisReference.parseMonthly("a b c d e f g h i j k l"))
    }

    @Test
    fun expectedCountsFullDaysAndShareOfToday() {
        val now = LocalDateTime.of(2026, 9, 25, 13, 0) // 13 Uhr = halber Sonnentag
        val sept = 1012.1 / 30
        // 1.–24. voll, der 25. zur Hälfte
        assertEquals(sept * 24.5, ref.expected(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 1), now), 1e-6)
        // Abgeschlossener Monat vollständig
        assertEquals(1228.6, ref.expected(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 9, 1), now), 1e-6)
        // Tag: voller Tagesdurchschnitt als Ziel
        assertEquals(sept, ref.target(Period(PeriodType.DAY, LocalDate.of(2026, 9, 25)), null, now), 1e-9)
    }

    @Test
    fun bucketTargetsPerMonthAndDay() {
        val year = Period(PeriodType.YEAR, LocalDate.of(2026, 1, 1))
        val yearBuckets = (1..12).map { StatsBucket(it, it.toString(), EnergyTotals()) }
        assertEquals(monthly, ref.bucketTargets(year, yearBuckets))
        val month = Period(PeriodType.MONTH, LocalDate.of(2026, 2, 1))
        val dayBuckets = (1..28).map { StatsBucket(it, it.toString(), EnergyTotals()) }
        assertEquals(577.6 / 28, ref.bucketTargets(month, dayBuckets)!![0], 1e-9)
    }

    @Test
    fun parsesApiResponse() {
        val body = """
            {"outputs":{"monthly":{"fixed":[
              {"month":1,"E_d":11.6,"E_m":360.5,"H(i)_d":1.5,"H(i)_m":45.4,"SD_m":20.1},
              {"month":2,"E_m":577.6},{"month":3,"E_m":970.8},{"month":4,"E_m":1166.7},{"month":5,"E_m":1235.6},{"month":6,"E_m":1347.7},
              {"month":7,"E_m":1354.0},{"month":8,"E_m":1228.6},{"month":9,"E_m":1012.1},{"month":10,"E_m":639.9},{"month":11,"E_m":370.3},{"month":12,"E_m":312.1}
            ]},"totals":{"fixed":{"E_y":10576.0}}}}
        """.trimIndent()
        assertEquals(monthly, PvgisReference.parse(body))
    }
}
