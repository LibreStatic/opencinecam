/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import com.librestatic.opencinecam.camera.EqCurve
import com.librestatic.opencinecam.camera.EqFilterType
import com.librestatic.opencinecam.camera.EqPoint
import com.librestatic.opencinecam.camera.EqProfile
import com.librestatic.opencinecam.media.audio.AudioBitDepth
import com.librestatic.opencinecam.media.audio.AudioOutputFormat
import com.librestatic.opencinecam.media.audio.AudioSourceSelection
import com.librestatic.opencinecam.media.audio.AudioSourceSelection.CAMCORDER
import com.librestatic.opencinecam.media.audio.AudioSourceSelection.LOW_LATENCY
import com.librestatic.opencinecam.media.audio.AudioSourceSelection.MIC
import com.librestatic.opencinecam.media.audio.AudioSourceSelection.UNPROCESSED
import com.librestatic.opencinecam.media.audio.AudioSourceSelection.VOICE_RECOGNITION
import com.librestatic.opencinecam.media.audio.PcmAudioConfiguration
import com.librestatic.opencinecam.media.audio.ProfessionalAudioCapabilities
import com.librestatic.opencinecam.settings.appliedAudioEqCurve
import com.librestatic.opencinecam.settings.appliedAudioEqProfileId
import com.librestatic.opencinecam.settings.AudioInputDefault
import com.librestatic.opencinecam.settings.audioInputDefault
import com.librestatic.opencinecam.settings.AudioInputPolicy
import com.librestatic.opencinecam.settings.CameraSettings
import com.librestatic.opencinecam.settings.CameraSettingsStore
import com.librestatic.opencinecam.settings.forDevice
import com.librestatic.opencinecam.settings.migrateAudioInput
import com.librestatic.opencinecam.settings.MigratedAudioInput
import com.librestatic.opencinecam.settings.normalizedFor
import com.librestatic.opencinecam.settings.PresetPreferences
import com.librestatic.opencinecam.settings.remappedAudioSource
import com.librestatic.opencinecam.settings.SettingsRepository
import com.librestatic.opencinecam.settings.settledAudioInput
import com.librestatic.opencinecam.settings.StoredAudioInput
import com.librestatic.opencinecam.settings.withMigratedAudioInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** PLAN-074 decision 1: the audio input a phone starts with, the one-time migration and the UNPROCESSED remap. */
class AudioSourceDefaultsTest {
    private val razrProfile = EqProfile(
        id = "bundled-motorola-blanc",
        name = "Razr Fold microphones",
        curve = EqCurve(points = listOf(EqPoint(EqFilterType.BELL, 8_800.0, -13.0, 1.25))),
        bundled = true,
        device = "blanc",
    )
    private val razr = AudioInputPolicy(lowLatencyDefault = true, unprocessedClips = true, lowLatencyAvailable = true, bundledProfile = razrProfile)

    // ---- the default -------------------------------------------------------------------------

    @Test
    fun theDefaultsAreCamcorderStereoAtTheHighestAacRateWithNoProfile() {
        val settings = CameraSettings()
        assertEquals(CAMCORDER, settings.audioSource)
        assertEquals(2, settings.audioChannels)
        assertEquals(320, settings.audioBitrateKbps)
        assertNull(settings.audioEqProfileId)
        assertEquals(EqCurve(), settings.audioEqCurve)
        val loaded = CameraSettingsStore(PresetPreferences()).load()
        assertEquals(CAMCORDER, loaded.audioSource)
        assertEquals(320, loaded.audioBitrateKbps)
        assertNull(loaded.audioEqProfileId)
    }

    @Test
    fun theLowLatencyDefaultNeedsTheQuirkAndTheLibrary() {
        assertEquals(AudioInputDefault(LOW_LATENCY, razrProfile.id, razrProfile.curve), audioInputDefault(true, true, razrProfile))
        // The quirk without a bundled profile still picks the low-latency input, flat.
        assertEquals(AudioInputDefault(LOW_LATENCY, null, EqCurve()), audioInputDefault(true, true, null))
        // Without the library, or without the quirk, Camcorder and flat whatever profile ships.
        assertEquals(AudioInputDefault(CAMCORDER, null, EqCurve()), audioInputDefault(true, false, razrProfile))
        assertEquals(AudioInputDefault(CAMCORDER, null, EqCurve()), audioInputDefault(false, true, razrProfile))
        assertEquals(AudioInputDefault(CAMCORDER, null, EqCurve()), audioInputDefault(false, false, null))
        assertEquals(AudioInputDefault(CAMCORDER, null, EqCurve()), AudioInputPolicy.NONE.default)
        assertEquals(LOW_LATENCY, razr.default.source)
    }

