/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.rawvideo

import com.librestatic.opencinecam.media.ocraw.OcrawRecoveryAction
import com.librestatic.opencinecam.media.ocraw.OcrawStreamReader
import java.nio.channels.FileChannel

/** What to do with an interrupted `.ocraw` file once it has been recovered in place. */
enum class RawTakeFileDecision { PUBLISH, DELETE }

data class RawTakeFileRecovery(
    val decision: RawTakeFileDecision,
    /** Complete RAW frames in the file after recovery. */
    val frames: Int,
    /** The reader's action, or null when recovery itself failed and the file was left as it was. */
    val action: OcrawRecoveryAction?,
    /** Bytes cut from a torn tail. */
    val truncatedBytes: Long,
)

/**
 * Makes an interrupted take readable in place with [OcrawStreamReader.recover]: cuts a torn tail and
 * appends `FINAL_INDEX` and `END`. When appending fails (a full volume is the usual reason a writer
 * failed) it falls back to truncating only, which still leaves a valid prefix. A file with at least
 * one complete frame is published; one without (no header, or a header and metadata only) is
 * deleted. If recovery cannot even read the file, [fallbackFrames] decides: frames the writer knows
 * it completed are never thrown away.
 */
fun recoverRawTakeFile(channel: FileChannel, fallbackFrames: Long = 0): RawTakeFileRecovery {
    val result = try {
        OcrawStreamReader.recover(channel, finalize = true)
    } catch (_: Exception) {
        try {
            OcrawStreamReader.recover(channel, finalize = false)
        } catch (_: Exception) {
            null
        }
    }
    if (result == null) {
        val frames = fallbackFrames.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
        return RawTakeFileRecovery(
            if (frames > 0) RawTakeFileDecision.PUBLISH else RawTakeFileDecision.DELETE, frames, null, 0,
        )
    }
    val frames = result.framesRecovered
    return RawTakeFileRecovery(
        decision = if (frames > 0) RawTakeFileDecision.PUBLISH else RawTakeFileDecision.DELETE,
        frames = frames,
        action = result.action,
        truncatedBytes = result.truncatedBytes,
    )
}
