package de.gun642.pvdashboard.senec.cloud

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WallboxTest {
    // Beispielantwort einer V3-Wallbox (aus der Home-Assistant-Integration)
    private val v3 = """
        [{"id":"1","productFamily":null,"controllerId":"S123","name":"Wallbox 1","prohibitUsage":false,
          "isInterchargeAvailable":true,"isSolarChargingAvailable":true,"type":"V123",
          "state":{"electricVehicleConnected":true,"hasError":false,"temperatureInCelsius":27.5,"isCharging":true,"statusCode":"CHARGING"},
          "chargingMode":{"type":"SOLAR","allowIntercharge":true,"compatibilityMode":true,
            "fastChargingSettings":{"allowIntercharge":true},
            "comfortChargeSettings":{"allowIntercharge":false,"configuredChargingCurrent":9},
            "solarOptimizeSettings":{"compatibilityMode":true,"minChargingCurrentInA":9,"useDynamicTariffs":null,"priceLimitInCtPerKwh":null}},
          "chargingCurrents":{"minPossibleCharging":6,"configuredChargingCurrent":9,"currentApparentChargingPowerInKw":4.2}}]
    """.trimIndent()

    @Test
    fun parsesV3Wallbox() {
        val wb = WallboxInfo.parseList(v3).single()
        assertEquals("1", wb.id)
        assertEquals(WallboxMode.SOLAR, wb.mode)
        assertTrue(wb.carConnected)
        assertTrue(wb.fastAllowIntercharge)
        // Fehlt "preventInterruptions", sind Unterbrechungen zulässig
        assertFalse(wb.solarPreventInterruptions)
        assertEquals(9.0, wb.solarMinCurrent!!, 1e-9)
        assertEquals(6.0, wb.minPossibleCurrent!!, 1e-9)
        assertEquals(4.2, wb.chargingPowerKw!!, 1e-9)
        assertFalse(wb.comfortAvailable) // Komfort nur bei V4/P4
        assertEquals("Lädt", wb.statusText)
    }

    @Test
    fun lockedWinsOverChargingMode() {
        val locked = WallboxInfo.parseList(v3.replace("\"prohibitUsage\":false", "\"prohibitUsage\":true")).single()
        assertEquals(WallboxMode.LOCKED, locked.mode)
    }

    @Test
    fun solarBodyKeepsExistingFields() {
        val wb = WallboxInfo.parseList(v3).single()
        val body = JSONObject(WallboxInfo.solarSettingsBody(wb.solarSettings, preventInterruptions = true))
        assertTrue(body.getBoolean("preventInterruptions"))
        assertEquals(9, body.getInt("minChargingCurrentInA"))
        assertTrue(body.getBoolean("compatibilityMode"))
        // null-Werte werden nicht mitgeschickt
        assertFalse(body.has("useDynamicTariffs"))

        val current = JSONObject(WallboxInfo.solarSettingsBody(wb.solarSettings, minCurrent = 11.0))
        assertEquals(11.0, current.getDouble("minChargingCurrentInA"), 1e-9)
        assertFalse(current.has("preventInterruptions"))
    }
}