    // ---- the UNPROCESSED remap -------------------------------------------------------------

    @Test
    fun unprocessedIsRemappedOnlyWhereItClips() {
        assertEquals(LOW_LATENCY, remappedAudioSource(UNPROCESSED, unprocessedClips = true, lowLatencyAvailable = true))
        assertEquals(CAMCORDER, remappedAudioSource(UNPROCESSED, unprocessedClips = true, lowLatencyAvailable = false))
        assertEquals(UNPROCESSED, remappedAudioSource(UNPROCESSED, unprocessedClips = false, lowLatencyAvailable = true))
        for (source in AudioSourceSelection.entries - UNPROCESSED) {
            assertEquals(source, remappedAudioSource(source, unprocessedClips = true, lowLatencyAvailable = true))
        }
    }

    @Test
    fun theRemapAppliesOnEveryLoadAfterTheMigration() {
        val chosen = CameraSettings(audioSource = UNPROCESSED, audioChannels = 2)
        // stored == null: the migration already ran.
        assertEquals(LOW_LATENCY, chosen.settledAudioInput(null, razr).audioSource)
        assertEquals(CAMCORDER, chosen.settledAudioInput(null, razr.copy(lowLatencyAvailable = false)).audioSource)
        assertEquals(UNPROCESSED, chosen.settledAudioInput(null, AudioInputPolicy.NONE).audioSource)
    }

    @Test
    fun aRemapOntoTheLowLatencyInputBringsTheDefaultCurveUnlessTheUserHasOne() {
        val remapped = CameraSettings(audioSource = UNPROCESSED, audioChannels = 2).settledAudioInput(null, razr)
        assertEquals(razrProfile.id, remapped.audioEqProfileId)
        assertEquals(razrProfile.curve, remapped.audioEqCurve)
        val own = EqCurve(trimDb = -3.0)
        val kept = CameraSettings(audioSource = UNPROCESSED, audioChannels = 2, audioEqCurve = own).settledAudioInput(null, razr)
        assertEquals(LOW_LATENCY, kept.audioSource)
        assertNull(kept.audioEqProfileId)
        assertEquals(own, kept.audioEqCurve)
        // Camcorder carries the vendor's equalisation: no curve comes with that remap.
        val camcorder = CameraSettings(audioSource = UNPROCESSED, audioChannels = 2).settledAudioInput(null, razr.copy(lowLatencyAvailable = false))
        assertEquals(EqCurve(), camcorder.audioEqCurve)
        // A low-latency input the user left flat is not a remap, so it stays flat.
        assertEquals(EqCurve(), CameraSettings(audioSource = LOW_LATENCY).settledAudioInput(null, razr).audioEqCurve)
    }

    // ---- the one-time migration ------------------------------------------------------------

    @Test
    fun aFreshInstallMovesToTheDefault() {
        val moved = migrateAudioInput(StoredAudioInput(null, null, null), razr.default)
        assertEquals(MigratedAudioInput.Moved(LOW_LATENCY, 2, 320, razrProfile.id, razrProfile.curve), moved)
        assertEquals(MigratedAudioInput.Moved(CAMCORDER, 2, 320, null, EqCurve()),
            migrateAudioInput(StoredAudioInput(null, null, null), AudioInputPolicy.NONE.default))
    }

    @Test
    fun an100InstallThatNeverTouchedAudioMovesToTheDefault() {
        for (channels in listOf(1, null)) {
            val moved = migrateAudioInput(StoredAudioInput(UNPROCESSED, channels, 128), razr.default)
            assertEquals(MigratedAudioInput.Moved(LOW_LATENCY, 2, 320, razrProfile.id, razrProfile.curve), moved)
        }
        val elsewhere = migrateAudioInput(StoredAudioInput(UNPROCESSED, 1, 128), AudioInputPolicy.NONE.default)
        assertEquals(MigratedAudioInput.Moved(CAMCORDER, 2, 320, null, EqCurve()), elsewhere)
    }

