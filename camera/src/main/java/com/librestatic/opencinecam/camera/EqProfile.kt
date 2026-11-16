/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

/**
 * A named input equaliser curve (PLAN-074 decision 8). A [bundled] profile ships with the app for one
 * [device] (its `Build.DEVICE` codename) and is read-only; a user profile has no device.
 * `CameraSettings` keeps the active profile's [id] and a copy of its [curve], so the read loops and
 * the take record never need the profile itself.
 */
data class EqProfile(
    val id: String,
    val name: String,
    val curve: EqCurve,
    val bundled: Boolean = false,
    val device: String? = null,
) {
    init {
        require(isValidEqProfileId(id)) { "An equaliser profile id is 1 to 64 letters, digits, '.', '_' or '-'." }
        require(name.isNotBlank() && name.length <= MAX_NAME_LENGTH && name.none(Char::isISOControl)) {
            "An equaliser profile name has 1 to $MAX_NAME_LENGTH readable characters."
        }
        require(device == null || (device.isNotBlank() && device.length <= MAX_NAME_LENGTH && device.none(Char::isISOControl))) {
            "An equaliser profile device is a non-blank codename."
        }
    }

    companion object {
        const val MAX_NAME_LENGTH = 64
    }
}

private val EQ_PROFILE_ID = Regex("[A-Za-z0-9_.-]{1,64}")

/** Every bundled profile's id starts with this, and no user profile's does. */
const val BUNDLED_EQ_PROFILE_ID_PREFIX = "bundled-"

/** Whether [id] names a profile that ships with the app: a curve measured for the phone's raw microphones. */
fun isBundledEqProfileId(id: String?): Boolean = id?.startsWith(BUNDLED_EQ_PROFILE_ID_PREFIX) == true

/** Whether [id] can name an [EqProfile]; a stored or imported id that cannot is dropped. */
fun isValidEqProfileId(id: String): Boolean = EQ_PROFILE_ID.matches(id)
