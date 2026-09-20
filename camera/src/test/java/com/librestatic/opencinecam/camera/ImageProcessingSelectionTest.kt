/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

import org.junit.Assert.*
import org.junit.Test

class ImageProcessingSelectionTest {
    private val caps = ImageProcessingCapabilities(setOf(0, 1), setOf(0, 1), setOf(0, 1, 2), setOf(0, 1, 2))
    private val defaults = ImageProcessingDefaults(optical = 1, video = 0, noise = 1, edge = 2)
    @Test fun defaultPreservesExactTemplateIncludingVendorValuesAndNulls() {
        val defaults = ImageProcessingDefaults(optical = null, video = 0, noise = 4, edge = 3)
        assertEquals(defaults, ImageProcessingSelection().resolve(caps, defaults).values)
    }
    @Test fun noiseAndEdgeAreIndependent() {
        val result = ImageProcessingSelection(noiseReduction = IspMode.HIGH_QUALITY, edge = IspMode.OFF).resolve(caps, defaults)
        assertEquals(ImageProcessingDefaults(1, 0, 2, 0), result.values)
        assertTrue(result.unavailable.isEmpty())
    }
    @Test fun opticalAndElectronicSelectionsNeverInferACombinedMode() {
        val optical = ImageProcessingSelection(StabilizationMode.OPTICAL).resolve(caps, defaults)
        assertEquals(1, optical.values.optical)
        assertEquals(0, optical.values.video)
        val electronic = ImageProcessingSelection(StabilizationMode.VIDEO).resolve(caps, defaults)
        assertEquals(0, electronic.values.optical)
        assertEquals(1, electronic.values.video)
        val off = ImageProcessingSelection(StabilizationMode.OFF).resolve(caps, defaults)
        assertEquals(0, off.values.optical)
        assertEquals(0, off.values.video)
    }
    @Test fun missingWritableModeRestoresThatTemplateFieldAndPreservesOtherChoices() {
        val requested = ImageProcessingSelection(StabilizationMode.VIDEO, IspMode.HIGH_QUALITY, IspMode.OFF)
        val result = requested.resolve(caps.copy(videoModes = setOf(0), noiseModes = setOf(1)), defaults)
        assertEquals(ImageProcessingDefaults(1, 0, 1, 0), result.values)
        assertEquals(setOf(ImageProcessingControl.STABILIZATION, ImageProcessingControl.NOISE_REDUCTION), result.unavailable)
        assertEquals(StabilizationMode.VIDEO, requested.stabilization)
    }
    @Test fun unknownEnabledCounterpartDoesNotCreateAnUnverifiedCombination() {
        val result = ImageProcessingSelection(StabilizationMode.OPTICAL).resolve(caps.copy(videoModes = emptySet()), defaults.copy(video = 1))
        assertEquals(setOf(ImageProcessingControl.STABILIZATION), result.unavailable)
        assertEquals(defaults.copy(video = 1), result.values)
    }
    @Test fun hfrKeepsTemplatesWithoutDiscardingIntent() {
        val requested = ImageProcessingSelection(StabilizationMode.OPTICAL, IspMode.OFF, IspMode.FAST)
        val result = requested.resolve(caps, defaults, constrainedHfr = true)
        assertEquals(defaults, result.values)
        assertEquals(ImageProcessingControl.entries.toSet(), result.unavailable)
        assertEquals(IspMode.OFF, requested.noiseReduction)
    }
    @Test fun resettingAfterAnOverrideRestoresTheOriginalNotTheMutatedRequest() {
        val changed = ImageProcessingSelection(StabilizationMode.VIDEO, IspMode.OFF, IspMode.OFF).resolve(caps, defaults)
        assertNotEquals(defaults, changed.values)
        assertEquals(defaults, ImageProcessingSelection().resolve(caps, defaults).values)
    }
    @Test fun unadvertisedModesAreNotSelectableAndPreviewEisIsNotTreatedAsNormalEis() {
        val empty = ImageProcessingCapabilities()
        assertTrue(empty.supports(null as StabilizationMode?))
        assertFalse(empty.supports(StabilizationMode.OFF))
        assertFalse(empty.supports(IspMode.HIGH_QUALITY, ImageProcessingControl.EDGE))
        assertFalse(caps.copy(videoModes = setOf(0, 2)).supports(StabilizationMode.VIDEO))
        assertFalse(caps.copy(opticalModes = setOf(1)).supports(StabilizationMode.VIDEO))
    }
}
