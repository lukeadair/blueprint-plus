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

/** One word read by OCR, boxed in PDF points; [line] numbers the text line it was read on. */
data class OcrWord(val text: String, val line: Int, val left: Float, val top: Float, val right: Float, val bottom: Float)

/** Room labels from OCR words (reading order): the words become the page text, and a search is a match in that text. */
fun ocrLabels(all: List<OcrWord>): List<RoomLabel> {
    // OCR boxes are too noisy for the label-height vote (a 7pt "BATH" reads 12pt tall), so notes that mention a room
    // are told apart by their line instead: a label's line is a few words, a sentence is longer.
    val lineLen = all.groupingBy { it.line }.eachCount()
    val words = all.filter { lineLen.getValue(it.line) <= 4 }
    val text = StringBuilder()
    val starts = words.mapIndexed { i, w ->
        if (i > 0) text.append(if (w.line == words[i - 1].line) ' ' else '\n')
        text.length.also { text.append(w.text.uppercase()) }
    }
    val upper = text.toString()
    return findRoomLabels(upper, false) { word ->
        generateSequence(upper.indexOf(word).takeIf { it >= 0 }) { i -> upper.indexOf(word, i + 1).takeIf { it >= 0 } }
            .mapNotNull { i ->
                val hit = words.indices.filter { starts[it] < i + word.length && starts[it] + words[it].text.length > i }.map { words[it] }
                if (hit.isEmpty()) null
                else TextHit(i, hit.minOf { it.left }, hit.minOf { it.top }, hit.maxOf { it.right }, hit.maxOf { it.bottom })
            }.toList()
    }
}

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
    "M.BTH", "M. BTH", "M.BATH", "MSTR BATH", "MSTR BDRM", "BEDRM", "BDRM", "BTH", "PDR", "LNDY", "UTIL", "CL", "MASTER CL", "MSTR CL",
).sortedByDescending { it.length }

private val ABBREVIATIONS = mapOf(
    "CLOS" to "Closet", "CL" to "Closet", "WIC" to "Walk-in Closet", "PWDR" to "Powder", "PDR" to "Powder",
    "M.BTH" to "Master Bath", "M. BTH" to "Master Bath", "M.BATH" to "Master Bath", "MSTR BATH" to "Master Bath",
    "MSTR BDRM" to "Master Bedroom", "BEDRM" to "Bedroom", "BDRM" to "Bedroom", "BTH" to "Bath", "LNDY" to "Laundry", "UTIL" to "Utility",
    "MASTER CL" to "Master Closet", "MSTR CL" to "Master Closet",
)
// Too short to trust alone (CL is also "centerline"): only counts as a room when numbered, like "CL 1".
private val NEEDS_NUMBER = setOf("CL")
// "BEDROOM 2", "BEDROOM #3", but not a room size: "KITCHEN 10'x26'1" or "FLEX 12' x 10'".
// A heading in an area table, not a room: "LIVING AREAS SQ. FT.", "GARAGE SF".
private val AREA_HEADING = Regex("""^\s*(?:AREAS?\s+)?(?:SQ\b|SF\b|S\.F\.)""")
private val NUMBER_AFTER = Regex("""^\s*#?(\d{1,2})(?![\d'"’′x×.,/-])""")

/**
 * Finds room labels on one page. [pageText] is the page's text; [search] returns every match of a word
 * with its position (PdfRenderer's searchText). Only whole words count, and only at the text height most
 * room words on the page share: notes that mention a room ("remove exist. deck") use a different size.
 */
fun findRoomLabels(pageText: String, search: (String) -> List<TextHit>): List<RoomLabel> = findRoomLabels(pageText, true, search)

