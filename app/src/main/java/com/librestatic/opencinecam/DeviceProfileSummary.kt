/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import com.librestatic.opencinecam.core.model.ProfileBuildCheck
import com.librestatic.opencinecam.core.model.ProfileStatus
import com.librestatic.opencinecam.core.model.QuirkEffect
import com.librestatic.opencinecam.core.model.QuirkResolution
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** What the Capabilities page says about the device profile of one camera. */
internal enum class DeviceProfileState {
    /** No profile for this device and camera. */
    NONE,

    /** A profile verified on this build. */
    ACTIVE,

    /** A profile recorded on another build: its quirks still apply, with a warning. */
    ACTIVE_NOT_VERIFIED_ON_THIS_BUILD,

    /** A profile that failed its digest check; nothing applies. */
    REJECTED,
}

internal fun QuirkResolution.profileState(): DeviceProfileState = when {
    status == ProfileStatus.REJECTED -> DeviceProfileState.REJECTED
    status != ProfileStatus.ACTIVE -> DeviceProfileState.NONE
    buildCheck == ProfileBuildCheck.NOT_VERIFIED_ON_THIS_BUILD -> DeviceProfileState.ACTIVE_NOT_VERIFIED_ON_THIS_BUILD
    else -> DeviceProfileState.ACTIVE
}

internal fun DeviceProfileState.label(): Int = when (this) {
    DeviceProfileState.NONE -> R.string.caps_profile_none
    DeviceProfileState.ACTIVE -> R.string.caps_profile_active
    DeviceProfileState.ACTIVE_NOT_VERIFIED_ON_THIS_BUILD -> R.string.caps_profile_unverified
    DeviceProfileState.REJECTED -> R.string.caps_profile_rejected
}

internal fun QuirkEffect.label(): Int = when (this) {
    QuirkEffect.HIGH_SPEED_PREFER_PHYSICAL_CAMERA -> R.string.caps_quirk_high_speed_physical
    QuirkEffect.MOTO_HIGH_SPEED_SESSION_KEYS -> R.string.caps_quirk_moto_keys
}

/**
 * The report section for one camera. It names no fingerprint: the running build is already in the
 * report's `device` block, which the redactor hides, and the profile's recorded build adds nothing.
 */
internal fun deviceProfileJson(resolution: QuirkResolution): JsonObject = buildJsonObject {
    put("state", resolution.profileState().name)
    put("status", resolution.status.name)
    put("buildCheck", resolution.buildCheck.name)
    resolution.profileId?.let { put("profileId", it) }
    put("warnings", JsonArray(resolution.warnings.map(::JsonPrimitive)))
    put(
        "quirks",
        JsonArray(
            resolution.quirks.map { quirk ->
                buildJsonObject {
                    put("quirkId", quirk.quirkId)
                    put("effect", quirk.effect.id)
                    put("enabled", quirk.enabled)
                    put("source", quirk.source.name)
                }
            },
        ),
    )
}