    @Test
    fun theAacRateMovesOnlyFromTheOldDefaultOrAbsence() {
        fun rate(stored: Int?) = (migrateAudioInput(StoredAudioInput(UNPROCESSED, 1, stored), razr.default) as MigratedAudioInput.Moved).bitrateKbps
        assertEquals(320, rate(128))
        assertEquals(320, rate(null))
        // A rate the user chose is kept (null keeps the stored value).
        assertNull(rate(96))
        assertNull(rate(256))
        assertEquals(96, CameraSettings(audioBitrateKbps = 96).withMigratedAudioInput(
            migrateAudioInput(StoredAudioInput(UNPROCESSED, 1, 96), razr.default)).audioBitrateKbps)
    }

    @Test
    fun anyOtherStoredCombinationIsAChoiceAndIsKept() {
        val choices = listOf(
            StoredAudioInput(UNPROCESSED, 2, 128),
            StoredAudioInput(MIC, 1, 128),
            StoredAudioInput(CAMCORDER, 1, 128),
            StoredAudioInput(VOICE_RECOGNITION, null, null),
            StoredAudioInput(LOW_LATENCY, 2, 192),
        )
        for (stored in choices) assertEquals(stored.toString(), MigratedAudioInput.Kept, migrateAudioInput(stored, razr.default))
        val settings = CameraSettings(audioSource = MIC, audioChannels = 1, audioBitrateKbps = 128)
        assertEquals(settings, settings.withMigratedAudioInput(MigratedAudioInput.Kept))
    }

    @Test
    fun aChosenUnprocessedStereoInputIsKeptButRemappedWhereItClips() {
        val stereo = CameraSettings(audioSource = UNPROCESSED, audioChannels = 2, audioBitrateKbps = 128)
        val settled = stereo.settledAudioInput(StoredAudioInput(UNPROCESSED, 2, 128), razr)
        assertEquals(LOW_LATENCY, settled.audioSource)
        assertEquals(2, settled.audioChannels)
        assertEquals(128, settled.audioBitrateKbps)
        // The remap brings the phone's curve; the channels and the rate are the user's.
        assertEquals(razrProfile.id, settled.audioEqProfileId)
        assertEquals(stereo, stereo.settledAudioInput(StoredAudioInput(UNPROCESSED, 2, 128), AudioInputPolicy.NONE))
    }

    @Test
    fun theMigrationRunsOnceThroughTheSettingsStore() {
        // A 1.0.0 install that never touched audio: everything saved with 1.0.0's defaults.
        val preferences = PresetPreferences(mapOf("audio-source" to "UNPROCESSED", "audio-channels" to 1, "audio-bitrate-kbps" to 128))
        val store = CameraSettingsStore(preferences)
        val repository = SettingsRepository(store)
        assertEquals(StoredAudioInput(UNPROCESSED, 1, 128), store.pendingAudioInputMigration())
        // A save before the migration runs does not hide the snapshot the first load took.
        repository.update { it.copy(torchStrengthLevel = 3) }
        assertEquals(StoredAudioInput(UNPROCESSED, 1, 128), store.pendingAudioInputMigration())

        repository.settleAudioInput(razr)
        val settled = repository.states.value
        assertEquals(LOW_LATENCY, settled.audioSource)
        assertEquals(2, settled.audioChannels)
        assertEquals(320, settled.audioBitrateKbps)
        assertEquals(razrProfile.id, settled.audioEqProfileId)
        assertEquals(razrProfile.curve, settled.audioEqCurve)
        assertEquals(true, preferences.getBoolean("audio-input-migrated-v1", false))
        assertNull(store.pendingAudioInputMigration())

        // The user then picks UNPROCESSED mono by hand: the next process keeps it (remapped where it clips).
        repository.update { it.copy(audioSource = UNPROCESSED, audioChannels = 1, audioBitrateKbps = 128) }
        val nextStore = CameraSettingsStore(preferences)
        val next = SettingsRepository(nextStore)
        assertNull(nextStore.pendingAudioInputMigration())
        next.settleAudioInput(AudioInputPolicy.NONE)
        assertEquals(UNPROCESSED, next.states.value.audioSource)
        assertEquals(1, next.states.value.audioChannels)
        assertEquals(128, next.states.value.audioBitrateKbps)
        next.settleAudioInput(razr)
        assertEquals(LOW_LATENCY, next.states.value.audioSource)
        assertEquals(1, next.states.value.audioChannels)
    }

