package com.scaletape

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

// Pure Kotlin: a starting border for each room, found by flooding the floor out from its label until walls stop it.

private const val CELL_IN = 2f // grid cell, real inches
private const val REACH_IN = 360f // a room reaches at most 30' from its label
private const val OPEN_IN = 21f // floor this far from any wall is open; narrower spots (doors up to ~42") are not
private const val MERGE_SQFT = 40f // unlabeled pockets smaller than this (closets) join the room they open onto
private const val MAX_OPENING_IN = 120f // a wall gap up to 10' with ink across it (dashed header, pocket door) is closed
private const val INKED = 0.3f // share of a gap's length that must have ink in the wall band

/**
 * The bounding rectangle (page pixels) of the floor each label in [labels] (page pixels) floods, or null where it
 * floods nothing (a label on a wall). Walls come from [walls] on a grid of 2" cells; a straight wall's gap with ink
 * across its band (a dashed cased-opening header) counts as wall. Open floor (21"+ from a wall, so doors up to
 * ~42" close) is split among the labels at its narrowest points; open floor no label reaches is an unlabeled room;
 * then every room grows out over the floor near walls until they meet, so a door splits between its two sides.
 * Unlabeled rooms under 40 sq ft join the room they touch most.
 */
fun fillRooms(labels: List<Offset>, width: Int, height: Int, pxPerInch: Float, walls: WallFinder, isDark: (x: Int, y: Int) -> Boolean): List<Rect?> {
    if (labels.isEmpty()) return emptyList()
    val cs = max(1f, CELL_IN * pxPerInch) // cell size in pixels
    val reach = ceil(REACH_IN / CELL_IN).toInt()
    // Grid only over the labels' reach, plus a margin: title blocks and notes far from any room aren't scanned.
    val ox = max(0, floor(labels.minOf { it.x } / cs).toInt() - reach - 6)
    val oy = max(0, floor(labels.minOf { it.y } / cs).toInt() - reach - 6)
    val gw = min(ceil(width / cs).toInt(), floor(labels.maxOf { it.x } / cs).toInt() + reach + 7) - ox
    val gh = min(ceil(height / cs).toInt(), floor(labels.maxOf { it.y } / cs).toInt() + reach + 7) - oy
    if (gw <= 0 || gh <= 0) return labels.map { null }
    val wall = BooleanArray(gw * gh)

    // Rows cross vertical walls, columns horizontal ones; angled walls show up in both.
    val left = ox * cs; val right = min(width.toFloat(), (ox + gw) * cs)
    val top = oy * cs; val bottom = min(height.toFloat(), (oy + gh) * cs)
    for (r in 0 until gh) {
        val y = (oy + r + 0.5f) * cs
        for (s in walls.wallSpans(left, y, right, y)) markCells(wall, gw, r, s, left, ox, cs, rows = true)
    }
    for (c in 0 until gw) {
        val x = (ox + c + 0.5f) * cs
        for (s in walls.wallSpans(x, top, x, bottom)) markCells(wall, gw, c, s, top, oy, cs, rows = false, gh = gh)
    }
    closeInkedOpenings(wall, gw, gh, ox, oy, cs, isDark)

    val unit = 3 // chamfer weights 3 (side) and 4 (diagonal) per cell
    fun units(inches: Float) = (inches / CELL_IN * unit).toInt()
    val dist = distanceToWall(wall, gw, gh, cap = units(REACH_IN))
    val open = units(OPEN_IN)
    val owner = IntArray(gw * gh) { -1 }
    val boxes = labels.map { l -> cellOf(l, cs, ox, oy).let { (x, y) -> intArrayOf(x - reach, y - reach, x + reach, y + reach) } }
    fun inBox(o: Int, x: Int, y: Int) = o >= boxes.size || boxes[o].let { b -> x in b[0]..b[2] && y in b[1]..b[3] }
    fun neighbors(c: Int, f: (n: Int, x: Int, y: Int) -> Unit) {
        val x = c % gw; val y = c / gw
        if (x > 0) f(c - 1, x - 1, y); if (x < gw - 1) f(c + 1, x + 1, y)
        if (y > 0) f(c - gw, x, y - 1); if (y < gh - 1) f(c + gw, x, y + 1)
    }

    // 1. Open floor (21"+ from any wall) goes to the labels by walking distance (all spread at once), so labels
    // sharing an open plan split it between them; a label on narrower floor (a hall) spreads into open floor next
    // to it too. The outdoors floods in from the grid's edge at the same time, so a room with a garage door or a
    // gap in an outside wall meets it there instead of filling the yard.
    val outside = labels.size
    val q = ArrayDeque<Int>()
    labels.forEachIndexed { i, l ->
        val (cx, cy) = cellOf(l, cs, ox, oy)
        val start = nearestFloor(wall, gw, gh, cx, cy) ?: return@forEachIndexed
        if (owner[start] < 0) { owner[start] = i; q.addLast(start) }
    }
    for (c in owner.indices) {
        val x = c % gw; val y = c / gw
        if ((x == 0 || y == 0 || x == gw - 1 || y == gh - 1) && owner[c] < 0 && dist[c] >= open) { owner[c] = outside; q.addLast(c) }
    }
    while (q.isNotEmpty()) {
        val c = q.removeFirst(); val o = owner[c]
        neighbors(c) { n, x, y -> if (owner[n] < 0 && dist[n] >= open && inBox(o, x, y)) { owner[n] = o; q.addLast(n) } }
    }
    // 2. Open floor no label reached (an unlabeled hall or closet) is a room of its own for now, so it keeps its
    // floor in step 3 instead of a neighbor flooding it through the door.
    var next = labels.size + 1
    for (start in owner.indices) {
        if (owner[start] >= 0 || dist[start] < open) continue
        val id = next++; owner[start] = id
        val q = ArrayDeque(listOf(start))
        while (q.isNotEmpty()) neighbors(q.removeFirst()) { n, _, _ -> if (owner[n] < 0 && dist[n] >= open) { owner[n] = id; q.addLast(n) } }
    }
    // 3. All rooms grow out together over the rest of the floor (along walls, through doors) until they meet.
    val front = ArrayDeque(owner.indices.filter { owner[it] >= 0 })
    while (front.isNotEmpty()) {
        val c = front.removeFirst(); val o = owner[c]
        neighbors(c) { n, x, y -> if (owner[n] < 0 && !wall[n] && inBox(o, x, y)) { owner[n] = o; front.addLast(n) } }
    }
    mergePockets(owner, gw, gh, labels.size, maxCells = (MERGE_SQFT * 144 / (CELL_IN * CELL_IN)).toInt())

    val ext = Array(labels.size) { intArrayOf(Int.MAX_VALUE, Int.MAX_VALUE, -1, -1) }
    for (cell in owner.indices) {
        val o = owner[cell]; if (o < 0) continue
        val x = cell % gw; val y = cell / gw; val e = ext[o]
        e[0] = min(e[0], x); e[1] = min(e[1], y); e[2] = max(e[2], x); e[3] = max(e[3], y)
    }
    val page = Rect(0f, 0f, width.toFloat(), height.toFloat())
    return ext.map { e -> if (e[2] < 0) null else Rect((ox + e[0]) * cs, (oy + e[1]) * cs, (ox + e[2] + 1) * cs, (oy + e[3] + 1) * cs).intersect(page) }
}

