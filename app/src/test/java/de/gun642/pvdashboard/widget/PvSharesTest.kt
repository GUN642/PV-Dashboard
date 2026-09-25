package de.gun642.pvdashboard.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PvSharesTest {
    @Test
    fun splitsPvIntoHouseBatteryWallboxAndExport() {
        // 5 kW PV: 1 kW Einspeisung, 1 kW in den Akku, 2 kW Wallbox, Rest (1 kW) Haus
        val s = PvShares.compute(pvW = 5000.0, gridW = -1000.0, batteryW = 1000.0, wallboxW = 2000.0)!!
        assertEquals(0.2, s.export, 1e-9)
        assertEquals(0.2, s.battery, 1e-9)
        assertEquals(0.4, s.wallbox, 1e-9)
        assertEquals(0.2, s.house, 1e-9)
        assertEquals(1.0, s.house + s.battery + s.wallbox + s.export, 1e-9)
    }

    @Test
    fun wallboxFromGridDoesNotExceedPv() {
        // 2 kW PV, Wallbox zieht 11 kW (Rest aus dem Netz): PV geht komplett in die Wallbox
        val s = PvShares.compute(pvW = 2000.0, gridW = 9500.0, batteryW = -500.0, wallboxW = 11000.0)!!
        assertEquals(1.0, s.wallbox, 1e-9)
        assertEquals(0.0, s.house, 1e-9)
        assertEquals(0.0, s.export, 1e-9)
    }

    @Test
    fun noSharesAtNight() {
        assertNull(PvShares.compute(pvW = 10.0, gridW = 300.0, batteryW = -200.0, wallboxW = 0.0))
    }

    @Test
    fun autarkyFromHouseAndGrid() {
        assertEquals(0.75, PvShares.autarky(houseW = 800.0, gridW = 200.0)!!, 1e-9)
        assertEquals(1.0, PvShares.autarky(houseW = 800.0, gridW = -1500.0)!!, 1e-9)
        assertNull(PvShares.autarky(houseW = 0.0, gridW = 0.0))
    }
}
