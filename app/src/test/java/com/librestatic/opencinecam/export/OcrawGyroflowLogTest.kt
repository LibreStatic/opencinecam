/* SPDX-License-Identifier: Apache-2.0 */

package com.librestatic.opencinecam.export

import com.librestatic.opencinecam.media.ocraw.OcrawFrameHeader
import com.librestatic.opencinecam.media.ocraw.OcrawHeader
import com.librestatic.opencinecam.media.ocraw.OcrawImuMetadata
import com.librestatic.opencinecam.media.ocraw.OcrawImuSamples
import com.librestatic.opencinecam.media.ocraw.OcrawStreamReader
import com.librestatic.opencinecam.media.ocraw.OcrawStreamWriter
import com.librestatic.opencinecam.media.ocraw.OcrawTimeBase
import java.io.File
import java.io.RandomAccessFile
import java.io.StringWriter
import java.math.BigInteger
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The `.gcsv` text of a RAW take's `IMU_SAMPLES` chunks, as `gcsv_text` in `desktop-tools/ocraw_to_dng.py` writes it. */
class OcrawGyroflowLogTest {
    /** Under the module's build directory: /tmp is RAM-backed on the dev hosts. */
    @get:Rule
    val temp = TemporaryFolder(File("build/tmp/ocraw-gcsv-test").apply { mkdirs() })

    private class Sample(val kind: Int, val timeNs: Long, val x: Float, val y: Float, val z: Float)

    private fun gyro(timeNs: Long, i: Int) = Sample(OcrawImuSamples.KIND_GYROSCOPE, timeNs, 0.01f * i, -0.02f, 0.5f)
    private fun accel(timeNs: Long, x: Float, y: Float, z: Float) = Sample(OcrawImuSamples.KIND_ACCELEROMETER, timeNs, x, y, z)
    private fun mag(timeNs: Long, x: Float, y: Float, z: Float) = Sample(OcrawImuSamples.KIND_MAGNETOMETER, timeNs, x, y, z)

    /** A take of [frames] RAW10 frames 1/30 s apart from 2 s, the [chunks] of samples written after its first frame. */
    private fun take(metadata: Map<String, String>, frames: Int, chunks: List<List<Sample>>): File {
        val file = temp.newFile()
        RandomAccessFile(file, "rw").use { raf ->
            val writer = OcrawStreamWriter(raf.channel, OcrawHeader(ByteArray(16)), OcrawTimeBase(1, 1_000_000_000),
                OcrawStreamWriter.Options(forceOnFinish = false), force = null)
            writer.writeMetadata(metadata, frameTs(0))
            for (index in 0 until frames) {
                val header = OcrawFrameHeader.raw10(32, 4, 0, 0, frameTs(index), index.toLong())
                writer.writeRawFrame(header, ByteBuffer.allocateDirect(header.dataLength), frameTs(index))
                if (index == 0) {
                    for (chunk in chunks) {
                        val payload = OcrawImuSamples.encode(
                            ByteArray(chunk.size) { chunk[it].kind.toByte() },
                            LongArray(chunk.size) { chunk[it].timeNs },
                            FloatArray(chunk.size * 3) { chunk[it / 3].let { s -> floatArrayOf(s.x, s.y, s.z)[it % 3] } },
                        )
                        writer.writeImuSamples(chunk.first().timeNs, payload)
                    }
                }
            }
            writer.finish()
        }
        return file
    }

    private fun render(file: File, name: String = "A001_C002.ocraw"): Pair<String, OcrawGyroflowLog.Report?> =
        RandomAccessFile(file, "r").use { raf ->
            val reader = OcrawStreamReader(raf.channel)
            val index = reader.indexFrames()
            val out = StringWriter()
            val report = OcrawGyroflowLog.render(out, reader, index, name)
            out.toString() to report
        }

    @Test
    fun interruptionStopsTheScanAndTheRenderBetweenChunks() {
        val file = take(mapOf("fps" to "30/1"), frames = 3, chunks = listOf(
            listOf(gyro(2_000_000_000L, 1), gyro(2_001_000_000L, 2)),
            listOf(gyro(2_002_000_000L, 3), gyro(2_003_000_000L, 4)),
        ))
        RandomAccessFile(file, "r").use { raf ->
            val reader = OcrawStreamReader(raf.channel)
            val index = reader.indexFrames()
            try {
                OcrawGyroflowLog.sensors(reader, index) { true }
                throw AssertionError("an interrupted scan must throw")
            } catch (_: OcrawGyroflowLog.Interrupted) {
            }
            val sensors = OcrawGyroflowLog.sensors(reader, index)
            // Interrupted once the first chunk is read: its rows are out, the second chunk is never read.
            var asked = 0
            val out = StringWriter()
            try {
                OcrawGyroflowLog.render(out, reader, index, "A001_C002.ocraw", sensors) { ++asked > 1 }
                throw AssertionError("an interrupted render must throw")
            } catch (_: OcrawGyroflowLog.Interrupted) {
            }
            assertEquals(2, asked)
            assertEquals(2, out.toString().lines().count { it.startsWith("0,") || it.startsWith("1000,") })
        }
    }

