package com.scaletape

import androidx.compose.ui.geometry.Offset
import org.json.JSONArray
import org.json.JSONObject

/**
 * A measured run: a polyline of [a], [b] and any [extra] points (a corner turned), in page-bitmap pixels, on a page rendered at [ppi].
 * [room]: null = not assigned yet; "" = Unassigned. [roomManual]: the user chose the room (by dragging), so the
 * app never changes it; otherwise the app picks it from the plan's labels and re-picks when the job reopens.
 */
data class Run(
    val page: Int, val a: Offset, val b: Offset, val ppi: Float,
    val room: String? = null, val roomManual: Boolean = false,
    val extra: List<Offset> = emptyList(),
) {
    fun points(): List<Offset> = listOf(a, b) + extra

    /** This run with the point at [i] (0 = [a], 1 = [b], then [extra]) moved to [p]. */
    fun withPoint(i: Int, p: Offset): Run = when (i) {
        0 -> copy(a = p)
        1 -> copy(b = p)
        else -> copy(extra = extra.mapIndexed { k, q -> if (k == i - 2) p else q })
    }

    private fun withPoints(l: List<Offset>) = copy(a = l[0], b = l[1], extra = l.drop(2))

    /** This run with a new point on the segment nearest [p], at the spot on it closest to [p]; unchanged if that is within [minGap] of an existing point. */
    fun withInserted(p: Offset, minGap: Float = 0f): Run {
        val pts = points()
        val k = (0 until pts.size - 1).minBy { Run(page, pts[it], pts[it + 1], ppi).distanceTo(p) }
        val ab = pts[k + 1] - pts[k]
        val len2 = ab.x * ab.x + ab.y * ab.y
        val t = if (len2 == 0f) 0f else (((p.x - pts[k].x) * ab.x + (p.y - pts[k].y) * ab.y) / len2).coerceIn(0f, 1f)
        val q = pts[k] + ab * t
        if ((q - pts[k]).getDistance() <= minGap || (q - pts[k + 1]).getDistance() <= minGap) return this
        return withPoints(pts.toMutableList().apply { add(k + 1, q) })
    }

    /** This run without point [i]; a run keeps at least two points. */
    fun withoutPoint(i: Int): Run = if (points().size <= 2) this else withPoints(points().filterIndexed { k, _ -> k != i })

    private fun segments() = points().zipWithNext()

    /** Midpoint of the longest segment, in page pixels: where the run's label sits and its room is picked. */
    fun midpoint(): Offset = segments().maxByOrNull { (p, q) -> (q - p).getDistance() }!!.let { (p, q) -> (p + q) / 2f }

    /** Midpoint in PDF points (1/72"), the units room labels come in. */
    fun midPoints(): Offset = midpoint() / ppi * 72f

    fun inches(ratio: Double) = realInches(segments().sumOf { (p, q) -> (q - p).getDistance().toDouble() }, ppi.toDouble(), ratio)

    /** Distance from [p] to the nearest segment of this run (not the infinite line), in page pixels. */
    fun distanceTo(p: Offset): Float = segments().minOf { (a, b) ->
        val ab = b - a
        val len2 = ab.x * ab.x + ab.y * ab.y
        val t = if (len2 == 0f) 0f else (((p.x - a.x) * ab.x + (p.y - a.y) * ab.y) / len2).coerceIn(0f, 1f)
        (p - (a + ab * t)).getDistance()
    }
}

data class Job(
    val id: String,
    val name: String,
    val scale: String = "1/4\" = 1'",
    val page: Int = 0,
    val updated: Long = 0,
    val created: Long = 0,
    val runs: List<Run> = emptyList(),
    /** Rooms the user added by hand; they show in the run list even with no runs in them. */
    val rooms: List<String> = emptyList(),
    /** Room borders. Runs on a page in [seededPages] get their room from these (see [reassignRooms]). */
    val boundaries: List<RoomBoundary> = emptyList(),
    /** Pages whose borders were auto-drawn from the room labels; redone only by Redetect or, if not edited, a new scale. */
    val seededPages: Set<Int> = emptySet(),
    /** Pages whose borders the user changed (moved, added, deleted); a scale change leaves these alone. */
    val editedPages: Set<Int> = emptySet(),
    /** The user confirmed the scale (Save in Job Config, or a calibration). Rooms aren't seeded before that. */
    val scaleSet: Boolean = true,
    /** The scale read from the plan when the job was made (null = none found); Job Config marks it "found in plan". */
    val foundScale: String? = null,
    /** JSON keys this app doesn't read (the Mac's "roomColors"), each value as JSON text; written back unchanged. */
    val unknownKeys: Map<String, String> = emptyMap(),
) {
    /**
     * Room [old] renamed to [to] (an existing room's name merges the two). Its runs move with it, marked
     * manual so re-picking doesn't put them back under the plan's label.
     */
    fun renameRoom(old: String, to: String): Job = copy(
        runs = runs.map { if (it.room == old) it.copy(room = to, roomManual = true) else it },
        rooms = (rooms.map { if (it == old) to else it } + to).distinct(),
        boundaries = boundaries.map { if (it.name == old) it.copy(name = to) else it },
        editedPages = editedPages + boundaries.filter { it.name == old }.map { it.page }, // a scale change must not undo it
    )

    /** Null when the scale text doesn't parse. */
    fun totalInches(): Double? = parseScale(scale)?.let { r -> runs.sumOf { it.inches(r) } }
}

