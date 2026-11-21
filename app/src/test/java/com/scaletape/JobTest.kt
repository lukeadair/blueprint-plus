package com.scaletape

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JobTest {
    private val run = Run(1, Offset(10f, 20f), Offset(154f, 20f), 144f) // 1" on paper

    @Test fun jsonRoundTrips() {
        val job = Job("id1", "Smith \"kitchen\"", "1/8\" = 1'", page = 2, updated = 123L, runs = listOf(run, run.copy(page = 0)))
        assertEquals(job, jobFromJson(job.toJson()))
    }

    @Test fun totals() {
        assertEquals(96.0, Job("i", "n", "1/4\" = 1'", runs = listOf(run, run)).totalInches()!!, 1e-9)
        assertNull(Job("i", "n", "nonsense", runs = listOf(run)).totalInches())
    }
}
