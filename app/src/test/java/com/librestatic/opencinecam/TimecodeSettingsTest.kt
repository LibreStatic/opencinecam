/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam
import org.junit.Assert.*
import org.junit.Test

class TimecodeSettingsTest {
    @Test fun loweringNominalRateKeepsAValidStartLabel() {
        val settings=CameraSettings(timecodeNominalFps=60,timecodeStartFrames=59).withTimecodeRate(24,false)
        assertEquals(23,settings.timecodeStartFrames);assertFalse(settings.timecodeDropFrame)
    }
    @Test fun enablingDropFrameAdvancesSkippedLabelToFirstValidFrame() {
        val settings=CameraSettings(timecodeStartMinutes=1).withTimecodeRate(30,true)
        assertEquals(2,settings.timecodeStartFrames)
        assertEquals(4,settings.withTimecodeRate(60,true).timecodeStartFrames)
    }
    @Test fun tenthMinuteDoesNotSkipLabels() {
        assertEquals(0,CameraSettings(timecodeStartMinutes=10).withTimecodeRate(30,true).timecodeStartFrames)
    }
    @Test fun legacyLocalPreferencesRepairInvalidRateAndStartTogether() {
        val prefs=PresetPreferences(mapOf("timecode-nominalfps" to 24,"timecode-dropframe" to true,"timecode-startframes" to 59))
        val settings=CameraSettingsStore(prefs).load()
        assertFalse(settings.timecodeDropFrame);assertEquals(23,settings.timecodeStartFrames)
        CameraSettingsStore(prefs).save(settings);assertEquals(settings,CameraSettingsStore(prefs).load())
    }
    @Test fun skippedDropFramePresetIsRejectedRatherThanSilentlyChanged() {
        val preset=CameraPreset(name="TC",settings=CameraSettings(timecodeNominalFps=30,timecodeDropFrame=true,timecodeStartMinutes=1,timecodeStartFrames=2))
        val valid=CameraPresetCodec.encode(preset);assertEquals(preset.settings,CameraPresetCodec.decode(valid).settings)
        val invalid=valid.replace("\"timecode-startframes\":2","\"timecode-startframes\":0")
        assertNotEquals(valid,invalid)
        assertThrows(IllegalArgumentException::class.java){CameraPresetCodec.decode(invalid)}
    }
}
