package com.scaletape

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.ext.SdkExtensions
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID

// PdfRenderer text search arrived in Android 15 (API 35), and on 11+ through SDK extension 13.
internal val canReadPdfText = Build.VERSION.SDK_INT >= 35 ||
    (Build.VERSION.SDK_INT >= 30 && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 13)

/**
 * Each job is `<id>.json` plus its own copy of the plan at `<id>.pdf`, in `filesDir/jobs` or in the sync folder the
 * user picked (a folder a sync tool shares with the Mac). Pure apart from [create], [exportQuote] and [Folder.openPdf].
 */
// One lock for every store: Home lists on IO while a job saves on main, and a sync switch reads two stores at once.
// A list can rename a temp a save is about to rename, or two lists can turn the same copy into two conflict jobs.
private val storeLock = Any()

class JobStore(val folder: Folder) {
    class ChangedElsewhere : Exception("The job was changed on another device.")

    private fun pdfName(id: String) = "$id.pdf"
    private fun jsonName(id: String) = "$id.json"
    fun openPdf(id: String): ParcelFileDescriptor = folder.openPdf(pdfName(id))

    private fun parse(name: String, listed: Boolean = false) =
        (if (listed) folder.readListed(name) else folder.read(name))?.let { runCatching { jobFromJson(it.decodeToString()) }.getOrNull() }

    /**
     * The jobs whose PDF is here (a sync tool may bring the JSON first). A JSON not named `<id>.json` is a sync tool's
     * copy (`<id> (1).json`, `<id>.sync-conflict-….json`): the same as the job, it's deleted; different, it becomes its
     * own "(conflict)" job; with no `<id>.json`, it takes that name.
     */
    fun list(): List<Job> = synchronized(storeLock) {
        val names = folder.names().toMutableSet()
        // A save cut off between deleting the old JSON and renaming its (complete) temp in: put the temp back.
        for (n in names.toList()) {
            if (!n.startsWith(".") || !n.endsWith(".json.tmp")) continue
            val id = n.removePrefix(".").removeSuffix(".json.tmp")
            if (jsonName(id) !in names && parse(n, listed = true)?.id == id && folder.rename(n, jsonName(id))) names += jsonName(id)
        }
        val jobs = mutableMapOf<String, Job>()
        val strays = mutableListOf<Pair<String, Job>>()
        for (n in names) {
            if (n.startsWith(".") || !n.endsWith(".json")) continue
            val j = parse(n, listed = true) ?: continue
            if ('/' in j.id || j.id.startsWith(".")) continue // an id is a file name; never let one leave the folder
            if (n == jsonName(j.id)) jobs[j.id] = j else strays += n to j
        }
        for ((n, j) in strays) {
            if (pdfName(j.id) !in names) continue
            val have = jobs[j.id]
            when {
                have == null -> if (folder.rename(n, jsonName(j.id))) jobs[j.id] = j
                have == j -> runCatching { folder.delete(n) }
                // The copy must go too, or every later list makes another conflict job from it.
                else -> runCatching { saveAsConflict(j) }.onSuccess { c ->
                    if (runCatching { folder.delete(n) }.isSuccess) { jobs[c.id] = c; names += pdfName(c.id) } else delete(c.id)
                }
            }
        }
        jobs.values.filter { pdfName(it.id) in names }.sortedByDescending { it.updated }
    }

    fun load(id: String): Job? = parse(jsonName(id))

    fun save(job: Job) = synchronized(storeLock) { folder.write(jsonName(job.id), job.toJson().toByteArray()) }

    /**
     * Saves unless another device changed the file since this one last loaded or saved it ([seen], that version's
     * `updated`): then throws [ChangedElsewhere]. Compared for equality, so the devices' clocks don't matter.
     */
    fun save(job: Job, seen: Long) = synchronized(storeLock) {
        // shortcut: a sync landing between this check and the write is still overwritten; it's a few ms wide.
        load(job.id)?.let { if (it.updated != seen) throw ChangedElsewhere() }
        save(job)
    }

    /** Saves [job] as a new job named "<name> (conflict)", with its own copy of the PDF. */
    fun saveAsConflict(job: Job): Job = synchronized(storeLock) {
        val c = job.copy(id = UUID.randomUUID().toString().lowercase(), name = job.name + " (conflict)")
        try {
            folder.copyFrom(folder, pdfName(job.id), pdfName(c.id))
            save(c)
        } catch (e: Exception) {
            runCatching { folder.delete(pdfName(c.id)) }
            throw e
        }
        c
    }

