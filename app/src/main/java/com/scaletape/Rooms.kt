package com.scaletape

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

// Pure Kotlin, no Android imports: finding room names in a page's text, and which room a run is in.

/** A room name on a plan page, centered at ([x], [y]) in PDF points from the page's top-left; [halfW]/[halfH] size its text box. */
data class RoomLabel(val name: String, val x: Float, val y: Float, val halfW: Float = 0f, val halfH: Float = 0f)

/** One text-search match: where it starts in the page text, and its box in PDF points. */
data class TextHit(val start: Int, val left: Float, val top: Float, val right: Float, val bottom: Float)

// Words that label rooms on residential plans. Longest first so "MASTER BATH" claims its text before "BATH".
// Left out on purpose: words that also show up in general notes (DECK, BAR, DEN, SHOP, FAMILY, SUITE).
private val ROOM_WORDS = listOf(
    "MASTER BATHROOM", "MASTER BATH", "MASTER BEDROOM", "MASTER CLOSET", "MASTER SUITE",
    "PRIMARY BATHROOM", "PRIMARY BATH", "PRIMARY BEDROOM", "PRIMARY CLOSET",
    "GUEST BATHROOM", "GUEST BATH", "GUEST BEDROOM", "GUEST ROOM", "HALF BATH",
    "POWDER ROOM", "LAUNDRY ROOM", "LIVING ROOM", "FAMILY ROOM", "GREAT ROOM", "DINING ROOM",
    "MUD ROOM", "BONUS ROOM", "GAME ROOM", "MEDIA ROOM", "SITTING ROOM", "UTILITY ROOM",
    "WALK-IN CLOSET", "BUTLER'S PANTRY", "BUTLERS PANTRY", "BREAKFAST NOOK", "WET BAR",
    "FRONT PORCH", "BACK PORCH", "SCREENED PORCH",
    "KITCHEN", "BATHROOM", "BATH", "BEDROOM", "CLOSET", "CLOS", "WIC", "PANTRY", "LAUNDRY", "UTILITY",
    "MUDROOM", "GARAGE", "OFFICE", "STUDY", "DINING", "LIVING", "BREAKFAST", "ENTRY", "FOYER",
    "HALLWAY", "HALL", "PORCH", "PATIO", "NOOK", "POWDER", "PWDR", "STORAGE", "LOFT", "LIBRARY", "GYM",
    "FLEX", "CARPORT",
    // Common plan abbreviations
    "M.BTH", "M. BTH", "M.BATH", "MSTR BATH", "MSTR BDRM", "BEDRM", "BDRM", "BTH", "PDR", "LNDY", "CL",
).sortedByDescending { it.length }

private val ABBREVIATIONS = mapOf(
    "CLOS" to "Closet", "CL" to "Closet", "WIC" to "Walk-in Closet", "PWDR" to "Powder", "PDR" to "Powder",
    "M.BTH" to "Master Bath", "M. BTH" to "Master Bath", "M.BATH" to "Master Bath", "MSTR BATH" to "Master Bath",
    "MSTR BDRM" to "Master Bedroom", "BEDRM" to "Bedroom", "BDRM" to "Bedroom", "BTH" to "Bath", "LNDY" to "Laundry",
)
// Too short to trust alone (CL is also "centerline"): only counts as a room when numbered, like "CL 1".
private val NEEDS_NUMBER = setOf("CL")
// "BEDROOM 2", "BEDROOM #3", but not a room size: "KITCHEN 10'x26'1" or "FLEX 12' x 10'".
private val NUMBER_AFTER = Regex("""^\s*#?(\d{1,2})(?![\d'"’′x×.,/-])""")

/**
 * Finds room labels on one page. [pageText] is the page's text; [search] returns every match of a word
 * with its position (PdfRenderer's searchText). Only whole words count, and only at the text height most
 * room words on the page share: notes that mention a room ("remove exist. deck") use a different size.
 */
