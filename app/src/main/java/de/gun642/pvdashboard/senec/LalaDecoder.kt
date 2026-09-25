package de.gun642.pvdashboard.senec

import java.math.BigInteger

/**
 * Dekodiert die typ-kodierten Werte der lala.cgi-Schnittstelle.
 *
 * Beispiele: `fl_43A1B2C3` (32-bit Float als Hex), `u8_01` (vorzeichenlos),
 * `i3_FFFFFFFF` (vorzeichenbehaftet), `st_Text` (Zeichenkette).
 */
object LalaDecoder {
    private val missingMarkers = setOf("VARIABLE_NOT_FOUND", "FILE_VARIABLE_NOT_READABLE")

    fun decode(value: Any?): Any? = when (value) {
        is String -> decodeString(value)
        is List<*> -> value.map { decode(it) }
        is Map<*, *> -> value.entries.associate { (k, v) -> k.toString() to decode(v) }
        else -> value
    }

    fun decodeString(value: String): Any? {
        if (value in missingMarkers) return null
        val separator = value.indexOf('_')
        if (separator < 0) return value
        val prefix = value.substring(0, separator)
        val payload = value.substring(separator + 1)
        return try {
            when {
                prefix == "fl" -> java.lang.Float.intBitsToFloat(payload.toLong(16).toInt()).toDouble()
                prefix == "st" -> payload
                isIntegerPrefix(prefix) -> decodeInteger(signed = prefix[0] == 'i', hex = payload)
                else -> value
            }
        } catch (e: NumberFormatException) {
            value
        }
    }

    private fun isIntegerPrefix(prefix: String): Boolean =
        prefix.length >= 2 && (prefix[0] == 'u' || prefix[0] == 'i') && prefix.substring(1).all { it.isDigit() }

    private fun decodeInteger(signed: Boolean, hex: String): Long {
        var number = BigInteger(hex, 16)
        val bits = hex.length * 4
        if (signed && number.testBit(bits - 1)) {
            number -= BigInteger.ONE.shiftLeft(bits)
        }
        return number.toLong()
    }
}
