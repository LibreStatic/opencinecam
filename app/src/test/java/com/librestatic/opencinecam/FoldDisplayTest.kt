/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class FoldDisplayTest {
    @Test fun unavailableAndUnknownCapabilitiesNeverStartASession() {
        for (capability in DisplayCapability.entries.filter { it != DisplayCapability.AVAILABLE }) {
            val machine = FoldSessionStateMachine()
            machine.capabilities(capability, capability)
            assertNull(machine.begin(DisplayOperation.PRESENT))
            assertNull(machine.begin(DisplayOperation.TRANSFER))
        }
    }

    @Test fun presentationAndTransferHaveIndependentCapabilityGates() {
        val machine = FoldSessionStateMachine()
        machine.capabilities(DisplayCapability.UNSUPPORTED, DisplayCapability.AVAILABLE)
        assertNull(machine.begin(DisplayOperation.PRESENT))
        assertNotNull(machine.begin(DisplayOperation.TRANSFER))
    }

    @Test fun duplicateStartDoesNotCreateASecondSession() {
        val machine = available()
        val token = requireNotNull(machine.begin(DisplayOperation.PRESENT))
        assertNull(machine.begin(DisplayOperation.PRESENT))
        assertNull(machine.begin(DisplayOperation.TRANSFER))
        assertTrue(machine.started(token))
        assertFalse(machine.started(token))
    }

    @Test fun closingPendingSessionInvalidatesALateStart() {
        val machine = available()
        val token = requireNotNull(machine.begin(DisplayOperation.PRESENT))
        machine.close()
        assertFalse(machine.started(token))
        assertEquals(DisplaySessionPhase.IDLE, machine.state.phase)
    }

    @Test fun oldEndAndVisibilityCannotClobberANewSession() {
        val machine = available()
        val old = requireNotNull(machine.begin(DisplayOperation.PRESENT))
        machine.close()
        val current = requireNotNull(machine.begin(DisplayOperation.TRANSFER))
        assertTrue(machine.started(current))
        machine.visibility(current, true)
        assertFalse(machine.ended(old, "late error"))
        machine.visibility(old, false)
        assertTrue(machine.state.visible)
        assertNull(machine.state.failure)
        assertEquals(DisplayOperation.TRANSFER, machine.state.operation)
    }

    @Test fun systemDismissalClosesDisplayAndPreservesItsFailure() {
        val machine = available()
        val token = requireNotNull(machine.begin(DisplayOperation.PRESENT))
        machine.started(token)
        assertTrue(machine.ended(token, "display removed"))
        assertEquals(DisplaySessionPhase.IDLE, machine.state.phase)
        assertEquals("display removed", machine.state.failure)
    }

    @Test fun hingeCloseRequiresAnObservedOpenAndRejectsNoise() {
        val detector = FoldCloseDetector()
        assertFalse(detector.sample(0f))
        assertFalse(detector.sample(Float.NaN))
        assertFalse(detector.sample(181f))
        assertFalse(detector.sample(180f))
        assertTrue(detector.sample(3f))
        for (value in listOf(0f, 6f, 2f, 12f, 3f)) assertFalse(detector.sample(value))
        assertFalse(detector.sample(30f))
        assertTrue(detector.sample(0f))
    }

    @Test fun missingHingeDoesNotImplyFoldOrSplit() {
        assertNull(foldPanes(800, 1000, 0, 0, null, 16, 180, false))
    }

    @Test fun tabletopPanesAvoidTheHingeAndHonorWindowOffset() {
        val panes = requireNotNull(foldPanes(800, 1000, 0, 100, FoldHinge(0, 600, 800, 620, true), 16, 180, false))
        assertEquals(FoldPane(0, 0, 800, 492), panes.preview)
        assertEquals(FoldPane(0, 528, 800, 472), panes.controls)
    }

    @Test fun bookPanesAndSwapDoNotOverlap() {
        val hinge = FoldHinge(500, 0, 500, 900, false)
        val normal = requireNotNull(foldPanes(1000, 900, 0, 0, hinge, 16, 180, false))
        val swapped = requireNotNull(foldPanes(1000, 900, 0, 0, hinge, 16, 180, true))
        assertEquals(normal.preview, swapped.controls)
        assertEquals(normal.controls, swapped.preview)
        assertTrue(normal.preview.left + normal.preview.width < normal.controls.left)
    }

    @Test fun smallOrOffscreenPanesFallBackToNormalLayout() {
        assertNull(foldPanes(360, 400, 0, 0, FoldHinge(0, 50, 360, 50, true), 16, 180, false))
        assertNull(foldPanes(360, 800, 0, 0, FoldHinge(400, 400, 700, 400, true), 16, 180, false))
    }

    @Test fun closePolicyIsFrozenDuringRecordingWhilePrompterUpdatesLive() {
        val old = CameraSettings()
        val next = old.copy(subjectDisplay = old.subjectDisplay.copy(continueRecordingOnFold = false, prompterText = "Next line"))
        val effective = old.withLivePreferencesFrom(next)
        assertTrue(effective.subjectDisplay.continueRecordingOnFold)
        assertEquals("Next line", effective.subjectDisplay.prompterText)
        assertNotEquals(next, effective)
    }

    @Test(expected = IllegalArgumentException::class)
    fun scriptsHaveAnExplicitMemoryBound() { SubjectDisplaySettings(prompterText = "x".repeat(20_001)) }

    @Test fun subjectFeatureDefaultsAreSafe() {
        val defaults = SubjectDisplaySettings()
        assertEquals(SubjectDisplayMode.STATUS, defaults.mode)
        assertTrue(defaults.previewMirror)
        assertTrue(defaults.previewRecordedAreaBands)
        assertEquals(SubjectPreviewGuide.NONE, defaults.previewGuide)
        assertFalse(defaults.previewAudioMeter)
        assertTrue(defaults.tallyBorder)
        assertTrue(defaults.giantCountdown)
        assertEquals(5000, defaults.fillLightKelvin)
        assertEquals(0, defaults.fillLightTint)
        assertEquals(0, defaults.fillLightTimeoutSeconds)
        assertTrue(defaults.interviewQuestions.isEmpty())
        assertEquals(SubjectSlateField.entries.toSet(), defaults.slateFields)
        // The sync beep alters the recorded audio, so neither marker is on by default.
        assertFalse(defaults.slateSyncFlash)
        assertFalse(defaults.slateSyncBeep)
        assertFalse(defaults.outOfFrameWarning)
        assertEquals(2, defaults.outOfFrameDelaySeconds)
        assertEquals(SubjectSessionCues(), SubjectSessionCues(reviewUri = null, interviewIndex = 0))
    }

    @Test fun subjectFeatureBoundsAreEnforced() {
        val invalid = listOf<() -> SubjectDisplaySettings>(
            { SubjectDisplaySettings(fillLightKelvin = 2699) },
            { SubjectDisplaySettings(fillLightKelvin = 6501) },
            { SubjectDisplaySettings(fillLightTint = 51) },
            { SubjectDisplaySettings(fillLightTint = -51) },
            { SubjectDisplaySettings(fillLightTimeoutSeconds = -1) },
            { SubjectDisplaySettings(fillLightTimeoutSeconds = 3601) },
            { SubjectDisplaySettings(interviewQuestions = List(51) { "Q$it" }) },
            { SubjectDisplaySettings(interviewQuestions = listOf("x".repeat(301))) },
            { SubjectDisplaySettings(interviewQuestions = listOf(" ")) },
            { SubjectDisplaySettings(outOfFrameDelaySeconds = 0) },
            { SubjectDisplaySettings(outOfFrameDelaySeconds = 11) },
        )
        for (build in invalid) assertTrue(runCatching(build).isFailure)
        assertTrue(runCatching { SubjectSessionCues(interviewIndex = -1) }.isFailure)
        SubjectDisplaySettings(fillLightKelvin = 2700, fillLightTint = -50, fillLightTimeoutSeconds = 3600, outOfFrameDelaySeconds = 10,
            interviewQuestions = List(50) { "x".repeat(300) })
    }

    @Test fun subjectFeaturePreferencesRoundTrip() {
        val store = CameraSettingsStore(PresetPreferences())
        val subject = SubjectDisplaySettings(mode = SubjectDisplayMode.INTERVIEW, previewRecordedAreaBands = false,
            previewGuide = SubjectPreviewGuide.SAFE_AREA, previewAudioMeter = true, tallyBorder = false, giantCountdown = false,
            fillLightKelvin = 3200, fillLightTint = -12, fillLightTimeoutSeconds = 600,
            interviewQuestions = listOf("What brought you here?", "Commas, \"quotes\" and\nbreaks survive"),
            slateFields = setOf(SubjectSlateField.SCENE, SubjectSlateField.TIMECODE), slateSyncFlash = true, slateSyncBeep = true,
            outOfFrameWarning = true, outOfFrameDelaySeconds = 5)
        for (mode in SubjectDisplayMode.entries) {
            val value = CameraSettings(subjectDisplay = subject.copy(mode = mode))
            store.save(value)
            assertEquals(value.subjectDisplay, store.load().subjectDisplay)
        }
        store.save(CameraSettings(subjectDisplay = subject.copy(slateFields = emptySet())))
        assertEquals(emptySet<SubjectSlateField>(), store.load().subjectDisplay.slateFields)
    }

    @Test fun storedDataWithoutSubjectFeatureKeysLoadsDefaults() {
        // As written by a build from before OCC-PLAN-068.
        val old = PresetPreferences(mapOf("subject-mode" to "PREVIEW", "subject-preview-mirror" to false, "subject-script" to "Hello"))
        val loaded = CameraSettingsStore(old).load().subjectDisplay
        assertEquals(SubjectDisplaySettings(mode = SubjectDisplayMode.PREVIEW, previewMirror = false, prompterText = "Hello"), loaded)
    }

    @Test fun unknownOrCorruptSubjectValuesFallBackInsteadOfFailingTheLoad() {
        val corrupt = PresetPreferences(mapOf(
            "subject-mode" to "HOLOGRAM",
            "subject-preview-guide" to "SPIRAL",
            "subject-fill-kelvin" to 9000,
            "subject-fill-tint" to -80,
            "subject-fill-timeout-seconds" to 99_999,
            "subject-out-of-frame-delay" to 0,
            "subject-slate-fields" to "SCENE,LENS,,TAKE",
            "subject-interview-questions" to "not json",
        ))
        val loaded = CameraSettingsStore(corrupt).load().subjectDisplay
        assertEquals(SubjectDisplayMode.STATUS, loaded.mode)
        assertEquals(SubjectPreviewGuide.NONE, loaded.previewGuide)
        assertEquals(6500, loaded.fillLightKelvin)
        assertEquals(-50, loaded.fillLightTint)
        assertEquals(3600, loaded.fillLightTimeoutSeconds)
        assertEquals(1, loaded.outOfFrameDelaySeconds)
        assertEquals(setOf(SubjectSlateField.SCENE, SubjectSlateField.TAKE), loaded.slateFields)
        assertTrue(loaded.interviewQuestions.isEmpty())
        val oversized = kotlinx.serialization.json.JsonArray((List(60) { "x".repeat(400) } + listOf("", "  ")).map(::JsonPrimitive)).toString()
        val clipped = CameraSettingsStore(PresetPreferences(mapOf("subject-interview-questions" to oversized))).load().subjectDisplay
        assertEquals(50, clipped.interviewQuestions.size)
        assertTrue(clipped.interviewQuestions.all { it.length == 300 })
        val mixed = """["Kept", 7, null, {"a": 1}, "Also kept"]"""
        assertEquals(listOf("Kept", "Also kept"),
            CameraSettingsStore(PresetPreferences(mapOf("subject-interview-questions" to mixed))).load().subjectDisplay.interviewQuestions)
    }

    @Test fun interviewEditorTextIsSplitIntoBoundedQuestions() {
        assertEquals(listOf("One", "Two"), parseInterviewQuestions("  One \n\n\t\nTwo\n"))
        assertEquals(50, parseInterviewQuestions((1..80).joinToString("\n") { "Q$it" }).size)
        assertEquals(300, parseInterviewQuestions("y".repeat(500)).single().length)
        assertTrue(parseInterviewQuestions("").isEmpty())
    }

    @Test fun subjectFeaturePreferencesAreLiveAndSearchable() {
        val old = CameraSettings()
        val next = old.copy(subjectDisplay = old.subjectDisplay.copy(mode = SubjectDisplayMode.FILL_LIGHT, fillLightKelvin = 3000, interviewQuestions = listOf("Next")))
        assertEquals(next.subjectDisplay, old.withLivePreferencesFrom(next).subjectDisplay)
        for ((query, id) in listOf("Kelvin" to "subject-fill-light", "fill light" to "subject-fill-light", "entrevista" to "subject-interview",
            "tally" to "subject-tally", "tercios" to "subject-self-monitor", "audio meter" to "subject-self-monitor",
            "claqueta sync" to "subject-slate", "beep" to "subject-slate", "out of frame" to "subject-out-of-frame", "review" to "fold-displays")) {
            assertTrue(query, id in SettingsCatalog.search(query, null) { "" })
            assertTrue(query, id in SettingsCatalog.search("", SettingsCategory.DISPLAYS) { "" })
        }
    }

    private fun available() = FoldSessionStateMachine().apply { capabilities(DisplayCapability.AVAILABLE, DisplayCapability.AVAILABLE) }

    @Test fun onlyAnActiveTransferCarriesTheBrightnessRequestOnTheActivityWindow() {
        val active = FoldDisplayState(phase = DisplaySessionPhase.ACTIVE, operation = DisplayOperation.TRANSFER)
        assertEquals(0.4f, transferBrightnessRequest(active, 0.4f))
        assertNull(transferBrightnessRequest(active.copy(operation = DisplayOperation.PRESENT), 0.4f))
        assertNull(transferBrightnessRequest(active.copy(phase = DisplaySessionPhase.STARTING), 0.4f))
        assertNull(transferBrightnessRequest(FoldDisplayState(), 0.4f))
    }
}
