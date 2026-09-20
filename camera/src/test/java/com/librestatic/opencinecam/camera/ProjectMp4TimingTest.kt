/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class ProjectMp4TimingTest {
    private class Memory(val bytes: ByteArray) : ProjectMp4File {
        var writes=0
        override val size get()=bytes.size.toLong()
        override fun read(offset: Long,length: Int)=bytes.copyOfRange(offset.toInt(),offset.toInt()+length)
        override fun write(offset: Long,bytes: ByteArray) { bytes.copyInto(this.bytes,offset.toInt());writes++ }
    }
    private fun word(n: Int)=ByteBuffer.allocate(4).putInt(n).array()
    private fun box(type: String,bytes: ByteArray)=word(bytes.size+8)+type.toByteArray(Charsets.US_ASCII)+bytes
    private fun header(type: String,wide: Boolean=false): ByteArray {
        val data=ByteArray(96); if(wide)data[0]=1
        val b=ByteBuffer.wrap(data)
        if(type=="tkhd") { if(wide)b.putLong(28,200) else b.putInt(20,200) }
        else { b.putInt(if(wide)20 else 12,if(type=="mvhd")1000 else 90000);if(wide)b.putLong(24,200) else b.putInt(16,200) }
        return box(type,data)
    }
    private fun file(wide: Boolean=false, extraTrack: Boolean=false, badCtts: Boolean=false, edit: Boolean=false): Memory {
        val hdlr=ByteArray(24);"vide".toByteArray().copyInto(hdlr,8)
        val stts=box("stts",word(0)+word(2)+word(1)+word(1501)+word(2)+word(1502))
        val stsz=box("stsz",word(0)+word(0)+word(3)+word(2)+word(2)+word(2))
        val ctts=if(badCtts)box("ctts",word(0)+word(1)+word(3)+word(1)) else byteArrayOf()
        val stco=box("stco",word(0)+word(1)+word(8))
        val mdia=box("mdia",header("mdhd",wide)+box("hdlr",hdlr)+box("minf",box("stbl",stts+stsz+stco+ctts)))
        val edts=if(edit)box("edts",box("elst",word(0)+word(1)+word(200)+word(0)+word(65536))) else byteArrayOf()
        val track=box("trak",header("tkhd",wide)+mdia+edts)
        return Memory(box("mdat",byteArrayOf(1,2,3,4,5,6))+box("moov",header("mvhd",wide)+track+(if(extraTrack)track else byteArrayOf())))
    }
    private fun offset(file: Memory,type: String): Int {
        val key=type.toByteArray()
        return (0..file.bytes.size-4).first { file.bytes.copyOfRange(it,it+4).contentEquals(key) }+4
    }
    private fun u32(file: Memory,at: Int)=ByteBuffer.wrap(file.bytes).getInt(at).toLong() and 0xffffffffL
    @Test fun allThreeRationalRatesGetExactSampleDurationsWithoutMovingPayloadOrOffsets() {
        for(n in listOf(24000,30000,60000)) {
            val f=file(); val before=f.bytes.copyOf();val stco=offset(f,"stco")
            finalizeProjectMp4Timing(f,CaptureFrameRate(n,1001),3)
            assertEquals(before.size,f.bytes.size);assertArrayEquals(before.copyOfRange(0,14),f.bytes.copyOfRange(0,14))
            assertEquals(8,u32(f,stco+8).toInt());val mdhd=offset(f,"mdhd")
            assertEquals(n.toLong(),u32(f,mdhd+12));assertEquals(3003L,u32(f,mdhd+16))
            val stts=offset(f,"stts");assertEquals(2L,u32(f,stts+4));assertEquals(1001L,u32(f,stts+12));assertEquals(1001L,u32(f,stts+20))
            assertEquals(3L,u32(f,stts+8)+u32(f,stts+16))
        }
    }
    @Test fun versionOneDurationFieldsAndEditListRemainConsistent() {
        val f=file(wide=true,edit=true);finalizeProjectMp4Timing(f,CaptureFrameRate(60000,1001),3)
        val mdhd=offset(f,"mdhd");assertEquals(60000L,u32(f,mdhd+20));assertEquals(3003L,ByteBuffer.wrap(f.bytes).getLong(mdhd+24))
        assertEquals(51L,ByteBuffer.wrap(f.bytes).getLong(offset(f,"mvhd")+24));assertEquals(51L,u32(f,offset(f,"elst")+8))
    }
    @Test fun inconsistentFrameCountFailsBeforeAnyWrite() {
        val f=file();val old=f.bytes.copyOf()
        assertThrows(IllegalArgumentException::class.java) { finalizeProjectMp4Timing(f,CaptureFrameRate(30),4) }
        assertEquals(0,f.writes);assertArrayEquals(old,f.bytes)
    }
    @Test fun multipleTracksAndCompositionOffsetsAreRejectedBeforeWriting() {
        for(f in listOf(file(extraTrack=true),file(badCtts=true))) {
            assertThrows(Exception::class.java) { finalizeProjectMp4Timing(f,CaptureFrameRate(30),3) }
            assertEquals(0,f.writes)
        }
    }
    @Test fun truncatedAndOverrunningBoxesFailBeforeWriting() {
        val source=file().bytes
        for(bytes in listOf(source.copyOf(source.size-1),source.copyOf().also { word(Int.MAX_VALUE).copyInto(it,0) },ByteArray(7))) {
            val f=Memory(bytes);assertThrows(Exception::class.java) { finalizeProjectMp4Timing(f,CaptureFrameRate(30),3) };assertEquals(0,f.writes)
        }
    }
    @Test fun malformedSampleSizeTableFailsBeforeAnyTimingWrite() {
        val f=file();word(1).copyInto(f.bytes,offset(f,"stsz")+4)
        assertThrows(IllegalArgumentException::class.java) { finalizeProjectMp4Timing(f,CaptureFrameRate(30),3) }
        assertEquals(0,f.writes)
    }
    @Test fun partialWritesAndReadbackMismatchAreNotReportedAsSuccessfulFinalization() {
        for (throwOnWrite in listOf(true,false)) {
            val memory=file();var writes=0
            val faulty=object: ProjectMp4File {
                override val size get()=memory.size
                override fun read(offset:Long,length:Int)=memory.read(offset,length)
                override fun write(offset:Long,bytes:ByteArray) {
                    writes++
                    if(writes==1)memory.write(offset,bytes)
                    else if(throwOnWrite)throw java.io.IOException("Injected partial write")
                }
            }
            assertThrows(Exception::class.java) { finalizeProjectMp4Timing(faulty,CaptureFrameRate(60000,1001),3) }
            assertTrue(writes>=2)
        }
    }
    @Test fun repeatFinalizationIsIdempotentAndIntegerCadenceUsesItsOwnTimescale() {
        val f=file();finalizeProjectMp4Timing(f,CaptureFrameRate(25),3);val once=f.bytes.copyOf()
        finalizeProjectMp4Timing(f,CaptureFrameRate(25),3);assertArrayEquals(once,f.bytes)
        assertEquals(25L,u32(f,offset(f,"mdhd")+12));assertEquals(3L,u32(f,offset(f,"mdhd")+16))
    }
}