fun findRoomLabels(pageText: String, search: (String) -> List<TextHit>): List<RoomLabel> {
    val upper = pageText.uppercase()
    val hits = mutableListOf<Pair<String, TextHit>>()
    val textHeight = mutableMapOf<TextHit, Float>()
    for (word in ROOM_WORDS) {
        if (!upper.contains(word)) continue
        for (h in search(word)) {
            val end = h.start + word.length
            val indexed = h.start >= 0 && end <= upper.length && upper.regionMatches(h.start, word, 0, word.length)
            if (indexed && h.start > 0 && upper[h.start - 1].isLetter()) continue // "...BATH" inside a word
            if (indexed && end < upper.length && upper[end].isLetter()) continue // "CLOS" in "CLOSED"
            if (hits.any { (_, o) -> o.left < h.right && h.left < o.right && o.top < h.bottom && h.top < o.bottom }) continue
            val after = if (indexed) upper.substring(end) else ""
            if (word in NEEDS_NUMBER && !NUMBER_AFTER.containsMatchIn(after)) continue
            hits += displayName(word, after) to h
            // A name set on two lines ("FRONT" over "PORCH") comes back as one box twice as tall as its text:
            // size it by its first word's own box instead.
            textHeight[h] = if (' ' !in word) h.bottom - h.top else search(word.substringBefore(' '))
                .filter { it.left >= h.left - 1 && it.right <= h.right + 1 && it.top >= h.top - 1 && it.bottom <= h.bottom + 1 }
                .minOfOrNull { it.bottom - it.top } ?: (h.bottom - h.top)
        }
    }
    if (hits.isEmpty()) return emptyList()
    val commonHeight = hits.groupingBy { textHeight.getValue(it.second).roundToInt() }.eachCount().maxBy { it.value }.key
    return hits
        // Tight: search boxes are whole points, so a 6pt note can measure 8 next to 10pt labels.
        .filter { abs(textHeight.getValue(it.second) - commonHeight) <= maxOf(1f, commonHeight * 0.12f) }
        .map { (name, h) -> RoomLabel(name, (h.left + h.right) / 2, (h.top + h.bottom) / 2, (h.right - h.left) / 2, (h.bottom - h.top) / 2) }
}

/** `"GUEST ROOM"` -> `Guest Room`; `"CLOS"` -> `Closet`; `"BEDROOM"` followed by `" 2"` -> `Bedroom 2`. */
private fun displayName(word: String, after: String): String {
    val name = ABBREVIATIONS[word]
        ?: word.lowercase().split(' ').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
    val n = NUMBER_AFTER.find(after)?.groupValues?.get(1)
    return if (n != null) "$name $n" else name
}

/** The room whose label is nearest to ([x], [y]) on the same page, or null if the page has none. */
fun nearestRoom(labels: List<RoomLabel>, x: Float, y: Float): String? =
    labels.minByOrNull { (it.x - x) * (it.x - x) + (it.y - y) * (it.y - y) }?.name

/**
 * The room for a run whose midpoint is at ([x], [y]) PDF points: the nearest label whose sightline doesn't
 * cross a wall, else the nearest label. [walls] checks a sightline in page-bitmap pixels; null skips it.
 */
fun pickRoom(labels: List<RoomLabel>, x: Float, y: Float, pxPerPoint: Float, walls: WallFinder?): String? {
    val byDistance = labels.sortedBy { (it.x - x) * (it.x - x) + (it.y - y) * (it.y - y) }
    if (walls == null) return byDistance.firstOrNull()?.name
    val clear = byDistance.firstOrNull { l ->
        !walls.crosses(
            x * pxPerPoint, y * pxPerPoint, l.x * pxPerPoint, l.y * pxPerPoint,
            // stop at the label's own text, plus a little, so its letters don't count
            halfW = l.halfW * pxPerPoint + 2, halfH = l.halfH * pxPerPoint + 2,
        )
    }
    return (clear ?: byDistance.firstOrNull())?.name
}

/**
 * Finds walls along a sightline on a rendered plan. A wall is two long parallel lines 3"-12" apart, center to
 * center (a stud wall is 3.5"; a countertop edge over its cabinet face is ~2.5"), or one long solid band at
 * least 3" thick (filled walls). "Long" means straight for 48"+, which
 * leaves out text, fixture symbols (a 36" cooktop's outline, burners, sinks) and dashed lines. Sizes are real-world inches,
 * turned into pixels with [pxPerInch] (bitmap ppi x drawing scale). [isDark] reads one pixel.
 */
class WallFinder(pxPerInch: Float, private val isDark: (x: Int, y: Int) -> Boolean) {
    private val minGap = 3 * pxPerInch
    private val maxGap = 12 * pxPerInch
    private val minLength = 48 * pxPerInch

    /** A long line the ray crosses [t] pixels along. */
    private class Line(val t: Float, val angle: Double, val thickness: Float, val sinToRay: Double)

