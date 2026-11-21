package com.scaletape

import java.util.Locale
import kotlin.math.roundToLong

// Pure Kotlin, no Android imports: keep it unit-testable.

private val PART = Regex("""(\d+/\d+|\d+(?:\.\d+)?(?:\s+\d+/\d+)?)\s*(['"]?)""")

/**
 * Parses a length like `1/4"`, `1 1/2"`, `12' 6"`, `1'-0"`, `3.5'` into inches.
 * A bare number (no `'` or `"`) is worth [bareUnitInches] each.
 */
fun parseLength(text: String, bareUnitInches: Double): Double? {
    val s = text.trim()
        .replace('’', '\'').replace('′', '\'').replace('”', '"').replace('″', '"')
        .replace(Regex("""\s*(ft|feet|foot)\b"""), "'")
        .replace(Regex("""\s*(in|inch|inches)\b"""), "\"")
    val parts = PART.findAll(s).toList()
    if (parts.isEmpty() || PART.replace(s, "").any { it != ' ' && it != '-' }) return null
    if (parts.size > 1 && parts.any { it.groupValues[2].isEmpty() }) return null
    return parts.sumOf { m ->
        val n = parseNumber(m.groupValues[1]) ?: return null
        when (m.groupValues[2]) {
            "'" -> n * 12
            "\"" -> n
            else -> n * bareUnitInches
        }
    }
}

private fun parseNumber(s: String): Double? = s.trim().split(Regex("""\s+""")).sumOf { piece ->
    if ('/' in piece) {
        val (a, b) = piece.split('/').map { it.toDouble() }
        if (b == 0.0) return null
        a / b
    } else piece.toDouble()
}

/**
 * Parses a drawing scale like `1/4" = 1'` into a ratio of paper inches per real inch
 * (1/48 for quarter-inch scale). Left side defaults to inches, right side to feet.
 */
fun parseScale(text: String): Double? {
    val sides = text.split('=')
    if (sides.size != 2) return null
    val paper = parseLength(sides[0], 1.0) ?: return null
    val real = parseLength(sides[1], 12.0) ?: return null
    if (paper <= 0 || real <= 0) return null
    return paper / real
}

/** Scale text for a calibration: [paperInches] on the sheet measured [realInches] in real life. */
fun calibratedScale(paperInches: Double, realInches: Double): String =
    String.format(Locale.ROOT, "%.4f\" = 1'", paperInches / realInches * 12)

/** Real-world inches for a line [px] long on a page rendered at [ppi], at scale [ratio]. */
fun realInches(px: Double, ppi: Double, ratio: Double): Double = px / ppi / ratio

/** `150.0` -> `12' 6"`, rounded to the nearest inch. */
fun formatFeetInches(inches: Double): String {
    val total = inches.roundToLong()
    return "${total / 12}' ${total % 12}\""
}