    /** Copies [from]'s PDF for [id] in; a copy cut off by an error is removed, so it can't pass for the plan later. */
    private fun copyPdfIn(from: Folder, id: String, name: String = pdfName(id)) {
        try { folder.copyFrom(from, name, pdfName(id)) } catch (e: Exception) { runCatching { folder.delete(pdfName(id)) }; throw e }
    }

    /** Copies [other]'s jobs in; a job both have keeps the later-saved copy. [other] is left as it was. */
    fun adopt(other: JobStore) = synchronized(storeLock) {
        for (j in other.list()) {
            // The JSON itself, not list(): that hides a job whose PDF hasn't synced yet, and its JSON may be newer.
            val have = load(j.id)
            if (j.updated <= (have?.updated ?: Long.MIN_VALUE)) continue
            // A PDF with no JSON here may be one a crash cut off mid-copy: copy it again.
            if (have == null || !folder.exists(pdfName(j.id))) copyPdfIn(other.folder, j.id)
            save(j)
        }
    }

    fun delete(id: String) = synchronized(storeLock) {
        runCatching { folder.delete(jsonName(id)) }
        runCatching { folder.delete(pdfName(id)) }
    }

    /** Writes [job]'s quote spreadsheet to the cache (shared through the FileProvider) and returns its URI. */
    fun exportQuote(context: Context, job: Job, ratio: Double): Uri {
        val dir = File(context.cacheDir, "export").apply { deleteRecursively(); mkdirs() } // only the latest export kept
        val name = job.name.filter { it.isLetterOrDigit() || it in " -_." }.trim().ifEmpty { "Quote" }
        val file = File(dir, "$name.xlsx")
        file.outputStream().use { writeQuoteXlsx(job, ratio, it) }
        return FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    }

    /** Copies the PDF in (picker URIs don't survive reboots) and creates a job named after the file. */
    fun create(context: Context, uri: Uri): Job {
        val id = UUID.randomUUID().toString()
        // Checked and scanned as a local file, then copied in, so a bad PDF never reaches the sync folder.
        val pdf = File(context.cacheDir, "new.pdf")
        try {
            val input = context.contentResolver.openInputStream(uri) ?: error("Can't read file")
            input.use { inp -> pdf.outputStream().use { inp.copyTo(it) } }
            // Fail now, not on open, if it's corrupt or password-protected.
            PdfRenderer(ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY)).use { }
            val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                ?.removeSuffix(".pdf")?.removeSuffix(".PDF")
                ?: "New job"
            val now = System.currentTimeMillis()
            val found = findPlanScale(pdf)
            val job = Job(id, name, scale = found ?: Job(id, name).scale, updated = now, created = now, scaleSet = false, foundScale = found)
            synchronized(storeLock) {
                copyPdfIn(FileFolder(pdf.parentFile!!), id, pdf.name)
                try { save(job) } catch (e: Exception) { runCatching { folder.delete(pdfName(id)) }; throw e }
            }
            return job
        } finally {
            pdf.delete()
        }
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

private fun syncPrefs(context: Context) = context.getSharedPreferences("sync", Context.MODE_PRIVATE)
private const val RW = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

fun localStore(context: Context) = JobStore(FileFolder(File(context.filesDir, "jobs")))

/** The store to open at launch, and a notice when the sync folder's permission is gone (then it's back to local). */
fun openStore(context: Context): Pair<JobStore, String?> {
    val prefs = syncPrefs(context)
    val tree = prefs.getString("tree", null)?.let(Uri::parse) ?: return localStore(context) to null
    if (context.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission && it.isWritePermission }) {
        return JobStore(SafFolder(context, tree)) to null
    }
    prefs.edit().remove("tree").apply()
    return localStore(context) to "Lost access to the sync folder, so jobs are saved on this phone again. Pick the folder again to sync."
}

/** Switches to the picked folder, copying this phone's jobs in first (the local copies stay as a backup). */
fun startSync(context: Context, tree: Uri): JobStore {
    val cr = context.contentResolver
    cr.takePersistableUriPermission(tree, RW)
    try {
        val s = JobStore(SafFolder(context, tree))
        s.adopt(localStore(context))
        syncPrefs(context).edit().putString("tree", tree.toString()).apply()
        return s
    } catch (e: Exception) {
        runCatching { cr.releasePersistableUriPermission(tree, RW) }
        throw e
    }
}

/** Copies the sync folder's jobs back to this phone (the folder is left as it was) and switches to local. */
fun stopSync(context: Context, synced: JobStore): JobStore {
    val local = localStore(context)
    local.adopt(synced)
    syncPrefs(context).edit().remove("tree").apply()
    (synced.folder as? SafFolder)?.let { runCatching { context.contentResolver.releasePersistableUriPermission(it.tree, RW) } }
    return local
}
