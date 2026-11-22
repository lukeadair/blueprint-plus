package com.scaletape

import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.AtomicFile
import java.io.File
import java.util.UUID

/** Each job is `jobs/<id>.json` plus its own copy of the plan at `jobs/<id>.pdf`. */
class JobStore(private val context: Context) {
    private val dir = File(context.filesDir, "jobs").apply { mkdirs() }

    /** Debug builds only: where room seeding dumps each page (bitmap + label hits) for replaying in a JVM test. */
    val debugDir: File? = if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
        File(context.filesDir, "debug").apply { mkdirs() } else null

    fun pdf(id: String) = File(dir, "$id.pdf")
    private fun json(id: String) = AtomicFile(File(dir, "$id.json"))

    fun list(): List<Job> = dir.listFiles { f -> f.extension == "json" }.orEmpty()
        .mapNotNull { load(it.nameWithoutExtension) }
        .sortedByDescending { it.updated }

    fun load(id: String): Job? = runCatching { jobFromJson(json(id).readFully().decodeToString()) }.getOrNull()

    // AtomicFile: a crash mid-write leaves the previous version intact.
    fun save(job: Job) {
        val f = json(job.id)
        val out = f.startWrite()
        try {
            out.write(job.toJson().toByteArray())
            f.finishWrite(out)
        } catch (e: Exception) {
            f.failWrite(out)
            throw e
        }
    }

    fun delete(id: String) {
        json(id).delete()
        pdf(id).delete()
    }

    /** Copies the PDF in (picker URIs don't survive reboots) and creates a job named after the file. */
    fun create(uri: Uri): Job {
        val id = UUID.randomUUID().toString()
        val pdf = pdf(id)
        try {
            val input = context.contentResolver.openInputStream(uri) ?: error("Can't read file")
            input.use { inp -> pdf.outputStream().use { inp.copyTo(it) } }
            // Fail now, not on open, if it's corrupt or password-protected.
            PdfRenderer(ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY)).use { }
        } catch (e: Exception) {
            pdf.delete()
            throw e
        }
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            ?.removeSuffix(".pdf")?.removeSuffix(".PDF")
            ?: "New job"
        val now = System.currentTimeMillis()
        return Job(id, name, updated = now, created = now).also { save(it) }
    }
}
