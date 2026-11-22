package com.scaletape

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/** A 200x200 "plan" at 4 px per real inch: walls need 12-48 px between line centers and 192 px of straight line. */
class WallFinderTest {
    private val ink = Array(200) { BooleanArray(200) }
    private fun fill(x0: Int, y0: Int, x1: Int, y1: Int) { for (x in x0..x1) for (y in y0..y1) ink[x][y] = true }
    private val walls = WallFinder(4f) { x, y -> x in 0 until 200 && y in 0 until 200 && ink[x][y] }

    @Test fun doubleLineWall() {
        fill(50, 0, 52, 199); fill(64, 0, 66, 199) // two 3px lines, 14px (3.5") apart center to center
        assertTrue(walls.crosses(20f, 100f, 100f, 90f))
        assertTrue(walls.crosses(20f, 100f, 100f, 40f)) // at an angle
        assertFalse(walls.crosses(20f, 100f, 40f, 30f)) // stays on one side
    }

    @Test fun filledWall() {
        fill(50, 0, 63, 199) // 14px = 3.5" solid
        assertTrue(walls.crosses(20f, 100f, 100f, 100f))
    }

    @Test fun counterEdgeAndCooktopAreNotWalls() {
        fill(50, 0, 51, 199) // one long line: a counter edge
        for (x in 0 until 200) for (y in 0 until 200) if (hypot(x - 90f, y - 100f) <= 10f) ink[x][y] = true // burner
        fill(110, 95, 111, 105); fill(118, 95, 119, 105) // short parallel strokes: lettering
        assertFalse(walls.crosses(20f, 100f, 150f, 100f))
    }

    @Test fun linesCloserThanAWallAreNotOne() {
        fill(50, 0, 51, 199); fill(60, 0, 61, 199) // countertop edge + cabinet face, 2.5" apart
        assertFalse(walls.crosses(20f, 100f, 100f, 100f))
    }

    @Test fun sightlineAlongALineIsNotBlocked() {
        fill(0, 99, 199, 101)
        assertFalse(walls.crosses(10f, 100f, 190f, 100f))
    }

    @Test fun stopsAtTheLabelText() {
        fill(150, 0, 152, 199); fill(164, 0, 166, 199) // a wall right where the label's text box is
        assertFalse(walls.crosses(20f, 100f, 158f, 100f, halfW = 12f, halfH = 5f))
    }

    @Test fun shortParallelEdgesAreAFixtureNotAWall() {
        fill(50, 40, 52, 184); fill(74, 40, 76, 184) // 36" long, 6" apart: a cooktop's sides
        fill(80, 0, 81, 199) // and the counter edge beside it
        fill(50, 40, 76, 41); fill(50, 183, 76, 184) // its front and back, crossing them
        assertFalse(walls.crosses(20f, 100f, 120f, 70f))
    }

    @Test fun stubBesideAWindowIsAWall() {
        // 400x400 at 4 px/in: a 2x6 stub (faces 22px = 5.5" apart, 60px = 15" long, capped) and the window's glass,
        // one thin line along its middle for 240px (60"). A second stub, with no window, isn't a wall.
        val big = Array(400) { BooleanArray(400) }
        fun put(x0: Int, y0: Int, x1: Int, y1: Int) { for (x in x0..x1) for (y in y0..y1) big[x][y] = true }
        put(100, 20, 101, 80); put(122, 20, 123, 80); put(100, 80, 123, 81)
        put(111, 81, 112, 321)
        put(250, 20, 251, 80); put(272, 20, 273, 80); put(250, 80, 273, 81)
        val f = WallFinder(4f) { x, y -> x in 0 until 400 && y in 0 until 400 && big[x][y] }
        assertTrue(f.crosses(60f, 50f, 160f, 50f)) // across the stub
        assertFalse(f.crosses(60f, 200f, 160f, 200f)) // across the glass alone
        assertFalse(f.crosses(210f, 50f, 310f, 50f)) // a stub with no window
    }

    @Test fun wallFaceTouchingAFixture() {
        fill(50, 0, 52, 199); fill(64, 0, 66, 199)
        fill(40, 95, 50, 105) // a faucet handle against the first face: one dark stretch on the ray
        assertTrue(walls.crosses(20f, 100f, 100f, 100f))
    }

    @Test fun picksTheNearestLabelNotBehindAWall() {
        fill(50, 0, 52, 199); fill(64, 0, 66, 199)
        val behindWall = RoomLabel("Hall", 80f, 100f) // 50 away, other side of the wall
        val sameRoom = RoomLabel("Kitchen", 30f, 170f) // 70 away
        assertEquals("Kitchen", pickRoom(listOf(behindWall, sameRoom), 30f, 100f, 1f, walls))
        assertEquals("Hall", pickRoom(listOf(behindWall, sameRoom), 30f, 100f, 1f, null)) // no wall check: nearest
        assertEquals("Hall", pickRoom(listOf(behindWall), 30f, 100f, 1f, walls)) // everything blocked: nearest
    }
}