/** [byHeight] false skips the label-height filter (OCR boxes are too noisy for it). */
private fun findRoomLabels(pageText: String, byHeight: Boolean, search: (String) -> List<TextHit>): List<RoomLabel> {
    val upper = pageText.uppercase()
    val hits = mutableListOf<Pair<String, TextHit>>()
    val textHeight = mutableMapOf<TextHit, Float>()
    for (word in ROOM_WORDS) {
        // The phrase as the text stores it, plus its words placed together on the page in any text order.
        val found = (if (upper.contains(word)) search(word).map { it to it.start + word.length } else emptyList()) +
            (if (' ' in word) placedTogether(word, upper, search) else emptyList())
        for ((h0, end0) in found) {
            // searchText's start can sit a few chars off ("SHALL" for HALL, 2 early after a CR/LF): find the word again nearby.
            val at = if (h0.start >= 0 && !upper.regionMatches(h0.start, word, 0, word.length)) {
                upper.indexOf(word, maxOf(0, h0.start - 4)).takeIf { it >= 0 && abs(it - h0.start) <= 4 }
            } else null
            val h = if (at != null) h0.copy(start = at) else h0
            val end = if (at != null) at + word.length else end0
            val indexed = h.start >= 0 && end <= upper.length && upper.regionMatches(h.start, word, 0, word.length)
            if (indexed && h.start > 0 && upper[h.start - 1].isLetter()) continue // "...BATH" inside a word
            if (indexed && end < upper.length && upper[end].isLetter()) continue // "CLOS" in "CLOSED"
            // Text already taken ("BATH" in "MASTER BATH", or the same name found twice): another hit covers half its
            // box. A tiny tag clipping a label ("PANTRY 1", "MASTER CLOSET 1") doesn't; the height filter drops those.
            if (hits.any { (_, o) -> overlapArea(o, h) >= 0.5f * (h.right - h.left) * (h.bottom - h.top) }) continue
            // A name pieced together from its words (start -1) reads its room number after its last word.
            val after = if (indexed || (h.start < 0 && end in 0..upper.length)) upper.substring(end) else ""
            if (word in NEEDS_NUMBER && !NUMBER_AFTER.containsMatchIn(after)) continue
            if (AREA_HEADING.containsMatchIn(after)) continue
            hits += (if (indexed) qualifier(upper, h, search)?.let { "$it " } ?: "" else "") + displayName(word, after) to h
            // A name set on two lines ("FRONT" over "PORCH") comes back as one box twice as tall as its text:
            // size it by its first word's own box instead.
            textHeight[h] = if (' ' !in word) glyphSize(h) else search(word.substringBefore(' '))
                .filter { it.left >= h.left - 1 && it.right <= h.right + 1 && it.top >= h.top - 1 && it.bottom <= h.bottom + 1 }
                .minOfOrNull { glyphSize(it) } ?: glyphSize(h)
        }
    }
    if (hits.isEmpty()) return emptyList()
    // Only horizontal text votes: a page full of tiny vertical tags (5pt "KITCHEN" on cabinets) must not outvote the labels.
    val voters = hits.filter { (_, h) -> h.right - h.left >= h.bottom - h.top }.ifEmpty { hits }
    val commonHeight = voters.groupingBy { textHeight.getValue(it.second).roundToInt() }.eachCount().maxBy { it.value }.key
    return hits
        // Tight: search boxes are whole points, so a 6pt note can measure 8 next to 10pt labels.
        .filter { !byHeight || abs(textHeight.getValue(it.second) - commonHeight) <= maxOf(1f, commonHeight * 0.12f) }
        .filterNot { notACabinetRoom(it.first) }
        .map { (name, h) -> RoomLabel(name, (h.left + h.right) / 2, (h.top + h.bottom) / 2, (h.right - h.left) / 2, (h.bottom - h.top) / 2) }
}

// Text size from its box: the short side, so a vertical label (tall narrow box) measures like a horizontal one.
private fun glyphSize(h: TextHit) = minOf(h.right - h.left, h.bottom - h.top)

// Closets worth a room of their own: master/his/her and walk-ins. Other closets, halls, and every porch, patio, garage, carport and storage are dropped
// after the height filter, so their words still claim text and count toward the common label height.
private val KEPT_CLOSET = Regex("""^(?:(?:Master|Mstr|Mst|Primary|His|Hers?|Walk-in)\b|M\.)""")
private fun notACabinetRoom(name: String): Boolean {
    val base = name.replace(Regex("""\s+\d+$"""), "")
    return listOf("Porch", "Patio", "Garage", "Carport", "Storage", "Foyer").any { it in base } || base.endsWith("Hall") || base.endsWith("Hallway") || base.endsWith("Closet") && !KEPT_CLOSET.containsMatchIn(base)
}

