package de.gun642.pvdashboard.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class BackupTest {
    @Test
    fun prefsRoundTripKeepsTypes() {
        val all = mapOf<String, Any>(
            "host" to "192.168.178.50",
            "use_https" to true,
            "interval_seconds" to 5,
            // Doubles werden als Long-Bits gespeichert – müssen exakt erhalten bleiben
            "price_kwh" to java.lang.Double.doubleToRawLongBits(31.57),
            "some_float" to 1.5f,
        )
        val decoded = Backup.decodePrefs(Backup.encodePrefs(all))
        assertEquals(all, decoded)
        assertEquals(31.57, java.lang.Double.longBitsToDouble(decoded["price_kwh"] as Long), 0.0)
    }

    @Test
    fun neverContainsPassword() {
        val encoded = Backup.encodePrefs(mapOf("senec_password" to "geheim", "senec_email" to "a@b.de"))
        assertFalse(encoded.has("senec_password"))
        assertEquals("a@b.de", Backup.decodePrefs(encoded)["senec_email"])
    }
}
