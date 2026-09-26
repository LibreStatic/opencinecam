/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

enum class ProbeLayoutMode {
    PORTRAIT,
    LANDSCAPE,
    WIDE,
    FOLDABLE,
}

fun probeLayoutMode(widthDp: Int, heightDp: Int, separatingFold: Boolean = false): ProbeLayoutMode = when {
    separatingFold -> ProbeLayoutMode.FOLDABLE
    widthDp >= 600 && heightDp >= 600 -> ProbeLayoutMode.WIDE
    widthDp > heightDp -> ProbeLayoutMode.LANDSCAPE
    else -> ProbeLayoutMode.PORTRAIT
}
