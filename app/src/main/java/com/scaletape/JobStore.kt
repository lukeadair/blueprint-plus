package com.scaletape

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.ext.SdkExtensions
import android.provider.OpenableColumns
import android.util.AtomicFile
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID

// PdfRenderer text search arrived in Android 15 (API 35), and on 11+ through SDK extension 13.
internal val canReadPdfText = Build.VERSION.SDK_INT >= 35 ||
    (Build.VERSION.SDK_INT >= 30 && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 13)

/** Each job is `jobs/<id>.json` plus its own copy of the plan at `jobs/<id>.pdf`. */
class JobStore(private val context: Context) {
    private val dir = File(context.filesDir, "jobs").apply { mkdirs() }

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

    /** Writes [job]'s quote spreadsheet to the cache (shared through the FileProvider) and returns its URI. */
    fun exportQuote(job: Job, ratio: Double): Uri {
        val dir = File(context.cacheDir, "export").apply { deleteRecursively(); mkdirs() } // only the latest export kept
        val name = job.name.filter { it.isLetterOrDigit() || it in " -_." }.trim().ifEmpty { "Quote" }
        val file = File(dir, "$name.xlsx")
        file.outputStream().use { writeQuoteXlsx(job, ratio, it) }
        return FileProvider.getUriForFile(context, "${context.packageName}.files", file)
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
        val found = findPlanScale(pdf)
        return Job(id, name, scale = found ?: Job(id, name).scale, updated = now, created = now, scaleSet = false, foundScale = found)
            .also { save(it) }
    }

    /**
     * The scale saved in the PDF's own data, else page by page ([pickPlanScale]: the first page with a `= 1'` scale,
     * so detail sheets' callouts can't outvote the plan and a site sheet before it doesn't win); null if none.
     */
    @SuppressLint("NewApi") // textContents guarded by canReadPdfText
    private fun findPlanScale(pdf: File): String? = runCatching {
        // shortcut: scaleInPdf holds ~3x the file in memory, so bigger files (mostly scans) skip it; stream it if a
        // large calibrated plan needs it.
        (if (pdf.length() <= 32L shl 20) scaleInPdf(pdf.readBytes()) else null) ?: if (!canReadPdfText) null else
            PdfRenderer(ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY)).use { r ->
                pickPlanScale((0 until r.pageCount).asSequence().map { i ->
                    findScale(r.openPage(i).use { p -> p.textContents.joinToString("") { it.text } })
                })
            }
    }.getOrNull()
}
