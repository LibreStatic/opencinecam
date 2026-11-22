/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.librestatic.opencinecam.camera.RecordingFrameSize
import com.librestatic.opencinecam.camera.RecordingGeometry
import com.librestatic.opencinecam.camera.RecordingGeometryMode
import com.librestatic.opencinecam.motion.MotionTakeInfo
import com.librestatic.opencinecam.motion.MotionTakeRecorder
import com.librestatic.opencinecam.motion.MotionTakeRoute
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSensor

/** A video output's motion data follows the output: kept with an output that holds bytes, deleted with one that is deleted. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], shadows = [ShadowDocumentsProviderQuery::class])
class VideoOutputMotionTest {
    @get:Rule val temp = TemporaryFolder()
    private lateinit var context: Context
    private lateinit var drive: FakeExternalStorageProvider
    private lateinit var tree: Uri
    private lateinit var motion: MotionRecoveryFiles

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        drive = FakeExternalStorageProvider.install(temp.newFolder("drive"))
        check(drive.file("Rec").mkdir())
        tree = drive.tree("Rec")
        FakeExternalStorageProvider.grant(context, tree)
        File(context.noBackupFilesDir, "recording-recovery.sqlite").delete()
        File(context.filesDir, MotionTakeRecorder.SPOOL_DIRECTORY).deleteRecursively()
        motion = MotionRecoveryFiles(context)
        shadowOf(context.getSystemService(SensorManager::class.java)).addSensor(ShadowSensor.newInstance(Sensor.TYPE_GYROSCOPE))
    }

    private fun output(): Pair<VideoOutput, String> {
        val output = VideoOutput.create(context, null, null, RecordingDestination.UsbFolder(tree, drive.volume))
        val id = output.recoveryGroup.id
        val recorder = requireNotNull(MotionTakeRecorder.start(context, MotionTakeInfo(
            takeId = id, videoFileName = output.displayName, orientation = "yxz", readoutDirection = 0, frontFacing = false,
            sensorOrientationDegrees = 90, storedRotationDegrees = 0, cameraClockRealtime = true, clockOffsetNs = 0L,
            startedAtUnixMs = 1_700_000_000_000L, firmware = "OpenCineCam test", route = MotionTakeRoute.GPU, captureFps = 30.0,
            gyroMaxHz = 200.0, accelerometer = false, accelMaxHz = null,
        ))) { "the shadow gyroscope gives a recorder" }
        output.attachMotionRecorder(recorder)
        assertTrue(motion.spoolOf(id).isFile && motion.infoOf(id).isFile)
        return output to id
    }

    /** A geometry no file here can match, so the preparation throws and the transaction aborts the output. */
    private val unmatched = RecordingGeometry(
        RecordingGeometryMode.COMPATIBLE, 0, 0, 0, 0, 0,
        RecordingFrameSize(7, 5), RecordingFrameSize(7, 5), RecordingFrameSize(7, 5),
    )

    @Test fun anAbortedOutputThatHoldsBytesKeepsItsMotionDataForRecovery() {
        val (output, id) = output()
        FileOutputStream(output.descriptor.fileDescriptor).write(ByteArray(64) { 1 })
        try {
            output.prepareCompletion(expectedGeometry = unmatched)
            fail("a geometry mismatch must fail the preparation")
        } catch (_: IllegalStateException) {
        }
        assertTrue(motion.spoolOf(id).isFile)
        assertTrue(motion.infoOf(id).isFile)
    }

    @Test fun anAbortedEmptyOutputDeletesItsMotionData() {
        val (output, id) = output()
        output.discardNeverRecorded()
        assertFalse(motion.spoolOf(id).exists())
        assertFalse(motion.infoOf(id).exists())
    }
}
