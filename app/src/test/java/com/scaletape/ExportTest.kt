package com.scaletape

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

class ExportTest {
    // 144 ppi, 1" on paper = 4' at 1/4" = 1'
    private fun run(room: String?, inches: Float = 1f) = Run(0, Offset(0f, 0f), Offset(144f * inches, 0f), 144f, room = room)
    private val ratio = 1.0 / 48

    @Test fun roomFeetInListOrder() {
        val job = Job("i", "n", runs = listOf(run("kitchen"), run(""), run("Bath", 2f), run("kitchen", 0.5f)), rooms = listOf("Pantry"))
        assertEquals(listOf("Bath" to 8.0, "kitchen" to 6.0, "Pantry" to 0.0, "" to 4.0), roomFeet(job, ratio))
    }

    @Test fun xlsxHasFormulasAndEscapes() {
        val job = Job("i", "Smith & <Sons>", runs = listOf(run("Bath"), run("Kitchen")))
        val parts = ByteArrayOutputStream().also { writeQuoteXlsx(job, ratio, it) }.toByteArray().let { bytes ->
            ZipInputStream(bytes.inputStream()).use { z ->
                generateSequence { z.nextEntry }.associate { it.name to z.readBytes().decodeToString() }
            }
        }
        assertEquals(
            setOf("[Content_Types].xml", "_rels/.rels", "xl/workbook.xml", "xl/_rels/workbook.xml.rels", "xl/styles.xml", "xl/worksheets/sheet1.xml"),
            parts.keys,
        )
        val sheet = parts.getValue("xl/worksheets/sheet1.xml")
        assertTrue(sheet.contains("<f>B6*IF(C6=&quot;&quot;,\$B\$3,C6)</f>"))
        assertTrue(sheet.contains("<f>B7*IF(C7=&quot;&quot;,\$B\$3,C7)</f>"))
        assertTrue(sheet.contains("""<c r="B8" s="5"><f>SUM(B6:B7)</f>"""))
        assertTrue(sheet.contains("<f>SUM(D6:D7)</f>"))
        assertTrue(sheet.contains("<v>4.0</v>"))
        assertTrue(sheet.contains("Smith &amp; &lt;Sons&gt;"))
        assertTrue(parts.getValue("xl/workbook.xml").contains("""name="Smith &amp; &lt;Sons&gt;""""))
    }
}