/** How rooms are listed (run list, export): alphabetical so they don't jump when a run moves, Unassigned ("") last. */
val roomOrder: Comparator<String> = compareBy({ it.isEmpty() }, { it.lowercase() })

fun Job.toJson(): String = JSONObject()
    .put("id", id).put("name", name).put("scale", scale).put("page", page).put("updated", updated).put("created", created)
    .put("runs", JSONArray(runs.map {
        JSONObject().put("page", it.page)
            .put("ax", it.a.x.toDouble()).put("ay", it.a.y.toDouble())
            .put("bx", it.b.x.toDouble()).put("by", it.b.y.toDouble())
            .put("ppi", it.ppi.toDouble())
            .apply { if (it.room != null) put("room", it.room) }
            .apply { if (it.roomManual) put("roomManual", true) }
            .apply { if (it.extra.isNotEmpty()) put("pts", JSONArray(it.extra.flatMap { p -> listOf(p.x.toDouble(), p.y.toDouble()) })) }
    }))
    .put("rooms", JSONArray(rooms))
    .put("boundaries", JSONArray(boundaries.map { b ->
        JSONObject().put("name", b.name).put("page", b.page)
            .put("pts", JSONArray(b.points.flatMap { listOf(it.x.toDouble(), it.y.toDouble()) }))
    }))
    .put("seededPages", JSONArray(seededPages.sorted()))
    .put("editedPages", JSONArray(editedPages.sorted()))
    .put("scaleSet", scaleSet)
    .apply { if (foundScale != null) put("foundScale", foundScale) }
    .apply { unknownKeys.forEach { (k, v) -> if (!has(k)) put(k, JSONArray(v).get(0)) } }
    .toString()

private val KNOWN_KEYS = setOf(
    "id", "name", "scale", "page", "updated", "created", "runs", "rooms", "boundaries",
    "seededPages", "editedPages", "scaleSet", "foundScale",
)

fun jobFromJson(json: String): Job {
    val o = JSONObject(json)
    val arr = o.getJSONArray("runs")
    val roomArr = o.optJSONArray("rooms")
    val boundArr = o.optJSONArray("boundaries")
    val seededArr = o.optJSONArray("seededPages")
    val editedArr = o.optJSONArray("editedPages")
    return Job(
        id = o.getString("id"),
        name = o.getString("name"),
        scale = o.getString("scale"),
        page = o.optInt("page"),
        updated = o.optLong("updated"),
        created = o.optLong("created", o.optLong("updated")), // jobs saved before this field: best guess
        runs = (0 until arr.length()).map { i ->
            val r = arr.getJSONObject(i)
            Run(
                r.getInt("page"),
                Offset(r.getDouble("ax").toFloat(), r.getDouble("ay").toFloat()),
                Offset(r.getDouble("bx").toFloat(), r.getDouble("by").toFloat()),
                r.getDouble("ppi").toFloat(),
                if (r.has("room")) r.getString("room") else null,
                r.optBoolean("roomManual"),
                r.optJSONArray("pts")?.let { p -> (0 until p.length() / 2).map { Offset(p.getDouble(2 * it).toFloat(), p.getDouble(2 * it + 1).toFloat()) } }
                    ?: emptyList(),
            )
        },
        rooms = (0 until (roomArr?.length() ?: 0)).map { roomArr!!.getString(it) },
        boundaries = (0 until (boundArr?.length() ?: 0)).map { i ->
            val b = boundArr!!.getJSONObject(i)
            val pts = b.getJSONArray("pts")
            RoomBoundary(
                b.getString("name"), b.getInt("page"),
                (0 until pts.length() / 2).map { Offset(pts.getDouble(2 * it).toFloat(), pts.getDouble(2 * it + 1).toFloat()) },
            )
        },
        seededPages = (0 until (seededArr?.length() ?: 0)).map { seededArr!!.getInt(it) }.toSet(),
        editedPages = (0 until (editedArr?.length() ?: 0)).map { editedArr!!.getInt(it) }.toSet(),
        scaleSet = o.optBoolean("scaleSet", true), // older saves: already in use, don't prompt
        foundScale = if (o.has("foundScale")) o.getString("foundScale") else null,
        // wrapped in an array so a bare string or number round-trips as JSON text
        unknownKeys = o.keys().asSequence().filter { it !in KNOWN_KEYS }.associateWith { JSONArray().put(o.get(it)).toString() },
    )
}
