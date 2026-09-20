/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.opencinecam.camera.*
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class TimecodeContinuityFileTest {
    private fun fixture(block: (File) -> Unit) {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "timecode-store-${System.nanoTime()}")
        check(root.mkdirs()); try { block(root) } finally { root.deleteRecursively() }
    }
    @Test fun atomicFileRestoresCompletedRecordRunAndRegenAcrossFreshInstances() = fixture { root ->
        for (mode in listOf(TimecodeMode.RECORD_RUN, TimecodeMode.REGEN)) {
            val file = File(root, mode.name + ".json"); val rate = TimecodeRate(30, true); val start = SmpteTimecode(0, 0, 59, 29, true)
            val first = TimecodeTracker(TimecodeContinuationFile(file)) { 0 }; first.configure(rate, mode, start, true)
            first.onRecordingStarted(1); first.observeEncodedProgress(EncodedRecordingProgress(1, 3, 0, 66666)); first.onRecordingStopped(true)
            assertTrue(file.length() in 1..4096); assertFalse(File(file.path + ".new").exists())
            val second = TimecodeTracker(TimecodeContinuationFile(file)) { 0 }; second.configure(rate, mode, start, true)
            second.onRecordingStarted(2); second.observeEncodedProgress(EncodedRecordingProgress(2, 1, 0, 0))
            assertEquals("00:01:00;04", second.currentDisplayTc()!!.format()); assertFalse(second.continuationStorageFailed)
        }
    }
    @Test fun interruptedAtomicWriteKeepsTheLastCompletedPosition() = fixture { root ->
        val file = File(root, "state.json"); val store = TimecodeContinuationFile(file)
        val value = TimecodeContinuation(TimecodeConfig(), 10, null); store.save(value)
        File(file.path + ".new").writeText("incomplete replacement")
        assertEquals(value, TimecodeContinuationFile(file).load())
        store.clear(); assertNull(TimecodeContinuationFile(file).load()); assertFalse(file.exists())
    }
    @Test fun corruptAndOversizedStateAreRejectedAndFailureIsReported() = fixture { root ->
        val file = File(root, "state.json")
        for (bytes in listOf("broken", "x".repeat(4097))) {
            file.writeText(bytes)
            assertThrows(Exception::class.java) { TimecodeContinuationFile(file).load() }
            val tracker = TimecodeTracker(TimecodeContinuationFile(file)) { 0 }
            tracker.configure(TimecodeRate(30), TimecodeMode.REGEN, SmpteTimecode(1, 0, 0, 0), true)
            assertTrue(tracker.continuationStorageFailed)
            tracker.onRecordingStarted(1); tracker.observeEncodedProgress(EncodedRecordingProgress(1, 1, 0, 0))
            assertEquals("01:00:00:00", tracker.currentDisplayTc()!!.format())
        }
    }
    @Test fun malformedNumericTypesAndUnknownFieldsAreNotSilentlyCoerced() = fixture { root ->
        val file = File(root, "state.json"); val store = TimecodeContinuationFile(file)
        store.save(TimecodeContinuation(TimecodeConfig(), 10, null))
        val original = file.readText()
        for ((key, value) in listOf("recordRunCursor" to "10", "recordRunCursor" to 1.5, "enabled" to "true", "surprise" to 1)) {
            file.writeText(org.json.JSONObject(original).put(key, value).toString())
            assertThrows(IllegalArgumentException::class.java) { store.load() }
        }
    }
    @Test fun unwritableStoreDoesNotAbortAnOtherwiseValidTake() = fixture { root ->
        val parent = File(root, "not-directory"); parent.writeText("occupied")
        val tracker = TimecodeTracker(TimecodeContinuationFile(File(parent, "state.json"))) { 0 }
        tracker.configure(TimecodeRate(30), TimecodeMode.RECORD_RUN, SmpteTimecode(1, 0, 0, 0), true)
        assertTrue(tracker.continuationStorageFailed)
        tracker.onRecordingStarted(1); tracker.observeEncodedProgress(EncodedRecordingProgress(1, 3, 0, 66666)); tracker.onRecordingStopped(true)
        tracker.onRecordingStarted(2); tracker.observeEncodedProgress(EncodedRecordingProgress(2, 1, 0, 0))
        assertEquals("01:00:00:03", tracker.currentDisplayTc()!!.format())
    }
}
