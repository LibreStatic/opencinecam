/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage

import java.io.StringWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GyroflowLogTest {
    private fun header(synced: Boolean = true, accel: Boolean = true, readout: Double? = 12.5) =
        GyroflowLog.header(
            GyroflowLog.Header("yxz", "A,B\nC.mp4", 1_700_000_000L, "0.3.5-beta", readout, 0, synced, accel),
        )

    @Test fun rearPortraitSensorMapsToYxz() {
        assertEquals("yxz", GyroflowLog.orientation(90, frontFacing = false, storedPixelRotationDegrees = 0))
    }

    @Test fun rearUnrotatedSensorUsesDeviceAxes() {
        assertEquals("Xyz", GyroflowLog.orientation(0, false, 0))
    }

    @Test fun frontCameraMirrorsHorizontalAxis() {
        assertEquals("xyZ", GyroflowLog.orientation(0, true, 0))
    }

    @Test fun storedRotationCancelsSensorOrientation() {
        assertEquals(GyroflowLog.orientation(0, false, 0), GyroflowLog.orientation(90, false, 90))
    }

    @Test fun nonRightAnglesRoundToNearestQuarterTurn() {
        assertEquals(GyroflowLog.orientation(90, false, 0), GyroflowLog.orientation(92, false, 0))
        assertEquals(GyroflowLog.orientation(270, false, 0), GyroflowLog.orientation(-88, false, 0))
    }

    @Test fun readoutDirectionFollowsStoredRotation() {
        assertEquals(listOf(0, 3, 1, 2), listOf(0, 90, 180, 270).map { GyroflowLog.readoutDirection(it) })
    }

    @Test fun headerListsFieldsInOrderAndSanitizesFileName() {
        val lines = header().lines()
        assertEquals("GYROFLOW IMU LOG", lines[0])
        assertEquals("version,1.3", lines[1])
        assertTrue(lines.contains("videofilename,A B C.mp4"))
        assertTrue(lines.contains("orientation,yxz"))
        assertTrue(lines.contains("frame_readout_time,12.5000"))
        assertTrue(lines.contains("tscale,0.000001"))
        assertTrue(lines.contains("gscale,0.00001"))
        assertTrue(lines.contains("ascale,0.00001"))
        assertEquals("t,gx,gy,gz,ax,ay,az", lines[lines.indexOf("ascale,0.00001") + 1])
    }

    @Test fun headerNotesUnsyncedAndOmitsMissingParts() {
        val text = header(synced = false, accel = false, readout = null)
        assertTrue(text.contains("note,unsynced"))
        assertFalse(text.contains("frame_readout"))
        assertFalse(text.contains("ascale"))
        assertTrue(text.endsWith("t,gx,gy,gz\n"))
    }

    @Test fun rowScalesGyroToRadiansAndAccelerationToG() {
        val sample = GyroflowLog.Sample(0, 1.5f, -0.25f, 0f, 9.80665f, 0f, -19.6133f)
        assertEquals("42,150000,-25000,0,100000,0,-200000\n", GyroflowLog.row(42, sample, true))
        assertEquals("42,150000,-25000,0\n", GyroflowLog.row(42, sample, false))
    }

    @Test fun anchorMapsNanosecondsToMicrosAndRejectsEarlierSamples() {
        assertEquals(0L, GyroflowLog.relativeMicros(1_000_000, 1_000_000))
        assertEquals(2_500L, GyroflowLog.relativeMicros(3_500_999, 1_000_000))
        assertNull(GyroflowLog.relativeMicros(999_999, 1_000_000))
    }

    @Test fun pairerUsesNearestAccelerometerSample() {
        val out = mutableListOf<GyroflowLog.Sample>()
        val pairer = GyroflowLog.Pairer { out.add(it) }
        pairer.onAccel(0, 1f, 0f, 0f)
        pairer.onGyro(2, 0f, 0f, 0f)
        pairer.onGyro(9, 0f, 0f, 0f)
        pairer.onAccel(10, 2f, 0f, 0f)
        assertEquals(listOf(1f, 2f), out.map { it.ax })
    }

    @Test fun pairerFlushUsesLastAccelerometerAndDropsWhenNone() {
        val out = mutableListOf<GyroflowLog.Sample>()
        val pairer = GyroflowLog.Pairer { out.add(it) }
        pairer.onGyro(1, 0f, 0f, 0f)
        pairer.flush()
        assertTrue(out.isEmpty())
        pairer.onAccel(0, 3f, 0f, 0f)
        pairer.onGyro(5, 0f, 0f, 0f)
        pairer.flush()
        assertEquals(listOf(3f), out.map { it.ax })
    }

    @Test fun streamBuffersUntilAnchorThenDropsEarlierSamples() {
        val writer = StringWriter()
        val stream = GyroflowLog.Stream(writer, { synced -> "H${if (synced) 1 else 0}\n" }, false)
        fun s(ns: Long) = GyroflowLog.Sample(ns, 0f, 0f, 0f, 0f, 0f, 0f)
        stream.add(s(1_000))
        stream.add(s(5_000))
        assertEquals("", writer.toString())
        stream.setAnchor(2_000, synced = true)
        stream.add(s(4_000))
        stream.setAnchor(9_000, synced = false)
        stream.close()
        stream.add(s(6_000))
        assertEquals("H1\n3,0,0,0\n2,0,0,0\n", writer.toString())
        assertEquals(2L, stream.rows)
    }
}
