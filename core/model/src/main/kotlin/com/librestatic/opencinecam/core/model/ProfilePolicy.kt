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
    val effect: String,
    val enabled: Boolean,
    val source: ProfileTrust,
)

data class QuirkResolution(
    val status: ProfileStatus,
    val quirks: List<EffectiveQuirk>,
    val warning: String? = null,
)

private data class OverrideKey(val scope: DeviceProfileScope, val quirkId: String)

/** Applies bundled/imported precedence and only narrowing user kill switches. */
class ProfilePolicyEngine(
    private val store: DeviceProfileStore,
    private val maxOverrides: Int = 128,
) {
    private val killSwitches = LinkedHashMap<OverrideKey, String>()

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
        scope: DeviceProfileScope,
        quirkId: String,
        effect: String,
        enabled: Boolean,
    ): OverrideStatus {
        require(quirkId.isNotBlank()) { "quirk ID must not be blank" }
        require(effect.isNotBlank()) { "quirk effect must not be blank" }
        if (enabled) return OverrideStatus.REJECTED_BROADENING
        val key = OverrideKey(scope, quirkId)
        if (killSwitches.containsKey(key)) return OverrideStatus.DUPLICATE_OVERRIDE
        if (killSwitches.size >= maxOverrides) return OverrideStatus.CAPACITY_EXCEEDED
        killSwitches[key] = effect
        return OverrideStatus.APPLIED
    }

    fun resolve(scope: DeviceProfileScope): QuirkResolution {
        val matches = store.exactMatches(scope)
        if (matches.isEmpty()) {
            return QuirkResolution(ProfileStatus.UNKNOWN, emptyList(), "no exact profile scope match")
        }
        val effective = LinkedHashMap<String, EffectiveQuirk>()
        matches
            .filter { DeviceProfileVerifier.verify(it).status == ProfileStatus.ACTIVE }
            .sortedBy { trustPriority(it.trust) }
            .forEach { profile ->
                profile.quirks.forEach { quirk ->
                    effective[quirk.quirkId] = EffectiveQuirk(
                        quirk.quirkId,
                        quirk.effect,
                        quirk.enabled,
                        profile.trust,
                    )
                }
            }
        killSwitches.filterKeys { it.scope == scope }.forEach { (key, effect) ->
            effective[key.quirkId] = EffectiveQuirk(
                key.quirkId,
                effect,
                enabled = false,
                source = ProfileTrust.USER_OVERRIDE,
            )
        }
        if (effective.isEmpty()) {
            return QuirkResolution(ProfileStatus.ACTIVE, emptyList(), "scope matched but no active quirks")
        }
        return QuirkResolution(ProfileStatus.ACTIVE, effective.values.sortedBy { it.quirkId })
    }

    fun clearOverrides() = killSwitches.clear()

    fun overrideCount(): Int = killSwitches.size

    private fun trustPriority(trust: ProfileTrust): Int = when (trust) {
        ProfileTrust.IMPORTED_UNTRUSTED -> 1
        ProfileTrust.USER_OVERRIDE -> 2
        ProfileTrust.BUNDLED_TRUSTED -> 3
    }
}
