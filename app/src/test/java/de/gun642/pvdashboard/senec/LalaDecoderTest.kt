package de.gun642.pvdashboard.senec

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LalaDecoderTest {

    private fun fl(value: Float) = "fl_%08X".format(java.lang.Float.floatToIntBits(value))

    @Test
    fun decodesFloats() {
        assertEquals(1234.5, LalaDecoder.decodeString(fl(1234.5f)) as Double, 1e-6)
        assertEquals(-250.0, LalaDecoder.decodeString(fl(-250f)) as Double, 1e-6)
    }

    @Test
    fun decodesIntegers() {
        assertEquals(10L, LalaDecoder.decodeString("u8_0A"))
        assertEquals(65535L, LalaDecoder.decodeString("u3_0000FFFF"))
        assertEquals(-1L, LalaDecoder.decodeString("i3_FFFFFFFF"))
        assertEquals(32767L, LalaDecoder.decodeString("i1_7FFF"))
        assertEquals(-128L, LalaDecoder.decodeString("i8_80"))
    }

    @Test
    fun decodesStringsAndMissingValues() {
        assertEquals("V3 hybrid", LalaDecoder.decodeString("st_V3 hybrid"))
        assertNull(LalaDecoder.decodeString("VARIABLE_NOT_FOUND"))
        assertEquals("kaputt", LalaDecoder.decodeString("kaputt"))
    }

    @Test
    fun buildsSnapshotIncludingWallboxList() {
        val raw = mapOf(
            "ENERGY" to mapOf(
                "GUI_INVERTER_POWER" to fl(5000f),
                "GUI_HOUSE_POW" to fl(800f),
                "GUI_GRID_POW" to fl(-2000f),
                "GUI_BAT_DATA_POWER" to fl(1000f),
                "GUI_BAT_DATA_FUEL_CHARGE" to fl(55.5f),
            ),
            "WALLBOX" to mapOf(
                "APPARENT_CHARGING_POWER" to listOf(fl(1200f), fl(0f), fl(0f), fl(0f)),
                "EV_CONNECTED" to listOf("u8_01", "u8_00", "u8_00", "u8_00"),
            ),
        )
        @Suppress("UNCHECKED_CAST")
        val snapshot = SenecSnapshot.from(LalaDecoder.decode(raw) as Map<String, Any?>, 0, 0L)
        assertEquals(5000.0, snapshot.pvW!!, 1e-6)
        assertEquals(-2000.0, snapshot.gridW!!, 1e-6)
        assertEquals(55.5, snapshot.batterySoc!!, 1e-6)
        assertEquals(1200.0, snapshot.wallboxW!!, 1e-6)
        assertTrue(snapshot.wallboxCarConnected!!)
        assertNull(snapshot.batteryTemp)
        // Kein Netzbezug -> 100 % Autarkie; 2 kW von 5 kW eingespeist -> 60 % Eigenverbrauch
        assertEquals(1.0, snapshot.autarky!!, 1e-6)
        assertEquals(0.6, snapshot.selfConsumption!!, 1e-6)

        @Suppress("UNCHECKED_CAST")
        val second = SenecSnapshot.from(LalaDecoder.decode(raw) as Map<String, Any?>, 1, 0L)
        assertFalse(second.wallboxCarConnected!!)
    }

    @Test
    fun normalizesHostInput() {
        assertEquals("192.168.178.50", SenecClient.normalizeHost(" https://192.168.178.50/vars.html "))
        assertEquals("senec.fritz.box", SenecClient.normalizeHost("senec.fritz.box"))
    }
}