private fun cellOf(p: Offset, cs: Float, ox: Int, oy: Int) = floor(p.x / cs).toInt() - ox to floor(p.y / cs).toInt() - oy

/** Marks the cells under span [s] (pixels along a row ray starting at [start], or a column ray). */
private fun markCells(wall: BooleanArray, gw: Int, line: Int, s: ClosedFloatingPointRange<Float>, start: Float, o: Int, cs: Float, rows: Boolean, gh: Int = 0) {
    val n = if (rows) gw else gh
    val a = max(0, floor((start + s.start) / cs).toInt() - o)
    val b = min(n - 1, floor((start + s.endInclusive) / cs).toInt() - o)
    for (k in a..b) wall[if (rows) line * gw + k else k * gw + line] = true
}

/**
 * A gap in a straight wall (wall cells running 12"+ along the same grid row or column on both sides) up to 10'
 * long, with ink across at least 30% of it inside that row's band of pixels, becomes wall: a dashed header over a
 * cased opening, a pocket or sliding door. A swinging door's leaf and arc only touch the band at its ends.
 */
private fun closeInkedOpenings(wall: BooleanArray, gw: Int, gh: Int, ox: Int, oy: Int, cs: Float, isDark: (Int, Int) -> Boolean) {
    val minRun = ceil(12f / CELL_IN).toInt()
    val maxGap = (MAX_OPENING_IN / CELL_IN).toInt()
    val toClose = mutableListOf<Int>()
    for (rows in booleanArrayOf(true, false)) {
        val lines = if (rows) gh else gw; val len = if (rows) gw else gh
        fun at(line: Int, k: Int) = if (rows) line * gw + k else k * gw + line
        for (line in 0 until lines) {
            var k = 0
            while (k < len) {
                if (wall[at(line, k)]) { k++; continue }
                val gapStart = k
                while (k < len && !wall[at(line, k)]) k++
                val gapEnd = k - 1
                if (gapStart == 0 || k == len || gapEnd - gapStart + 1 > maxGap) continue
                fun run(from: Int, dir: Int): Int { var j = from; var n = 0; while (j in 0 until len && wall[at(line, j)] && n < minRun) { n++; j += dir }; return n }
                if (run(gapStart - 1, -1) < minRun || run(k, 1) < minRun) continue
                // Ink across the gap, in this row's (column's) band of pixels.
                val bandFrom = ((if (rows) oy else ox) + line) * cs; val bandTo = bandFrom + cs
                val along0 = ((if (rows) ox else oy) + gapStart) * cs; val along1 = ((if (rows) ox else oy) + gapEnd + 1) * cs
                var inked = 0; var total = 0
                var a = along0.toInt()
                while (a < along1.toInt()) {
                    total++
                    var b = bandFrom.toInt()
                    while (b < bandTo.toInt()) { if (if (rows) isDark(a, b) else isDark(b, a)) { inked++; break }; b++ }
                    a++
                }
                if (total > 0 && inked >= INKED * total) for (j in gapStart..gapEnd) toClose += at(line, j)
            }
        }
    }
    for (c in toClose) wall[c] = true
}

