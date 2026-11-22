package com.scaletape

import java.util.Locale
import kotlin.math.abs
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

// Architectural scales, as paper inches per foot. Neighbours are 25%+ apart, so a 2% snap can't pick the wrong one.
private val STANDARD_SCALES = listOf("3", "1 1/2", "1", "3/4", "1/2", "3/8", "1/4", "3/16", "1/8", "3/32", "1/16")

/**
 * Scale text for a calibration: [paperInches] on the sheet measured [realInches] in real life.
 * Within 2% of a standard scale it is that scale (tap error); a plan printed reduced (~5%+ off) keeps its own.
 */
fun calibratedScale(paperInches: Double, realInches: Double): String {
    val perFoot = paperInches / realInches * 12
    STANDARD_SCALES.firstOrNull { abs(perFoot / parseNumber(it)!! - 1) <= 0.02 }?.let { return "$it\" = 1'" }
    return String.format(Locale.ROOT, "%.4f\" = 1'", perFoot)
}

/** Real-world inches for a line [px] long on a page rendered at [ppi], at scale [ratio]. */
fun realInches(px: Double, ppi: Double, ratio: Double): Double = px / ppi / ratio

/** `150.0` -> `12' 6"`, rounded to the nearest inch. */
fun formatFeetInches(inches: Double): String {
    val total = inches.roundToLong()
    return "${total / 12}' ${total % 12}\""
}

private const val IN = """(?:"|in\b|inch(?:es)?\b)"""
private const val NUM = """(?:\d+/\d+|\d+(?:\.\d+)?(?:\s+\d+/\d+)?)"""
// `1/4" = 1'-0"`, `3/16 in = 1 ft`, `1" = 20'`: paper (inches, unit optional) = real (feet, then optional inches).
// The inches tail needs the hyphen and stays on the line, so `1' 6" UPPERS` or `1'⏎36"` isn't read as 1'-6" / 1'-36".
private val SCALE_PHRASE = Regex(
    """(?<![\d/.])$NUM\s*$IN?\s*=\s*\d+(?:\.\d+)?\s*(?:'|ft\b|feet\b|foot\b)(?:[ \t]*-[ \t]*$NUM[ \t]*$IN)?""",
    RegexOption.IGNORE_CASE,
)

/**
 * The drawing scale stated in [text] (a plan's title block, a PDF measure string), or null. Only plan scales count
 * (3" per foot or smaller, so a note like `30" = 2'-6"` doesn't), and ones ending `= 1'` beat others (`1" = 20'`).
 * Among those the most common wins (a sheet's main scale outnumbers its detail callouts). Standard scales come back
 * as `1/4" = 1'`.
 */
fun findScale(text: String): String? {
    val s = text.replace('’', '\'').replace('′', '\'').replace('”', '"').replace('″', '"')
    val all = SCALE_PHRASE.findAll(s).mapNotNull { m ->
        parseScale(m.value)?.takeIf { it * 12 <= 3 + 1e-9 }?.let { it to m.value.trim() }
    }.toList()
    val perOneFoot = all.filter { isPerOneFoot(it.second) }
    val found = perOneFoot.ifEmpty { all }
    val ratio = found.groupingBy { it.first }.eachCount().maxByOrNull { it.value }?.key ?: return null
    val perFoot = ratio * 12
    return STANDARD_SCALES.firstOrNull { abs(perFoot - parseNumber(it)!!) < 1e-9 }?.let { "$it\" = 1'" }
        ?: found.first { it.first == ratio }.second
}

private fun isPerOneFoot(scale: String) = parseLength(scale.substringAfter('='), 12.0) == 12.0

/**
 * The plan's scale from each page's [findScale] in page order (lazy, so pages after a hit aren't read): the first
 * `= 1'` one, else the first of any kind. A cover or site sheet (`1" = 20'`) usually comes before the floor plan.
 */
fun pickPlanScale(pageScales: Sequence<String?>): String? {
    var first: String? = null
    for (s in pageScales) {
        if (s == null) continue
        if (isPerOneFoot(s)) return s
        if (first == null) first = s
    }
    return first
}

/**
 * The scale saved in the PDF file itself: `/R (1/4 in = 1 ft)` in a `/Measure` dict (Bluebeam/Acrobat calibration),
 * or one written in the Info Title/Subject/Keywords. Looks in the raw file and in Flate-compressed object streams.
 * shortcut: UTF-16 strings, XMP and non-Flate object streams are skipped; add them if a real plan needs it.
 */
fun scaleInPdf(bytes: ByteArray): String? {
    val raw = String(bytes, Charsets.ISO_8859_1)
    // Lazy: only one inflated object stream is alive at a time.
    val texts = sequenceOf(raw) + Regex("""/Type\s*/ObjStm""").findAll(raw).mapNotNull { m ->
        val start = Regex("""stream\r?\n""").find(raw, m.range.last)?.range?.last ?: return@mapNotNull null
        inflate(bytes, start + 1)
    }
    val strings = texts.flatMap { t ->
        Regex("""/(?:R|Title|Subject|Keywords)\s*\(((?:\\.|[^\\)])*)\)""").findAll(t).map { it.groupValues[1] }
    }
    return findScale(strings.joinToString("\n"))
}

/** A zlib stream starting at [from], or null if it isn't one. Capped at 16 MB so a bad file can't run out of memory. */
private fun inflate(bytes: ByteArray, from: Int): String? = runCatching {
    val inf = java.util.zip.Inflater().apply { setInput(bytes, from, bytes.size - from) }
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(65536)
    while (!inf.finished() && out.size() < 16 shl 20) {
        val n = inf.inflate(buf)
        if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
        out.write(buf, 0, n)
    }
    inf.end()
    String(out.toByteArray(), Charsets.ISO_8859_1)
}.getOrNull()
