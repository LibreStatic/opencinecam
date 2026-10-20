/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.core.model

enum class OverrideStatus {
    APPLIED,
    DUPLICATE_OVERRIDE,
    REJECTED_BROADENING,
    CAPACITY_EXCEEDED,
}

data class EffectiveQuirk(
    val quirkId: String,
    val effect: QuirkEffect,
    val enabled: Boolean,
    val source: ProfileTrust,
)

/**
 * What the profiles say about one device and camera. [profileId] is the highest-priority active
 * profile; [buildCheck] says whether the running build is the one it was verified on (a drifted
 * build stays [ProfileStatus.ACTIVE] and carries a warning).
 */
data class QuirkResolution(
    val status: ProfileStatus,
    val quirks: List<EffectiveQuirk>,
    val warnings: List<String> = emptyList(),
    val profileId: String? = null,
    val buildCheck: ProfileBuildCheck = ProfileBuildCheck.NO_PROFILE,
) {
    /** True when an active profile enables [effect] and no kill switch has turned it off. */
    fun applies(effect: QuirkEffect): Boolean =
        status == ProfileStatus.ACTIVE && quirks.any { it.effect == effect && it.enabled }

    companion object {
        val NONE = QuirkResolution(ProfileStatus.UNKNOWN, emptyList())
    }
}

/** A kill switch outlives an OTA: it is keyed by device, camera and quirk, not by build. */
private data class OverrideKey(val device: String, val cameraId: String, val quirkId: String)

/** Applies bundled/imported precedence and only narrowing user kill switches. */
class ProfilePolicyEngine(
    private val store: DeviceProfileStore,
    private val maxOverrides: Int = 128,
) {
    private val killSwitches = LinkedHashMap<OverrideKey, QuirkEffect>()

    init {
        require(maxOverrides > 0) { "maxOverrides must be positive" }
    }

    fun loadBundled(profile: DeviceProfile): ProfileStoreStatus {
        if (profile.trust != ProfileTrust.BUNDLED_TRUSTED) {
            return ProfileStoreStatus.REJECTED_SCHEMA
        }
        return store.put(profile)
    }

    fun importUntrusted(profile: DeviceProfile): ProfileStoreStatus =
        store.put(profile.copy(trust = ProfileTrust.IMPORTED_UNTRUSTED, signature = null))

    fun setUserKillSwitch(
        query: DeviceProfileQuery,
        quirkId: String,
        effect: QuirkEffect,
        enabled: Boolean,
    ): OverrideStatus {
        require(quirkId.isNotBlank()) { "quirk ID must not be blank" }
        if (enabled) return OverrideStatus.REJECTED_BROADENING
        val key = OverrideKey(query.device, query.cameraId, quirkId)
        if (killSwitches.containsKey(key)) return OverrideStatus.DUPLICATE_OVERRIDE
        if (killSwitches.size >= maxOverrides) return OverrideStatus.CAPACITY_EXCEEDED
        killSwitches[key] = effect
        return OverrideStatus.APPLIED
    }

    fun resolve(query: DeviceProfileQuery): QuirkResolution {
        val matches = store.matches(query)
        if (matches.isEmpty()) return QuirkResolution.NONE
        val warnings = mutableListOf<String>()
        val active = matches.filter { profile ->
            val verification = DeviceProfileVerifier.verify(profile)
            if (verification.status != ProfileStatus.ACTIVE) {
                warnings += "profile ${profile.profileId} ignored: ${verification.reason}"
            }
            verification.status == ProfileStatus.ACTIVE
        }.sortedWith(
            compareBy<DeviceProfile>({ trustPriority(it.trust) }, { it.scope.buildFingerprint == query.buildFingerprint }),
        )
        if (active.isEmpty()) return QuirkResolution(ProfileStatus.REJECTED, emptyList(), warnings)
        val verified = active.any { it.scope.buildFingerprint == query.buildFingerprint }
        val buildCheck = if (verified) ProfileBuildCheck.VERIFIED else ProfileBuildCheck.NOT_VERIFIED_ON_THIS_BUILD
        if (!verified) warnings += "not verified on this build: the profile was recorded for another build fingerprint"
        val effective = LinkedHashMap<String, EffectiveQuirk>()
        active.forEach { profile ->
            profile.quirks.forEach { quirk ->
                val effect = quirk.knownEffect
                if (effect == null) {
                    warnings += "profile ${profile.profileId} quirk ${quirk.quirkId}: unknown effect '${quirk.effect}' ignored"
                } else {
                    effective[quirk.quirkId] = EffectiveQuirk(quirk.quirkId, effect, quirk.enabled, profile.trust)
                }
            }
        }
        killSwitches.filterKeys { it.device == query.device && it.cameraId == query.cameraId }.forEach { (key, effect) ->
            effective[key.quirkId] = EffectiveQuirk(key.quirkId, effect, enabled = false, source = ProfileTrust.USER_OVERRIDE)
        }
        if (effective.isEmpty()) warnings += "profile matched but no active quirks"
        return QuirkResolution(
            ProfileStatus.ACTIVE,
            effective.values.sortedBy { it.quirkId },
            warnings,
            active.last().profileId,
            buildCheck,
        )
    }

    fun clearOverrides() = killSwitches.clear()

    fun overrideCount(): Int = killSwitches.size

    private fun trustPriority(trust: ProfileTrust): Int = when (trust) {
        ProfileTrust.IMPORTED_UNTRUSTED -> 1
        ProfileTrust.USER_OVERRIDE -> 2
        ProfileTrust.BUNDLED_TRUSTED -> 3
    }
}
