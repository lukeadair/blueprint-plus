package com.scaletape

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drawn plans at 2 px per real inch, in plan inches (origin at room A's top-left wall center). Walls are two 1"
 * lines 6" apart. Room A is 12'x16' (x 0..144), room B 20'x16' (x 144..384), sharing the wall at x = 144.
 */
class RoomFillTest {
    private val ppi = 2f
    private val m = 96f // margin, so a closet can sit left of room A
    private val w = ((384 + 2 * m) * ppi).toInt()
    private val h = ((192 + 2 * m) * ppi).toInt()
    private val ink = BooleanArray(w * h)
    private val isDark: (Int, Int) -> Boolean = { x, y -> x in 0 until w && y in 0 until h && ink[y * w + x] }

    private fun px(inches: Float) = ((inches + m) * ppi)
    private fun fill(x0: Float, y0: Float, x1: Float, y1: Float) {
        for (x in px(x0).toInt() until px(x1).toInt()) for (y in px(y0).toInt() until px(y1).toInt()) ink[y * w + x] = true
    }
    /** A wall centered on x = [c] from y0 to y1, open over [gap]. */
    private fun wallV(c: Float, y0: Float, y1: Float, gap: ClosedFloatingPointRange<Float>? = null) {
        for (f in floatArrayOf(c - 3, c + 3)) {
            if (gap == null) fill(f - 0.5f, y0 - 3, f + 0.5f, y1 + 3)
            else { fill(f - 0.5f, y0 - 3, f + 0.5f, gap.start); fill(f - 0.5f, gap.endInclusive, f + 0.5f, y1 + 3) }
        }
    }
    private fun wallH(c: Float, x0: Float, x1: Float) { for (f in floatArrayOf(c - 3, c + 3)) fill(x0 - 3, f - 0.5f, x1 + 3, f + 0.5f) }
    /** Dashed header lines across [gap] at both faces of the wall at x = [c]: 6" dashes, 3" gaps. */
    private fun dashesV(c: Float, gap: ClosedFloatingPointRange<Float>) {
        var y = gap.start
        while (y < gap.endInclusive) { for (f in floatArrayOf(c - 3, c + 3)) fill(f - 0.5f, y, f + 0.5f, minOf(y + 6, gap.endInclusive)); y += 9 }
    }

    private fun house(shared: ClosedFloatingPointRange<Float>?) {
        wallH(0f, 0f, 384f); wallH(192f, 0f, 384f); wallV(0f, 0f, 192f); wallV(384f, 0f, 192f)
        wallV(144f, 0f, 192f, shared)
    }

    private val a = Offset(72f, 96f); private val b = Offset(264f, 96f)
    /** Each label's room, back in plan inches. */
    private fun rooms(labels: List<Offset>): List<Rect?> =
        fillRooms(labels.map { Offset(px(it.x), px(it.y)) }, w, h, ppi, WallFinder(ppi, isDark), isDark)
            .map { r -> r?.let { Rect(it.left / ppi - m, it.top / ppi - m, it.right / ppi - m, it.bottom / ppi - m) } }

    private fun assertRoomA(r: Rect?) {
        assertNotNull(r); r!!
        assertTrue("$r", r.left in -4f..6f && r.top in -4f..6f && r.right in 138f..150f && r.bottom in 186f..196f)
    }

    @Test fun aDoorKeepsTheRoomFromLeaking() {
        house(shared = 78f..114f) // 36" door, B has no label
        assertRoomA(rooms(listOf(a))[0])
    }

    @Test fun twoRoomsSplitAWideOpeningBetweenTheirLabels() {
        house(shared = 60f..132f) // 6' opening, nothing drawn across it: they meet about halfway between the labels
        val (ra, rb) = rooms(listOf(a, b))
        assertTrue("$ra", ra!!.left < 6f && ra.right in 130f..200f)
        assertTrue("$rb", rb!!.right > 378f && rb.left in 130f..200f)
    }

    @Test fun aDashedHeaderClosesTheOpening() {
        house(shared = 60f..132f); dashesV(144f, 60f..132f)
        assertRoomA(rooms(listOf(a))[0])
    }

    @Test fun anOpeningWithNothingAcrossItLetsTheRoomThrough() {
        house(shared = 60f..132f) // with B unlabeled, there's nothing to tell the two apart
        assertTrue(rooms(listOf(a))[0]!!.right > 300f)
    }

    @Test fun anUnlabeledClosetJoinsItsRoom() {
        house(shared = null)
        // 5'x8' closet left of room A, its 30" door in A's left wall
        wallH(0f, -60f, 0f); wallH(96f, -60f, 0f); wallV(-60f, 0f, 96f)
        for (i in 0 until w * h) if (i % w in px(-3.5f).toInt() until px(3.5f).toInt() && i / w in px(60f).toInt() until px(90f).toInt()) ink[i] = false
        val r = rooms(listOf(a))[0]!!
        assertTrue("$r", r.left < -50f && r.right in 138f..150f)
    }

    @Test fun agreedKeepsTheOverlapUnlessItLosesTheLabel() {
        val rays = Rect(0f, 0f, 100f, 50f)
        assertTrue(agreed(Rect(20f, 10f, 300f, 300f), rays, Offset(30f, 20f)) == Rect(20f, 10f, 100f, 50f))
        assertTrue(agreed(Rect(60f, 10f, 300f, 300f), rays, Offset(30f, 20f)) == rays) // overlap misses the label
        assertTrue(agreed(null, rays, Offset(30f, 20f)) == rays)
    }

    @Test fun noLabelsNoRooms() {
        assertTrue(fillRooms(emptyList(), w, h, ppi, WallFinder(ppi, isDark), isDark).isEmpty())
    }
}
