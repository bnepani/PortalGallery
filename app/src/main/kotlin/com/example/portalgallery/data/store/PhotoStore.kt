package com.example.portalgallery.data.store

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

/**
 * On-disk photo library: a directory of JPEGs plus an index file.
 *
 * Deliberately not an LRU cache. At roughly 300 KB per photo and a few hundred photos
 * the whole album is ~92 MB, so there is nothing to evict — and an eviction policy
 * would reintroduce the failure this design exists to prevent (the slideshow reaching
 * for a photo that was silently thrown away, then hitting the network mid-render).
 *
 * "Never blank" is therefore a property of the filesystem rather than an invariant
 * anyone has to maintain: whatever is in [photosDir] can be displayed, offline,
 * forever, regardless of what Google does next.
 */
class PhotoStore(context: Context) {

    private val root = File(context.filesDir, "album").apply { mkdirs() }
    private val photosDir = File(root, "photos").apply { mkdirs() }
    private val indexFile = File(root, "index.json")
    private val gson = Gson()

    data class Entry(
        val id: String,
        val width: Int,
        val height: Int,
        /** Defaults false so an index written before video support still deserialises. */
        val isVideo: Boolean = false,
    )

    data class StoredPhoto(
        val id: String,
        val file: File,
        val width: Int,
        val height: Int,
        val isVideo: Boolean,
    ) {
        val isPortrait: Boolean get() = height > width
    }

    fun fileFor(id: String, isVideo: Boolean = false): File =
        File(photosDir, if (isVideo) "$id.mp4" else "$id.jpg")

    /** Either extension, since callers do not always know the type yet. */
    private fun existingFile(id: String): File? =
        listOf(fileFor(id, false), fileFor(id, true))
            .firstOrNull { it.exists() && it.length() > 0 }

    /** Photos whose bytes are actually present. Never throws; returns empty on any problem. */
    fun load(): List<StoredPhoto> = runCatching {
        if (!indexFile.exists()) return emptyList()
        val type = object : TypeToken<List<Entry>>() {}.type
        val entries: List<Entry> = gson.fromJson(indexFile.readText(), type) ?: emptyList()
        entries.mapNotNull { e ->
            val f = fileFor(e.id, e.isVideo)
            if (f.exists() && f.length() > 0) {
                StoredPhoto(e.id, f, e.width, e.height, e.isVideo)
            } else {
                null
            }
        }
    }.getOrDefault(emptyList())

    /** Write-temp-then-rename, with an fsync so a power cut can't leave a torn index. */
    fun saveIndex(entries: List<Entry>) {
        val tmp = File(root, "index.json.tmp")
        tmp.outputStream().use { out ->
            out.write(gson.toJson(entries).toByteArray())
            out.flush()
            out.fd.sync()
        }
        check(tmp.renameTo(indexFile)) { "could not replace index" }
    }

    /** Same temp-then-rename discipline, so a partial download is never indexed. */
    fun writePhoto(id: String, bytes: ByteArray, isVideo: Boolean = false) {
        val target = fileFor(id, isVideo)
        val tmp = File(photosDir, "${target.name}.tmp")
        tmp.outputStream().use { out ->
            out.write(bytes)
            out.flush()
            out.fd.sync()
        }
        check(tmp.renameTo(target)) { "could not move $id into place" }
    }

    fun hasPhoto(id: String): Boolean = existingFile(id) != null

    /** Removes files no longer referenced. Only safe to call when no pass is in flight. */
    fun prune(keepIds: Set<String>): Int =
        photosDir.listFiles()
            ?.filter { it.name.substringBeforeLast('.') !in keepIds }
            ?.count { it.delete() }
            ?: 0

    fun totalBytes(): Long = photosDir.listFiles()?.sumOf { it.length() } ?: 0L
}
