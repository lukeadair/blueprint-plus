package com.scaletape

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JobTest {
    private val run = Run(1, Offset(10f, 20f), Offset(154f, 20f), 144f) // 1" on paper

    @Test fun jsonRoundTrips() {
        val job = Job("id1", "Smith \"kitchen\"", "1/8\" = 1'", page = 2, updated = 123L, created = 100L, runs = listOf(run, run.copy(page = 0, room = "Kitchen"), run.copy(room = "", roomManual = true)), rooms = listOf("Pantry", "Office"))
        assertEquals(job, jobFromJson(job.toJson()))
    }

    @Test fun distanceToRun() {
        assertEquals(10f, run.distanceTo(Offset(82f, 30f)), 1e-4f) // beside the middle
        assertEquals(10f, run.distanceTo(Offset(0f, 20f)), 1e-4f) // past the start: to the endpoint, not the line
        assertEquals(5f, Run(0, Offset(1f, 1f), Offset(1f, 1f), 72f).distanceTo(Offset(4f, 5f)), 1e-4f) // zero-length
    }

    @Test fun midPointIsInPdfPoints() {
        assertEquals(Offset(41f, 10f), run.midPoints()) // (82, 20) px at 144 ppi = 2x points
    }

    @Test fun renameAndMergeRooms() {
        val job = Job(
            "i", "n",
            runs = listOf(run.copy(room = "Bath"), run.copy(room = "Kitchen"), run.copy(room = "Bath")),
            rooms = listOf("Bath", "Pantry"),
        )
        val renamed = job.renameRoom("Bath", "Master Bath")
        assertEquals(listOf("Master Bath", "Kitchen", "Master Bath"), renamed.runs.map { it.room })
        assertEquals(listOf(true, false, true), renamed.runs.map { it.roomManual }) // only the moved runs
        assertEquals(listOf("Master Bath", "Pantry"), renamed.rooms)

        val merged = renamed.renameRoom("Master Bath", "Kitchen")
        assertEquals(listOf("Kitchen", "Kitchen", "Kitchen"), merged.runs.map { it.room })
        assertEquals(listOf("Kitchen", "Pantry"), merged.rooms) // no duplicate, old name gone
    }

    @Test fun olderSavesWithoutCreatedUseUpdated() {
        val old = Job("i", "n", updated = 500L).toJson().replace(Regex(",?\"created\":\\d+"), "")
        assertEquals(500L, jobFromJson(old).created)
    }

    @Test fun totals() {
        assertEquals(96.0, Job("i", "n", "1/4\" = 1'", runs = listOf(run, run)).totalInches()!!, 1e-9)
        assertNull(Job("i", "n", "nonsense", runs = listOf(run)).totalInches())
    }
}
