/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

import org.junit.Assert.*
import org.junit.Test

class AudioEffectsSnapshotTest {
    private fun read(requested: Boolean = true, available: Boolean? = true, failed: Boolean = false,
        software: Boolean = false, enabled: (() -> Boolean)? = null, control: (() -> Boolean)? = null) =
        readAudioEffectObservation(requested, available, failed, software, enabled, control)

    @Test fun absentPublicHandleDistinguishesUnknownUnavailableAndAdvertisedFailure() {
        assertEquals(AudioEffectState.UNKNOWN, read(available = null).state)
        assertEquals(AudioEffectState.UNAVAILABLE, read(available = false).state)
        assertEquals(AudioEffectState.FAILED, read(available = true).state)
        assertTrue(read(available = true).configurationFailed)
        assertNull(read(available = false).hasControl)
        assertEquals(AudioEffectImplementation.NONE, read(available = false).implementation)
    }
    @Test fun failedCreateRemainsVisibleEvenWhenInventoryReportsUnavailable() {
        val value = read(available = false, failed = true)
        assertEquals(AudioEffectState.FAILED, value.state); assertTrue(value.configurationFailed)
    }
    @Test fun validGetterDefinesStateNotRequestedPreference() {
        assertEquals(AudioEffectState.DISABLED, read(enabled = { false }, control = { true }).state)
        val enabledDespiteOff = read(requested = false, enabled = { true }, control = { false })
        assertEquals(AudioEffectState.ENABLED, enabledDespiteOff.state)
        assertEquals(false, enabledDespiteOff.hasControl)
        assertFalse(enabledDespiteOff.requested)
    }
    @Test fun failedSetDoesNotHideActualHardwareEnabledOrDisabled() {
        for (enabled in listOf(true, false)) {
            val value = read(failed = true, enabled = { enabled }, control = { true })
            assertEquals(if (enabled) AudioEffectState.ENABLED else AudioEffectState.DISABLED, value.state)
            assertTrue(value.configurationFailed)
        }
    }
    @Test fun getterFailuresAreIndependentAndNeverInventDisabledState() {
        var controlRead = false
        val failedEnabled = read(enabled = { error("released") }, control = { controlRead = true; false })
        assertTrue(controlRead); assertEquals(AudioEffectState.FAILED, failedEnabled.state)
        assertEquals(false, failedEnabled.hasControl)
        val failedControl = read(enabled = { false }, control = { error("control failed") })
        assertEquals(AudioEffectState.FAILED, failedControl.state); assertNull(failedControl.hasControl)
    }
    @Test fun softwareFallbackPreservesPlatformFailureAndDoesNotClaimHardwareControl() {
        val value = read(software = true)
        assertEquals(AudioEffectState.ENABLED, value.state)
        assertEquals(AudioEffectImplementation.SOFTWARE, value.implementation)
        assertTrue(value.configurationFailed); assertNull(value.hasControl)
        assertFalse(read(available = false, software = true).configurationFailed)
    }
    @Test fun softwareOnRetainedInactiveHardwareIsDistinctFromPlatformProcessing() {
        val value = read(software = true, failed = true, enabled = { false }, control = { false })
        assertEquals(AudioEffectState.ENABLED, value.state)
        assertEquals(AudioEffectImplementation.SOFTWARE, value.implementation)
        assertTrue(value.configurationFailed)
    }
    @Test fun softwareCannotHideHardwareActivationOrUnreadableHandle() {
        assertEquals(AudioEffectState.FAILED, read(software = true, enabled = { true }, control = { true }).state)
        assertEquals(AudioEffectState.FAILED, read(software = true, enabled = { error("native") }, control = { true }).state)
    }
    @Test fun repeatedReadsObserveFreshStateAndPriorSnapshotIsImmutable() {
        var actual = false
        val first = read(enabled = { actual }, control = { true })
        actual = true
        val second = read(enabled = { actual }, control = { true })
        assertEquals(AudioEffectState.DISABLED, first.state)
        assertEquals(AudioEffectState.ENABLED, second.state)
    }
    @Test fun manualAndSoftwareRequireRetainedHardwareToStayDisabled() {
        for (manual in listOf(false, true)) for (software in listOf(false, true)) {
            for (state in AudioEffectState.entries) {
                val value = AudioEffectObservation(true, state, AudioEffectImplementation.PLATFORM)
                val mustReject = (manual || software) && state != AudioEffectState.DISABLED
                try {
                    requireExclusiveAgcObservation(value, true, manual, software)
                    assertFalse("$state manual=$manual software=$software", mustReject)
                } catch (_: IllegalStateException) { assertTrue(mustReject) }
            }
        }
    }
    @Test fun noPublicAgcDoesNotCertifyHiddenHalButAllowsDigitalProcessing() {
        val missing = read(available = false)
        requireExclusiveAgcObservation(missing, false, true, false)
        assertEquals(AudioEffectState.UNAVAILABLE, missing.state)
    }
    @Test fun losingControlAloneDoesNotInventEnabledButSubsequentEnableRejects() {
        val disabled = read(enabled = { false }, control = { false })
        requireExclusiveAgcObservation(disabled, true, true, false)
        val enabled = read(enabled = { true }, control = { false })
        try { requireExclusiveAgcObservation(enabled, true, true, false); fail("AGC is active") }
        catch (_: IllegalStateException) { }
    }
    @Test fun snapshotKeepsOriginalRequestsAndLegacyMeterDefaultsToNoEvidence() {
        val off = read(requested = false, available = null)
        val manualAgc = read(requested = true, enabled = { false }, control = { true })
        val snapshot = AudioEffectsSnapshot(off, manualAgc, off)
        assertTrue(snapshot.automaticGainControl.requested)
        assertEquals(AudioEffectState.DISABLED, snapshot.automaticGainControl.state)
        assertNull(AudioLevelSnapshot(emptyList(), false, 0).effects)
    }
}
