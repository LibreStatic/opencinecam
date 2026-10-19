/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.media.video

import com.librestatic.opencinecam.core.model.PolicyMode

/** What the SDR AVC Surface route is recording; only these two may ever use a software encoder (ADR-0010, ADR-0033). */
enum class SdrAvcCaptureKind { VIDEO, TIMELAPSE }

/** Stable code of the Strict refusal when only a software AVC encoder fits a normal VIDEO take. */
const val VIDEO_HARDWARE_ENCODER_UNAVAILABLE = "video-hardware-encoder-unavailable"

sealed interface SdrAvcEncoderDecision {
    /** A qualifying hardware encoder: nothing to disclose. */
    data class Hardware(val pick: SurfaceEncoderPick) : SdrAvcEncoderDecision

    /**
     * A software encoder records the take. [disclose] is true when the user must be told (Adaptive VIDEO);
     * the timelapse route keeps its own progress disclosure. Never hardware qualification evidence.
     */
    data class Software(val pick: SurfaceEncoderPick, val disclose: Boolean) : SdrAvcEncoderDecision

    /** Strict refuses the start: only a software encoder fits. */
    data class Refused(val code: String, val codecName: String) : SdrAvcEncoderDecision

    /** No advertised AVC Surface encoder accepts the size and rate. */
    data object Unavailable : SdrAvcEncoderDecision
}

/**
 * Decides the SDR AVC encoder from the best [pick] of an [EncoderHardwarePolicy.ALLOW_SOFTWARE] selection, which
 * already orders hardware first, so a software pick means no hardware encoder fits the size and rate.
 * Timelapse keeps accepting software in either policy; a normal VIDEO take refuses it in Strict and records with
 * it in Adaptive. LOG, HLG and HEVC never reach this function: they stay hardware-only.
 */
fun decideSdrAvcEncoder(pick: SurfaceEncoderPick?, policy: PolicyMode, kind: SdrAvcCaptureKind): SdrAvcEncoderDecision = when {
    pick == null -> SdrAvcEncoderDecision.Unavailable
    pick.hardwareAccelerated -> SdrAvcEncoderDecision.Hardware(pick)
    kind == SdrAvcCaptureKind.TIMELAPSE -> SdrAvcEncoderDecision.Software(pick, disclose = false)
    policy == PolicyMode.STRICT -> SdrAvcEncoderDecision.Refused(VIDEO_HARDWARE_ENCODER_UNAVAILABLE, pick.codecName)
    else -> SdrAvcEncoderDecision.Software(pick, disclose = true)
}

/** English disclosure recorded in the hardware-truth warnings and the take sidecar. */
fun softwareVideoEncoderDisclosure(codecName: String): String = "Software video encoder: $codecName; frames may drop"
