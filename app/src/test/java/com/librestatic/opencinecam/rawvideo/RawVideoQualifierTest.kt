/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.rawvideo

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.librestatic.opencinecam.storage.RawVideoQualificationStore
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The camera-free paths of [RawVideoQualifier]: Robolectric advertises no camera. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class RawVideoQualifierTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private class Recorder : RawVideoQualifier.Listener {
        val progress = mutableListOf<RawQualificationProgress>()
        val results = mutableListOf<JSONObject>()
        override fun onProgress(progress: RawQualificationProgress) { this.progress += progress }
        override fun onResult(result: JSONObject) { results += result }
    }

    @Test fun cancelledBeforeItStartsItReturnsCancelledWithoutTouchingTheStore() {
        val listener = Recorder()
        val qualifier = RawVideoQualifier(context, listener = listener)
        val file = File(context.filesDir, RawVideoQualificationStore.FILE_NAME)
        file.delete()
        qualifier.cancel()
        assertTrue(qualifier.isCancelled)
        val outcome = qualifier.run(RawVideoQualificationStore(file, "fp"))
        assertEquals(RawQualificationStatus.CANCELLED, outcome.status)
        assertEquals(RawVideoQualifier.REASON_CANCELLED, outcome.reasonCode)
        assertEquals("CANCELLED", outcome.report.getString("status"))
        assertTrue(outcome.records.isEmpty())
        assertFalse(file.exists())
        assertTrue(listener.results.isEmpty())
        assertEquals(RawQualificationPhase.DONE, listener.progress.last().phase)
        assertEquals(1.0, listener.progress.last().fraction, 0.0)
    }

    @Test fun withoutARaw10CameraItIsUnknownAndUnsupported() {
        val qualifier = RawVideoQualifier(context)
        assertNull(qualifier.estimate())
        val outcome = qualifier.run()
        assertEquals(RawQualificationStatus.UNKNOWN, outcome.status)
        assertEquals(RawVideoQualifier.REASON_UNSUPPORTED, outcome.reasonCode)
        assertEquals(RawVideoQualifier.SCHEMA, outcome.report.getString("schema"))
        assertTrue(outcome.records.isEmpty())
        // Cancelling after the end is harmless.
        qualifier.cancel()
    }

    @Test(expected = IllegalStateException::class) fun aQualifierRunsOnce() {
        val qualifier = RawVideoQualifier(context)
        qualifier.run()
        qualifier.run()
    }
}