    @Test
    fun aFreshInstallMigratesThroughTheStoreToo() {
        val preferences = PresetPreferences()
        val store = CameraSettingsStore(preferences)
        val repository = SettingsRepository(store)
        assertEquals(StoredAudioInput(null, null, null), store.pendingAudioInputMigration())
        repository.settleAudioInput(AudioInputPolicy.NONE)
        assertEquals(CAMCORDER, repository.states.value.audioSource)
        assertEquals(2, repository.states.value.audioChannels)
        assertEquals(320, repository.states.value.audioBitrateKbps)
        assertTrue(preferences.getBoolean("audio-input-migrated-v1", false))
        // The defaults already are the migrated values, so nothing else is written.
        assertNull(store.pendingAudioInputMigration())
    }

    // ---- what the phone offers -------------------------------------------------------------

    private val capabilities = ProfessionalAudioCapabilities(
        permissionGranted = true,
        formats = listOf(AudioOutputFormat.AAC_MP4, AudioOutputFormat.WAV_PCM),
        sampleRates = listOf(48_000),
        bitDepths = listOf(AudioBitDepth.PCM_16),
        channelCounts = listOf(1, 2),
        pcmConfigurations = listOf(PcmAudioConfiguration(48_000, AudioBitDepth.PCM_16, 2)),
        aacSampleRates = listOf(48_000),
        aacChannelCounts = listOf(1, 2),
        aacBitratesKbps = listOf(96, 128, 192),
        sources = listOf(UNPROCESSED, CAMCORDER, MIC, LOW_LATENCY),
        inputs = emptyList(),
        noiseSuppressorAvailable = true,
        automaticGainControlAvailable = true,
        acousticEchoCancelerAvailable = true,
    )

    @Test
    fun unprocessedIsNotOfferedWhereItClips() {
        val offered = capabilities.forDevice(razr)
        assertFalse(UNPROCESSED in offered.sources)
        assertEquals(LOW_LATENCY, offered.preferredSource)
        assertEquals(listOf(UNPROCESSED, CAMCORDER, MIC, LOW_LATENCY), capabilities.forDevice(AudioInputPolicy.NONE).sources)
        assertNull(capabilities.forDevice(AudioInputPolicy.NONE).preferredSource)
        // A stored UNPROCESSED can never survive normalisation on such a phone.
        assertEquals(LOW_LATENCY, CameraSettings(audioSource = UNPROCESSED).normalizedFor(offered).audioSource)
    }

    @Test
    fun anUnofferedSourceFallsBackToLowLatencyThenCamcorderThenTheFirst() {
        val offered = capabilities.forDevice(razr)
        assertEquals(LOW_LATENCY, CameraSettings(audioSource = VOICE_RECOGNITION).normalizedFor(offered).audioSource)
        // Without the library the low-latency input is not offered: Camcorder.
        val noLibrary = capabilities.copy(sources = capabilities.sources - LOW_LATENCY).forDevice(razr)
        assertEquals(CAMCORDER, CameraSettings(audioSource = VOICE_RECOGNITION).normalizedFor(noLibrary).audioSource)
        // Without the quirk, Camcorder even when the low-latency input is offered.
        assertEquals(CAMCORDER, CameraSettings(audioSource = VOICE_RECOGNITION)
            .normalizedFor(capabilities.forDevice(AudioInputPolicy.NONE)).audioSource)
        // Neither: the first source offered.
        assertEquals(MIC, CameraSettings(audioSource = VOICE_RECOGNITION)
            .normalizedFor(capabilities.copy(sources = listOf(MIC, UNPROCESSED))).audioSource)
        // An offered source is never replaced.
        assertEquals(MIC, CameraSettings(audioSource = MIC).normalizedFor(offered).audioSource)
    }

