/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.util.Log
import com.librestatic.opencinecam.motion.MotionTakeInfo
import com.librestatic.opencinecam.motion.MotionTakeRecorder
import com.librestatic.opencinecam.motion.MotionTakeRoute
import com.librestatic.opencinecam.motion.MotionTakeTiming
import java.io.File

/** The name of a take's Gyroflow log row: the video's stem with `.gcsv`, the name Gyroflow looks for beside the video. */
internal fun motionLogName(videoName: String): String = videoName.removeSuffix(".mp4") + "." + GyroflowLog.EXTENSION

/**
 * Copies the rendered log [file] into the take's log row [uri], held for the drive's volume monitor as every take write
 * is (ADR-0037): a drive that leaves mid-copy ends it with an I/O error instead of the process holding its descriptor.
 */
internal fun writeMotionLogRow(resolver: ContentResolver, uri: Uri, file: File, mode: String = "w") {
    val mark = DriveDescriptors.releaseMark(uri)
    (resolver.openOutputStream(uri, mode) ?: error("Gyroflow log row could not be opened")).useOnDrive(uri, mark) { out ->
        file.inputStream().use { it.copyTo(out) }
    }
}

/**
 * The motion data a take whose process died left behind: its spool and description in `filesDir/motion/<stem>.ocmo` and
 * `.json` ([MotionTakeRecorder]), keyed by the recording journal's take id. Recovery renders the spool into
 * `cacheDir/gyroflow/<stem>.recovered.gcsv` ([render], with the journal unlocked), attaches it to the take it keeps
 * ([RecordingCaptureRecovery]) and deletes everything once the take is resolved ([delete]). Every failure costs only the
 * log: nothing here throws into the take's recovery.
 */
internal class MotionRecoveryFiles(filesDir: File, cacheDir: File) {
    constructor(context: Context) : this(context.applicationContext.filesDir, context.applicationContext.cacheDir)

    private val spoolDir = File(filesDir, MotionTakeRecorder.SPOOL_DIRECTORY)
    private val gcsvDir = File(cacheDir, MotionTakeRecorder.GCSV_DIRECTORY)

    private fun stem(takeId: String) = MotionTakeRecorder.fileStem(takeId)
    fun spoolOf(takeId: String) = File(spoolDir, "${stem(takeId)}.${MotionTakeRecorder.SPOOL_EXTENSION}")
    fun infoOf(takeId: String) = File(spoolDir, "${stem(takeId)}.${MotionTakeRecorder.INFO_EXTENSION}")
    /** The log [render] made for take [takeId]; never the live take's own temporary log (`<stem>.gcsv`). */
    fun renderedOf(takeId: String) = File(gcsvDir, "${stem(takeId)}$RENDERED_SUFFIX")

    /** The take ids with a spool, read from the spool folder's names; empty when there is none. */
    fun spooledTakes(): List<String> = spoolDir.list()?.filter { it.endsWith(".${MotionTakeRecorder.SPOOL_EXTENSION}") }
        ?.map { it.removeSuffix(".${MotionTakeRecorder.SPOOL_EXTENSION}") }.orEmpty()

    /** The rendered log of take [takeId], or null without one. */
    fun rendered(takeId: String): File? = renderedOf(takeId).takeIf { it.isFile && it.length() > 0L }

    /**
     * Whether take [takeId]'s spool still waits for [render]: spool and description present, no rendered log, and no
     * render of it finished in this process (one that rendered nothing, a MediaRecorder take or a torn description, is
     * not retried). File existence only, so the take-start pass can ask on the main thread. Never throws.
     */
    fun awaitsRender(takeId: String): Boolean = try {
        spoolOf(takeId).isFile && infoOf(takeId).isFile && rendered(takeId) == null && spoolOf(takeId).absolutePath !in attempted
    } catch (problem: Exception) {
        false
    }

    /**
     * Renders take [takeId]'s spool into [renderedOf], once: an existing log is returned as it is. A GPU take only: the
     * file time of each spooled frame is its presentation time from the first frame (the muxer's offset is not known
     * after a crash, so 0), and two frames are needed. A MediaRecorder take cannot be placed without its stop, a torn
     * description or a spool with a bad header renders nothing; a torn spool tail is cut by [MotionSpoolReader]. Null
     * when nothing was rendered. Renders run one at a time in the process, so two recovery passes never write the same
     * `.part`, and the second finds the first's log. Never throws.
     */
    fun render(takeId: String): File? = synchronized(renderLock) {
        try {
            renderOnce(takeId)
        } finally {
            attempted += spoolOf(takeId).absolutePath
        }
    }

