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

    /** Stündliche Zeilen mit konstanter Leistung: Netzbezug 1 kW, Verbrauch 2 kW, PV 3 kW. */
    private fun file(from: String, hours: Int, day: Int = 1, month: Int = 3, year: Int = 2025, startHour: Int = 0): String {
        val sb = StringBuilder(header)
        var t = LocalDateTime.of(year, month, day, startHour, 0)
        repeat(hours + 1) {
            sb.append(String.format("%02d.%02d.%04d %02d:%02d:%02d;1,0;0;2,0;0;0;3,0;0;0;0\r\n", t.dayOfMonth, t.monthValue, t.year, t.hour, t.minute, 0))
            t = t.plusMinutes(15).plusMinutes(45)
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
        // 10 Intervalle à 1 h bei 1/2/3 kW → 10 kWh Netz, 20 kWh Verbrauch, 30 kWh PV
        val r = PortalImport.combine(sequenceOf(file("", 10, startHour = 0)))
        val m = r.months.single()
        assertEquals(YearMonth.of(2025, 3), m.month)
        assertEquals(10.0, m.totals.gridImport, 1e-9)
        assertEquals(20.0, m.totals.consumption, 1e-9)
        assertEquals(30.0, m.totals.pv, 1e-9)
    }

    @Test
    fun ordersFilesAndSkipsDuplicates() {
        val a = file("", 5, day = 1)                 // 01.03. 00:00 – 05:00 → 5 kWh
        val b = file("", 5, day = 1, startHour = 6)  // 06:00 – 11:00 → 5 kWh
        val r = PortalImport.combine(sequenceOf(b, a, a)) // Reihenfolge egal, Duplikat wird ausgelassen
        assertEquals(10.0, r.months.single().totals.gridImport, 1e-9)
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
        val a = file("", 2, day = 1)
        val b = file("", 2, day = 2) // 22 h Lücke dazwischen
        val r = PortalImport.combine(sequenceOf(a, b))
        assertEquals(4.0, r.months.single().totals.gridImport, 1e-9)
    }

    @Test
    fun cutoffDropsSamplesFromTheNewSystem() {
        val a = file("", 10) // 00:00 – 10:00
        val r = PortalImport.combine(sequenceOf(a), cutoff = LocalDateTime.of(2025, 3, 1, 5, 0))
        // Messwerte 00:00 … 04:00 → 4 Intervalle
        assertEquals(4.0, r.months.single().totals.gridImport, 1e-9)
    }

    @Test
    fun reportsIncompleteMonths() {
        val r = PortalImport.combine(sequenceOf(file("", 5)))
        assertTrue(YearMonth.of(2025, 3) in r.incomplete)
    }
}
