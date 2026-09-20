/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import org.junit.Assert.*
import org.junit.Test

class SubjectSurfaceRegistryTest {
    @Test fun lateSurfaceDestructionAndStatusDoNotAffectNewLease() {
        val registry = SubjectSurfaceRegistry<Any>()
        val sameSurfaceWrapper = Any()
        val old = registry.attach(sameSurfaceWrapper, 0)
        val newer = registry.attach(sameSurfaceWrapper, 90)
        assertFalse(registry.release(old.token))
        assertFalse(registry.owns(old.token))
        assertTrue(registry.owns(newer.token))
        assertEquals(90, registry.current!!.rotationDegrees)
        assertTrue(registry.release(newer.token))
        assertNull(registry.current)
        assertFalse(registry.owns(newer.token))
    }
    @Test fun liveMirrorAndColorPreferencesDoNotChangeRecordingGeometry() {
        val original = CameraSettings(subjectDisplay = SubjectDisplaySettings(mode = SubjectDisplayMode.PREVIEW))
        val requested = original.copy(videoWidth = 1280, subjectDisplay = original.subjectDisplay.copy(previewMirror = false, previewViewAssist = false))
        val effective = original.withLivePreferencesFrom(requested)
        assertEquals(original.videoWidth, effective.videoWidth)
        assertFalse(effective.subjectDisplay.previewMirror)
        assertFalse(effective.subjectDisplay.previewViewAssist)
    }
    @Test(expected = IllegalArgumentException::class) fun invalidRotationNeverCreatesALease() {
        SubjectSurfaceRegistry<String>().attach("surface", 45)
    }
}