/** `"GUEST ROOM"` -> `Guest Room`; `"CLOS"` -> `Closet`; `"BEDROOM"` followed by `" 2"` -> `Bedroom 2`. */
/**
 * A multi-word name found word by word: each next word right after the last on the same line, or starting
 * on the line below it, at the same text height. Some PDFs store a name set on two lines out of order
 * ("ROOM 18'x20'7 ... GREAT"), so the phrase search misses it. Returns one box per name, with start -1 and
 * the text index just past its last word.
 */
private fun placedTogether(word: String, upper: String, search: (String) -> List<TextHit>): List<Pair<TextHit, Int>> {
    val parts = word.split(' ')
    if (parts.any { !upper.contains(it) }) return emptyList()
    fun whole(h: TextHit, w: String) = h.start >= 0 && h.start + w.length <= upper.length &&
        upper.regionMatches(h.start, w, 0, w.length) &&
        (h.start == 0 || !upper[h.start - 1].isLetter()) &&
        (h.start + w.length == upper.length || !upper[h.start + w.length].isLetter())
    val found = parts.map { p -> search(p).filter { whole(it, p) } }
    return found[0].mapNotNull { first ->
        val lineH = first.bottom - first.top
        var box = first
        var last = first
        for (next in found.drop(1)) {
            val n = next.firstOrNull { h ->
                val sameHeight = abs((h.bottom - h.top) - lineH) <= lineH * 0.2f
                val sameLine = abs(h.top - last.top) <= lineH / 2 && h.left >= last.right && h.left - last.right <= lineH
                val nextLine = h.top >= last.top + lineH / 2 && h.top - last.bottom <= lineH &&
                    h.left < last.right && last.left < h.right
                sameHeight && (sameLine || nextLine)
            } ?: return@mapNotNull null
            box = TextHit(-1, minOf(box.left, n.left), box.top, maxOf(box.right, n.right), maxOf(box.bottom, n.bottom))
            last = n
        }
        box to (last.start + parts.last().length)
    }
}

private fun overlapArea(a: TextHit, b: TextHit) =
    maxOf(0f, minOf(a.right, b.right) - maxOf(a.left, b.left)) * maxOf(0f, minOf(a.bottom, b.bottom) - maxOf(a.top, b.top))

private val QUALIFIER = Regex("""[A-Z][A-Z']{0,7}\.?""")
private val NOT_QUALIFIERS = setOf("A", "AT", "AND", "FOR", "IN", "OF", "ON", "THE", "TO", "NEW", "EXIST", "EXIST.", "EXISTING")

/**
 * The word in front of a room word on the same line, so "GIRLS BATH" and "M. BATH" stay apart from plain "BATH":
 * `Girls`, `M.`. Null when there's none, it's another room word ("HALL BATH" stays two rooms), or a filler word.
 */
private fun qualifier(upper: String, h: TextHit, search: (String) -> List<TextHit>): String? {
    if (h.start < 2 || upper[h.start - 1] != ' ') return null
    var from = h.start - 1
    while (from > 0 && (upper[from - 1].isLetter() || upper[from - 1] in ".'")) from--
    if (from > 0 && !upper[from - 1].isWhitespace()) return null // "LNDY/MUD PDR": MUD belongs to LNDY/MUD
    val w = upper.substring(from, h.start - 1).takeIf { QUALIFIER.matches(it) } ?: return null
    if (w in NOT_QUALIFIERS || ROOM_WORDS.any { it == w || it.endsWith(" $w") }) return null
    val height = h.bottom - h.top
    // On the page right before the room word, on its line (the text order alone can join unrelated lines).
    search(w).firstOrNull { it.start == h.start - w.length - 1 } ?.takeIf {
        abs(it.top - h.top) <= height / 2 && it.right <= h.left + 1 && h.left - it.right <= 2 * (it.right - it.left) / w.length // a space or two
    } ?: return null
    return w.lowercase().replaceFirstChar(Char::uppercase)
}

/**
 * [labels] with repeated names numbered in reading order ("Storage", "Storage 2"), so two rooms with one name
 * don't merge into one in the run list. Names in [taken] (rooms on other pages) count as used.
 */
