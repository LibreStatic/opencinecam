/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import android.content.Context
import com.librestatic.opencinecam.camera.EqCurve
import com.librestatic.opencinecam.camera.EqProfile
import com.librestatic.opencinecam.camera.decodeEqProfile
import com.librestatic.opencinecam.camera.isBundledEqProfileId
import com.librestatic.opencinecam.camera.isValidEqProfileId
import com.librestatic.opencinecam.camera.toJson
import java.io.File
import java.io.IOException
import java.security.SecureRandom

/**
 * Device-local store of the user's equaliser profiles (PLAN-074 decision 8), one `<id>.json` per profile. Bundled
 * profiles ship as read-only assets, so a stored file claiming to be one is ignored and none can be saved. A missing,
 * damaged or mismatched file reads as absent; nothing here throws on read. One writer at a time: every call locks
 * the instance.
 */
class EqProfileStore(private val dir: File) {
    private val random = SecureRandom()

    /** Every readable user profile, by name (ignoring case), then id. */
    @Synchronized
    fun all(): List<EqProfile> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(SUFFIX) }.orEmpty()
            .mapNotNull { read(it, it.name.removeSuffix(SUFFIX)) }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, EqProfile::name).thenBy(EqProfile::id))

    @Synchronized
    fun load(id: String): EqProfile? = fileFor(id)?.let { read(it, id) }

    /**
     * Writes atomically: a temp file in the same directory, then a rename. A user profile has no device, so it is
     * stored without one. Refuses a bundled profile ([IllegalArgumentException]) and a new profile past
     * [MAX_PROFILES] ([IllegalStateException]).
     */
    @Synchronized
    fun save(profile: EqProfile) {
        require(!profile.bundled && !isBundledEqProfileId(profile.id)) { "Bundled equaliser profiles are read-only." }
        val target = requireNotNull(fileFor(profile.id))
        check(target.isFile || all().size < MAX_PROFILES) { "At most $MAX_PROFILES equaliser profiles can be stored." }
        write(target, profile.copy(device = null))
    }

    /** Removes the profile; false when there was none to remove. */
    @Synchronized
    fun delete(id: String): Boolean = fileFor(id)?.let { it.isFile && it.delete() } ?: false

    /** Renames a stored profile, or returns null when there is none; a blank or too long [name] is refused. */
    @Synchronized
    fun rename(id: String, name: String): EqProfile? {
        val profile = load(id) ?: return null
        val renamed = profile.copy(name = name.trim())
        save(renamed)
        return renamed
    }

    /**
     * Stores [curve] as a new profile under a fresh `user-` id. Names need not be unique; a blank one is refused
     * ([IllegalArgumentException]), and so is a profile past [MAX_PROFILES] ([IllegalStateException]).
     */
    @Synchronized
    fun create(name: String, curve: EqCurve): EqProfile {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "An equaliser profile needs a name." }
        check(all().size < MAX_PROFILES) { "At most $MAX_PROFILES equaliser profiles can be stored." }
        var id: String
        do id = USER_ID_PREFIX + newIdSuffix() while (File(dir, id + SUFFIX).exists())
        val profile = EqProfile(id, trimmed, curve)
        save(profile)
        return profile
    }

    private fun newIdSuffix(): String {
        val bytes = ByteArray(ID_BYTES).also(random::nextBytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /** The file of a user profile id, or null for an id that cannot name one (invalid, or bundled). */
    private fun fileFor(id: String): File? =
        if (isValidEqProfileId(id) && !isBundledEqProfileId(id)) File(dir, id + SUFFIX) else null

    private fun write(target: File, profile: EqProfile) {
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) throw IOException("Cannot create $dir")
        val temp = File(dir, "${target.name}.tmp")
        try {
            temp.writeText(profile.toJson(), Charsets.UTF_8)
            if (!temp.renameTo(target)) {
                target.delete()
                if (!temp.renameTo(target)) throw IOException("Cannot replace $target")
            }
        } finally {
            temp.delete()
        }
    }

    /** A file whose profile is bundled, or whose id is not its name, is not a user profile and reads as absent. */
    private fun read(file: File, id: String): EqProfile? = try {
        if (!file.isFile) null
        else decodeEqProfile(file.readText(Charsets.UTF_8))
            ?.takeIf { it.id == id && !it.bundled && !isBundledEqProfileId(it.id) }
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }

    companion object {
        /** How many user profiles the store keeps. */
        const val MAX_PROFILES = 64
        const val USER_ID_PREFIX = "user-"
        private const val ID_BYTES = 5
        private const val SUFFIX = ".json"

        fun forContext(context: Context): EqProfileStore = EqProfileStore(File(context.filesDir, "eq-profiles"))
    }
}
