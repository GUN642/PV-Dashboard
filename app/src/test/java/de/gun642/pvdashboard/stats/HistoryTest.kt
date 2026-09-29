package de.gun642.pvdashboard.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class HistoryTest {
    @Test
    fun parsesCsvWithGermanNumbersAndMonthNames() {
        val text = "﻿Jahr;Monat;PV;Verbrauch;Netzbezug;Einspeisung;Speicher geladen;Speicher entladen;Wallbox\n" +
            "2024;5;1.234,5;300,25;100;900;150;140;0\n" +
            "2024;Jun;1100;280;90;850\n" +
            "# Kommentar\n" +
            "kaputt;zeile\n" +
            "2024;13;1;1\n"
        val list = HistoryCsv.parse(text)
        assertEquals(2, list.size)
        assertEquals(YearMonth.of(2024, 5), list[0].month)
        assertEquals(1234.5, list[0].totals.pv, 1e-9)
        assertEquals(300.25, list[0].totals.consumption, 1e-9)
        assertEquals(YearMonth.of(2024, 6), list[1].month)
        assertEquals(850.0, list[1].totals.gridExport, 1e-9)
        assertEquals(0.0, list[1].totals.wallbox, 1e-9) // fehlende Spalte
    }

    @Test
    fun formatAndParseRoundTrip() {
        val list = listOf(HistoryMonth(YearMonth.of(2023, 7), EnergyTotals(pv = 1500.5, consumption = 400.0)))
        val back = HistoryCsv.parse(HistoryCsv.format(list))
        assertEquals(1500.5, back.single().totals.pv, 0.05)
        assertNull(HistoryCsv.number("abc"))
    }

    @Test
    fun mergeReplacesSameMonth() {
        val a = listOf(HistoryMonth(YearMonth.of(2024, 1), EnergyTotals(pv = 1.0)), HistoryMonth(YearMonth.of(2024, 2), EnergyTotals(pv = 2.0)))
        val b = listOf(HistoryMonth(YearMonth.of(2024, 2), EnergyTotals(pv = 9.0)))
        val merged = HistoryStore.merge(a, b)
        assertEquals(listOf(1.0, 9.0), merged.map { it.totals.pv })
    }

    @Test
    fun historyAddsUpToStartMonthOfCloudRecording() {
        // Neuer Speicher ab 10.11.2025: Vorjahre und Nov. (Teil davor) aus der Historie, Dez. nur Cloud
        val buckets = (1..12).map { m ->
            StatsBucket(m, "", if (m == 11) EnergyTotals(pv = 100.0) else if (m == 12) EnergyTotals(pv = 200.0) else EnergyTotals())
        }
        val history = listOf(
            HistoryMonth(YearMonth.of(2025, 6), EnergyTotals(pv = 1300.0)),
            HistoryMonth(YearMonth.of(2025, 11), EnergyTotals(pv = 40.0)),
            HistoryMonth(YearMonth.of(2025, 12), EnergyTotals(pv = 999.0)), // nach Beginn → ignoriert
        )
        val out = StatsRepository.overlayYear(2025, buckets, history, LocalDate.of(2025, 11, 10))
        assertEquals(1300.0, out[5].totals.pv, 1e-9)
        assertEquals(140.0, out[10].totals.pv, 1e-9)
        assertEquals(200.0, out[11].totals.pv, 1e-9)
        assertEquals(LocalDate.of(2025, 6, 1), StatsRepository.earliest(LocalDate.of(2025, 11, 10), history))
    }

    @Test
    fun wholeYearOverlayForTotalView() {
        val total = listOf(StatsBucket(2024, "2024", EnergyTotals()))
        val history = listOf(
            HistoryMonth(YearMonth.of(2024, 1), EnergyTotals(pv = 300.0)),
            HistoryMonth(YearMonth.of(2024, 7), EnergyTotals(pv = 1400.0)),
        )
        val out = StatsRepository.overlayYear(2024, total, history, LocalDate.of(2025, 11, 10), wholeYear = true)
        assertEquals(1700.0, out.single().totals.pv, 1e-9)
    }
}
