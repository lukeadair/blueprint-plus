package com.scaletape

import androidx.compose.ui.geometry.Offset
import org.json.JSONArray
import org.json.JSONObject

/**
 * A measured run: two points in page-bitmap pixels, on a page rendered at [ppi].
 * [room]: null = not assigned yet; "" = Unassigned. [roomManual]: the user chose the room (by dragging), so the
 * app never changes it; otherwise the app picks it from the plan's labels and re-picks when the job reopens.
 */
data class Run(
    val page: Int, val a: Offset, val b: Offset, val ppi: Float,
    val room: String? = null, val roomManual: Boolean = false,
) {
    /** Midpoint in PDF points (1/72"), the units room labels come in. */
    fun midPoints(): Offset = (a + b) / 2f / ppi * 72f

    fun inches(ratio: Double) = realInches((b - a).getDistance().toDouble(), ppi.toDouble(), ratio)

    /** Distance from [p] to this run's segment (not the infinite line), in page pixels. */
    fun distanceTo(p: Offset): Float {
        val ab = b - a
        val len2 = ab.x * ab.x + ab.y * ab.y
        val t = if (len2 == 0f) 0f else (((p.x - a.x) * ab.x + (p.y - a.y) * ab.y) / len2).coerceIn(0f, 1f)
        return (p - (a + ab * t)).getDistance()
    }
}

data class Job(
    val id: String,
    val name: String,
    val scale: String = "1/4\" = 1'",
    val page: Int = 0,
    val updated: Long = 0,
    val runs: List<Run> = emptyList(),
) {
    /** Null when the scale text doesn't parse. */
    fun totalInches(): Double? = parseScale(scale)?.let { r -> runs.sumOf { it.inches(r) } }
}

fun Job.toJson(): String = JSONObject()
    .put("id", id).put("name", name).put("scale", scale).put("page", page).put("updated", updated)
    .put("runs", JSONArray(runs.map {
        JSONObject().put("page", it.page)
            .put("ax", it.a.x.toDouble()).put("ay", it.a.y.toDouble())
            .put("bx", it.b.x.toDouble()).put("by", it.b.y.toDouble())
            .put("ppi", it.ppi.toDouble())
            .apply { if (it.room != null) put("room", it.room) }
            .apply { if (it.roomManual) put("roomManual", true) }
    }))
    .toString()

fun jobFromJson(json: String): Job {
    val o = JSONObject(json)
    val arr = o.getJSONArray("runs")
    return Job(
        id = o.getString("id"),
        name = o.getString("name"),
        scale = o.getString("scale"),
        page = o.optInt("page"),
        updated = o.optLong("updated"),
        runs = (0 until arr.length()).map { i ->
            val r = arr.getJSONObject(i)
            Run(
                r.getInt("page"),
                Offset(r.getDouble("ax").toFloat(), r.getDouble("ay").toFloat()),
                Offset(r.getDouble("bx").toFloat(), r.getDouble("by").toFloat()),
                r.getDouble("ppi").toFloat(),
                if (r.has("room")) r.getString("room") else null,
                r.optBoolean("roomManual"),
            )
        },
    )
}
