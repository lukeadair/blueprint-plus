package com.scaletape

import org.junit.Assert.assertEquals
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
        // note's Porch (7pt), or Closet from "CLOSED".
        assertEquals(
            listOf("Bath", "Bedroom", "Closet", "Closet", "Dining", "Front Porch", "Guest Room", "Hall", "Kitchen"),
            labels.map { it.name }.sorted(),
        )
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
        // Test plan A on the phone: "FRONT PORCH" comes back as one 24pt box (both lines); "FRONT" alone is 10pt.
        val text = "GUEST ROOM KITCHEN FRONT PORCH HALL"
        val at = text.indexOf("FRONT PORCH")
        val labels = findRoomLabels(text) { word ->
            search(text) { 10f }(word).map { h -> if (h.start == at && word == "FRONT PORCH") h.copy(bottom = h.top + 24f) else h }
        }
        assertEquals(listOf("Front Porch", "Guest Room", "Hall", "Kitchen"), labels.map { it.name }.sorted())
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
        assertEquals( // the bare "CL" (centerline) is skipped
            listOf("Bath 2", "Bedroom 2", "Closet 1", "Flex", "Hall 1", "Kitchen", "Laundry", "Master Bath", "Powder"),
            names,
        )
    }

    @Test fun roomSizesAreNotRoomNumbers() {
        // Test plan B's text puts each room's size right after its name.
        val text = "KITCHEN 10'x26'1 HALL 1 4'x4' BEDRM 2 12'x11' FLEX 12' x 10' PDR 5'x5'"
        val names = findRoomLabels(text, search(text) { 10f }).map { it.name }.sorted()
        assertEquals(listOf("Bedroom 2", "Flex", "Hall 1", "Kitchen", "Powder"), names)
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
