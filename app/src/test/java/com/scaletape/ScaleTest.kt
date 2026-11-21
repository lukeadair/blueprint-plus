package com.scaletape

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScaleTest {
    private fun eq(expected: Double, actual: Double?) = assertEquals(expected, actual!!, 1e-9)

    @Test fun lengths() {
        eq(0.25, parseLength("1/4\"", 1.0))
        eq(1.5, parseLength("1 1/2\"", 1.0))
        eq(150.0, parseLength("12' 6\"", 1.0))
        eq(18.0, parseLength("1'-6\"", 1.0))
        eq(12.0, parseLength("1'-0\"", 1.0))
        eq(42.0, parseLength("3.5'", 1.0))
        eq(24.0, parseLength("2", 12.0))
        eq(30.0, parseLength("2 ft 6 in", 1.0))
        eq(12.0, parseLength("1’", 1.0))
    }

    @Test fun badLengths() {
        assertNull(parseLength("", 1.0))
        assertNull(parseLength("abc", 1.0))
        assertNull(parseLength("1/0\"", 1.0))
        assertNull(parseLength("2 3", 1.0)) // two bare numbers is ambiguous
    }

    @Test fun scales() {
        eq(1.0 / 48, parseScale("1/4\" = 1'"))
        eq(1.0 / 48, parseScale("1/4\"=1'-0\""))
        eq(1.0 / 96, parseScale("1/8 = 1"))
        eq(1.0 / 16, parseScale("3/4\" = 1'"))
        assertNull(parseScale("1/4\""))
        assertNull(parseScale("0 = 1'"))
    }

    @Test fun calibrationRoundTrips() {
        // 2" on paper measured as 8' -> quarter-inch scale
        eq(1.0 / 48, parseScale(calibratedScale(2.0, 96.0)))
    }

    @Test fun measuring() {
        // 1" on a 144 ppi render at 1/4" scale = 4'
        eq(48.0, realInches(144.0, 144.0, 1.0 / 48))
        assertEquals("12' 6\"", formatFeetInches(150.0))
        assertEquals("1' 0\"", formatFeetInches(11.6))
    }
}