    private fun renderOnce(takeId: String): File? = try {
        rendered(takeId) ?: run {
            val spool = spoolOf(takeId)
            val infoFile = infoOf(takeId)
            if (!spool.isFile || !infoFile.isFile) return null
            val info = MotionTakeInfo.fromJson(infoFile.readText(Charsets.UTF_8))
            if (stem(info.takeId) != stem(takeId) || info.route != MotionTakeRoute.GPU) return null
            val target = renderedOf(takeId)
            // Rendered beside, then renamed: a process that dies mid-render never leaves half a log to attach.
            val partial = File(gcsvDir, target.name + PART_SUFFIX)
            val outputs = MotionTakeRecorder.renderSpool(spool, info, MotionTakeTiming.EncodedFrames(fileOffsetUs = 0L, minFrames = 2),
                partial, withCamm = false) ?: return null
            if (!outputs.gcsv.renameTo(target)) {
                outputs.gcsv.delete()
                return null
            }
            Log.i(TAG, "Take $takeId: motion log rendered from its spool (${outputs.result.gyroSamples} gyro samples)")
            target
        }
    } catch (problem: Exception) {
        Log.w(TAG, "Take $takeId: the motion spool could not be rendered", problem)
        null
    }

    /** Deletes take [takeId]'s spool, description and rendered log. Never throws. */
    fun delete(takeId: String) {
        val stem = stem(takeId)
        listOf(spoolOf(takeId), infoOf(takeId), File(spoolDir, "$stem.${MotionTakeRecorder.INFO_EXTENSION}.tmp"), renderedOf(takeId),
            File(gcsvDir, renderedOf(takeId).name + PART_SUFFIX)).forEach { runCatching { it.delete() } }
        attempted -= spoolOf(takeId).absolutePath
    }

    /**
     * Deletes the motion files of takes not in [known] (the journal's entries: the takes recording, finalising or waiting
     * for recovery) once they are older than [maxAgeMs], in the spool folder and the Gyroflow log folder. Returns how
     * many went. Never throws.
     */
    fun sweepOrphans(known: Set<String>, maxAgeMs: Long, now: Long = System.currentTimeMillis()): Int {
        var deleted = 0
        listOf(spoolDir to MotionFileFolder.SPOOL, gcsvDir to MotionFileFolder.GYROFLOW).forEach { (dir, kind) ->
            val files = runCatching { dir.listFiles()?.toList() }.getOrNull().orEmpty().filter { it.isFile }
            val orphans = motionOrphans(files.map { MotionFileEntry(it.name, it.lastModified()) }, kind, known, maxAgeMs, now).toSet()
            files.filter { it.name in orphans }.forEach { if (runCatching { it.delete() }.getOrDefault(false)) deleted++ }
        }
        return deleted
    }

    companion object {
        private const val TAG = "MotionRecovery"
        const val RENDERED_SUFFIX = ".recovered.gcsv"
        const val PART_SUFFIX = ".part"
        /** Serialises [render] across instances and passes; never taken under the journal's lock, nor it under this. */
        private val renderLock = Any()
        /** The spools a [render] finished with in this process; read without [renderLock], which a render holds for long. */
        private val attempted: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    }
}

internal enum class MotionFileFolder { SPOOL, GYROFLOW }
internal data class MotionFileEntry(val name: String, val lastModifiedMs: Long)

/**
 * The files of [folder] to delete: ours (in the spool folder `<stem>.ocmo`, `.json` and `.json.tmp`; in the Gyroflow
 * folder any `.gcsv`, rendered or the stop's temporary one, and their `.part`), whose stem is no take in [known], and
 * untouched for [maxAgeMs]. A take that is recording or still finalising when the next starts is journalled, so it is
 * in [known]; the age keeps anything else that might be in flight. A modification time of 0 (unreadable) is never old.
 */
internal fun motionOrphans(entries: List<MotionFileEntry>, folder: MotionFileFolder, known: Set<String>, maxAgeMs: Long, now: Long): List<String> {
    val suffixes = when (folder) {
        MotionFileFolder.SPOOL -> listOf(".${MotionTakeRecorder.SPOOL_EXTENSION}", ".${MotionTakeRecorder.INFO_EXTENSION}.tmp",
            ".${MotionTakeRecorder.INFO_EXTENSION}")
        MotionFileFolder.GYROFLOW -> listOf(MotionRecoveryFiles.RENDERED_SUFFIX + MotionRecoveryFiles.PART_SUFFIX,
            MotionRecoveryFiles.RENDERED_SUFFIX, ".${GyroflowLog.EXTENSION}${MotionRecoveryFiles.PART_SUFFIX}", ".${GyroflowLog.EXTENSION}")
    }
    val knownStems = known.mapTo(HashSet(), MotionTakeRecorder::fileStem)
    return entries.filter { entry ->
        val suffix = suffixes.firstOrNull { entry.name.endsWith(it) && entry.name.length > it.length } ?: return@filter false
        val stem = entry.name.removeSuffix(suffix)
        stem !in knownStems && entry.lastModifiedMs > 0L && now - entry.lastModifiedMs >= maxAgeMs
    }.map { it.name }
}
