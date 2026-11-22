package com.scaletape

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomsTest {
    /** Fake searchText: every occurrence in [text], laid out on one line at 20pt per character, [height] tall. */
    private fun search(text: String, height: (Int) -> Float?) = { word: String ->
        generateSequence(text.indexOf(word).takeIf { it >= 0 }) { i -> text.indexOf(word, i + 1).takeIf { it >= 0 } }
            .mapNotNull { i -> height(i)?.let { h -> TextHit(i, i * 20f, 100f, (i + word.length) * 20f - 5f, 100f + h) } }
            .toList()
    }

    @Test fun findsLabelsOnARealFloorPlan() {
        // Real text from the test plan (labels are 10pt), plus a 7pt note that mentions a deck and a porch.
        val text = "GUEST ROOM BEDROOM CLOS CLOS DINING KITCHEN HALL BATH FRONT PORCH BACK PORCHDW N" +
            " REMOVE EXIST. DECK, FOUNDATION FOR NEW PORCH ADDITION TV CLOSED"
        val noteStart = text.indexOf("REMOVE")
        val labels = findRoomLabels(text, search(text) { i -> if (i >= noteStart) 7f else 10f })
        // Not: Room (inside Guest Room), Porch (inside Front Porch), Back Porch (glued to "DW"), Deck, the
        // note's Porch (7pt), or Closet from "CLOSED". Front Porch and the plain closets are found but dropped.
        assertEquals(
            listOf("Bath", "Bedroom", "Dining", "Guest Room", "Kitchen"),
            labels.map { it.name }.sorted(),
        )
    }

    @Test fun verticalLabelIsMeasuredByItsShortSide() {
        // BATHROOM set sideways: its box is 10pt wide and 8 chars tall, like the 10pt labels beside it.
        val text = "KITCHEN BEDROOM BATHROOM"
        val i = text.indexOf("BATHROOM")
        val horizontal = search(text) { 10f }
        val labels = findRoomLabels(text) { w ->
            if (w == "BATHROOM") listOf(TextHit(i, 300f, 100f, 310f, 260f)) else horizontal(w)
        }
        assertEquals(listOf("Bathroom", "Bedroom", "Kitchen"), labels.map { it.name }.sorted())
    }

    @Test fun tinyVerticalTagsDoNotOutvoteTheLabels() {
        // Finley plan: 14pt labels, a 5pt horizontal tag, and several 5pt-wide vertical "KITCHEN"/"PANTRY" tags.
        val text = "KITCHEN DINING PANTRY KITCHEN PANTRY KITCHEN KITCHEN"
        val boxes = mapOf(
            "KITCHEN" to listOf(TextHit(0, 0f, 0f, 55f, 14f), TextHit(22, 300f, 0f, 320f, 5f), TextHit(30, 400f, 0f, 405f, 20f), TextHit(38, 500f, 0f, 505f, 20f)),
            "DINING" to listOf(TextHit(8, 100f, 0f, 144f, 14f)),
            "PANTRY" to listOf(TextHit(15, 200f, 0f, 251f, 14f), TextHit(30, 600f, 0f, 605f, 19f)),
        )
        val names = findRoomLabels(text) { boxes[it].orEmpty() }.map { it.name }
        assertEquals(listOf("Dining", "Kitchen", "Pantry"), names.sorted())
    }

    @Test fun noteNextToTheCounterIsNotARoom() {
        // Test plan A: "NEW BEDROOM ADDITION" note (8pt box on the phone) sits closer to a kitchen run
        // than the 10pt KITCHEN label does. It must not count as a room.
        val text = "GUEST ROOM BEDROOM DINING KITCHEN HALL BATH AS REQUIRED FOR NEW BEDROOM ADDITION"
        val noteStart = text.indexOf("AS REQUIRED")
        val labels = findRoomLabels(text, search(text) { i -> if (i >= noteStart) 8f else 10f })
        assertEquals(1, labels.count { it.name == "Bedroom" })
    }

    @Test fun labelOnTwoLines() {
        // Test plan A on the phone: "FRONT PORCH" came back as one 24pt box (both lines); "FRONT" alone is 10pt.
        // Porches are dropped now, so this uses a kept two-word name.
        val text = "GUEST ROOM KITCHEN MASTER CLOSET HALL"
        val at = text.indexOf("MASTER CLOSET")
        val labels = findRoomLabels(text) { word ->
            search(text) { 10f }(word).map { h -> if (h.start == at && word == "MASTER CLOSET") h.copy(bottom = h.top + 24f) else h }
        }
        assertEquals(listOf("Guest Room", "Kitchen", "Master Closet"), labels.map { it.name }.sorted())
    }

    @Test fun labelOnTwoLinesStoredOutOfOrder() {
        // Test plan C on the phone: "GREAT" over "ROOM", but the page text has ROOM first, so "GREAT ROOM" never matches.
        val text = "ROOM 18'x20'7 9' CLN HT GREAT KITCHEN"
        val boxes = mapOf(
            "ROOM" to TextHit(0, 501f, 223f, 520f, 228f),
            "GREAT" to TextHit(text.indexOf("GREAT"), 501f, 218f, 521f, 223f),
            "KITCHEN" to TextHit(text.indexOf("KITCHEN"), 300f, 100f, 330f, 105f),
        )
        assertEquals(listOf("Great Room", "Kitchen"), findRoomLabels(text) { listOfNotNull(boxes[it]) }.map { it.name }.sorted())
        // ROOM elsewhere on the page (not right under GREAT) isn't paired
        val apart = boxes + ("ROOM" to TextHit(0, 501f, 260f, 520f, 265f))
        assertEquals(listOf("Kitchen"), findRoomLabels(text) { listOfNotNull(apart[it]) }.map { it.name })
        // Same line, stored out of order: "GREAT ROOM" on the page, "ROOM ... GREAT" in the text.
        val sideBySide = boxes + ("ROOM" to TextHit(0, 524f, 218f, 543f, 223f))
        assertEquals(listOf("Great Room", "Kitchen"), findRoomLabels(text) { listOfNotNull(sideBySide[it]) }.map { it.name }.sorted())
        // ROOM before GREAT on the line isn't "GREAT ROOM"
        val reversed = boxes + ("ROOM" to TextHit(0, 478f, 218f, 497f, 223f))
        assertEquals(listOf("Kitchen"), findRoomLabels(text) { listOfNotNull(reversed[it]) }.map { it.name })
    }

    @Test fun porchesAndPlainClosetsAreDropped() {
        val text = "CLOSET ; CL 1 ; GIRLS CLOSET ; BACK PORCH ; COVERED PATIO ; PATIO 2 ; GARAGE ; CARPORT ; STORAGE ; HALL ; HALLWAY ; HALL 2 ; M. CLOSET ; MASTER CLOSET ; MASTER CL ; MSTR CL 2 ; HIS CLOSET ; HERS CLOSET ;" +
            " PRIMARY CLOSET ; WIC ; KITCHEN"
        val names = findRoomLabels(text, search(text) { 10f }).map { it.name }.sorted()
        assertEquals(
            listOf("Hers Closet", "His Closet", "Kitchen", "M. Closet", "Master Closet", "Master Closet", "Master Closet 2", "Primary Closet", "Walk-in Closet"),
            names,
        )
    }

    @Test fun numberedRooms() {
        val text = "BEDROOM 2 BEDROOM #3 BEDROOM"
        val names = findRoomLabels(text, search(text) { 10f }).map { it.name }
        assertEquals(listOf("Bedroom 2", "Bedroom 3", "Bedroom"), names)
    }

    @Test fun planAbbreviations() {
        // Labels from test plan B.
        val text = "M.BTH BEDRM 2 BTH 2 CL 1 LNDY/MUD PDR FLEX HALL 1 KITCHEN CL OF WALL"
        val names = findRoomLabels(text, search(text) { 10f }).map { it.name }.sorted()
        assertEquals( // the bare "CL" (centerline) is skipped; "CL 1" is a plain closet, dropped
            listOf("Bath 2", "Bedroom 2", "Flex", "Kitchen", "Laundry", "Master Bath", "Powder"),
            names,
        )
    }

    @Test fun roomSizesAreNotRoomNumbers() {
        // Test plan B's text puts each room's size right after its name.
        val text = "KITCHEN 10'x26'1 HALL 1 4'x4' BEDRM 2 12'x11' FLEX 12' x 10' PDR 5'x5'"
        val names = findRoomLabels(text, search(text) { 10f }).map { it.name }.sorted()
        assertEquals(listOf("Bedroom 2", "Flex", "Kitchen", "Powder"), names)
    }

    @Test fun wordBeforeTheRoomWordKeepsRoomsApart() {
        // Finley plan: GIRLS BATH, BOYS BATH and M. BATH were all "Bath". HALL BATH stays Hall + Bath; a note's
        // "NEW BATH" is just Bath.
        val text = "GIRLS BATH BOYS BATH M. BATH HALL BATH NEW BATH M. CLOSET"
        val names = findRoomLabels(text, search(text) { 10f }).map { it.name }.sorted()
        assertEquals(listOf("Bath", "Bath", "Boys Bath", "Girls Bath", "M. Bath", "M. Closet"), names)
    }

    @Test fun wordBeforeOnAnotherLineIsNotPartOfTheName() {
        val text = "GIRLS BATH"
        val boxes = mapOf("BATH" to TextHit(6, 300f, 200f, 340f, 210f), "GIRLS" to TextHit(0, 300f, 100f, 350f, 110f))
        assertEquals(listOf("Bath"), findRoomLabels(text) { listOfNotNull(boxes[it]) }.map { it.name })
    }

    @Test fun repeatedNamesAreNumbered() {
        val labels = listOf(
            RoomLabel("Storage", 500f, 300f), RoomLabel("Storage", 100f, 100f), RoomLabel("Bedroom", 0f, 50f),
            RoomLabel("Bedroom 2", 0f, 60f), RoomLabel("Bedroom", 0f, 70f), RoomLabel("Bath", 0f, 0f), RoomLabel("Kitchen", 0f, 0f),
        )
        assertEquals(
            listOf("Storage 2", "Storage", "Bedroom", "Bedroom 2", "Bedroom 3", "Bath 2", "Kitchen"),
            numberRepeats(labels, taken = setOf("Bath")).map { it.name },
        )
    }

    @Test fun tagOverTheLabelDoesNotHideIt() {
        // Finley plan on the phone: a 5pt "PANTRY 1" elevation tag box overlaps the 10pt PANTRY label.
        val text = "PANTRY 1 P KITCHEN PANTRY DINING"
        val boxes = mapOf(
            "PANTRY" to listOf(TextHit(0, 871f, 695f, 891f, 700f), TextHit(text.lastIndexOf("PANTRY"), 877f, 695f, 928f, 705f)),
            "KITCHEN" to listOf(TextHit(text.indexOf("KITCHEN"), 901f, 1011f, 956f, 1021f)),
            "DINING" to listOf(TextHit(text.indexOf("DINING"), 943f, 1176f, 986f, 1186f)),
        )
        val labels = findRoomLabels(text) { boxes[it].orEmpty() }
        assertEquals(listOf("Dining", "Kitchen", "Pantry"), labels.map { it.name }.sorted())
    }

    @Test fun rotatedTagClippingTheLabelDoesNotHideIt() {
        // Finley plan on the phone: a rotated "MASTER CLOSET 1" tag (5pt wide, 41pt tall) clips the M. CLOSET label.
        val text = "MASTER CLOSET 1 NURSERY M. BEDROOM M. CLOSET M. BATH"
        val at = text.indexOf("M. CLOSET") + 3
        val boxes = mapOf(
            "MASTER CLOSET" to listOf(TextHit(0, 508f, 854f, 513f, 895f)),
            "CLOSET" to listOf(TextHit(7, 508f, 854f, 513f, 874f), TextHit(at, 495f, 888f, 546f, 899f)),
            "M." to listOf(TextHit(at - 3, 478f, 888f, 491f, 898f)),
            "BATH" to listOf(TextHit(text.indexOf("BATH"), 640f, 833f, 670f, 844f)),
            "BEDROOM" to listOf(TextHit(text.indexOf("BEDROOM"), 275f, 922f, 330f, 933f)),
        )
        val names = findRoomLabels(text) { boxes[it].orEmpty() }.map { it.name }.sorted()
        assertEquals(listOf("Bath", "Bedroom", "M. Closet"), names) // only the closet's "M." has a box here
    }

    @Test fun labelInTheLastRowOfATableIsSkipped() {
        // Ruled rows every 11pt above the label, nothing below it.
        val table = { x: Int, y: Int -> x in 100..300 && y in 150..205 && (y - 150) % 11 == 0 }
        assertEquals(true, inTable(200f, 200f, 1f, table))
    }

    @Test fun labelInATableIsSkipped() {
        // 1 px per point. A schedule: ruled rows every 12pt, 200pt wide; and a plan room: two walls 100pt apart.
        val table = { x: Int, y: Int -> x in 100..300 && y in 100..300 && y % 12 == 0 }
        assertEquals(true, inTable(200f, 200f, 1f, table))
        val room = { x: Int, y: Int -> x in 100..300 && (y == 150 || y == 250) }
        assertEquals(false, inTable(200f, 200f, 1f, room))
    }

    @Test fun tableOnABigSheet() {
        // FILE_7325's schedules: ruled rows every ~20.4pt; label mid-row, and in the last row above the bottom rule.
        val table = { x: Int, y: Int -> x in 100..300 && y in 100..310 && (y - 100) % 21 == 0 }
        assertEquals(true, inTable(200f, 215.5f, 1f, table))
        assertEquals(true, inTable(200f, 299.5f, 1f, table))
    }

    @Test fun labelPastEvenlySpacedLinesIsNotInATable() {
        // Stacked dimension strings outside a room: four lines 12pt apart, the label 34pt below the last one.
        val dims = { x: Int, y: Int -> x in 100..300 && y in listOf(150, 162, 174, 186) }
        assertEquals(false, inTable(200f, 220f, 1f, dims))
    }

    @Test fun areaTableHeadingsAreNotRooms() {
        // Heath plan's title block: the only real text on a page whose room names are drawn as lines.
        fun names(text: String) = findRoomLabels(text, search(text) { 10f }).map { it.name }.sorted()
        assertEquals(emptyList<String>(), names("LIVING AREAS SQ. FT. SF NOTES 1ST FLOOR PLAN"))
        assertEquals(listOf("Kitchen", "Living"), names("LIVING AREA ; KITCHEN"))
        assertEquals(listOf("Bedroom"), names("GARAGE SQ. FT. ; BEDROOM"))
    }

    @Test fun nothingFound() {
        assertEquals(emptyList<RoomLabel>(), findRoomLabels("SITE PLAN NORTH", search("SITE PLAN NORTH") { 10f }))
    }

    @Test fun nearest() {
        val labels = listOf(RoomLabel("Kitchen", 100f, 100f), RoomLabel("Bath", 300f, 100f))
        assertEquals("Kitchen", nearestRoom(labels, 150f, 120f))
        assertEquals("Bath", nearestRoom(labels, 260f, 300f))
        assertEquals(null, nearestRoom(emptyList(), 0f, 0f))
    }
}
