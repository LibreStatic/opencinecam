/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.util.AtomicFile
import com.librestatic.opencinecam.camera.*
import java.io.File
import java.io.IOException
import org.json.JSONObject

/** Private runtime state; never included in portable camera presets. */
internal class TimecodeContinuationFile(file: File) : TimecodeContinuationStore {
    private val atomic = AtomicFile(file)
    @Synchronized override fun load(): TimecodeContinuation? {
        if (!atomic.baseFile.exists() && !File(atomic.baseFile.path + ".bak").exists()) return null
        val bytes = atomic.openRead().use { input ->
            val buffer = ByteArray(4097); var count = 0
            while (count < buffer.size) {
                val read = input.read(buffer, count, buffer.size - count)
                if (read < 0) break
                count += read
            }
            require(count <= 4096) { "Oversized timecode continuation" }
            buffer.copyOf(count)
        }
        val json = JSONObject(bytes.toString(Charsets.UTF_8))
        require(json.keys().asSequence().toSet() == setOf("version", "enabled", "mode", "nominalFps", "dropFrame",
            "startOrdinal", "rememberPosition", "resetRevision", "recordRunCursor", "regenNext"))
        fun number(key: String): Long {
            val value = json.get(key)
            require(value is Int || value is Long) { "Noncanonical timecode number" }
            return (value as Number).toLong()
        }
        fun integer(key: String): Int = number(key).also { require(it in Int.MIN_VALUE..Int.MAX_VALUE) }.toInt()
        fun boolean(key: String): Boolean = json.get(key).also { require(it is Boolean) } as Boolean
        require(integer("version") == 1)
        val rate = TimecodeRate(integer("nominalFps"), boolean("dropFrame"))
        val ordinal = number("startOrdinal")
        require(ordinal in 0 until rate.framesPerDay)
        val mode = json.get("mode").also { require(it is String) } as String
        val config = TimecodeConfig(boolean("enabled"), TimecodeMode.valueOf(mode),
            rate, SmpteTimecode.fromTotalFrames(ordinal, rate), boolean("rememberPosition"), integer("resetRevision"))
        return TimecodeContinuation(config, number("recordRunCursor"),
            if (json.isNull("regenNext")) null else number("regenNext"))
    }
    @Synchronized override fun save(value: TimecodeContinuation) {
        val config = value.config
        val json = JSONObject().put("version", 1).put("enabled", config.enabled).put("mode", config.mode.name)
            .put("nominalFps", config.rate.nominalFps).put("dropFrame", config.rate.dropFrame)
            .put("startOrdinal", config.startValue.toTotalFrames(config.rate)).put("rememberPosition", config.rememberPosition)
            .put("resetRevision", config.resetRevision).put("recordRunCursor", value.recordRunCursor)
            .put("regenNext", value.regenNext ?: JSONObject.NULL)
        val stream = atomic.startWrite()
        try {
            stream.write(json.toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
            // AtomicFile logs certain rename/fsync failures; readback must still equal this exact state.
            if (load() != value) throw IOException("Timecode continuation readback mismatch")
        } catch (failure: Exception) {
            atomic.failWrite(stream)
            throw failure
        }
    }
    @Synchronized override fun clear() {
        atomic.delete()
        if (atomic.baseFile.exists() || File(atomic.baseFile.path + ".bak").exists() || File(atomic.baseFile.path + ".new").exists()) {
            throw IOException("Timecode continuation could not be cleared")
        }
    }
}
