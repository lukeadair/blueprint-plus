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

    @Test fun calibrationSnapsToStandardScales() {
        assertEquals("1/4\" = 1'", calibratedScale(2.0 * 1.015, 96.0)) // 1.5% tap error
        assertEquals("1/4\" = 1'", calibratedScale(2.0 * 0.985, 96.0))
        assertEquals("3/16\" = 1'", calibratedScale(1.5, 96.0))
        assertEquals("1 1/2\" = 1'", calibratedScale(12.0, 96.0))
        eq(1.0 / 8, parseScale(calibratedScale(12.0, 96.0)))
        // 5% off (a reduced print) keeps its own scale
        assertEquals("0.2625\" = 1'", calibratedScale(2.1, 96.0))
    }

    @Test fun measuring() {
        // 1" on a 144 ppi render at 1/4" scale = 4'
        eq(48.0, realInches(144.0, 144.0, 1.0 / 48))
        assertEquals("12' 6\"", formatFeetInches(150.0))
        assertEquals("1' 0\"", formatFeetInches(11.6))
    }

    @Test fun findsScaleInPlanText() {
        assertEquals("1/4\" = 1'", findScale("FLOOR PLAN\nSCALE: 1/4\" = 1'-0\""))
        assertEquals("1/8\" = 1'", findScale("SCALE 1/8\"=1'"))
        assertEquals("3/16\" = 1'", findScale("3/16 in = 1 ft"))
        assertEquals("1 1/2\" = 1'", findScale("DETAIL 1 1/2\" = 1'-0\""))
        assertEquals("1/4\" = 1'", findScale("SCALE: 1/4” = 1’-0”"))
        assertEquals("1\" = 20'", findScale("SITE PLAN 1\" = 20'")) // not architectural: kept as written, exact
        assertNull(findScale("SCALE: AS NOTED"))
        assertNull(findScale("NTS  2 = 3  X=4'"))
        // main plan scale outnumbers a detail callout
        assertEquals("1/4\" = 1'", findScale("1/2\" = 1'-0\" ... 1/4\" = 1'-0\" ... 1/4\"=1'"))
        // the inches tail doesn't swallow the next text
        assertEquals("1/4\" = 1'", findScale("SCALE: 1/4\" = 1'\n36\" BASE"))
        assertEquals("1/4\" = 1'", findScale("SCALE: 1/4\" = 1' 6\" UPPERS"))
        assertEquals("1/4\" = 1'", findScale("SCALE: 1/4\" = 1' - 0\""))
        // unit conversions aren't plan scales
        assertEquals("1/4\" = 1'", findScale("30\" = 2'-6\" ... 30\" = 2'-6\" ... SCALE 1/4\" = 1'"))
        assertNull(findScale("12\" = 1'-0\""))
        assertEquals("3\" = 1'", findScale("DETAIL 3\" = 1'-0\""))
        // `= 1'` beats a more common other form
        assertEquals("1/8\" = 1'", findScale("1\" = 20' ... 1\" = 20' ... 1/8\" = 1'-0\""))
        assertEquals("1\" = 20'", findScale("1\" = 20' ... 1\" = 20'"))
    }

    @Test fun picksPlanScaleAcrossPages() {
        // site sheet first: the floor plan's `= 1'` scale wins
        assertEquals("1/4\" = 1'", pickPlanScale(sequenceOf(null, "1\" = 20'", "1/4\" = 1'", "1/2\" = 1'")))
        assertEquals("1\" = 20'", pickPlanScale(sequenceOf("1\" = 20'", null, "1\" = 10'")))
        assertNull(pickPlanScale(sequenceOf(null, null)))
        // stops at the first hit: later pages aren't read
        assertEquals("1/8\" = 1'", pickPlanScale(sequenceOf("1/8\" = 1'", null).map { it ?: error("read past hit") }))
    }

    @Test fun findsScaleInPdfData() {
        val plain = "%PDF-1.7\n5 0 obj << /Type /Measure /Subtype /RL /R (1/4 in = 1 ft-0 in) >> endobj".toByteArray(Charsets.ISO_8859_1)
        assertEquals("1/4\" = 1'", scaleInPdf(plain))
        val dict = "<< /Type /Measure /Subtype /RL /R (1/8 in = 1 ft) >>".toByteArray(Charsets.ISO_8859_1)
        val z = java.io.ByteArrayOutputStream().also { o -> java.util.zip.DeflaterOutputStream(o).use { it.write(dict) } }.toByteArray()
        val packed = "%PDF-1.7\n7 0 obj << /Type /ObjStm /N 1 /First 4 /Filter /FlateDecode /Length ${z.size} >>\nstream\n".toByteArray(Charsets.ISO_8859_1) +
            z + "\nendstream endobj".toByteArray(Charsets.ISO_8859_1)
        assertEquals("1/8\" = 1'", scaleInPdf(packed))
        assertNull(scaleInPdf("%PDF-1.7\n/Title (Smith Kitchen)".toByteArray(Charsets.ISO_8859_1)))
    }
}
