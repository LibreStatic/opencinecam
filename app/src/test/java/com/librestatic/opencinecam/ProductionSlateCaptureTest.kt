/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import org.junit.Assert.*
import org.junit.Test

class ProductionSlateCaptureTest {
    @Test fun enabledSavedSlateIncrementsOnceAndPreservesOtherSettings() {
        val slate = ProductionSlateSettings(project = "Shoot", takeNumber = 12, autoIncrementTake = true)
        val current = CameraSettings(productionSlate = slate, audioEnabled = false, photoQuality = 73)
        val next = afterSavedProductionSlate(current, slate)
        assertEquals(current.copy(productionSlate = slate.copy(takeNumber = 13)), next)
        assertSame(next, afterSavedProductionSlate(next, slate))
    }
    @Test fun disabledAutoIncrementLeavesSameSettingsInstance() {
        val current = CameraSettings(productionSlate = ProductionSlateSettings(takeNumber = 3))
        assertSame(current, afterSavedProductionSlate(current, current.productionSlate))
    }
    @Test fun nextTakeEditorialChangesAreNeverOverwritten() {
        val saved = ProductionSlateSettings(scene = "A", takeNumber = 7, autoIncrementTake = true)
        for (next in listOf(saved.copy(scene = "B"), saved.copy(takeNumber = 44), saved.copy(goodTake = true), saved.copy(autoIncrementTake = false))) {
            val current = CameraSettings(productionSlate = next)
            assertSame(current, afterSavedProductionSlate(current, saved))
        }
    }
    @Test fun unrelatedLivePreferenceDoesNotDiscardTheNextNumber() {
        val saved = ProductionSlateSettings(autoIncrementTake = true)
        val current = CameraSettings(productionSlate = saved, audioMeter = AudioMeterSettings(visible = false))
        val next = afterSavedProductionSlate(current, saved)
        assertEquals(2, next.productionSlate.takeNumber);assertEquals(current.audioMeter, next.audioMeter)
    }
    @Test fun maximumNeverWrapsOrChangesIdentity() {
        val current = CameraSettings(productionSlate = ProductionSlateSettings(takeNumber = 999999, autoIncrementTake = true))
        assertSame(current, afterSavedProductionSlate(current, current.productionSlate))
    }
}