/** Chamfer distance (3 per side step, 4 per diagonal) from each cell to the nearest wall cell, capped at [cap]. */
private fun distanceToWall(wall: BooleanArray, gw: Int, gh: Int, cap: Int): IntArray {
    val d = IntArray(gw * gh) { if (wall[it]) 0 else cap }
    fun relax(i: Int, x: Int, y: Int, w: Int) { if (x in 0 until gw && y in 0 until gh) d[i] = min(d[i], d[y * gw + x] + w) }
    for (y in 0 until gh) for (x in 0 until gw) { val i = y * gw + x
        relax(i, x - 1, y, 3); relax(i, x - 1, y - 1, 4); relax(i, x, y - 1, 3); relax(i, x + 1, y - 1, 4) }
    for (y in gh - 1 downTo 0) for (x in gw - 1 downTo 0) { val i = y * gw + x
        relax(i, x + 1, y, 3); relax(i, x + 1, y + 1, 4); relax(i, x, y + 1, 3); relax(i, x - 1, y + 1, 4) }
    for (i in d.indices) d[i] = min(d[i], cap)
    return d
}

/** The floor cell nearest ([cx], [cy]) within 6 cells (a label drawn over a wall line), or null. */
private fun nearestFloor(wall: BooleanArray, gw: Int, gh: Int, cx: Int, cy: Int): Int? {
    for (r in 0..6) for (dy in -r..r) for (dx in -r..r) {
        if (max(kotlin.math.abs(dx), kotlin.math.abs(dy)) != r) continue
        val x = cx + dx; val y = cy + dy
        if (x in 0 until gw && y in 0 until gh && !wall[y * gw + x]) return y * gw + x
    }
    return null
}

/**
 * Unlabeled rooms (owner [labels] or more) under [maxCells] go to the labeled room they touch most; larger ones, and
 * ones running off the grid (outside the house), are dropped.
 */
private fun mergePockets(owner: IntArray, gw: Int, gh: Int, labels: Int, maxCells: Int) {
    val size = HashMap<Int, Int>(); val offGrid = HashSet<Int>(); val touches = HashMap<Int, HashMap<Int, Int>>()
    for (c in owner.indices) {
        val o = owner[c]; if (o < labels) continue
        size.merge(o, 1, Int::plus)
        val x = c % gw; val y = c / gw
        if (x == 0 || y == 0 || x == gw - 1 || y == gh - 1) offGrid += o
        for (n in intArrayOf(if (x > 0) c - 1 else -1, if (x < gw - 1) c + 1 else -1, if (y > 0) c - gw else -1, if (y < gh - 1) c + gw else -1)) {
            if (n >= 0 && owner[n] in 0 until labels) touches.getOrPut(o) { HashMap() }.merge(owner[n], 1, Int::plus)
        }
    }
    val to = size.keys.associateWith { o ->
        if (o in offGrid || size[o]!! >= maxCells) -1 else touches[o]?.maxByOrNull { it.value }?.key ?: -1
    }
    for (c in owner.indices) if (owner[c] >= labels) owner[c] = to[owner[c]]!!
}

/**
 * The part of [flood] (from [fillRooms]) that [rays] (from [seedRect]) agrees with: a flood leaks through any hole in
 * the walls it finds (a window, a garage door, a wall piece under 4'), rays leak through a door one of them passes,
 * so a room leaks only where both do. [rays] alone if the overlap doesn't hold the [label].
 */
fun agreed(flood: Rect?, rays: Rect, label: Offset): Rect =
    flood?.intersect(rays)?.takeIf { it.width > 0 && it.height > 0 && label.x in it.left..it.right && label.y in it.top..it.bottom } ?: rays
