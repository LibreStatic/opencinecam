/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.capture

import com.librestatic.opencinecam.camera.AnamorphicOutputMode
import com.librestatic.opencinecam.camera.AnamorphicSqueeze

/*
 * Text for the capture status lines. Values the device does not report are left out instead of
 * being shown as a dash, so the lines only ever say something the operator can act on.
 */

/**
 * Recording time [freeBytes] holds at [bytesPerMinute] (the take's projection, `projectedTakeBytesPerMinute`: the
 * encoder's real bitrate with its frame rate, audio and mode, not the 30 fps reference of the setting), or null when
 * either is unknown. Computed in Double: bytes times 60 000 can pass a Long on a large drive.
 */
fun recordTimeLeftMs(freeBytes: Long?, bytesPerMinute: Long?): Long? {
    val free = freeBytes?.takeIf { it > 0L } ?: return null
    val perMinute = bytesPerMinute?.takeIf { it > 0L } ?: return null
    return (free.toDouble() * 60_000.0 / perMinute).toLong()
}

/** "2 h 15 min", "3 h", "45 min", "< 1 min". Minutes round down, so the card never runs out early. */
fun formatRecordTimeLeft(durationMs: Long): String {
    val minutes = durationMs.coerceAtLeast(0L) / 60_000L
    val hours = minutes / 60L
    val rest = minutes % 60L
    return when {
        minutes < 1L -> "< 1 min"
        hours == 0L -> "$minutes min"
        rest == 0L -> "$hours h"
        else -> "$hours h $rest min"
    }
}

/** "ANA 1.33x/DQ" for a 1.33x squeeze shown desqueezed ("SQ" when kept squeezed); null without a squeeze. */
fun anamorphicStatusLabel(squeeze: AnamorphicSqueeze, output: AnamorphicOutputMode): String? {
    if (!squeeze.isActive) return null
    val factor = when (squeeze) {
        AnamorphicSqueeze.SQUEEZE_1_33X -> "1.33x"
        AnamorphicSqueeze.SQUEEZE_1_5X -> "1.5x"
        AnamorphicSqueeze.SQUEEZE_2X -> "2x"
        else -> return null
    }
    return "ANA $factor/${if (output == AnamorphicOutputMode.DESQUEEZED) "DQ" else "SQ"}"
}
