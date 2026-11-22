/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage

import com.librestatic.opencinecam.motion.MappedSeries
import java.io.StringWriter
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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

    private fun magHeader(accel: Boolean, note: String? = null) = GyroflowLog.header(
        GyroflowLog.Header(
            "yxz", "A.mp4", 1_700_000_000L, "fw", null, 0, synced = true, hasAccelerometer = accel,
            hasMagnetometer = true, note = note,
        ),
    )

    @Test fun headerWithMagnetometerAddsScaleAndColumns() {
        val lines = magHeader(accel = true).lines()
        assertEquals("mscale,0.00001", lines[lines.indexOf("ascale,0.00001") + 1])
        assertEquals("t,gx,gy,gz,ax,ay,az,mx,my,mz", lines[lines.indexOf("mscale,0.00001") + 1])
    }

    @Test fun magnetometerWithoutAccelerometerIsOmitted() {
        val text = magHeader(accel = false)
        assertFalse(text.contains("mscale"))
        assertTrue(text.endsWith("t,gx,gy,gz\n"))
        val sample = GyroflowLog.Sample(0, 1f, 0f, 0f, 0f, 0f, 0f, 40f, 0f, 0f)
        assertEquals("7,100000,0,0\n", GyroflowLog.row(7, sample, withAccelerometer = false, withMagnetometer = true))
    }

    @Test fun noteReplacesTheSyncText() {
        assertTrue(magHeader(accel = true, note = "rendered\nat stop").contains("\nnote,rendered at stop\n"))
        assertTrue(magHeader(accel = true).contains("\nnote,t=0 at first recorded frame\n"))
        assertTrue(header(synced = false).contains("\nnote,unsynced: t=0 at recording start\n"))
    }

    @Test fun rowWritesMagnetometerInGauss() {
        val sample = GyroflowLog.Sample(0, 1.5f, 0f, 0f, 9.80665f, 0f, 0f, 45f, -12.5f, 0.001f)
        assertEquals("3,150000,0,0,100000,0,0,45000,-12500,1\n", GyroflowLog.row(3, sample, true, withMagnetometer = true))
        assertEquals("3,150000,0,0,100000,0,0\n", GyroflowLog.row(3, sample, true))
    }

    private fun series(times: LongArray, x: FloatArray) =
        MappedSeries(times, x, FloatArray(times.size), FloatArray(times.size))

    @Test fun renderPairsEachGyroRowWithTheNearestAccelerometerAndMagnetometer() {
        val out = StringWriter()
        val gyro = series(longArrayOf(0, 10, 20, 30), floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f))
        val g = GyroflowLog.STANDARD_GRAVITY.toFloat()
        // 10 is 6 from 4 and 5 from 15; 20 is 5 from both 15 and 25, and a tie takes the later one.
        val accel = series(longArrayOf(4, 15, 25), floatArrayOf(g, 2 * g, 3 * g))
        val mag = series(longArrayOf(0, 100), floatArrayOf(10f, 20f))
        val header = GyroflowLog.Header("yxz", "A.mp4", 1L, "fw", null, 0, true, true, hasMagnetometer = true)
        val rows = GyroflowLog.render(out, header, gyro, accel, mag)
        assertEquals(4L, rows)
        val lines = out.toString().lines()
        val columns = lines.indexOf("t,gx,gy,gz,ax,ay,az,mx,my,mz")
        assertTrue(columns > 0)
        assertEquals(
            listOf(
                "0,10000,0,0,100000,0,0,10000,0,0",
                "10,20000,0,0,200000,0,0,10000,0,0",
                "20,30000,0,0,300000,0,0,10000,0,0",
                "30,40000,0,0,300000,0,0,10000,0,0",
                "",
            ),
            lines.drop(columns + 1),
        )
    }

    @Test fun renderDropsColumnsOfMissingSensors() {
        val out = StringWriter()
        val gyro = series(longArrayOf(5), floatArrayOf(1f))
        val header = GyroflowLog.Header("yxz", "A.mp4", 1L, "fw", null, 0, true, true, hasMagnetometer = true)
        assertEquals(1L, GyroflowLog.render(out, header, gyro, MappedSeries.EMPTY, series(longArrayOf(5), floatArrayOf(3f))))
        val text = out.toString()
        assertFalse(text.contains("ascale"))
        assertFalse(text.contains("mscale"))
        assertTrue(text.endsWith("t,gx,gy,gz\n5,100000,0,0\n"))
    }

    private fun assertApplied(expected: FloatArray, orientation: String, x: Float, y: Float, z: Float) =
        assertArrayEquals(expected, GyroflowLog.applyOrientation(orientation, x, y, z), 0f)

    @Test fun applyOrientationRearPortraitByHand() {
        // Back camera, sensor orientation 90, stored rotation 0: orientation() gives "yxz". The raw
        // buffer must be turned a quarter clockwise to stand upright, so its left edge is the device
        // top: the device's up (+y) is image left (-X), the device's right (+x) is the buffer's top
        // (image up, -Y), and the lens looks out of the back (-z), so forward (+Z) is -z.
        val orientation = GyroflowLog.orientation(90, frontFacing = false, storedPixelRotationDegrees = 0)
        assertEquals("yxz", orientation)
        assertApplied(floatArrayOf(-1f, 0f, 0f), orientation, 0f, 1f, 0f)
        assertApplied(floatArrayOf(0f, -1f, 0f), orientation, 1f, 0f, 0f)
        // A turn about the device's out-of-screen axis is a turn about the lens's backward axis.
        assertApplied(floatArrayOf(0f, 0f, -1f), orientation, 0f, 0f, 1f)
        assertApplied(floatArrayOf(-2f, -1f, -3f), orientation, 1f, 2f, 3f)
    }

    /** Rows of the matrix taking raw device axes to image axes. */
    private fun matrix(orientation: String) = arrayOf(
        GyroflowLog.applyOrientation(orientation, 1f, 0f, 0f),
        GyroflowLog.applyOrientation(orientation, 0f, 1f, 0f),
        GyroflowLog.applyOrientation(orientation, 0f, 0f, 1f),
    ).let { columns -> Array(3) { row -> FloatArray(3) { column -> columns[column][row] } } }

    private fun determinant(m: Array<FloatArray>): Float =
        m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1]) -
            m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0]) +
            m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0])

    @Test fun applyOrientationAgreesWithOrientationForEveryStoredRotation() {
        for (front in listOf(false, true)) {
            val sensor = if (front) 270 else 90
            val strings = listOf(0, 90, 180, 270).map { GyroflowLog.orientation(sensor, front, it) }
            for ((index, orientation) in strings.withIndex()) {
                val m = matrix(orientation)
                // Letter i names the device axis feeding image axis i.
                for (i in 0 until 3) {
                    val axis = "XYZ".indexOf(orientation[i].uppercaseChar())
                    val sign = if (orientation[i].isUpperCase()) 1f else -1f
                    for (j in 0 until 3) assertEquals("$orientation[$i][$j]", if (j == axis) sign else 0f, m[i][j], 0f)
                }
                assertEquals("$orientation is a proper rotation", 1f, determinant(m), 0f)
                assertEquals("forward is through the lens", if (front) 1f else -1f, m[2][2], 0f)
                if (index == 0) continue
                // Each further quarter turn of the stored pixels turns the image axes a quarter
                // about forward: the back camera's new X is the old -Y, the mirrored front's the old Y.
                val previous = matrix(strings[index - 1])
                val turn = if (front) 1f else -1f
                for (j in 0 until 3) {
                    assertEquals(turn * previous[1][j], m[0][j], 0f)
                    assertEquals(-turn * previous[0][j], m[1][j], 0f)
                    assertEquals(previous[2][j], m[2][j], 0f)
                }
            }
        }
    }

    @Test fun applyOrientationRejectsMalformedStrings() {
        for (bad in listOf("xy", "xyzz", "xxz", "xYy", "abc")) {
            try {
                GyroflowLog.applyOrientation(bad, 1f, 2f, 3f)
                fail("accepted $bad")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message!!.isNotEmpty())
            }
        }
        assertArrayEquals(floatArrayOf(1f, 2f, 3f), GyroflowLog.applyOrientation("XYZ", 1f, 2f, 3f), 0f)
    }
}