fun numberRepeats(labels: List<RoomLabel>, taken: Set<String>): List<RoomLabel> {
    val used = taken.toMutableSet()
    val onPage = labels.map { it.name }.toSet()
    val out = labels.toMutableList()
    for (i in labels.indices.sortedWith(compareBy({ labels[it].y }, { labels[it].x }))) {
        val name = labels[i].name
        // a number already used by a label on this page ("Bedroom 2") is skipped too
        val n = generateSequence(1) { it + 1 }.map { if (it == 1) name else "$name $it" }
            .first { it !in used && (it == name || it !in onPage) }
        used += n
        out[i] = labels[i].copy(name = n)
    }
    return out
}

/**
 * Whether ([x], [y]) sits in a table (a schedule listing rooms) rather than on the floor plan: 4+ long horizontal
 * lines evenly spaced 6-30pt apart (ruled rows; a 24x36 sheet's are ~20pt) within 105pt above or below, with the
 * label among them or at most one row past them (a last row with no rule below it).
 * [pxPerPt] is bitmap pixels per PDF point; [isDark] reads one pixel.
 */
fun inTable(x: Float, y: Float, pxPerPt: Float, isDark: (x: Int, y: Int) -> Boolean): Boolean {
    val minLen = 72 * pxPerPt // a paper inch
    val cx = (x * pxPerPt).roundToInt()
    fun spanAt(yy: Int): Float {
        fun run(dir: Int): Int { // dark pixels along the row, bridging gaps of up to 2px (antialiasing)
            var n = 0; var gap = 0; var k = 1
            while (n < minLen && gap <= 2) { if (isDark(cx + dir * k, yy)) { n += gap + 1; gap = 0 } else gap++; k++ }
            return n
        }
        return if (isDark(cx, yy)) 1f + run(1) + run(-1) else 0f
    }
    val rows = mutableListOf<Int>()
    var yy = ((y - 105) * pxPerPt).roundToInt()
    val end = ((y + 105) * pxPerPt).roundToInt()
    while (yy <= end) {
        if (spanAt(yy) >= minLen) { rows += yy; yy += (3 * pxPerPt).roundToInt().coerceAtLeast(2) } else yy++
    }
    val py = y * pxPerPt
    // three gaps in a row, each 6-30pt and within 1.5pt of the first, around the label (not dimension strings nearby)
    return rows.windowed(4).any { r ->
        val g = r.zipWithNext { a, b -> (b - a) / pxPerPt }
        g.all { it in 6f..30f && abs(it - g[0]) <= 1.5f } && py in (r[0] - g[0] * pxPerPt)..(r[3] + g[0] * pxPerPt)
    }
}

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
 * leaves out text, fixture symbols (a 36" cooktop's outline, burners, sinks) and dashed lines. A wall stub beside a
 * door (faces 24"-48") counts too when the pair is closed at one end only: the jamb at the door, open where it
 * joins the next wall (a fixture's box is closed at both). Sizes are real-world inches,
 * turned into pixels with [pxPerInch] (bitmap ppi x drawing scale). [isDark] reads one pixel.
 */
class WallFinder(pxPerInch: Float, private val isDark: (x: Int, y: Int) -> Boolean) {
    private val minGap = 3 * pxPerInch
    private val maxGap = 12 * pxPerInch
    private val minLength = 48 * pxPerInch
    private val stubLength = 24 * pxPerInch

    /** A line the ray crosses [t] pixels along; [ends] (x, y, x, y) only on a short (stub) line, null on a long one. */
    private class Line(val t: Float, val angle: Double, val thickness: Float, val sinToRay: Double, val ends: FloatArray? = null)

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

        fun pair(a: Line, b: Line): Boolean {
            val parallel = abs(sin(a.angle - b.angle)) < sin(15 * PI / 180)
            // center to center, measured across the lines rather than along the ray
            val gap = (b.t - a.t) * a.sinToRay.toFloat()
            return parallel && gap in minGap..maxGap
        }
        val long = lines.filter { it.ends == null }
        if (long.any { it.thickness >= minGap }) return true // filled wall
        if (long.zipWithNext().any { (a, b) -> pair(a, b) }) return true
        return lines.zipWithNext().any { (a, b) -> a.ends != null && b.ends != null && pair(a, b) && isStub(a, b) }
    }

    /**
     * Short faces [a] and [b] are a wall stub beside a door: the shorter one is joined straight across to the other
     * at one end only (the jamb), and its other end meets a long line across it (the face of the wall it joins). A
     * fixture's box is closed at both ends, a washer's double outline meets no long line, unrelated lines neither.
     */
    private fun isStub(a: Line, b: Line): Boolean {
        fun len(l: Line) = l.ends!!.let { hypot(it[0] - it[2], it[1] - it[3]) }
        val (s, o) = if (len(a) <= len(b)) a to b else b to a
        val e = s.ends!!; val oe = o.ends!!
        val ox = cos(o.angle).toFloat(); val oy = sin(o.angle).toFloat()
        val sx = cos(s.angle).toFloat(); val sy = sin(s.angle).toFloat()
        val closed = (0..2 step 2).filter { i ->
            val x1 = e[i]; val y1 = e[i + 1]
            val k = (x1 - oe[0]) * ox + (y1 - oe[1]) * oy // the other face's point straight across
            val x2 = oe[0] + ox * k; val y2 = oe[1] + oy * k
            (-2..2).any { d -> // the face's last dark pixel may sit on either side of the closing stroke
                listOf(0.25f, 0.5f, 0.75f).all { f -> isDark((x1 + (x2 - x1) * f + sx * d).roundToInt(), (y1 + (y2 - y1) * f + sy * d).roundToInt()) }
            }
        }
        if (closed.size != 1) return false
        // The open end of either face: only the one on the joined wall's side meets its face.
        val ex = e[2 - closed[0]]; val ey = e[3 - closed[0]]
        val j = if (hypot(oe[0] - ex, oe[1] - ey) <= hypot(oe[2] - ex, oe[3] - ey)) 0 else 2
        return listOf(ex to ey, oe[j] to oe[j + 1]).any { (x0, y0) ->
            (-2..2).any { d -> // the face may stop a pixel short of the wall's face, or on it
                val x = x0 + sx * d; val y = y0 + sy * d
                (-2..2).any { k -> val a2 = s.angle + PI / 2 + k * PI / 36; darkSpan(x, y, cos(a2).toFloat(), sin(a2).toFloat(), minLength) >= minLength }
            }
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
        return merged.map { g ->
            Line(g.sumOf { it.t.toDouble() }.toFloat() / g.size, g.first().angle, g.maxOf { it.thickness }, g.first().sinToRay,
                g.first().ends.takeIf { g.all { it.ends != null } })
        }
    }

    /** The straight line (24"+) through ([cx], [cy]), [t] along the ray, or null if there isn't one. */
    private fun lineAt(cx: Float, cy: Float, t: Float, rayAngle: Double): Line? {
        var best = 0f
        var bestAngle = 0.0
        for (step in 0 until 36) { // every 5 degrees
            val a = step * PI / 36
            val length = darkSpan(cx, cy, cos(a).toFloat(), sin(a).toFloat(), minLength)
            if (length > best) { best = length; bestAngle = a }
            if (best >= minLength) break
        }
        if (best < stubLength) return null
        val sinToRay = abs(sin(bestAngle - rayAngle))
        if (sinToRay < 0.25) return null // the ray runs along this line, not across it
        // Thickness across the line, at three spots along it: the smallest ignores places where another line crosses.
        val ax = cos(bestAngle).toFloat()
        val ay = sin(bestAngle).toFloat()
        val thickness = listOf(0f, minLength / 4, -minLength / 4).minOf { o ->
            darkSpan(cx + ax * o, cy + ay * o, -ay, ax, maxGap)
        }
        val ends = if (best >= minLength) null else {
            val f = darkRun(cx, cy, ax, ay, minLength); val b = darkRun(cx, cy, -ax, -ay, minLength)
            floatArrayOf(cx + ax * f, cy + ay * f, cx - ax * b, cy - ay * b)
        }
        return Line(t, bestAngle, thickness, sinToRay, ends)
    }

    /** How many dark pixels follow (cx, cy) along (dx, dy), up to [cap]. */
    private fun darkRun(cx: Float, cy: Float, dx: Float, dy: Float, cap: Float): Int {
        var k = 1
        while (k < cap && isDark((cx + dx * k).roundToInt(), (cy + dy * k).roundToInt())) k++
        return k - 1
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
