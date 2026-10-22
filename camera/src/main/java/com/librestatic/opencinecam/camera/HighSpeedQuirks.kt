/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import com.librestatic.opencinecam.core.model.QuirkResolution
import kotlin.math.abs

/**
 * The device profile verdict for one camera. The engine asks this instead of reading `Build`, so
 * a quirk applies to the device and camera a profile names and to nothing else.
 */
fun interface DeviceQuirks {
    fun resolve(cameraId: String): QuirkResolution

    companion object {
        /** No profile for any camera: every quirk is off. */
        val None = DeviceQuirks { QuirkResolution.NONE }
    }
}

/** One vendor request value the high-speed session needs, in the order it is applied. */
internal sealed interface HighSpeedSessionValue {
    val keyName: String

    class Bytes(override val keyName: String, val value: ByteArray) : HighSpeedSessionValue

    class Ints(override val keyName: String, val value: IntArray) : HighSpeedSessionValue
}

/** A physical camera behind a logical one that advertises the requested high-speed profile. */
internal data class PhysicalHighSpeedCandidate(val id: String, val focalLengthMm: Float?)

/**
 * The Motorola constrained high-speed decisions, kept free of Android types so they are host-tested.
 * Whether they apply to a device is the device profile's call, never read from here.
 */
internal object MotorolaHighSpeed {
    const val IS_CAMERA2_KEY = "com.lenovo.moto.clientapp.is_motcamera2"
    const val CURRENT_MODE_KEY = "com.lenovo.moto.clientapp.current_mode"
    const val SLOW_MOTION_MODE = 3

    /**
     * Motorola's own slow-motion client supplies both keys before configuring the constrained
     * session; a key the camera does not advertise is skipped.
     */
    fun sessionValues(advertisedKeyNames: Collection<String>): List<HighSpeedSessionValue> = buildList {
        if (advertisedKeyNames.any { it == IS_CAMERA2_KEY }) add(HighSpeedSessionValue.Bytes(IS_CAMERA2_KEY, byteArrayOf(1)))
        if (advertisedKeyNames.any { it == CURRENT_MODE_KEY }) {
            add(HighSpeedSessionValue.Ints(CURRENT_MODE_KEY, intArrayOf(SLOW_MOTION_MODE)))
        }
    }

    /**
     * The physical member whose focal length is closest to [targetFocalMm] (the first one on a tie,
     * in candidate order); one without a known focal length ranks last.
     */
    fun closestFocalPhysicalId(candidates: List<PhysicalHighSpeedCandidate>, targetFocalMm: Float?): String? =
        candidates.map { candidate ->
            val focal = candidate.focalLengthMm
            candidate.id to if (targetFocalMm != null && focal != null) abs(targetFocalMm - focal) else Float.MAX_VALUE
        }.minByOrNull { it.second }?.first
}
