/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

import org.junit.Assert.*
import org.junit.Test

class PhotoFlashTest {
    private val fixed = PhotoFlashCapabilities(true, setOf(0, 1, 2, 3))
    private val variable = fixed.copy(singleMax = 5, singleDefault = 3)
    private fun selection(mode: PhotoFlashMode, level: Int? = null) = PhotoFlashSelection(mode, level)
    private fun rejected(reason: PhotoFlashRejection) = PhotoFlashResolution.Rejected(reason)
    private fun plan(ae: Int?, flash: Int, strength: Int?, precapture: Boolean) =
        PhotoFlashResolution.Plan(ae, flash, strength, precapture)

    @Test fun defaultsAreOffAndDoNotInventHardware() {
        val capabilities = PhotoFlashCapabilities()
        assertFalse(capabilities.available); assertFalse(capabilities.adjustable)
        assertTrue(capabilities.aeModes.isEmpty())
        assertEquals(1, capabilities.singleMax); assertEquals(1, capabilities.singleDefault)
        assertEquals(plan(null, 0, null, false), PhotoFlashSelection().resolve(capabilities, ExposureMode.AUTO))
    }

    @Test fun offPreservesEveryExposureModeIncludingPrioritiesWithoutFlashHardware() {
        ExposureMode.entries.forEach {
            assertEquals(plan(null, 0, null, false), selection(PhotoFlashMode.OFF).resolve(PhotoFlashCapabilities(), it))
        }
    }

    @Test fun offDoesNotWriteAnInactivePersistedStrength() {
        listOf(Int.MIN_VALUE, 0, 1, 5, Int.MAX_VALUE).forEach {
            assertEquals(plan(null, 0, null, false), selection(PhotoFlashMode.OFF, it).resolve(variable, ExposureMode.MANUAL))
        }
    }

    @Test fun activeModesRejectAbsentFlashInsteadOfDowngrading() {
        listOf(PhotoFlashMode.AUTO, PhotoFlashMode.ON).forEach {
            assertEquals(rejected(PhotoFlashRejection.FLASH_UNAVAILABLE), selection(it).resolve(fixed.copy(available = false), ExposureMode.AUTO))
        }
    }

    @Test fun autoRequiresExplicitAdvertisedAutoFlashAndPrecapture() {
        assertEquals(plan(2, 0, null, true), selection(PhotoFlashMode.AUTO).resolve(fixed.copy(aeModes = setOf(2)), ExposureMode.AUTO))
    }

    @Test fun autoDoesNotSubstituteAlwaysFlashOrRedEyeOrExternalFlashModes() {
        listOf(emptySet(), setOf(0, 1, 3, 4, 5)).forEach {
            assertEquals(rejected(PhotoFlashRejection.AE_MODE_UNSUPPORTED), selection(PhotoFlashMode.AUTO).resolve(fixed.copy(aeModes = it), ExposureMode.AUTO))
        }
    }

    @Test fun autoRejectsManualAndBothPriorityModes() {
        listOf(ExposureMode.MANUAL, ExposureMode.ISO_PRIORITY, ExposureMode.SHUTTER_PRIORITY).forEach {
            assertEquals(rejected(PhotoFlashRejection.EXPOSURE_UNSUPPORTED), selection(PhotoFlashMode.AUTO).resolve(variable, it))
        }
    }

    @Test fun autoRejectsEveryExplicitStrengthRatherThanIgnoringIt() {
        listOf(Int.MIN_VALUE, 0, 1, 3, 6, Int.MAX_VALUE).forEach {
            assertEquals(rejected(PhotoFlashRejection.STRENGTH_WITH_AUTO), selection(PhotoFlashMode.AUTO, it).resolve(variable, ExposureMode.AUTO))
        }
    }

    @Test fun onAutomaticWithoutLevelUsesOnlyAlwaysFlashEvenWithVariableStrength() {
        listOf(fixed, variable).forEach {
            assertEquals(plan(3, 0, null, true), selection(PhotoFlashMode.ON).resolve(it, ExposureMode.AUTO))
        }
    }

    @Test fun onAutomaticWithoutLevelNeverSilentlySubstitutesSingleOrDefaultStrength() {
        assertEquals(rejected(PhotoFlashRejection.AE_MODE_UNSUPPORTED), selection(PhotoFlashMode.ON)
            .resolve(variable.copy(aeModes = setOf(0, 1, 2)), ExposureMode.AUTO))
    }