    @Test
    fun anUnofferedAacRateFallsBackToTheHighestOffered() {
        val normalized = CameraSettings(audioOutputFormat = AudioOutputFormat.AAC_MP4, audioBitrateKbps = 320).normalizedFor(capabilities)
        assertEquals(192, normalized.audioBitrateKbps)
    }

    // ---- the applied curve -------------------------------------------------------------------

    @Test
    fun aBundledCurveAppliesOnlyWithTheLowLatencyInput() {
        val settings = CameraSettings(audioSource = LOW_LATENCY, audioEqProfileId = razrProfile.id, audioEqCurve = razrProfile.curve)
        assertEquals(razrProfile.curve, settings.appliedAudioEqCurve)
        // The vendor equalises every other source itself; the stored curve waits for the low-latency input.
        listOf(CAMCORDER, MIC, VOICE_RECOGNITION).forEach { source ->
            val other = settings.copy(audioSource = source)
            assertEquals(EqCurve(), other.appliedAudioEqCurve)
            assertEquals(razrProfile.curve, other.audioEqCurve)
            assertEquals(razrProfile.curve, other.copy(audioSource = LOW_LATENCY).appliedAudioEqCurve)
        }
    }

    @Test
    fun aCurveOfTheUsersOwnAppliesWithEverySource() {
        val own = EqCurve(points = listOf(EqPoint(EqFilterType.BELL, 200.0, -3.0, 1.0)))
        assertEquals(own, CameraSettings(audioSource = CAMCORDER, audioEqCurve = own).appliedAudioEqCurve)
        assertEquals(own, CameraSettings(audioSource = CAMCORDER, audioEqProfileId = "my-profile", audioEqCurve = own).appliedAudioEqCurve)
    }

    @Test
    fun theAppliedProfileIdFollowsTheAppliedCurve() {
        val bundled = CameraSettings(audioSource = LOW_LATENCY, audioEqProfileId = razrProfile.id, audioEqCurve = razrProfile.curve)
        assertEquals(razrProfile.id, bundled.appliedAudioEqProfileId)
        // The bundled curve waits with another source, so the take names no profile.
        assertNull(bundled.copy(audioSource = CAMCORDER).appliedAudioEqProfileId)
        // A bypassed curve is not applied.
        assertNull(bundled.copy(audioEqCurve = razrProfile.curve.copy(enabled = false)).appliedAudioEqProfileId)
        val own = EqCurve(points = listOf(EqPoint(EqFilterType.BELL, 200.0, -3.0, 1.0)))
        assertEquals("my-profile", CameraSettings(audioSource = CAMCORDER, audioEqProfileId = "my-profile", audioEqCurve = own).appliedAudioEqProfileId)
        // A custom curve that was never saved has no id; a flat curve names none even with one.
        assertNull(CameraSettings(audioSource = CAMCORDER, audioEqCurve = own).appliedAudioEqProfileId)
        assertNull(CameraSettings(audioSource = CAMCORDER, audioEqProfileId = "my-profile", audioEqCurve = EqCurve()).appliedAudioEqProfileId)
    }

    @Test
    fun aStoredCopyOfTheBundledCurveFollowsTheOneThatShips() {
        val older = EqCurve(points = listOf(EqPoint(EqFilterType.BELL, 7_000.0, -6.0, 1.0)), enabled = false)
        val stored = CameraSettings(audioSource = LOW_LATENCY, audioEqProfileId = razrProfile.id, audioEqCurve = older)
        // The bypass is the user's; the bands are the profile's.
        assertEquals(razrProfile.curve.copy(enabled = false), stored.settledAudioInput(null, razr).audioEqCurve)
        // Another profile's curve, and a phone without a bundled profile, are left alone.
        assertEquals(older, stored.copy(audioEqProfileId = "my-profile").settledAudioInput(null, razr).audioEqCurve)
        assertEquals(older, stored.settledAudioInput(null, AudioInputPolicy.NONE).audioEqCurve)
    }
}
