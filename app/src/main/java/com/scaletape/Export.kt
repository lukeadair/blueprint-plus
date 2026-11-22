package com.scaletape

import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

// Pure Kotlin, no Android imports: keep it unit-testable.

/** Each room's linear feet, in run-list order: rooms with runs plus the user's [Job.rooms] (empty ones at 0). */
fun roomFeet(job: Job, ratio: Double): List<Pair<String, Double>> {
    val byRoom = job.runs.groupBy { it.room.orEmpty() }
    return (byRoom.keys + job.rooms).distinct().sortedWith(roomOrder)
        .map { room -> room to byRoom[room].orEmpty().sumOf { it.inches(ratio) } / 12 }
}

const val XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

private const val FIRST_ROOM_ROW = 6

/**
 * A quote sheet for [job]: one row per room (LF, $/LF, total), a default $/LF in B3 that a blank room price uses,
 * and totals. Formulas carry no cached values; `fullCalcOnLoad` makes the spreadsheet app compute them on open.
 */
fun writeQuoteXlsx(job: Job, ratio: Double, out: OutputStream) {
    val rooms = roomFeet(job, ratio)
    val last = FIRST_ROOM_ROW + rooms.size - 1
    val rows = buildList {
        add(row(1, str("A1", job.name, S_BOLD)))
        add(row(2, str("A2", "Scale"), str("B2", job.scale)))
        add(row(3, str("A3", "Default \$/LF"), empty("B3", S_PRICE)))
        add(row(5, str("A5", "Room", S_BOLD), str("B5", "Linear ft", S_BOLD), str("C5", "\$/LF (blank = default)", S_BOLD), str("D5", "Total", S_BOLD)))
        rooms.forEachIndexed { i, (room, feet) ->
            val r = FIRST_ROOM_ROW + i
            add(row(r, str("A$r", room.ifEmpty { "Unassigned" }), num("B$r", feet, S_FEET), empty("C$r", S_PRICE),
                formula("D$r", "B$r*IF(C$r=\"\",\$B\$3,C$r)", S_MONEY)))
        }
        val t = last + 1
        add(row(t, str("A$t", "Total", S_BOLD), formula("B$t", "SUM(B$FIRST_ROOM_ROW:B$last)", S_FEET_BOLD), formula("D$t", "SUM(D$FIRST_ROOM_ROW:D$last)", S_MONEY_BOLD)))
    }
    val sheet = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><cols><col min="1" max="1" width="26" customWidth="1"/><col min="2" max="2" width="12" customWidth="1"/><col min="3" max="3" width="22" customWidth="1"/><col min="4" max="4" width="14" customWidth="1"/></cols><sheetData>${rows.joinToString("")}</sheetData></worksheet>"""
    val sheetName = job.name.filterNot { it in "[]:*?/\\" }.trim('\'', ' ').take(31).ifEmpty { "Quote" }
    ZipOutputStream(out).use { zip ->
        fun put(path: String, xml: String) { zip.putNextEntry(ZipEntry(path)); zip.write(xml.toByteArray()); zip.closeEntry() }
        put("[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/></Types>""")
        put("_rels/.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""")
        put("xl/workbook.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="${esc(sheetName)}" sheetId="1" r:id="rId1"/></sheets><calcPr fullCalcOnLoad="1"/></workbook>""")
        put("xl/_rels/workbook.xml.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>""")
        put("xl/styles.xml", STYLES)
        put("xl/worksheets/sheet1.xml", sheet)
    }
}

// Cell style indices into STYLES' cellXfs.
private const val S_BOLD = 1
private const val S_FEET = 2
private const val S_MONEY = 3
private const val S_PRICE = 4 // an input: money format on a light yellow fill
private const val S_FEET_BOLD = 5
private const val S_MONEY_BOLD = 6

private val STYLES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><numFmts count="1"><numFmt numFmtId="164" formatCode="&quot;$&quot;#,##0.00"/></numFmts><fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font></fonts><fills count="3"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FFFFF2CC"/><bgColor indexed="64"/></patternFill></fill></fills><borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders><cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs><cellXfs count="7"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/><xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1"/><xf numFmtId="2" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/><xf numFmtId="164" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/><xf numFmtId="164" fontId="0" fillId="2" borderId="0" xfId="0" applyNumberFormat="1" applyFill="1"/><xf numFmtId="2" fontId="1" fillId="0" borderId="0" xfId="0" applyNumberFormat="1" applyFont="1"/><xf numFmtId="164" fontId="1" fillId="0" borderId="0" xfId="0" applyNumberFormat="1" applyFont="1"/></cellXfs></styleSheet>"""

private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    .filter { it == '\t' || it == '\n' || it >= ' ' } // control characters aren't allowed in XML 1.0
private fun row(r: Int, vararg cells: String) = """<row r="$r">${cells.joinToString("")}</row>"""
private fun str(ref: String, s: String, style: Int = 0) = """<c r="$ref" s="$style" t="inlineStr"><is><t xml:space="preserve">${esc(s)}</t></is></c>"""
private fun num(ref: String, v: Double, style: Int) = """<c r="$ref" s="$style"><v>$v</v></c>"""
private fun empty(ref: String, style: Int) = """<c r="$ref" s="$style"/>"""
private fun formula(ref: String, f: String, style: Int) = """<c r="$ref" s="$style"><f>${esc(f)}</f></c>"""