    @Test
    fun rendersTheDesktopToolsLog() {
        val metadata = linkedMapOf(
            "fps" to "30/1",
            "appVersion" to "1.2.3",
            OcrawImuMetadata.ORIENTATION to "yxz",
            OcrawImuMetadata.READOUT_DIRECTION to "0",
            OcrawImuMetadata.CLOCK to OcrawImuMetadata.CLOCK_ESTIMATED,
            OcrawImuMetadata.TAKE_START_UNIX_MS to "1763700000123",
            OcrawImuMetadata.ROLLING_SHUTTER_SKEW_NS to "12345678",
        )
        // Frames at 2.0, 2.0333 and 2.0667 s; the last one's interval ends at 2.1 s. The samples span two chunks.
        val file = take(metadata, frames = 3, chunks = listOf(
            listOf(
                gyro(1_999_000_000L, 0), // before the first frame: dropped
                gyro(2_000_000_000L, 1),
                mag(2_000_000_000L, 100f, -50f, 25f),
                accel(2_004_000_000L, 9.80665f, 0f, -9.80665f),
                gyro(2_005_000_000L, 2), // 1 ms from either accelerometer sample: the later one
            ),
            listOf(
                accel(2_006_000_000L, 0f, 4.903325f, 0f),
                gyro(2_010_000_500L, 3),
                gyro(2_099_999_999L, 4), // within the last frame's interval
                gyro(2_100_000_000L, 5), // past it: dropped
            ),
        ))

        val (text, report) = render(file)
        assertEquals(
            """
            GYROFLOW IMU LOG
            version,1.3
            id,opencinecam
            orientation,yxz
            note,sync estimated: camera clock is not the sensor clock
            fwversion,1.2.3
            timestamp,1763700000
            vendor,OpenCineCam
            videofilename,A001_C002.ocraw
            frame_readout_time,12.3457
            frame_readout_direction,0
            tscale,0.000001
            gscale,0.00001
            ascale,0.00001
            mscale,0.00001
            t,gx,gy,gz,ax,ay,az,mx,my,mz
            0,1000,-2000,50000,100000,0,-100000,100000,-50000,25000
            5000,2000,-2000,50000,0,50000,0,100000,-50000,25000
            10000,3000,-2000,50000,0,50000,0,100000,-50000,25000
            99999,4000,-2000,50000,0,50000,0,100000,-50000,25000

            """.trimIndent(),
            text,
        )
        assertNotNull(report)
        assertEquals(4L, report!!.rows)
        assertEquals(2L, report.droppedRows)
        assertTrue(report.accelerometer)
        assertTrue(report.magnetometer)
        assertFalse(report.synced)
    }

    @Test
    fun sharedClockGyroscopeOnlyAndNoFrameRate() {
        val metadata = mapOf(
            OcrawImuMetadata.ORIENTATION to "not an orientation",
            OcrawImuMetadata.CLOCK to OcrawImuMetadata.CLOCK_SHARED,
            OcrawImuMetadata.TAKE_START_UNIX_MS to "12x",
            OcrawImuMetadata.ROLLING_SHUTTER_SKEW_NS to "0",
        )
        // A magnetometer without an accelerometer is not written; with one frame and no frame rate, nothing after it.
        val file = take(metadata, frames = 1, chunks = listOf(listOf(
            gyro(2_000_000_000L, 1), mag(2_000_000_000L, 1f, 1f, 1f), gyro(2_000_001_000L, 2),
        )))
        val (text, report) = render(file, "B.ocraw")
        assertEquals(
            """
            GYROFLOW IMU LOG
            version,1.3
            id,opencinecam
            note,t=0 at first recorded frame
            vendor,OpenCineCam
            videofilename,B.ocraw
            tscale,0.000001
            gscale,0.00001
            t,gx,gy,gz
            0,1000,-2000,50000

            """.trimIndent(),
            text,
        )
        assertEquals(1L, report!!.rows)
        assertEquals(1L, report.droppedRows)
        assertFalse(report.magnetometer)
        assertTrue(report.synced)
    }

    @Test
    fun aTakeWithoutGyroscopeSamplesHasNoLog() {
        val file = take(emptyMap(), frames = 2, chunks = listOf(listOf(accel(2_000_000_000L, 1f, 2f, 3f))))
        val (text, report) = render(file)
        assertNull(report)
        assertEquals("", text)
        val none = take(emptyMap(), frames = 2, chunks = emptyList())
        assertNull(render(none).second)
    }

    @Test
    fun parsesTheFrameRateLikeAFraction() {
        assertEquals(BigInteger.valueOf(30) to BigInteger.ONE, OcrawGyroflowLog.parseFps("30/1"))
        assertEquals(BigInteger.valueOf(30_000) to BigInteger.valueOf(1_001), OcrawGyroflowLog.parseFps(" 30000/1001 "))
        assertEquals(BigInteger.valueOf(2_997) to BigInteger.valueOf(100), OcrawGyroflowLog.parseFps("29.97"))
        assertEquals(BigInteger.valueOf(24) to BigInteger.ONE, OcrawGyroflowLog.parseFps("24"))
        assertNull(OcrawGyroflowLog.parseFps("0"))
        assertNull(OcrawGyroflowLog.parseFps("30/0"))
        assertNull(OcrawGyroflowLog.parseFps("fast"))
        assertNull(OcrawGyroflowLog.parseFps(null))
    }

    private fun frameTs(index: Int): Long = 2_000_000_000L + index * 33_333_333L
}
