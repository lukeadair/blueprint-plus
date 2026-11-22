package com.scaletape

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.math.abs

// Room borders: polygons in PDF points (the units room labels and Run.midPoints use), so they don't depend on
// render size or scale. A run belongs to the room whose border holds its midpoint.

/** A room's border on [page]: a closed polygon of at least 3 [points]. */
data class RoomBoundary(val name: String, val page: Int, val points: List<Offset>) {
    fun area(): Float = abs(points.indices.sumOf { i ->
        val a = points[i]; val b = points[(i + 1) % points.size]
        (a.x * b.y - b.x * a.y).toDouble()
    }.toFloat()) / 2f

    fun contains(p: Offset): Boolean {
        var inside = false
        var j = points.size - 1
        for (i in points.indices) {
            val a = points[i]; val b = points[j]
            if ((a.y > p.y) != (b.y > p.y) && p.x < (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x) inside = !inside
            j = i
        }
        return inside
    }

    fun moveVertex(i: Int, by: Offset) = copy(points = points.mapIndexed { j, p -> if (j == i) p + by else p })

    /** A new vertex at the middle of the edge from vertex [i] to the next one. */
    fun splitEdge(i: Int) = if (points.size >= MAX_CORNERS) this else copy(points = points.toMutableList().apply { add(i + 1, (points[i] + points[(i + 1) % points.size]) / 2f) })

    /** Null when it would leave fewer than 3 vertices. */
    fun withoutVertex(i: Int) = if (points.size <= 3) null else copy(points = points.filterIndexed { j, _ -> j != i })
}

/** The room holding [p] (PDF points) on [page]; the smallest border wins, so a closet inside a bedroom is the closet. */
fun roomAt(boundaries: List<RoomBoundary>, page: Int, p: Offset): String? =
    boundaries.filter { it.page == page && it.contains(p) }.minByOrNull { it.area() }?.name

/** Most corners a border may have: rooms are rectangles, or an L at most. */
const val MAX_CORNERS = 6

fun Rect.corners() = listOf(topLeft, topRight, bottomRight, bottomLeft)

fun Offset.clampTo(r: Rect) = Offset(x.coerceIn(r.left, r.right), y.coerceIn(r.top, r.bottom))

fun RoomBoundary.bounds() = Rect(points.minOf { it.x }, points.minOf { it.y }, points.maxOf { it.x }, points.maxOf { it.y })

/**
 * A starting rectangle for the room labelled at [center]. Each side is where walls stop rays going that way: three
 * parallel rays, [spread] apart, and the shortest wins, so a door opening (or a wall missed in one ray) doesn't let
 * the room leak into the next one.
 * A ray stops where [hitsWall] says the segment has crossed a wall (bisected; a wall shows up at its far face, so
 * it's pulled back by [inset]), or at [maxLen]. Clamped to [page].
 */
fun seedRect(center: Offset, maxLen: Float, inset: Float, spread: Float, page: Rect, hitsWall: (from: Offset, to: Offset) -> Boolean): Rect {
    fun reach(from: Offset, dir: Offset): Float {
        if (!hitsWall(from, from + dir * maxLen)) return maxLen
        var lo = 0f
        var hi = maxLen
        repeat(8) { val mid = (lo + hi) / 2; if (hitsWall(from, from + dir * mid)) hi = mid else lo = mid }
        return (hi - inset).coerceAtLeast(inset)
    }
    val l = reach(center, Offset(-1f, 0f)); val u = reach(center, Offset(0f, -1f))
    val r = reach(center, Offset(1f, 0f)); val d = reach(center, Offset(0f, 1f))
    // Side rays stay inside the room: in a 4' hall, rays 2' off the middle would run outside it. Each goes as far
    // as its own side allows ([minus]/[plus]: reach toward -across/+across), so a label near one wall still spans
    // the room the other way and its rays can't all fit through one door.
    fun side(dir: Offset, middle: Float, minus: Float, plus: Float): Float {
        val across = Offset(dir.y, dir.x)
        return listOf(-minOf(spread, 0.8f * minus), minOf(spread, 0.8f * plus)).minOf { reach(center + across * it, dir) }.coerceAtMost(middle)
    }
    return Rect(
        center.x - side(Offset(-1f, 0f), l, d, u), center.y - side(Offset(0f, -1f), u, r, l),
        center.x + side(Offset(1f, 0f), r, u, d), center.y + side(Offset(0f, 1f), d, l, r),
    ).intersect(page)
}

/**
 * Removes overlaps between seeded rectangles ([seeds]: label center to rectangle). Smallest first, so a closet
 * keeps its space and the room around it is cut back; each later one is cut by [clipAway] against all placed so
 * far. A label inside a room already placed, [minApart] or more from that room's label along the axis they're
 * furthest apart on, splits that room halfway between the two (open plans: hall into kitchen, kitchen into
 * dining). Null where nothing is left that holds its label (a label right next to another room's).
 */
fun separate(seeds: List<Pair<Offset, Rect>>, minApart: Float = Float.MAX_VALUE): List<Rect?> {
    val placed = mutableListOf<Int>()
    val out = MutableList<Rect?>(seeds.size) { null }
    for (i in seeds.indices.sortedBy { seeds[it].second.width * seeds[it].second.height }) {
        val (c, seed) = seeds[i]
        for (j in placed) {
            val p = out[j]!!; val pc = seeds[j].first
            if (!(c.x in p.left..p.right && c.y in p.top..p.bottom)) continue
            val dx = c.x - pc.x; val dy = c.y - pc.y
            if (maxOf(abs(dx), abs(dy)) < minApart) continue
            val m = (c + pc) / 2f
            out[j] = if (abs(dx) >= abs(dy)) { if (dx > 0) p.copy(right = m.x) else p.copy(left = m.x) }
                else { if (dy > 0) p.copy(bottom = m.y) else p.copy(top = m.y) }
        }
        var r: Rect? = seed
        for (j in placed) { r = clipAway(r!!, out[j]!!, c); if (r == null) break }
        if (r != null) { placed += i; out[i] = r }
    }
    return out
}

/**
 * [r] moved back off [taken]: of the four cuts (one side to [taken]'s facing edge), the biggest that still holds
 * [keep]; null if none does. [r] itself if they don't overlap.
 */
fun clipAway(r: Rect, taken: Rect, keep: Offset): Rect? {
    if (!(r.left < taken.right && taken.left < r.right && r.top < taken.bottom && taken.top < r.bottom)) return r
    return listOf(r.copy(right = taken.left), r.copy(left = taken.right), r.copy(bottom = taken.top), r.copy(top = taken.bottom))
        .filter { it.width > 0 && it.height > 0 && keep.x in it.left..it.right && keep.y in it.top..it.bottom }
        .maxByOrNull { it.width * it.height }
}

private fun RoomBoundary.edges() = points.indices.map { points[it] to points[(it + 1) % points.size] }

private fun cross(o: Offset, a: Offset, b: Offset) = (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)

/** Segments ab and cd cross at a point inside both (touching or running along each other doesn't count). */
private fun properlyCross(a: Offset, b: Offset, c: Offset, d: Offset): Boolean {
    val d1 = cross(c, d, a); val d2 = cross(c, d, b); val d3 = cross(a, b, c); val d4 = cross(a, b, d)
    return ((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0)) && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0))
}

