/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.content.Context
import com.librestatic.opencinecam.core.model.CalibrationSessionKind
import com.librestatic.opencinecam.core.model.CalibrationSet
import com.librestatic.opencinecam.core.model.LensCalibrationCodec
import com.librestatic.opencinecam.core.model.LensCalibrationDecoding
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Device-local store of lens calibrations, one JSON file per (build fingerprint, logical camera,
 * session kind). A missing or invalid file reads as "not calibrated"; nothing here throws on read.
 */
class LensCalibrationStore(private val dir: File) {
    fun load(fingerprint: String, logicalId: String, kind: CalibrationSessionKind): CalibrationSet? =
        read(file(fingerprint, logicalId, kind))

    /** Writes atomically: a temp file in the same directory, then a rename. */
    fun save(set: CalibrationSet) {
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) throw IOException("Cannot create $dir")
        val target = file(set.fingerprint, set.logicalId, set.sessionKind)
        val temp = File(dir, "${target.name}.tmp")
        try {
            temp.writeText(LensCalibrationCodec.encode(set), Charsets.UTF_8)
            if (!temp.renameTo(target)) {
                target.delete()
                if (!temp.renameTo(target)) throw IOException("Cannot replace $target")
            }
        } finally {
            temp.delete()
        }
    }

    fun delete(fingerprint: String, logicalId: String, kind: CalibrationSessionKind) {
        file(fingerprint, logicalId, kind).delete()
    }

    /** Every valid stored calibration, newest first. */
    fun all(): List<CalibrationSet> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(SUFFIX) }.orEmpty()
            .mapNotNull(::read).sortedByDescending { it.createdAtMs }

    private fun file(fingerprint: String, logicalId: String, kind: CalibrationSessionKind): File {
        val key = "$fingerprint|$logicalId|${kind.name}"
        val hex = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(dir, hex + SUFFIX)
    }

    private fun read(file: File): CalibrationSet? = try {
        if (!file.isFile) null
        else (LensCalibrationCodec.decode(file.readText(Charsets.UTF_8)) as? LensCalibrationDecoding.Decoded)?.set
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }

    companion object {
        private const val SUFFIX = ".json"

        fun forContext(context: Context): LensCalibrationStore =
            LensCalibrationStore(File(context.filesDir, "lens-calibration"))
    }
}