    @Test fun explicitOnStrengthUsesAeOnSingleAndPrecaptureAtAllValidLevels() {
        (1..5).forEach {
            assertEquals(plan(1, 1, it, true), selection(PhotoFlashMode.ON, it).resolve(variable, ExposureMode.AUTO))
        }
    }

    @Test fun explicitOnStrengthDoesNotUseAlwaysFlashWhenAeOnMissing() {
        assertEquals(rejected(PhotoFlashRejection.AE_MODE_UNSUPPORTED), selection(PhotoFlashMode.ON, 3)
            .resolve(variable.copy(aeModes = setOf(0, 2, 3)), ExposureMode.AUTO))
    }

    @Test fun fixedStrengthCapabilityRejectsEvenExplicitLevelOne() {
        listOf(ExposureMode.AUTO, ExposureMode.MANUAL).forEach {
            assertEquals(rejected(PhotoFlashRejection.STRENGTH_UNSUPPORTED), selection(PhotoFlashMode.ON, 1).resolve(fixed, it))
        }
    }

    @Test fun invalidActiveLevelsAreRejectedNotClampedInAutoOrManual() {
        listOf(Int.MIN_VALUE, -1, 0, 6, Int.MAX_VALUE).forEach { level ->
            listOf(ExposureMode.AUTO, ExposureMode.MANUAL).forEach { exposure ->
                assertEquals(rejected(PhotoFlashRejection.STRENGTH_OUT_OF_RANGE), selection(PhotoFlashMode.ON, level).resolve(variable, exposure))
            }
        }
    }

    @Test fun manualOnUsesAeOffSingleAndNoPrecapture() {
        assertEquals(plan(0, 1, null, false), selection(PhotoFlashMode.ON).resolve(fixed, ExposureMode.MANUAL))
        assertEquals(plan(0, 1, null, false), selection(PhotoFlashMode.ON).resolve(variable, ExposureMode.MANUAL))
    }

    @Test fun manualOnHonorsExplicitVariableStrengthWithoutChangingExposure() {
        (1..5).forEach {
            assertEquals(plan(0, 1, it, false), selection(PhotoFlashMode.ON, it).resolve(variable, ExposureMode.MANUAL))
        }
    }

    @Test fun manualOnRejectsMissingAeOffRatherThanEnablingAutomaticExposure() {
        listOf(null, 1).forEach {
            assertEquals(rejected(PhotoFlashRejection.AE_MODE_UNSUPPORTED), selection(PhotoFlashMode.ON, it)
                .resolve(variable.copy(aeModes = setOf(1, 2, 3)), ExposureMode.MANUAL))
        }
    }

    @Test fun onRejectsBothPriorityModesWithAndWithoutStrength() {
        listOf(ExposureMode.ISO_PRIORITY, ExposureMode.SHUTTER_PRIORITY).forEach { exposure ->
            listOf(null, 1, 3).forEach { level ->
                assertEquals(rejected(PhotoFlashRejection.EXPOSURE_UNSUPPORTED), selection(PhotoFlashMode.ON, level).resolve(variable, exposure))
            }
        }
    }

    @Test fun adjustableRequiresAvailableAndSingleFlashMaximumAboveOne() {
        assertFalse(fixed.adjustable); assertTrue(variable.adjustable)
        assertFalse(variable.copy(available = false).adjustable)
    }

    @Test fun capabilityRejectsInvalidMaximumOrDefaultInsteadOfRepairingThem() {
        listOf(0 to 1, -1 to 1, 1 to 0, 5 to -1, 5 to 6, 0 to 0).forEach { (maximum, default) ->
            try { PhotoFlashCapabilities(true, setOf(0, 1, 2, 3), maximum, default); fail("Invalid capability bounds") }
            catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun maximumIntStrengthIsHandledWithoutOverflowOrDefaultSubstitution() {
        val caps = PhotoFlashCapabilities(true, setOf(0, 1), Int.MAX_VALUE, Int.MAX_VALUE)
        assertEquals(plan(1, 1, Int.MAX_VALUE, true), selection(PhotoFlashMode.ON, Int.MAX_VALUE).resolve(caps, ExposureMode.AUTO))
        assertEquals(plan(0, 1, Int.MAX_VALUE, false), selection(PhotoFlashMode.ON, Int.MAX_VALUE).resolve(caps, ExposureMode.MANUAL))
    }
}
