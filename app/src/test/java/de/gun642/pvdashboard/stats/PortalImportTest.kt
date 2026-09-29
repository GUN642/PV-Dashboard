package de.gun642.pvdashboard.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.YearMonth

class PortalImportTest {
    private val header = "Uhrzeit;Netzbezug [kW];Netzeinspeisung [kW];Stromverbrauch [kW];Akkubeladung [kW];Akkuentnahme [kW];" +
        "Stromerzeugung [kW];Akku Spannung [V];Akku Stromstärke [A];Akku Füllstand [%]\r\n"

    /**
     * Messwerte im 5-Minuten-Takt am [day].03.2025 ab [startMinute] Minuten nach Mitternacht, [intervals] Intervalle
     * (also intervals + 1 Zeilen) bei konstant Netzbezug 1 kW, Verbrauch 2 kW, PV 3 kW.
     */
    private fun file(day: Int, startMinute: Int, intervals: Int): String {
        val sb = StringBuilder(header)
        repeat(intervals + 1) { i ->
            val minute = startMinute + 5 * i
            sb.append(String.format("%02d.03.2025 %02d:%02d:00;1,0;0;2,0;0;0;3,0;0;0;0\r\n", day, minute / 60, minute % 60))
        }
        return sb.toString()
    }

    @Test
    fun recognizesPortalFilesByHeader() {
        assertTrue(PortalImport.isPortalFile("﻿" + header))
        assertFalse(PortalImport.isPortalFile("Jahr;Monat;PV;Verbrauch\n2024;5;1;1"))
    }

    @Test
    fun integratesPowerToEnergyPerMonth() {
        // 12 Intervalle à 5 min = 1 h bei 1/2/3 kW → 1 kWh Netz, 2 kWh Verbrauch, 3 kWh PV
        val r = PortalImport.combine(sequenceOf(file(1, 0, 12)))
        val m = r.months.single()
        assertEquals(YearMonth.of(2025, 3), m.month)
        assertEquals(1.0, m.totals.gridImport, 1e-9)
        assertEquals(2.0, m.totals.consumption, 1e-9)
        assertEquals(3.0, m.totals.pv, 1e-9)
    }

    @Test
    fun ordersFilesAndSkipsDuplicates() {
        val a = file(1, 0, 12)   // 00:00 – 01:00 → 1 kWh
        val b = file(1, 120, 12) // 02:00 – 03:00 → 1 kWh (die Lücke dazwischen zählt nicht)
        val r = PortalImport.combine(sequenceOf(b, a, a)) // Reihenfolge egal, Duplikat wird ausgelassen
        assertEquals(2.0, r.months.single().totals.gridImport, 1e-9)
        assertEquals(2, r.files)
        assertEquals(1, r.skipped)
    }

    @Test
    fun stitchesConsecutiveFilesAcrossTheMonthBoundary() {
        fun rows(vararg times: String) = header + times.joinToString("") { "$it;1,0;0;1,0;0;0;0;0;0;0\r\n" }
        val week1 = rows("28.02.2025 23:50:00", "28.02.2025 23:55:00")
        val week2 = rows("01.03.2025 00:00:00", "01.03.2025 00:05:00")
        val r = PortalImport.combine(sequenceOf(week2, week1))
        val byMonth = r.months.associateBy { it.month }
        // Februar: 23:50–23:55 und der Übergang 23:55–00:00 (je 5 min bei 1 kW), März: 00:00–00:05
        assertEquals(2 * (5 / 60.0), byMonth.getValue(YearMonth.of(2025, 2)).totals.gridImport, 1e-9)
        assertEquals(5 / 60.0, byMonth.getValue(YearMonth.of(2025, 3)).totals.gridImport, 1e-9)
    }

    @Test
    fun bigGapsAreNotExtrapolated() {
        val r = PortalImport.combine(sequenceOf(file(1, 0, 12), file(2, 0, 12))) // 23 h Lücke dazwischen
        assertEquals(2.0, r.months.single().totals.gridImport, 1e-9)
    }

    @Test
    fun cutoffDropsSamplesFromTheNewSystem() {
        val r = PortalImport.combine(sequenceOf(file(1, 0, 24)), cutoff = LocalDateTime.of(2025, 3, 1, 1, 0))
        // Messwerte 00:00 … 00:55 → 11 Intervalle
        assertEquals(11 * 5 / 60.0, r.months.single().totals.gridImport, 1e-9)
    }

    @Test
    fun reportsIncompleteMonths() {
        val r = PortalImport.combine(sequenceOf(file(1, 0, 12)))
        assertTrue(YearMonth.of(2025, 3) in r.incomplete)
    }
}
