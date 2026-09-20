/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.content.ContextWrapper
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.opencinecam.camera.*
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class LutLibraryUiTest {
    @get:Rule val compose = createComposeRule()
    private val library = mutableStateOf(LutLibraryState())
    private val status = mutableStateOf(CameraUiState())
    private val imported = mutableListOf<Triple<String, LutTransformKind, LutSignalDomain>>()
    private val exported = mutableListOf<String>()
    private val deleted = mutableListOf<String>()
    private val fileSelections = mutableListOf<String?>()
    private var resetCount = 0

    @Test fun importRequiresAnExplicitNameKindAndDomainRatherThanInferringThem() {
        show()
        node("import").performScrollTo().assertIsNotEnabled()
        node("name").performScrollTo().performTextReplacement("Creative-looking name")
        click("kind-TECHNICAL")
        node("import").performScrollTo().assertIsNotEnabled()
        click("input-OCLOG2_CODE")
        node("name").performScrollTo().performTextReplacement("x".repeat(121))
        node("import").performScrollTo().assertIsNotEnabled()
        node("name").performScrollTo().performTextReplacement("Creative-looking name")
        click("import")
        compose.runOnIdle {
            assertEquals(listOf(Triple("Creative-looking name", LutTransformKind.TECHNICAL, LutSignalDomain.OCLOG2_CODE)), imported)
            assertNull(library.value.operatorHash)
        }
    }
    @Test fun selectExportDisableAndConfirmedRemovalDoNotPretendSelectionIsApplication() {
        val entry = entry()
        library.value = LutLibraryState(listOf(entry))
        status.value = CameraUiState(operatorLutStatus = OperatorLutStatus(entry.hash, OperatorLutState.WAITING_FOR_GPU))
        show()
        click("select-${entry.hash}")
        node("selected-${entry.hash}").performScrollTo().assertIsDisplayed()
        node("operator-status").performScrollTo().assertTextEquals(string(R.string.lut_status_waiting))
        click("export-${entry.hash}")
        compose.runOnIdle { assertEquals(listOf(entry.hash), exported) }
        click("disable")
        compose.runOnIdle { assertNull(library.value.operatorHash) }
        click("select-${entry.hash}")
        click("delete-${entry.hash}")
        node("cancel").performClick()
        compose.runOnIdle { assertTrue(deleted.isEmpty()); assertEquals(entry.hash, library.value.operatorHash) }
        click("delete-${entry.hash}")
        node("confirm").performClick()
        compose.runOnIdle { assertEquals(listOf(entry.hash), deleted); assertTrue(library.value.entries.isEmpty()); assertNull(library.value.operatorHash) }
    }
    @Test fun doubleFontControlsReflowAndCorruptLibraryRequiresConfirmedReset() {
        val entry = entry()
        library.value = LutLibraryState(listOf(entry), entry.hash, subjectHash = entry.hash, recordingHash = entry.hash)
        show(fontScale = 2f)
        for (tag in listOf("name", "kind-TECHNICAL", "input-SDR_BT709_CODE", "export-${entry.hash}", "delete-${entry.hash}", "subject-select-${entry.hash}", "subject-disable", "recording-select-${entry.hash}", "recording-disable")) {
            node(tag).performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        }
        for (tag in listOf("kind-TECHNICAL", "input-SDR_BT709_CODE", "export-${entry.hash}", "subject-select-${entry.hash}", "subject-disable", "recording-select-${entry.hash}", "recording-disable")) {
            node(tag).performScrollTo()
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithTag("lut-$tag-label", useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
            assertEquals(1, layouts.size); assertFalse("Overflow at $tag: ${layouts.single().size}", layouts.single().hasVisualOverflow)
        }
        compose.runOnIdle { library.value = LutLibraryState(listOf(entry), entry.hash, LutLibraryError.CORRUPT, entry.hash) }
        node("export-${entry.hash}").performScrollTo().assertIsNotEnabled()
        click("reset"); node("cancel").performClick()
        compose.runOnIdle { assertEquals(0, resetCount); assertNotNull(library.value.error) }
        click("reset"); node("confirm").performClick()
        compose.runOnIdle { assertEquals(1, resetCount); assertTrue(library.value.entries.isEmpty()); assertNull(library.value.error) }
    }
    @Test fun subjectSelectionStatusAndRemovalNeverFollowTheOperatorSelection() {
        val operator = entry("Operator original"); val subject = entry("Subject original")
        library.value = LutLibraryState(listOf(operator, subject), operator.hash)
        status.value = CameraUiState(operatorLutStatus = OperatorLutStatus(operator.hash, OperatorLutState.ACTIVE),
            subjectLutStatus = OperatorLutStatus(subject.hash, OperatorLutState.WAITING_FOR_GPU))
        show()
        click("subject-select-${subject.hash}")
        compose.runOnIdle { assertEquals(operator.hash, library.value.operatorHash); assertEquals(subject.hash, library.value.subjectHash) }
        node("subject-selected-${subject.hash}").performScrollTo().assertIsDisplayed()
        node("subject-status").performScrollTo().assertTextEquals(string(R.string.lut_subject_status_waiting))
        node("operator-status").performScrollTo().assertTextEquals(string(R.string.lut_status_active))
        click("disable")
        compose.runOnIdle { assertNull(library.value.operatorHash); assertEquals(subject.hash, library.value.subjectHash) }
        click("select-${operator.hash}")
        click("subject-disable")
        compose.runOnIdle { assertEquals(operator.hash, library.value.operatorHash); assertNull(library.value.subjectHash) }
        click("subject-select-${subject.hash}")
        click("delete-${subject.hash}"); node("confirm").performClick()
        compose.runOnIdle {
            assertEquals(operator.hash, library.value.operatorHash); assertNull(library.value.subjectHash)
            assertEquals(listOf(operator.hash), library.value.entries.map { it.hash })
        }
    }

    @Test fun recordingSelectionRequiresIrreversibleConfirmationAndNeverInheritsMonitorIntent() {
        val entry = entry()
        library.value = LutLibraryState(listOf(entry), entry.hash, subjectHash = entry.hash)
        show()
        compose.runOnIdle { assertNull(library.value.recordingHash); assertTrue(fileSelections.isEmpty()) }
        click("recording-select-${entry.hash}")
        node("recording-confirm-help").assertTextEquals(string(R.string.lut_recording_confirm_help))
        node("recording-cancel").performClick()
        compose.runOnIdle { assertNull(library.value.recordingHash); assertTrue(fileSelections.isEmpty()) }
        click("recording-select-${entry.hash}"); node("recording-confirm").performClick()
        compose.runOnIdle {
            assertEquals(listOf(entry.hash), fileSelections)
            assertEquals(entry.hash, library.value.recordingHash)
            assertEquals(entry.hash, library.value.operatorHash); assertEquals(entry.hash, library.value.subjectHash)
        }
        node("recording-selected-${entry.hash}").performScrollTo().assertIsDisplayed()
        node("recording-status").performScrollTo().assertTextEquals(string(R.string.lut_recording_status_disabled))
        click("recording-disable")
        compose.runOnIdle {
            assertNull(library.value.recordingHash)
            assertEquals(entry.hash, library.value.operatorHash); assertEquals(entry.hash, library.value.subjectHash)
        }
    }
    @Test fun recordingControlsAndOpenConfirmationFreezeWhileMonitorsRemainEditable() {
        val first = entry("File selection"); val second = entry("Other monitor")
        library.value = LutLibraryState(listOf(first, second), recordingHash = first.hash)
        show()
        click("recording-select-${second.hash}")
        compose.runOnIdle { status.value = CameraUiState(phase = CameraUiPhase.CAPTURING, selectedMode = CaptureMode.VIDEO) }
        node("recording-confirm").assertIsNotEnabled()
        node("recording-cancel").performClick()
        for (frozen in listOf(CameraUiState(phase = CameraUiPhase.CAPTURING, selectedMode = CaptureMode.VIDEO),
            CameraUiState(phase = CameraUiPhase.RECORDING), CameraUiState(phase = CameraUiPhase.PREVIEWING, recordingFinalizing = true),
            CameraUiState(phase = CameraUiPhase.PREVIEWING, stillCapturePending = true))) {
            compose.runOnIdle { status.value = frozen.copy(recordingLutSelectionPending = true,
                recordingLutStatus = OperatorLutStatus(first.hash, OperatorLutState.ACTIVE)) }
            node("recording-select-${second.hash}").performScrollTo().assertIsNotEnabled()
            node("recording-disable").performScrollTo().assertIsNotEnabled()
            node("delete-${first.hash}").performScrollTo().assertIsNotEnabled()
            node("recording-pending").performScrollTo().assertIsDisplayed()
            node("recording-status").performScrollTo().assertTextEquals(string(R.string.lut_recording_status_active))
            compose.runOnIdle { assertEquals(first.hash, library.value.recordingHash); assertTrue(fileSelections.isEmpty()) }
        }
        click("select-${second.hash}"); click("subject-select-${second.hash}")
        compose.runOnIdle {
            assertEquals(second.hash, library.value.operatorHash); assertEquals(second.hash, library.value.subjectHash)
            assertEquals(first.hash, library.value.recordingHash)
            library.value = LutLibraryState(library.value.entries, library.value.operatorHash, LutLibraryError.CORRUPT,
                library.value.subjectHash, library.value.recordingHash)
        }
        node("reset").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, resetCount) }
    }

    @Test fun pendingPickerDeclarationAndExportHashSurviveSavedStateRestorationAndConsumeOnce() {
        val restoration = StateRestorationTester(compose)
        var holder: LutPickerPending? = null
        restoration.setContent {
            val pending = rememberLutPickerPending()
            SideEffect { holder = pending }
        }
        val draft = LutImportDraft("Declared before picker", LutTransformKind.TECHNICAL, LutSignalDomain.OCLOG2_CODE)
        compose.runOnIdle { requireNotNull(holder).importDraft = draft }
        val originalImportHolder = requireNotNull(holder)
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle {
            val restored = requireNotNull(holder)
            assertNotSame(originalImportHolder, restored)
            assertTrue(restored.awaitingResult)
            assertEquals(draft, restored.takeImport())
            assertNull(restored.takeImport())
            assertFalse(restored.awaitingResult)
            restored.exportHash = entry().hash
        }
        val originalExportHolder = requireNotNull(holder)
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle {
            val restored = requireNotNull(holder)
            assertNotSame(originalExportHolder, restored)
            assertNull(restored.importDraft)
            assertTrue(restored.awaitingResult)
            assertEquals(entry().hash, restored.takeExport())
            assertNull(restored.takeExport())
            assertFalse(restored.awaitingResult)
        }
    }

    @Test fun actualAtomicFileReopensOriginalAndPreservesCorruptionUntilExplicitReset() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "lut-native-${UUID.randomUUID()}")
        val isolated = object : ContextWrapper(context) { override fun getNoBackupFilesDir() = directory }
        try {
            val first = LutLibrary(isolated)
            val raw = cube().toString(Charsets.UTF_8).replace("\n", "\r\n").toByteArray()
            val entry = first.importLut(raw, "Native fixture", LutTransformKind.TECHNICAL, LutSignalDomain.SDR_BT709_CODE)
            first.select(entry.hash); first.selectSubject(entry.hash); first.selectRecording(entry.hash)
            // Simulate a process interruption before AtomicFile replaces an already committed file.
            File(directory, "operator-lut-library.bin.new").writeBytes(byteArrayOf(7, 8, 9))
            val reopened = LutLibrary(isolated)
            assertEquals(entry.hash, reopened.active()?.cube?.sha256)
            assertEquals(entry.hash, reopened.activeSubject()?.cube?.sha256)
            assertEquals(entry.hash, reopened.activeRecording()?.cube?.sha256)
            assertArrayEquals(raw, reopened.export(entry.hash))
            val file = File(directory, "operator-lut-library.bin")
            val broken = file.readBytes().also { it[7] = 99 }
            file.writeBytes(broken)
            val corrupt = LutLibrary(isolated)
            assertEquals(LutLibraryError.CORRUPT, corrupt.states.value.error); assertNull(corrupt.active())
            assertArrayEquals(broken, file.readBytes())
            corrupt.reset()
            val empty = LutLibrary(isolated)
            assertNull(empty.states.value.error); assertTrue(empty.states.value.entries.isEmpty())
            assertNull(empty.states.value.operatorHash); assertNull(empty.states.value.subjectHash); assertNull(empty.states.value.recordingHash)
        } finally { directory.deleteRecursively() }
    }

    private fun show(fontScale: Float = 1f) {
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(fontScale)) {
                MaterialTheme {
                    Column(Modifier.width(280.dp).height(440.dp).verticalScroll(rememberScrollState())) {
                        LutLibraryContent(library.value, status.value,
                            onImport = { name, kind, input -> imported += Triple(name, kind, input) },
                            onExport = { exported += it },
                            onSelect = { library.value = LutLibraryState(library.value.entries, it, subjectHash = library.value.subjectHash, recordingHash = library.value.recordingHash) },
                            onDelete = { hash -> deleted += hash; library.value = LutLibraryState(library.value.entries.filterNot { it.hash == hash }, library.value.operatorHash?.takeUnless { it == hash }, subjectHash = library.value.subjectHash?.takeUnless { it == hash }, recordingHash = library.value.recordingHash?.takeUnless { it == hash }) },
                            onReset = { resetCount++; library.value = LutLibraryState() },
                            onSelectSubject = { library.value = LutLibraryState(library.value.entries, library.value.operatorHash, subjectHash = it, recordingHash = library.value.recordingHash) },
                            onSelectRecording = { fileSelections += it; library.value = LutLibraryState(library.value.entries, library.value.operatorHash, subjectHash = library.value.subjectHash, recordingHash = it) })
                    }
                }
            }
        }
    }
    private fun node(tag: String) = compose.onNodeWithTag("lut-$tag")
    private fun click(tag: String) { node(tag).performScrollTo().assertIsEnabled().performClick() }
    private fun string(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)
    private fun entry(name: String = "Exact original"): LutLibraryEntry {
        val raw = cube() + "# $name\n".toByteArray(); val cube = CubeLut.parse(raw)
        return LutLibraryEntry(cube.sha256, name, LutTransformKind.CREATIVE, LutSignalDomain.SDR_BT709_CODE,
            cube.size, raw.size, cube.domainMin.toList(), cube.domainMax.toList())
    }
    private fun cube() = ("# native fixture\nLUT_3D_SIZE 2\n" +
        "0 0 0\n1 0 0\n0 1 0\n1 1 0\n0 0 1\n1 0 1\n0 1 1\n1 1 1\n").toByteArray()
}
