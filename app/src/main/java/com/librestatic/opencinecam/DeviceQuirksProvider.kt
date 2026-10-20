/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import android.content.Context
import android.os.Build
import android.util.Log
import com.librestatic.opencinecam.camera.DeviceQuirks
import com.librestatic.opencinecam.core.model.DeviceProfile
import com.librestatic.opencinecam.core.model.DeviceProfileCodec
import com.librestatic.opencinecam.core.model.DeviceProfileDecoding
import com.librestatic.opencinecam.core.model.DeviceProfileQuery
import com.librestatic.opencinecam.core.model.DeviceProfileStore
import com.librestatic.opencinecam.core.model.DeviceProfileVerifier
import com.librestatic.opencinecam.core.model.ProfilePolicyEngine
import com.librestatic.opencinecam.core.model.ProfileStatus
import com.librestatic.opencinecam.core.model.ProfileStoreStatus
import com.librestatic.opencinecam.core.model.QuirkResolution

/** The asset folder the bundled profiles ship in (`app/src/main/profiles/device-profiles`). */
internal const val BUNDLED_PROFILE_DIRECTORY = "device-profiles"

/**
 * The bundled device profiles resolved for the phone this process runs on. Profiles are matched by
 * device codename (`Build.DEVICE`) and camera; the build fingerprint only decides whether a
 * match is reported as verified on this build.
 */
internal class BundledDeviceQuirks(
    profiles: List<DeviceProfile>,
    private val device: String,
    private val buildFingerprint: String,
    private val log: (String) -> Unit = {},
) : DeviceQuirks {
    private val engine = ProfilePolicyEngine(DeviceProfileStore())

    init {
        // A profile that fails its digest is stored on purpose: it resolves as REJECTED, which Capabilities shows.
        profiles.forEach { profile ->
            val stored = engine.loadBundled(profile)
            val verification = DeviceProfileVerifier.verify(profile)
            if (stored != ProfileStoreStatus.STORED || verification.status != ProfileStatus.ACTIVE) {
                log("bundled profile ${profile.profileId}: $stored, ${verification.reason}")
            }
        }
    }

    // The policy engine is a plain map store; Capabilities (IO) and the camera thread both ask.
    @Synchronized
    override fun resolve(cameraId: String): QuirkResolution =
        engine.resolve(DeviceProfileQuery(device, buildFingerprint, cameraId))

    companion object {
        /** Decodes each document; one that does not parse is logged and skipped, never half applied. */
        fun fromDocuments(
            documents: Map<String, String>,
            device: String,
            buildFingerprint: String,
            log: (String) -> Unit = {},
        ): BundledDeviceQuirks {
            val profiles = documents.mapNotNull { (name, text) ->
                when (val decoded = DeviceProfileCodec.decode(text)) {
                    is DeviceProfileDecoding.Decoded -> decoded.profile
                    is DeviceProfileDecoding.Invalid -> {
                        log("bundled profile $name is invalid: ${decoded.reason}")
                        null
                    }
                }
            }
            return BundledDeviceQuirks(profiles, device, buildFingerprint, log)
        }
    }
}

/** Loads the bundled profiles once per process, on first use (never at construction on the main thread). */
internal object DeviceQuirksProvider {
    private const val TAG = "DeviceQuirks"

    @Volatile private var loaded: BundledDeviceQuirks? = null

    fun get(context: Context): BundledDeviceQuirks = loaded ?: synchronized(this) {
        loaded ?: load(context.applicationContext).also { loaded = it }
    }

    /** The lazy handle the capture engine takes: the first lookup, on its own thread, reads the assets. */
    fun lazy(context: Context): DeviceQuirks {
        val app = context.applicationContext
        return DeviceQuirks { cameraId -> get(app).resolve(cameraId) }
    }

    private fun load(context: Context): BundledDeviceQuirks {
        val assets = context.assets
        val documents = runCatching {
            assets.list(BUNDLED_PROFILE_DIRECTORY).orEmpty().filter { it.endsWith(".json") }.sorted().associateWith { name ->
                assets.open("$BUNDLED_PROFILE_DIRECTORY/$name").use { it.readBytes().toString(Charsets.UTF_8) }
            }
        }.getOrElse {
            Log.w(TAG, "bundled device profiles could not be read", it)
            emptyMap()
        }
        return BundledDeviceQuirks.fromDocuments(documents, Build.DEVICE, Build.FINGERPRINT) { Log.w(TAG, it) }
    }
}
