/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam
import com.librestatic.opencinecam.camera.TimecodeRate
import org.junit.Assert.*
import org.junit.Test

class TimecodeStartInputTest {
    @Test fun parsesValidStartWithoutSilentlyNormalizingSkippedLabels() {
        assertEquals("23:59:59:23", parseTimecodeStartInput("23", "59", "59", "23", TimecodeRate(24))!!.format())
        assertEquals("00:01:00;02", parseTimecodeStartInput("0", "1", "0", "2", TimecodeRate(30, true))!!.format())
        assertNull(parseTimecodeStartInput("0", "1", "0", "0", TimecodeRate(30, true)))
        assertNull(parseTimecodeStartInput("0", "1", "0", "3", TimecodeRate(60, true)))
    }
    @Test fun rejectsIncompleteMalformedAndOutOfRangeFields() {
        for (value in listOf("", "-1", " 1", "1 ", "a", "001", "١")) assertNull(parseTimecodeStartInput(value, "0", "0", "0", TimecodeRate(30)))
        assertNull(parseTimecodeStartInput("24", "0", "0", "0", TimecodeRate(30)))
        assertNull(parseTimecodeStartInput("1", "60", "0", "0", TimecodeRate(30)))
        assertNull(parseTimecodeStartInput("1", "0", "60", "0", TimecodeRate(30)))
        assertNull(parseTimecodeStartInput("1", "0", "0", "24", TimecodeRate(24)))
    }
    @Test fun localContinuityPreferencesRoundTripButStayOutsidePortablePresets() {
        val memory = PresetPreferences(); val store = CameraSettingsStore(memory)
        val settings = CameraSettings(timecodeRememberPosition = false, timecodeResetRevision = 7)
        store.save(settings); assertEquals(settings, store.load())
        assertFalse(CameraPresetCodec.portableKeys.contains("timecode-remember-position"))
        assertFalse(CameraPresetCodec.portableKeys.contains("timecode-reset-revision"))
        val merged = CameraPresetCodec.mergeLocal(CameraSettings(), settings)
        assertFalse(merged.timecodeRememberPosition); assertEquals(7, merged.timecodeResetRevision)
    }
    @Test fun legacySettingsDefaultToRememberAndClampInvalidResetRevision() {
        val memory = PresetPreferences(); assertTrue(CameraSettingsStore(memory).load().timecodeRememberPosition)
        memory.edit().putInt("timecode-reset-revision", -7).commit()
        assertEquals(0, CameraSettingsStore(memory).load().timecodeResetRevision)
    }
}
