/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.playback

import com.librestatic.opencinecam.media.ocraw.OcrawCodec
import com.librestatic.opencinecam.media.ocraw.OcrawFrameHeader
import com.librestatic.opencinecam.media.ocraw.OcrawHeader
import com.librestatic.opencinecam.media.ocraw.OcrawStreamReader
import com.librestatic.opencinecam.media.ocraw.OcrawStreamWriter
import com.librestatic.opencinecam.media.ocraw.OcrawTimeBase
import com.librestatic.opencinecam.media.raw.NativeRawCodec
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import kotlin.math.roundToInt
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RawThumbnailTest {
    /** Under the module's build directory: /tmp is RAM-backed on the dev hosts. */
    @get:Rule
    val temp = TemporaryFolder(File("build/tmp/raw-thumbnail-test").apply { mkdirs() })

    private val width = 16
    private val height = 8
    private val nanos = OcrawTimeBase(1, 1_000_000_000)
    private val colour = mapOf("cfa" to "RGGB", "blackLevel" to "64 64 64 64", "whiteLevel" to "1023")
    private val frameMetadata = mapOf("neutral" to "1/2 1/1 3/4", "iso" to "400")

    @Test fun theFirstFrameDevelopsAsTheReviewsHalfResolution() {
        val file = temp.newFile("plain.ocraw")
        val frames = (0 until 3).map(::frame)
        writeTake(file, frames)
        val thumbnail = render(file)!!
        assertEquals(width / 2, thumbnail.width)
        assertEquals(height / 2, thumbnail.height)
        assertArrayEquals(expected(frames[0]), thumbnail.argb)
        assertTrue(thumbnail.argb.all { it ushr 24 == 0xff })
    }

    @Test fun theTakesOrientationTurnsThePictureUpright() {
        val frames = listOf(frame(0))
        val stored = expected(frames[0])
        val storedWidth = width / 2
        val storedHeight = height / 2
        for (degrees in listOf(90, 180, 270)) {
            val file = temp.newFile("turned-$degrees.ocraw")
            writeTake(file, frames, colour + ("orientation" to "$degrees"))
            val thumbnail = render(file)!!
            val shown = RawDevelop.displaySize(storedWidth, storedHeight, degrees)
            assertEquals("$degrees", shown[0], thumbnail.width)
            assertEquals("$degrees", shown[1], thumbnail.height)
            // Every shown pixel is the stored one the review's blit samples at its centre.
            for (y in 0 until thumbnail.height) for (x in 0 until thumbnail.width) {
                val point = RawDevelop.orientedSample((x + 0.5f) / thumbnail.width, (y + 0.5f) / thumbnail.height, degrees)
                val sx = (point[0] * storedWidth).toInt()
                val sy = (point[1] * storedHeight).toInt()
                assertEquals("$degrees at ($x, $y)", stored[sy * storedWidth + sx], thumbnail.argb[y * thumbnail.width + x])
            }
        }
        // Half a turn is the picture upside down.
        val file = File(temp.root, "turned-180.ocraw")
        val turned = render(file)!!
        assertEquals(stored.last(), turned.argb.first())
        assertEquals(stored.first(), turned.argb.last())
    }

    @Test fun aLargeFrameIsSampledDownToTheLongEdge() {
        val file = temp.newFile("large.ocraw")
        val big = frame(0, frameWidth = 128, frameHeight = 32)
        writeTake(file, listOf(big))
        val thumbnail = FileChannel.open(file.toPath()).use { RawThumbnails.render(it, maxEdge = 16) }!!
        // 64×16 quads at one in four: 16×4.
        assertEquals(4, RawThumbnails.stepFor(128, 32, 16))
        assertEquals(16, thumbnail.width)
        assertEquals(4, thumbnail.height)
        assertArrayEquals(expected(big, step = 4), thumbnail.argb)
        // The default edge holds a 4080×3060 frame in 255×191.
        val step = RawThumbnails.stepFor(4080, 3060, RawThumbnails.MAX_EDGE)
        assertArrayEquals(intArrayOf(255, 191), RawDevelop.halfSize(4080, 3060, step))
    }

    @Test fun aDamagedFirstFrameGivesWayToTheNext() {
        val file = temp.newFile("damaged.ocraw")
        val frames = (0 until 3).map(::frame)
        writeTake(file, frames)
        corruptFrames(file, 1)
        assertArrayEquals(expected(frames[1]), render(file)!!.argb)
    }

    @Test fun aCompressedFrameThatCannotDecodeGivesWayToTheNext() {
        val file = temp.newFile("undecodable.ocraw")
        // Not a valid LJ92 container, or no codec on this JVM: Missing either way.
        val broken = frame(0).first.copy(encoding = OcrawFrameHeader.ENCODING_LJ92, dataLength = 333) to Random(9).nextBytes(333)
        val plain = frame(1)
        writeTake(file, listOf(broken, plain))
        assertArrayEquals(expected(plain), render(file)!!.argb)
    }

    @Test fun aCompressedFirstFrameDecodesAsTheReviewReadsIt() {
        assumeTrue("libocc_rawcodec is not loaded on the host JVM", NativeRawCodec.available)
        val file = temp.newFile("lj92.ocraw")
        val raw = frame(0)
        val encoded = NativeRawCodec.newEncoder(NativeRawCodec.Codec.LJ92, threads = 1).use { encoder ->
            val src = ByteBuffer.allocateDirect(raw.second.size).put(raw.second).also { it.flip() }
            val dst = ByteBuffer.allocateDirect(encoder.maxEncodedSize(width, height).toInt())
            val size = encoder.encode(src, width, height, dst)
            ByteArray(size).also { dst.get(it) }
        }
        writeTake(file, listOf(raw.first.copy(encoding = OcrawFrameHeader.ENCODING_LJ92, dataLength = encoded.size) to encoded, frame(1)))
        assertArrayEquals(expected(raw), render(file)!!.argb)
    }

    @Test fun onlyTheLeadingFramesAreTried() {
        val file = temp.newFile("all-damaged.ocraw")
        val frames = (0 until RawThumbnails.FRAMES_TRIED + 1).map(::frame)
        writeTake(file, frames)
        corruptFrames(file, RawThumbnails.FRAMES_TRIED)
        // The one good frame comes after the frames tried.
        assertNull(render(file))
    }

    @Test fun anEmptyTruncatedOrForeignFileHasNoPicture() {
        assertNull(render(temp.newFile("empty.ocraw")))
        val foreign = temp.newFile("foreign.ocraw").apply { writeBytes(Random(4).nextBytes(4096)) }
        assertNull(render(foreign))
        // A take that stopped before its first frame: header and metadata only.
        val noFrames = temp.newFile("no-frames.ocraw")
        writeTake(noFrames, emptyList())
        assertNull(render(noFrames))
        // A take cut inside its first frame, as one still being written can be.
        val whole = temp.newFile("whole.ocraw")
        writeTake(whole, listOf(frame(0)))
        val first = FileChannel.open(whole.toPath()).use { OcrawStreamReader(it).indexFrames() }.frames.single()
        val cut = temp.newFile("cut.ocraw").apply { writeBytes(whole.readBytes().copyOf(first.fileOffset.toInt() + 100)) }
        assertNull(render(cut))
        assertNotNull(render(whole))
    }

    private fun render(file: File): RawThumbnail? = FileChannel.open(file.toPath()).use { RawThumbnails.render(it) }

    /** What the review's half-resolution develop makes of [frame] with frame 0's metadata, as unturned ARGB. */
    private fun expected(frame: Pair<OcrawFrameHeader, ByteArray>, step: Int = 1): IntArray {
        val (header, data) = frame
        val params = RawDevelop.params(RawStaticColour.from(colour), frameMetadata)
        val rgb = RawDevelop.developHalf(ByteBuffer.allocateDirect(data.size).put(data).also { it.flip() }, header, params, step)
        return IntArray(rgb.size / 3) {
            fun c(v: Float) = (v.coerceIn(0f, 1f) * 255f).roundToInt()
            (0xff shl 24) or (c(rgb[it * 3]) shl 16) or (c(rgb[it * 3 + 1]) shl 8) or c(rgb[it * 3 + 2])
        }
    }

    private fun frame(index: Int, frameWidth: Int = width, frameHeight: Int = height): Pair<OcrawFrameHeader, ByteArray> {
        val header = OcrawFrameHeader.raw10(frameWidth, frameHeight, 0, 0, 1_000_000_000L + index * 33_333_333L, 100L + index)
        return header to Random(index + 1).nextBytes(header.dataLength)
    }

    private fun writeTake(file: File, frames: List<Pair<OcrawFrameHeader, ByteArray>>, metadata: Map<String, String> = colour) {
        FileChannel.open(file.toPath(), StandardOpenOption.WRITE).use { channel ->
            val writer = OcrawStreamWriter(channel, OcrawHeader(ByteArray(16) { it.toByte() }), nanos)
            writer.writeMetadata(metadata)
            frames.forEachIndexed { i, (header, data) ->
                val sequence = writer.writeRawFrame(header, ByteBuffer.wrap(data), i * 33_333_333L)
                writer.writeFrameMetadata(sequence, frameMetadata)
            }
            writer.finish()
        }
    }

    /** Flips a data byte in each of the first [count] frames, so their CRC fails. */
    private fun corruptFrames(file: File, count: Int) {
        val frames = FileChannel.open(file.toPath()).use { OcrawStreamReader(it).indexFrames() }.frames
        RandomAccessFile(file, "rw").use { raf ->
            frames.take(count).forEach { frame ->
                val at = frame.fileOffset + OcrawCodec.CHUNK_HEADER_BYTES + OcrawFrameHeader.BYTES + 3
                raf.seek(at)
                val value = raf.read()
                raf.seek(at)
                raf.write(value xor 0x40)
            }
        }
    }
}
