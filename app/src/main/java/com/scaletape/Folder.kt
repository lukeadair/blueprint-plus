package com.scaletape

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** A flat folder of job files: `filesDir/jobs`, or the sync folder the user picked (a SAF tree). */
interface Folder {
    fun names(): List<String>
    fun read(name: String): ByteArray?
    /** [read] through the last [names] listing's handle: no lookup per file, but only fresh right after listing. */
    fun readListed(name: String): ByteArray? = read(name)
    /** Replaces [name] with [bytes] without ever leaving a half-written [name] behind (a sync tool could upload it). */
    fun write(name: String, bytes: ByteArray)
    fun input(name: String): InputStream
    /** Not crash-safe; only for PDFs, which nothing reads until their JSON is saved. */
    fun output(name: String): OutputStream
    fun exists(name: String): Boolean = name in names()
    fun delete(name: String)
    fun rename(from: String, to: String): Boolean
    fun openPdf(name: String): ParcelFileDescriptor
}

/** The hidden temp a JSON is written to before it's put in place; the Mac and [JobStore.list] skip hidden files. */
internal fun tempName(name: String) = ".$name.tmp"

fun Folder.copyFrom(other: Folder, name: String, to: String = name) {
    other.input(name).use { i -> output(to).use { i.copyTo(it) } }
}

class FileFolder(val dir: File) : Folder {
    init { dir.mkdirs() }
    private fun f(name: String) = File(dir, name)
    override fun names() = dir.list().orEmpty().toList()
    override fun read(name: String) = runCatching { f(name).readBytes() }.getOrNull()
    // Atomic rename over the old file: a crash mid-write leaves the previous version intact.
    override fun write(name: String, bytes: ByteArray) {
        val tmp = f(tempName(name))
        tmp.outputStream().use { it.write(bytes); it.fd.sync() }
        Files.move(tmp.toPath(), f(name).toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
    override fun input(name: String): InputStream = f(name).inputStream()
    override fun output(name: String): OutputStream = f(name).outputStream()
    override fun exists(name: String) = f(name).exists()
    override fun delete(name: String) { f(name).let { if (!it.delete() && it.exists()) throw IOException("Can't delete $name") } }
    override fun rename(from: String, to: String) = f(from).renameTo(f(to))
    override fun openPdf(name: String): ParcelFileDescriptor = ParcelFileDescriptor.open(f(name), ParcelFileDescriptor.MODE_READ_ONLY)
}

/** A folder picked with ACTION_OPEN_DOCUMENT_TREE. Every lookup is one children query (DocumentFile's listing is slow). */
class SafFolder(context: Context, val tree: Uri) : Folder {
    private val cr = context.contentResolver
    private val treeId = DocumentsContract.getTreeDocumentId(tree)
    private val root = DocumentsContract.buildDocumentUriUsingTree(tree, treeId)

    private fun children(): Map<String, Uri> = cr.query(
        DocumentsContract.buildChildDocumentsUriUsingTree(tree, treeId),
        arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME), null, null, null,
    )?.use { c ->
        buildMap { while (c.moveToNext()) put(c.getString(1), DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))) }
    }?.also { listed = it } ?: throw IOException("Can't read the sync folder")

    // The last listing's URIs, so list() reading every JSON isn't one folder query per file; a stale one falls back.
    @Volatile private var listed: Map<String, Uri> = emptyMap()

    private fun uri(name: String) = children()[name]
    private fun need(name: String) = uri(name) ?: throw FileNotFoundException(name)
    // octet-stream: providers keep the name as given instead of adding an extension for the type.
    private fun create(name: String) = DocumentsContract.createDocument(cr, root, "application/octet-stream", name)
        ?: throw IOException("Can't create $name in the sync folder")

    override fun names() = children().keys.toList()
    private fun readUri(u: Uri) = runCatching { cr.openInputStream(u)?.use { it.readBytes() } }.getOrNull()
    override fun read(name: String) = runCatching { uri(name) }.getOrNull()?.let(::readUri)
    override fun readListed(name: String) = listed[name]?.let(::readUri) ?: read(name)

    /**
     * SAF can't replace a file atomically, so: full content to a hidden temp, delete the old file, rename the temp in.
     * A crash can leave no `<name>` (only the complete temp, which [JobStore.list] puts back), never a half-written one.
     */
    override fun write(name: String, bytes: ByteArray) {
        val tmp = tempName(name)
        delete(tmp) // a crash's leftover; createDocument would otherwise pick another name
        val t = create(tmp)
        try {
            (cr.openOutputStream(t, "wt") ?: throw IOException("Can't write $tmp")).use { it.write(bytes) }
        } catch (e: Exception) {
            runCatching { DocumentsContract.deleteDocument(cr, t) }
            throw e
        }
        delete(name) // renaming onto an existing name gives "<name> (1)" instead of replacing it
        DocumentsContract.renameDocument(cr, t, name) ?: throw IOException("Can't rename $tmp")
    }

    override fun input(name: String): InputStream = cr.openInputStream(need(name)) ?: throw FileNotFoundException(name)
    override fun output(name: String): OutputStream = cr.openOutputStream(uri(name) ?: create(name), "wt") ?: throw IOException("Can't write $name")
    override fun delete(name: String) { uri(name)?.let { if (!DocumentsContract.deleteDocument(cr, it)) throw IOException("Can't delete $name") } }
    override fun rename(from: String, to: String) = runCatching { DocumentsContract.renameDocument(cr, need(from), to) != null }.getOrDefault(false)
    override fun openPdf(name: String): ParcelFileDescriptor = cr.openFileDescriptor(need(name), "r") ?: throw FileNotFoundException(name)
}
