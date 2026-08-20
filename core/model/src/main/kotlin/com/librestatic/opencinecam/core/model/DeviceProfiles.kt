/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.core.model

import java.security.MessageDigest

enum class ProfileTrust {
    BUNDLED_TRUSTED,
    IMPORTED_UNTRUSTED,
    USER_OVERRIDE,
}

enum class ProfileStatus {
    ACTIVE,
    STALE,
    REJECTED,
    UNKNOWN,
}

enum class ProfileStoreStatus {
    STORED,
    DUPLICATE_PROFILE,
    CAPACITY_EXCEEDED,
    REJECTED_SCHEMA,
}

data class DeviceProfileScope(
    val buildFingerprint: String,
    val cameraId: String,
    val physicalCameraId: String?,
    val codecName: String?,
    val protocolVersion: String,
    val characteristicsDigest: String,
) {
    init {
        require(buildFingerprint.isNotBlank()) { "build fingerprint must not be blank" }
        require(cameraId.isNotBlank()) { "camera ID must not be blank" }
        require(physicalCameraId == null || physicalCameraId.isNotBlank()) {
            "physical camera ID must not be blank"
        }
        require(codecName == null || codecName.isNotBlank()) { "codec name must not be blank" }
        require(protocolVersion.isNotBlank()) { "protocol version must not be blank" }
        require(characteristicsDigest.isNotBlank()) { "characteristics digest must not be blank" }
    }
}

data class ProfileSignature(
    val algorithm: String,
    val keyId: String,
    val digestHex: String,
) {
    init {
        require(algorithm == "SHA-256") { "only SHA-256 profile signatures are supported" }
        require(keyId.isNotBlank()) { "signature key ID must not be blank" }
        require(digestHex.matches(Regex("[0-9a-fA-F]{64}"))) {
            "profile digest must be a 64-character hexadecimal SHA-256 value"
        }
    }
}

data class DeviceProfileQuirk(
    val quirkId: String,
    val effect: String,
    val enabled: Boolean,
) {
    init {
        require(quirkId.isNotBlank()) { "quirk ID must not be blank" }
        require(effect.isNotBlank()) { "quirk effect must not be blank" }
    }
}

data class DeviceProfile(
    val schemaVersion: String,
    val profileId: String,
    val source: String,
    val trust: ProfileTrust,
    val scope: DeviceProfileScope,
    val signature: ProfileSignature?,
    val quirks: List<DeviceProfileQuirk>,
) {
    init {
        require(profileId.isNotBlank()) { "profile ID must not be blank" }
        require(source.isNotBlank()) { "profile source must not be blank" }
        require(quirks.map { it.quirkId }.distinct().size == quirks.size) {
            "profile quirk IDs must be unique"
        }
        checkSchemaCompatibility(schemaVersion)
    }
}

data class ProfileVerification(
    val status: ProfileStatus,
    val expectedDigestHex: String,
    val signatureDigestHex: String?,
    val reason: String,
)

data class ProfileLookup(
    val profile: DeviceProfile?,
    val status: ProfileStatus,
    val reason: String,
)

object DeviceProfileVerifier {
    fun verify(profile: DeviceProfile): ProfileVerification {
        val expected = canonicalDigest(profile)
        val signature = profile.signature
        if (signature == null) {
            return ProfileVerification(
                status = if (profile.trust == ProfileTrust.BUNDLED_TRUSTED) {
                    ProfileStatus.REJECTED
                } else {
                    ProfileStatus.ACTIVE
                },
                expectedDigestHex = expected,
                signatureDigestHex = null,
                reason = if (profile.trust == ProfileTrust.USER_OVERRIDE) {
                    "narrow user override is retained without release signature"
                } else if (profile.trust == ProfileTrust.IMPORTED_UNTRUSTED) {
                    "imported profile is retained as untrusted"
                } else {
                    "trusted profile is missing a signature"
                },
            )
        }
        val matches = expected.equals(signature.digestHex, ignoreCase = true)
        return ProfileVerification(
            status = if (matches) ProfileStatus.ACTIVE else ProfileStatus.REJECTED,
            expectedDigestHex = expected,
            signatureDigestHex = signature.digestHex,
            reason = if (matches) "signature digest matches" else "signature digest mismatch",
        )
    }

    fun canonicalDigest(profile: DeviceProfile): String {
        val canonical = buildString {
            append(profile.schemaVersion).append('|')
            append(profile.profileId).append('|')
            append(profile.source).append('|')
            append(profile.trust.name).append('|')
            append(profile.scope.buildFingerprint).append('|')
            append(profile.scope.cameraId).append('|')
            append(profile.scope.physicalCameraId ?: "").append('|')
            append(profile.scope.codecName ?: "").append('|')
            append(profile.scope.protocolVersion).append('|')
            append(profile.scope.characteristicsDigest).append('|')
            profile.quirks.sortedBy { it.quirkId }.forEach { quirk ->
                append(quirk.quirkId).append('=').append(quirk.effect).append('=').append(quirk.enabled)
                    .append(';')
            }
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}

/** In-memory local profile storage; stale entries are retained for diagnostics. */
class DeviceProfileStore(private val maxProfiles: Int = 128) {
    private val profiles = LinkedHashMap<String, DeviceProfile>()

    init {
        require(maxProfiles > 0) { "maxProfiles must be positive" }
    }

    fun put(profile: DeviceProfile): ProfileStoreStatus {
        if (profiles.containsKey(profile.profileId)) return ProfileStoreStatus.DUPLICATE_PROFILE
        if (profiles.size >= maxProfiles) return ProfileStoreStatus.CAPACITY_EXCEEDED
        return try {
            checkSchemaCompatibility(profile.schemaVersion)
            profiles[profile.profileId] = profile
            ProfileStoreStatus.STORED
        } catch (_: UnsupportedSchemaMajor) {
            ProfileStoreStatus.REJECTED_SCHEMA
        }
    }

    fun lookup(scope: DeviceProfileScope): ProfileLookup {
        val matching = profiles.values.filter { it.scope == scope }
        if (matching.isEmpty()) {
            return ProfileLookup(null, ProfileStatus.UNKNOWN, "no exact profile scope match")
        }
        val selected = matching.maxBy { trustPriority(it.trust) }
        val verification = DeviceProfileVerifier.verify(selected)
        return ProfileLookup(selected, verification.status, verification.reason)
    }

    fun exactMatches(scope: DeviceProfileScope): List<DeviceProfile> =
        profiles.values.filter { it.scope == scope }.toList()

    fun invalidate(currentScope: DeviceProfileScope): List<ProfileLookup> = profiles.values.map { profile ->
        if (profile.scope == currentScope) {
            val verification = DeviceProfileVerifier.verify(profile)
            ProfileLookup(profile, verification.status, verification.reason)
        } else {
            ProfileLookup(profile, ProfileStatus.STALE, "fingerprint/camera/codec/protocol/digest changed")
        }
    }

    fun remove(profileId: String): Boolean = profiles.remove(profileId) != null

    fun clear() = profiles.clear()

    fun size(): Int = profiles.size

    private fun trustPriority(trust: ProfileTrust): Int = when (trust) {
        ProfileTrust.BUNDLED_TRUSTED -> 3
        ProfileTrust.USER_OVERRIDE -> 2
        ProfileTrust.IMPORTED_UNTRUSTED -> 1
    }
}