private fun distToSegment(p: Offset, a: Offset, b: Offset): Float {
    val ab = b - a
    val len2 = ab.x * ab.x + ab.y * ab.y
    val t = if (len2 == 0f) 0f else (((p.x - a.x) * ab.x + (p.y - a.y) * ab.y) / len2).coerceIn(0f, 1f)
    return (p - (a + ab * t)).getDistance()
}

private const val EDGE_EPS = 0.01f // PDF points: a point this close to an edge is on it

private fun RoomBoundary.strictlyContains(p: Offset) = contains(p) && edges().none { (a, b) -> distToSegment(p, a, b) < EDGE_EPS }

/** A point just inside each edge's middle: catches one border lying on or inside another along shared lines. */
private fun RoomBoundary.insideEdgePoints() = edges().mapNotNull { (a, b) ->
    val m = (a + b) / 2f
    val len = (b - a).getDistance().takeIf { it > 0f } ?: return@mapNotNull null
    val n = Offset(-(b - a).y, (b - a).x) / len * (EDGE_EPS * 10)
    listOf(m + n, m - n).firstOrNull { contains(it) }
}

/** Whether the two borders share any area. Sharing a wall (touching edges or corners) is fine. */
fun RoomBoundary.overlaps(o: RoomBoundary): Boolean =
    edges().any { (a, b) -> o.edges().any { (c, d) -> properlyCross(a, b, c, d) } } ||
        points.any { o.strictlyContains(it) } || o.points.any { strictlyContains(it) } ||
        insideEdgePoints().any { o.strictlyContains(it) } || o.insideEdgePoints().any { strictlyContains(it) }

/** A border whose edges cross each other (a bow tie). */
fun RoomBoundary.selfCrosses(): Boolean {
    val e = edges()
    return e.indices.any { i -> (i + 2 until e.size).any { j -> (i != 0 || j != e.size - 1) && properlyCross(e[i].first, e[i].second, e[j].first, e[j].second) } }
}

/**
 * Puts every run that isn't manual and sits on a seeded page into the room its midpoint is in (Unassigned if
 * none). Pages not seeded yet keep the label-based picks.
 */
fun Job.reassignRooms(): Job = copy(runs = runs.map { r ->
    if (r.roomManual || r.page !in seededPages) r
    else (roomAt(boundaries, r.page, r.midPoints()) ?: "").let { if (it == r.room) r else r.copy(room = it) }
})

/** [pages] with their borders gone and not seeded, so the seeding effect draws them fresh from the labels. */
fun Job.unseed(pages: Set<Int>): Job = copy(
    boundaries = boundaries.filter { it.page !in pages },
    seededPages = seededPages - pages, editedPages = editedPages - pages,
)

/**
 * This job after [old]: when the scale changed, pages whose borders the user hasn't touched are redrawn, since
 * their walls were found with the old scale (which is often just the 1/4" default on a job's first open).
 */
fun Job.reseedIfScaleChanged(old: Job): Job =
    if (parseScale(scale) == parseScale(old.scale)) this else unseed(seededPages - editedPages)
