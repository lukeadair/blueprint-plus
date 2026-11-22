package com.scaletape

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundariesTest {
    private fun box(name: String, x0: Float, y0: Float, x1: Float, y1: Float, page: Int = 0) =
        RoomBoundary(name, page, listOf(Offset(x0, y0), Offset(x1, y0), Offset(x1, y1), Offset(x0, y1)))

    @Test fun containsAndArea() {
        val l = RoomBoundary("L", 0, listOf(Offset(0f, 0f), Offset(10f, 0f), Offset(10f, 5f), Offset(5f, 5f), Offset(5f, 10f), Offset(0f, 10f)))
        assertEquals(75f, l.area(), 1e-3f)
        assertTrue(l.contains(Offset(2f, 8f)))
        assertTrue(l.contains(Offset(8f, 2f)))
        assertFalse(l.contains(Offset(8f, 8f))) // the notch
    }

    @Test fun smallestBorderWinsAndPagesAreSeparate() {
        val bs = listOf(box("Bedroom", 0f, 0f, 100f, 100f), box("Closet", 10f, 10f, 30f, 30f), box("Other page", 0f, 0f, 100f, 100f, page = 1))
        assertEquals("Closet", roomAt(bs, 0, Offset(20f, 20f)))
        assertEquals("Bedroom", roomAt(bs, 0, Offset(60f, 60f)))
        assertEquals("Other page", roomAt(bs, 1, Offset(20f, 20f)))
        assertNull(roomAt(bs, 0, Offset(150f, 20f)))
    }

    @Test fun editingVertices() {
        val b = box("R", 0f, 0f, 10f, 10f)
        assertEquals(Offset(12f, -1f), b.moveVertex(1, Offset(2f, -1f)).points[1])
        assertEquals(b.points[0], b.moveVertex(1, Offset(2f, -1f)).points[0]) // only that vertex moves
        val split = b.splitEdge(3) // last edge wraps to vertex 0
        assertEquals(5, split.points.size)
        assertEquals(Offset(0f, 5f), split.points[4])
        assertNull(RoomBoundary("T", 0, b.points.take(3)).withoutVertex(0)) // a triangle stays a triangle
        assertEquals(3, b.withoutVertex(2)!!.points.size)
    }

    private val page = Rect(0f, 0f, 1000f, 1000f)

    @Test fun seedRectFindsTheRoomBetweenWalls() {
        // Walls at the edges of a 100x80 room at (100, 100): a ray has crossed one once its end is outside the box.
        val room = Rect(100f, 100f, 200f, 180f)
        val r = seedRect(Offset(150f, 140f), maxLen = 500f, inset = 0f, spread = 10f, page = page) { _, to -> !room.contains(to) }
        assertEquals(room.left, r.left, 2.5f); assertEquals(room.top, r.top, 2.5f)
        assertEquals(room.right, r.right, 2.5f); assertEquals(room.bottom, r.bottom, 2.5f)
    }

    @Test fun seedRectIgnoresADoorInOneRay() {
        // A door in the right wall right in front of the label: only the middle ray escapes.
        val room = Rect(100f, 100f, 200f, 180f)
        fun escaped(to: Offset) = to.x > 200f && to.y in 135f..145f
        val r = seedRect(Offset(150f, 140f), 500f, 0f, 10f, page) { from, to -> !room.contains(to) && !(from.y in 135f..145f && escaped(to)) }
        assertEquals(200f, r.right, 2.5f)
    }

    @Test fun seedRectStopsAtTheWallEvenIfTwoRaysMissIt() {
        // Finley plan: a door in front of the label, and the wall missed in a second ray; one ray still finds it.
        val room = Rect(100f, 100f, 200f, 180f)
        val r = seedRect(Offset(150f, 140f), 500f, 0f, 10f, page) { from, to -> !room.contains(to) && !(from.y < 145f && to.x > 200f) }
        assertEquals(200f, r.right, 2.5f)
    }

    @Test fun seedRectInANarrowHallKeepsItsRaysInside() {
        // A 20-tall hall (spread 10 would put side rays on its walls) whose right wall has a door away from the middle.
        val hall = Rect(100f, 130f, 300f, 150f)
        val r = seedRect(Offset(150f, 140f), 500f, 0f, 10f, page) { from, to ->
            !hall.contains(to) && !(from.y !in 132f..148f && to.x > 300f) // rays off the hall's middle 16 see no right wall
        }
        assertEquals(300f, r.right, 2.5f)
    }

    @Test fun seedRectNearOneWallSpansTheRoomPastADoor() {
        // Building Plan 2 laundry: the label is 10 below the top wall and a door fills the top of the left wall. Side
        // rays kept within 0.8 x 10 of the label would all go through the door; the lower one must use the room below.
        val room = Rect(100f, 100f, 200f, 200f)
        val r = seedRect(Offset(150f, 110f), 500f, 0f, 40f, page) { _, to -> !room.contains(to) && !(to.x < 100f && to.y in 100f..130f) }
        assertEquals(100f, r.left, 2.5f)
    }

    @Test fun separateSplitsOpenPlanRooms() {
        // Finley plan: the hall's seed runs into the open kitchen and holds the kitchen's label; the kitchen and
        // dining seeds each hold the other's label.
        val seeds = listOf(
            Offset(600f, 1010f) to Rect(194f, 975f, 1004f, 1034f), // hall
            Offset(928f, 1016f) to Rect(765f, 761f, 1156f, 1285f), // kitchen
            Offset(964f, 1181f) to Rect(764f, 776f, 1156f, 1286f), // dining
        )
        val out = separate(seeds, minApart = 50f)
        assertEquals(764f, out[0]!!.right, 1f) // halfway from hall label to kitchen label
        assertNotNull(out[1]); assertNotNull(out[2])
        assertTrue(out[1]!!.contains(seeds[1].first)); assertTrue(out[2]!!.contains(seeds[2].first))
        val kept = out.filterNotNull()
        fun b(r: Rect) = RoomBoundary("", 0, r.corners())
        kept.forEachIndexed { i, r -> kept.drop(i + 1).forEach { o -> assertFalse("$r vs $o", b(r).overlaps(b(o))) } }
    }

    @Test fun seedRectWithNoWallsIsCappedAndStaysOnThePage() {
        val r = seedRect(Offset(30f, 500f), maxLen = 50f, inset = 5f, spread = 10f, page = page) { _, _ -> false }
        assertEquals(Rect(0f, 450f, 80f, 550f), r)
    }

    @Test fun separateLeavesNoOverlaps() {
        fun b(r: Rect) = RoomBoundary("", 0, r.corners())
        // Two neighbours whose seeds overlap; a closet whose label sits inside the bedroom's seed; a label inside the closet.
        val seeds = listOf(
            Offset(50f, 50f) to Rect(0f, 0f, 120f, 100f), // bedroom
            Offset(150f, 50f) to Rect(80f, 0f, 220f, 100f), // neighbour, bigger
            Offset(20f, 20f) to Rect(10f, 10f, 30f, 30f), // closet
            Offset(25f, 25f) to Rect(0f, 0f, 60f, 60f), // label inside the closet: dropped
        )
        val out = separate(seeds)
        assertEquals(Rect(10f, 10f, 30f, 30f), out[2]) // smallest keeps its seed
        assertNull(out[3])
        val kept = out.filterNotNull()
        assertEquals(3, kept.size)
        kept.forEachIndexed { i, r -> kept.drop(i + 1).forEach { o -> assertFalse("$r vs $o", b(r).overlaps(b(o))) } }
        seeds.zip(out).forEach { (s, r) -> if (r != null) assertTrue(r.contains(s.first) || s.first.x == r.right || s.first.y == r.bottom) }
    }

    @Test fun overlapsAllowsSharedWalls() {
        val a = box("A", 0f, 0f, 10f, 10f)
        assertFalse(a.overlaps(box("B", 10f, 0f, 20f, 10f))) // shares an edge
        assertFalse(a.overlaps(box("B", 10f, 10f, 20f, 20f))) // shares a corner
        assertTrue(a.overlaps(box("B", 5f, 5f, 15f, 15f))) // crossing
        assertTrue(a.overlaps(box("B", 2f, 2f, 8f, 8f))) // inside
        assertTrue(a.overlaps(box("B", 0f, 0f, 5f, 10f))) // inside, along shared lines
        assertTrue(a.overlaps(box("B", 0f, 0f, 10f, 10f))) // the same
    }

    @Test fun selfCrossing() {
        assertFalse(box("A", 0f, 0f, 10f, 10f).selfCrosses())
        assertTrue(RoomBoundary("A", 0, listOf(Offset(0f, 0f), Offset(10f, 10f), Offset(10f, 0f), Offset(0f, 10f))).selfCrosses())
    }

    @Test fun atMostSixCorners() {
        var b = box("A", 0f, 0f, 10f, 10f)
        repeat(5) { b = b.splitEdge(0) }
        assertEquals(MAX_CORNERS, b.points.size)
    }

    @Test fun reassignOnlyTouchesSeededPagesAndNeverManualRuns() {
        // 144 ppi: a point at (x, y) px is (x/2, y/2) PDF points. Midpoint of each run below is (60, 60) px = (30, 30) pt.
        fun run(page: Int, room: String? = null, manual: Boolean = false) = Run(page, Offset(40f, 60f), Offset(80f, 60f), 144f, room, manual)
        val job = Job(
            "i", "n",
            runs = listOf(run(0), run(0, "Old"), run(0, "Hand", manual = true), run(1, "Label pick"), run(0).copy(a = Offset(400f, 400f), b = Offset(440f, 400f))),
            boundaries = listOf(box("Kitchen", 0f, 0f, 100f, 100f), box("Kitchen", 0f, 0f, 100f, 100f, page = 1)),
            seededPages = setOf(0),
        )
        assertEquals(listOf("Kitchen", "Kitchen", "Hand", "Label pick", ""), job.reassignRooms().runs.map { it.room })
    }

    @Test fun boundariesSurviveJsonAndOldSavesLoad() {
        val job = Job("i", "n", boundaries = listOf(box("Kitchen", 1.5f, 2f, 30f, 40f, page = 2)), seededPages = setOf(0, 2), editedPages = setOf(2))
        assertEquals(job, jobFromJson(job.toJson()))
        val old = jobFromJson("""{"id":"i","name":"n","scale":"1/4\" = 1'","runs":[]}""")
        assertTrue(old.boundaries.isEmpty() && old.seededPages.isEmpty() && old.editedPages.isEmpty())
    }

    @Test fun renamingARoomRenamesItsBorder() {
        val job = Job("i", "n", boundaries = listOf(box("Bath", 0f, 0f, 1f, 1f), box("Hall", 2f, 2f, 3f, 3f)))
        assertEquals(listOf("Master Bath", "Hall"), job.renameRoom("Bath", "Master Bath").boundaries.map { it.name })
    }

    @Test fun renamedBorderSurvivesScaleChange() {
        val job = Job(
            "i", "n", scale = "1/4\" = 1'", seededPages = setOf(0, 1),
            boundaries = listOf(box("Bath", 0f, 0f, 1f, 1f), box("Hall", 0f, 0f, 1f, 1f, page = 1)),
        ).renameRoom("Bath", "Kids Bath")
        assertEquals(setOf(0), job.editedPages)
        val rescaled = job.copy(scale = "1/8\" = 1'").reseedIfScaleChanged(job)
        assertEquals(listOf("Kids Bath"), rescaled.boundaries.map { it.name }) // page 1 untouched: redrawn
    }

    @Test fun scaleChangeRedrawsOnlyUneditedPages() {
        val job = Job(
            "i", "n", scale = "1/4\" = 1'",
            boundaries = listOf(box("Kitchen", 0f, 0f, 1f, 1f), box("Bath", 0f, 0f, 1f, 1f, page = 1), box("Hall", 0f, 0f, 1f, 1f, page = 2)),
            seededPages = setOf(0, 1), editedPages = setOf(1),
        )
        // same ratio, different text: nothing redone
        assertEquals(job, job.copy(scale = "1/4\"=1'-0\"").reseedIfScaleChanged(job).copy(scale = job.scale))
        val rescaled = job.copy(scale = "1/8\" = 1'").reseedIfScaleChanged(job)
        assertEquals(listOf("Bath", "Hall"), rescaled.boundaries.map { it.name }) // page 2 was never seeded: hand-drawn
        assertEquals(setOf(1), rescaled.seededPages)
        assertEquals(setOf(1), rescaled.editedPages)
    }

    @Test fun unseedClearsThePageEvenIfEdited() {
        val job = Job("i", "n", boundaries = listOf(box("Kitchen", 0f, 0f, 1f, 1f), box("Bath", 0f, 0f, 1f, 1f, page = 1)), seededPages = setOf(0, 1), editedPages = setOf(0, 1))
        val u = job.unseed(setOf(0))
        assertEquals(listOf("Bath"), u.boundaries.map { it.name })
        assertEquals(setOf(1), u.seededPages); assertEquals(setOf(1), u.editedPages)
    }
}
