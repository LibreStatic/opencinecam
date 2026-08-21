/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

enum class AnamorphicSqueeze(val factor: Float, val sarWidth: Int, val sarHeight: Int) {
    NONE(1.0f, 1, 1),
    SQUEEZE_1_33X(1.33f, 4, 3),
    SQUEEZE_1_5X(1.5f, 3, 2),
    SQUEEZE_2X(2.0f, 2, 1);

    val isActive: Boolean get() = this != NONE
}

enum class AnamorphicOutputMode { SQUEEZED, DESQUEEZED }
