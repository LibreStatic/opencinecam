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

/**
 * The closed set of behaviours a quirk may switch on. A profile names one by [id]; an id this build
 * does not know is ignored with a warning and never applied.
 */
enum class QuirkEffect(val id: String) {
    /** Open a constrained high-speed session on the physical camera that owns the HFR mode. */
    HIGH_SPEED_PREFER_PHYSICAL_CAMERA("high-speed-prefer-physical-camera"),

    /** Supply the Motorola slow-motion client keys on constrained high-speed requests. */
    MOTO_HIGH_SPEED_SESSION_KEYS("moto-high-speed-session-keys"),
    ;

    companion object {
        fun fromId(id: String): QuirkEffect? = entries.firstOrNull { it.id == id }
    }
}

/**
 * What a profile applies to: one device model (the codename, `Build.DEVICE`) and one camera.
 * Manufacturer-wide scopes do not exist. [buildFingerprint] is the build the profile was verified
 * on; it is recorded but is not part of matching, so a profile survives an OTA with a warning.
 * [physicalCameraId] and [codecName] narrow the match only when set. [protocolVersion] and
 * [characteristicsDigest] are recorded evidence and are not matched.
 */
data class DeviceProfileScope(
    val device: String,
    val buildFingerprint: String,
    val cameraId: String,
    val physicalCameraId: String?,
    val codecName: String?,
    val protocolVersion: String,
    val characteristicsDigest: String?,
) {
    init {
        require(device.isNotBlank()) { "device codename must not be blank" }
        require(device != "*") { "a wildcard device codename is not a profile scope" }
        require(buildFingerprint.isNotBlank()) { "build fingerprint must not be blank" }
        require(cameraId.isNotBlank()) { "camera ID must not be blank" }
        require(physicalCameraId == null || physicalCameraId.isNotBlank()) {
            "physical camera ID must not be blank"
        }
        require(codecName == null || codecName.isNotBlank()) { "codec name must not be blank" }
        require(protocolVersion.isNotBlank()) { "protocol version must not be blank" }
        require(characteristicsDigest == null || characteristicsDigest.isNotBlank()) {
            "characteristics digest must not be blank"
        }
    }

    fun matches(query: DeviceProfileQuery): Boolean = device == query.device &&
        cameraId == query.cameraId &&
        (physicalCameraId == null || physicalCameraId == query.physicalCameraId) &&
        (codecName == null || codecName == query.codecName)
}

/** The running device and camera a profile is looked up for. */
data class DeviceProfileQuery(
    val device: String,
    val buildFingerprint: String,
    val cameraId: String,
    val physicalCameraId: String? = null,
    val codecName: String? = null,
) {
    init {
        require(device.isNotBlank()) { "device codename must not be blank" }
        require(cameraId.isNotBlank()) { "camera ID must not be blank" }
    }
}

/** Whether the running build is the one the selected profile was verified on. */
enum class ProfileBuildCheck {
    VERIFIED,
    NOT_VERIFIED_ON_THIS_BUILD,
    NO_PROFILE,
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

/** [effect] is the stable id from JSON; it is kept as text so an id this build lacks survives to be warned about. */
data class DeviceProfileQuirk(
    val quirkId: String,
    val effect: String,
    val enabled: Boolean,
) {
    init {
        require(quirkId.isNotBlank()) { "quirk ID must not be blank" }
        require(effect.isNotBlank()) { "quirk effect must not be blank" }
    }

    /** The typed effect, or null when this build does not know the id. */
    val knownEffect: QuirkEffect? get() = QuirkEffect.fromId(effect)
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
    val buildCheck: ProfileBuildCheck = ProfileBuildCheck.NO_PROFILE,
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
            append(profile.scope.device).append('|')
            append(profile.scope.buildFingerprint).append('|')
            append(profile.scope.cameraId).append('|')
            append(profile.scope.physicalCameraId ?: "").append('|')
            append(profile.scope.codecName ?: "").append('|')
            append(profile.scope.protocolVersion).append('|')
            append(profile.scope.characteristicsDigest ?: "").append('|')
            profile.quirks.sortedBy { it.quirkId }.forEach { quirk ->
                append(quirk.quirkId).append('=').append(quirk.effect).append('=').append(quirk.enabled)
                    .append(';')
            }
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}

/** In-memory local profile storage; entries that stop matching are retained for diagnostics. */
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

    /**
     * The best profile for [query]: the device and camera match, a build fingerprint that differs
     * from the recorded one keeps the profile active but flags it as not verified on this build.
     */
    fun lookup(query: DeviceProfileQuery): ProfileLookup {
        val selected = matches(query).maxWithOrNull(
            compareBy<DeviceProfile>({ trustPriority(it.trust) }, { it.scope.buildFingerprint == query.buildFingerprint }),
        ) ?: return ProfileLookup(null, ProfileStatus.UNKNOWN, "no profile for this device and camera")
        val verification = DeviceProfileVerifier.verify(selected)
        val check = buildCheck(selected, query)
        val reason = if (verification.status == ProfileStatus.ACTIVE && check == ProfileBuildCheck.NOT_VERIFIED_ON_THIS_BUILD) {
            "${verification.reason}; recorded for another build"
        } else {
            verification.reason
        }
        return ProfileLookup(selected, verification.status, reason, check)
    }

    fun matches(query: DeviceProfileQuery): List<DeviceProfile> =
        profiles.values.filter { it.scope.matches(query) }.toList()

    /** Re-evaluates every stored profile; one that no longer matches the device or camera is STALE. */
    fun invalidate(current: DeviceProfileQuery): List<ProfileLookup> = profiles.values.map { profile ->
        if (profile.scope.matches(current)) {
            val verification = DeviceProfileVerifier.verify(profile)
            ProfileLookup(profile, verification.status, verification.reason, buildCheck(profile, current))
        } else {
            ProfileLookup(profile, ProfileStatus.STALE, "device/camera/codec changed")
        }
    }

    fun remove(profileId: String): Boolean = profiles.remove(profileId) != null

    fun clear() = profiles.clear()

    fun size(): Int = profiles.size

    private fun buildCheck(profile: DeviceProfile, query: DeviceProfileQuery) =
        if (profile.scope.buildFingerprint == query.buildFingerprint) {
            ProfileBuildCheck.VERIFIED
        } else {
            ProfileBuildCheck.NOT_VERIFIED_ON_THIS_BUILD
        }

    private fun trustPriority(trust: ProfileTrust): Int = when (trust) {
        ProfileTrust.BUNDLED_TRUSTED -> 3
        ProfileTrust.USER_OVERRIDE -> 2
        ProfileTrust.IMPORTED_UNTRUSTED -> 1
    }
}
