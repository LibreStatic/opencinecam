/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import com.librestatic.opencinecam.camera.AnamorphicOutputMode
import com.librestatic.opencinecam.camera.AnamorphicSqueeze

/*
 * Text for the capture status lines. Values the device does not report are left out instead of
 * being shown as a dash, so the lines only ever say something the operator can act on.
 */

/** Recording time [freeBytes] holds at [bitrateMbps], or null when either is unknown. */
fun recordTimeLeftMs(freeBytes: Long?, bitrateMbps: Int): Long? =
    if (freeBytes == null || freeBytes <= 0L || bitrateMbps <= 0) null
    else freeBytes * 8L * 1_000L / (bitrateMbps * 1_000_000L)

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