    /** Whether the segment from (x0, y0) toward (x1, y1) crosses a wall before reaching the box around (x1, y1). */
    fun crosses(x0: Float, y0: Float, x1: Float, y1: Float, halfW: Float = 0f, halfH: Float = 0f): Boolean {
        val len = hypot(x1 - x0, y1 - y0)
        if (len < 1f) return false
        val ux = (x1 - x0) / len
        val uy = (y1 - y0) / len
        val rayAngle = kotlin.math.atan2(uy.toDouble(), ux.toDouble())
        val lines = mutableListOf<Line>()
        var runStart = -1
        var i = 0
        while (i <= len.toInt()) {
            val px = x0 + ux * i
            val py = y0 + uy * i
            if (abs(px - x1) <= halfW && abs(py - y1) <= halfH) break // reached the label's text
            val d = isDark(px.roundToInt(), py.roundToInt())
            if (d && runStart < 0) runStart = i
            if (!d && runStart >= 0) { lines += linesIn(x0, y0, ux, uy, runStart, i - 1, rayAngle); runStart = -1 }
            i++
        }
        if (runStart >= 0) lines += linesIn(x0, y0, ux, uy, runStart, i - 1, rayAngle)

        if (lines.any { it.thickness >= minGap }) return true // filled wall
        return lines.zipWithNext().any { (a, b) ->
            val parallel = abs(sin(a.angle - b.angle)) < sin(15 * PI / 180)
            // center to center, measured across the lines rather than along the ray
            val gap = (b.t - a.t) * a.sinToRay.toFloat()
            parallel && gap in minGap..maxGap
        }
    }

    /**
     * Long lines inside dark stretch [from]..[to] of the ray. Checked every 2px, not just at the middle: a wall
     * face can touch a fixture (a faucet against it) or its twin face, and they'd share one dark stretch.
     */
    private fun linesIn(x0: Float, y0: Float, ux: Float, uy: Float, from: Int, to: Int, rayAngle: Double): List<Line> {
        val found = mutableListOf<Line>()
        var t = from
        while (t <= to) {
            val line = lineAt(x0 + ux * t, y0 + uy * t, t.toFloat(), rayAngle)
            if (line != null) { found += line; t += 3 } else t += 2 // skip past this line's own stroke
        }
        // A stroke crossed at a shallow angle covers several samples: merge hits within one stroke's width
        // along the ray, and place the line at their middle.
        val merged = mutableListOf<MutableList<Line>>()
        for (l in found) {
            val group = merged.lastOrNull()
            val width = group?.let { g -> g.maxOf { it.thickness } / g.first().sinToRay.toFloat() + 2 }
            if (group != null && width != null && l.t - group.last().t <= width) group += l else merged += mutableListOf(l)
        }
        return merged.map { g -> Line(g.sumOf { it.t.toDouble() }.toFloat() / g.size, g.first().angle, g.maxOf { it.thickness }, g.first().sinToRay) }
    }

    /** The long straight line through ([cx], [cy]), [t] along the ray, or null if there isn't one. */
    private fun lineAt(cx: Float, cy: Float, t: Float, rayAngle: Double): Line? {
        var best = 0f
        var bestAngle = 0.0
        for (step in 0 until 36) { // every 5 degrees
            val a = step * PI / 36
            val length = darkSpan(cx, cy, cos(a).toFloat(), sin(a).toFloat(), minLength)
            if (length > best) { best = length; bestAngle = a }
            if (best >= minLength) break
        }
        if (best < minLength) return null
        val sinToRay = abs(sin(bestAngle - rayAngle))
        if (sinToRay < 0.25) return null // the ray runs along this line, not across it
        // Thickness across the line, at three spots along it: the smallest ignores places where another line crosses.
        val ax = cos(bestAngle).toFloat()
        val ay = sin(bestAngle).toFloat()
        val thickness = listOf(0f, minLength / 4, -minLength / 4).minOf { o ->
            darkSpan(cx + ax * o, cy + ay * o, -ay, ax, maxGap)
        }
        return Line(t, bestAngle, thickness, sinToRay)
    }

    /** How far the dark pixels continue through (cx, cy) in both directions along (dx, dy), up to [cap]. */
    private fun darkSpan(cx: Float, cy: Float, dx: Float, dy: Float, cap: Float): Float {
        var n = 1f
        for (sign in intArrayOf(1, -1)) {
            var k = 1
            while (n < cap && isDark((cx + sign * dx * k).roundToInt(), (cy + sign * dy * k).roundToInt())) { n++; k++ }
        }
        return n
    }
}
