/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.storage

import android.content.Context
import com.librestatic.opencinecam.rawvideo.RawVideoCrop
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.SecureRandom
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** PLAN-076: a RAW video window the operator saved under [name], for the [sensorWidth] x [sensorHeight] RAW output it was placed on. */
data class RawSavedCrop(
    val id: String,
    val name: String,
    val sensorWidth: Int,
    val sensorHeight: Int,
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
) {
    val crop: RawVideoCrop get() = RawVideoCrop(left, top, width, height)
}

/**
 * PLAN-076: the device-local list of saved RAW video windows, one JSON file written atomically (a temp file, then a
 * rename). A missing or damaged file reads as empty and is left alone until the next successful [save]; an entry that is
 * not a writable window on its own sensor ([RawVideoCrop.snapped]) or has no valid name is dropped on read. Not part of
 * presets. One writer at a time: every call locks the instance.
 */
class RawCropStore(private val file: File) {
    private val random = SecureRandom()

    /** Every readable entry, in the order saved. */
    @Synchronized
    fun all(): List<RawSavedCrop> = read()

    /** The entries placed on a [sensorWidth] x [sensorHeight] RAW output. */
    @Synchronized
    fun list(sensorWidth: Int, sensorHeight: Int): List<RawSavedCrop> =
        read().filter { it.sensorWidth == sensorWidth && it.sensorHeight == sensorHeight }

    /**
     * Stores [crop] on a [sensorWidth] x [sensorHeight] output under [name] (trimmed, 1 to [MAX_NAME_LENGTH] characters)
     * with a fresh id. Refuses ([IllegalArgumentException]) a bad name or a window that cannot be written there, and
     * ([IllegalStateException]) an entry past [MAX_CROPS].
     */
    @Synchronized
    fun save(name: String, sensorWidth: Int, sensorHeight: Int, crop: RawVideoCrop): RawSavedCrop {
        val trimmed = requireNotNull(validName(name)) { "A saved crop needs a name of 1 to $MAX_NAME_LENGTH characters." }
        require(isWindow(sensorWidth, sensorHeight, crop)) { "The crop is not a writable window on this sensor." }
        val entries = read()
        check(entries.size < MAX_CROPS) { "At most $MAX_CROPS crops can be saved." }
        var id: String
        do id = newId() while (entries.any { it.id == id })
        val entry = RawSavedCrop(id, trimmed, sensorWidth, sensorHeight, crop.left, crop.top, crop.width, crop.height)
        write(entries + entry)
        return entry
    }

    /** Renames entry [id]; null when there is none. A bad name is refused ([IllegalArgumentException]). */
    @Synchronized
    fun rename(id: String, name: String): RawSavedCrop? {
        val trimmed = requireNotNull(validName(name)) { "A saved crop needs a name of 1 to $MAX_NAME_LENGTH characters." }
        val entries = read()
        val entry = entries.firstOrNull { it.id == id } ?: return null
        val renamed = entry.copy(name = trimmed)
        write(entries.map { if (it.id == id) renamed else it })
        return renamed
    }

    /** Removes entry [id]; false when there was none. */
    @Synchronized
    fun delete(id: String): Boolean {
        val entries = read()
        if (entries.none { it.id == id }) return false
        write(entries.filterNot { it.id == id })
        return true
    }

    private fun newId(): String = ByteArray(ID_BYTES).also(random::nextBytes).joinToString("") { "%02x".format(it) }

    private fun read(): List<RawSavedCrop> = try {
        if (!file.isFile) emptyList()
        else (Json.parseToJsonElement(file.readText(Charsets.UTF_8)) as? JsonObject)
            ?.get(KEY_CROPS)?.let { it as? JsonArray }
            ?.mapNotNull { (it as? JsonObject)?.let(::decode) }
            ?.distinctBy { it.id }
            .orEmpty()
    } catch (_: IOException) {
        emptyList()
    } catch (_: SecurityException) {
        emptyList()
    } catch (_: IllegalArgumentException) {
        // A malformed document (SerializationException is one).
        emptyList()
    }

    private fun decode(json: JsonObject): RawSavedCrop? {
        fun int(key: String) = (json[key] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
        val id = (json["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() } ?: return null
        val name = (json["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let(::validName) ?: return null
        val sensorWidth = int("sensorWidth") ?: return null
        val sensorHeight = int("sensorHeight") ?: return null
        val left = int("left") ?: return null
        val top = int("top") ?: return null
        val width = int("width") ?: return null
        val height = int("height") ?: return null
        // The constructor refuses a misaligned origin; that entry alone is dropped.
        val crop = runCatching { RawVideoCrop(left, top, width, height) }.getOrNull() ?: return null
        if (!isWindow(sensorWidth, sensorHeight, crop)) return null
        return RawSavedCrop(id, name, sensorWidth, sensorHeight, crop.left, crop.top, crop.width, crop.height)
    }

    private fun write(entries: List<RawSavedCrop>) {
        val dir = file.absoluteFile.parentFile
        if (dir != null && !dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) throw IOException("Cannot create $dir")
        val document = buildJsonObject {
            put(KEY_VERSION, VERSION)
            put(KEY_CROPS, buildJsonArray {
                entries.forEach { e ->
                    add(buildJsonObject {
                        put("id", e.id)
                        put("name", e.name)
                        put("sensorWidth", e.sensorWidth)
                        put("sensorHeight", e.sensorHeight)
                        put("left", e.left)
                        put("top", e.top)
                        put("width", e.width)
                        put("height", e.height)
                    })
                }
            })
        }
        val temp = File(dir, "${file.name}.tmp")
        try {
            temp.writeText(document.toString(), Charsets.UTF_8)
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temp.delete()
        }
    }

    companion object {
        const val MAX_NAME_LENGTH = 40
        const val MAX_CROPS = 64
        private const val ID_BYTES = 5
        private const val VERSION = 1
        private const val KEY_VERSION = "version"
        private const val KEY_CROPS = "crops"

        fun forContext(context: Context): RawCropStore = RawCropStore(File(context.filesDir, "raw-crops.json"))

        /** [name] trimmed when it is 1 to [MAX_NAME_LENGTH] characters, else null. */
        fun validName(name: String): String? = name.trim().takeIf { it.length in 1..MAX_NAME_LENGTH }

        /** True when [crop] is a writable window on a [sensorWidth] x [sensorHeight] output: [RawVideoCrop.snapped] keeps it. */
        fun isWindow(sensorWidth: Int, sensorHeight: Int, crop: RawVideoCrop): Boolean =
            sensorWidth >= 4 && sensorHeight >= 2 &&
                RawVideoCrop.snapped(sensorWidth, sensorHeight, crop.left, crop.top, crop.width, crop.height) == crop
    }
}
