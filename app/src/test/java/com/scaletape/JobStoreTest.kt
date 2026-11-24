package com.scaletape

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

// The sync-folder rules in JobStore (the Mac's JobStoreTests): half-synced jobs, sync tools' copies, saves over
// another device's version.
class JobStoreTest {
    private fun tempStore() = JobStore(FileFolder(Files.createTempDirectory("JobStoreTest").toFile()))
    private val JobStore.dir get() = (folder as FileFolder).dir
    private fun job(id: String, updated: Long, name: String = "Smith") = Job(id, name, updated = updated, created = 1)
    private fun addPdf(s: JobStore, id: String) = File(s.dir, "$id.pdf").writeText("%PDF")
    private fun files(s: JobStore) = s.dir.list()!!.toSet()
    private fun write(s: JobStore, name: String, j: Job) = File(s.dir, name).writeText(j.toJson())

    @Test fun jobWithoutItsPdfIsHidden() {
        val s = tempStore()
        s.save(job("a", 5))
        assertTrue(s.list().isEmpty())
        addPdf(s, "a")
        assertEquals(listOf("a"), s.list().map { it.id })
    }

    @Test fun differentCopyBecomesAConflictJob() {
        val s = tempStore()
        addPdf(s, "a")
        s.save(job("a", 5))
        write(s, "a (1).json", job("a", 9))
        val jobs = s.list()
        assertEquals(2, jobs.size)
        val c = jobs.first { it.id != "a" }
        assertEquals("Smith (conflict)", c.name)
        assertEquals(9L, c.updated)
        assertEquals(c.id.lowercase(), c.id)
        assertEquals(setOf("a.json", "a.pdf", "${c.id}.json", "${c.id}.pdf"), files(s))
        assertEquals(2, s.list().size) // not imported twice
    }

    @Test fun identicalCopyIsDeleted() {
        val s = tempStore()
        addPdf(s, "a")
        s.save(job("a", 5))
        write(s, "a.sync-conflict-20261123-120000-ABC.json", job("a", 5))
        assertEquals(listOf("a"), s.list().map { it.id })
        assertEquals(setOf("a.json", "a.pdf"), files(s))
    }

    @Test fun copyWithoutTheOriginalTakesItsName() {
        val s = tempStore()
        addPdf(s, "a")
        write(s, "a (1).json", job("a", 5))
        assertEquals(listOf("a"), s.list().map { it.id })
        assertEquals(setOf("a.json", "a.pdf"), files(s))
    }

    @Test fun copyIsLeftAloneUntilThePdfArrives() {
        val s = tempStore()
        s.save(job("a", 5))
        write(s, "a (1).json", job("a", 9))
        assertTrue(s.list().isEmpty())
        assertEquals(setOf("a.json", "a (1).json"), files(s))
    }

    @Test fun hiddenAndNonJsonFilesAreSkipped() {
        val s = tempStore()
        addPdf(s, "a")
        write(s, ".a.json", job("a", 5))
        write(s, "a.txt", job("a", 5))
        assertTrue(s.list().isEmpty())
    }

    @Test fun completeTempIsPutBackWhenTheJsonIsMissing() {
        // a SAF save cut off between deleting the old JSON and renaming the temp in
        val s = tempStore()
        addPdf(s, "a")
        write(s, ".a.json.tmp", job("a", 5))
        assertEquals(listOf("a"), s.list().map { it.id })
        assertEquals(setOf("a.json", "a.pdf"), files(s))
    }

    @Test fun saveRefusesToOverwriteAnotherDevicesVersion() {
        val s = tempStore()
        s.save(job("a", 5))
        s.save(job("a", 6), seen = 5) // ours: fine
        try {
            s.save(job("a", 7), seen = 5)
            throw AssertionError("expected ChangedElsewhere")
        } catch (_: JobStore.ChangedElsewhere) {}
        assertEquals(6L, s.load("a")?.updated)
        s.save(job("b", 1), seen = 0) // not on disk (new, or deleted elsewhere): saved
        assertNotNull(s.load("b"))
    }

    @Test fun failedConflictSaveRemovesItsPdf() {
        val s = tempStore()
        addPdf(s, "a")
        s.save(job("a", 5))
        s.dir.setWritable(false) // copy or save fails
        try { runCatching { s.saveAsConflict(job("a", 9)) } } finally { s.dir.setWritable(true) }
        assertEquals(setOf("a.json", "a.pdf"), files(s))
    }

    @Test fun adoptKeepsTheLaterCopy() {
        val local = tempStore()
        val synced = tempStore()
        for ((s, id, t) in listOf(Triple(local, "a", 9L), Triple(synced, "a", 5L), Triple(local, "b", 3L), Triple(synced, "c", 4L))) {
            addPdf(s, id); s.save(job(id, t))
        }
        synced.adopt(local)
        assertEquals(mapOf("a" to 9L, "b" to 3L, "c" to 4L), synced.list().associate { it.id to it.updated })
        assertEquals(setOf("a", "b"), local.list().map { it.id }.toSet()) // left as it was
        assertEquals(mapOf("a" to 9L, "b" to 3L), local.list().associate { it.id to it.updated })
    }

    @Test fun unknownKeysSurviveALoadSaveRoundTrip() {
        val s = tempStore()
        val o = org.json.JSONObject(job("a", 5).toJson()).put("futureKey", org.json.JSONObject().put("Kitchen", 0xFF8800))
        File(s.dir, "a.json").writeText(o.toString())
        val loaded = s.load("a")!!
        s.save(loaded.copy(name = "Jones"))
        val back = org.json.JSONObject(File(s.dir, "a.json").readText())
        assertEquals(0xFF8800, back.getJSONObject("futureKey").getInt("Kitchen"))
        assertEquals("Jones", back.getString("name"))
        assertEquals(loaded, s.load("a")!!.copy(name = "Smith")) // equal after a round trip, so a copy isn't a false conflict
        assertNull(s.load("missing"))
    }

    @Test fun adoptNeverOverwritesANewerJsonWhosePdfHasntSynced() {
        val local = tempStore()
        val synced = tempStore()
        addPdf(local, "a"); local.save(job("a", 5))
        synced.save(job("a", 9)) // the Mac's newer save; its PDF is still downloading
        synced.adopt(local)
        assertEquals(9L, synced.load("a")?.updated)
    }

    @Test fun adoptRecopiesAPdfLeftWithoutItsJson() {
        val local = tempStore()
        val synced = tempStore()
        File(local.dir, "a.pdf").writeText("%PDF-whole"); local.save(job("a", 5))
        File(synced.dir, "a.pdf").writeText("%PD") // a copy a crash cut off
        synced.adopt(local)
        assertEquals("%PDF-whole", File(synced.dir, "a.pdf").readText())
    }

    @Test fun copyThatCantBeDeletedDoesntPileUpConflictJobs() {
        val ff = FileFolder(Files.createTempDirectory("JobStoreTest").toFile())
        val s = JobStore(object : Folder by ff {
            override fun delete(name: String) { if (name == "a (1).json") throw java.io.IOException("no") else ff.delete(name) }
        })
        File(ff.dir, "a.pdf").writeText("%PDF"); s.save(job("a", 5))
        File(ff.dir, "a (1).json").writeText(job("a", 9).toJson())
        repeat(3) { assertEquals(listOf("a"), s.list().map { it.id }) }
        assertEquals(setOf("a.json", "a.pdf", "a (1).json"), ff.dir.list()!!.toSet())
    }
}
