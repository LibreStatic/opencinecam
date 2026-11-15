/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import android.content.Context
import android.util.Log
import com.librestatic.opencinecam.camera.EqProfile
import com.librestatic.opencinecam.camera.decodeEqProfile

/** The asset folder the bundled equaliser profiles ship in (`app/src/main/profiles/eq-profiles`). */
internal const val BUNDLED_EQ_PROFILE_DIRECTORY = "eq-profiles"

/**
 * The equaliser profiles that ship with the app (PLAN-074 decision 8), each for one device
 * codename. They are read-only: a decoded profile is always marked bundled, whatever its file says.
 */
internal class BundledEqProfiles(val profiles: List<EqProfile>) {
    /** The profile for [device] (`Build.DEVICE`), or null when none ships for it. */
    fun forDevice(device: String): EqProfile? = profiles.firstOrNull { it.device == device }

    companion object {
        private const val TAG = "BundledEqProfiles"

        @Volatile private var loaded: BundledEqProfiles? = null

        /** Decodes each document; one that does not parse, has no device or repeats an id is logged and skipped. */
        fun fromDocuments(documents: Map<String, String>, log: (String) -> Unit = {}): BundledEqProfiles {
            val profiles = mutableListOf<EqProfile>()
            documents.toSortedMap().forEach { (name, text) ->
                val profile = decodeEqProfile(text)
                when {
                    profile == null -> log("bundled equaliser profile $name is invalid")
                    profile.device == null -> log("bundled equaliser profile $name names no device")
                    profiles.any { it.id == profile.id || it.device == profile.device } ->
                        log("bundled equaliser profile $name repeats an id or a device")
                    else -> profiles += profile.copy(bundled = true)
                }
            }
            return BundledEqProfiles(profiles)
        }

        /** Reads the folder once per process, on first use. It reads assets: never call it first on the main thread. */
        fun get(context: Context): BundledEqProfiles = loaded ?: synchronized(this) {
            loaded ?: load(context.applicationContext).also { loaded = it }
        }

        private fun load(context: Context): BundledEqProfiles {
            val assets = context.assets
            val documents = runCatching {
                assets.list(BUNDLED_EQ_PROFILE_DIRECTORY).orEmpty().filter { it.endsWith(".json") }.associateWith { name ->
                    assets.open("$BUNDLED_EQ_PROFILE_DIRECTORY/$name").use { it.readBytes().toString(Charsets.UTF_8) }
                }
            }.getOrElse {
                Log.w(TAG, "bundled equaliser profiles could not be read", it)
                emptyMap()
            }
            return fromDocuments(documents) { Log.w(TAG, it) }
        }
    }
}
