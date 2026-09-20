/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.MediaCodecList
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.opencinecam.camera.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

/** Real HEIC fixtures are generated from known RGB pixels. The rotated container has identical
 * coded mdat bytes, so these checks detect ignored or double-applied container rotation. */
class HeicAspectDeviceTest {
    private val assets get() = InstrumentationRegistry.getInstrumentation().context.assets
    private fun fixture(rotated: Boolean = false) = assets.open(if (rotated)
        "e8-heic-quadrants-rotated90.heic" else "e8-heic-quadrants.heic").use { it.readBytes() }
    private fun invoke(name: String, method: String, vararg arguments: Any?): Any? {
        try { return Class.forName("com.librestatic.opencinecam.camera.$name")
            .declaredMethods.single { it.name == method }.apply { isAccessible = true }.invoke(null, *arguments) }
        catch (failure: InvocationTargetException) { throw requireNotNull(failure.cause) }
    }
    private fun crop(bytes: ByteArray, selection: PhotoAspectSelection = PhotoAspectSelection(true,1,1),
        rotation: Int = 0, checkRunning: () -> Unit = {}, consume: (Bitmap,PhotoAspectReport) -> Unit) {
        invoke("HeicAspectBitmapProcessorKt", "withCroppedHeicBitmap", bytes, selection, rotation, checkRunning, consume)
    }
    private fun color(expected: Int, actual: Int) {
        for (shift in listOf(0,8,16)) assertTrue("expected=$expected actual=$actual channel=$shift",
            kotlin.math.abs((expected shr shift and 255) - (actual shr shift and 255)) <= 35)
    }
    @Test fun containerOrientationAndFrozenCameraRotationAreAppliedExactlyOnce() {
        val quadrants=intArrayOf(Color.RED,Color.GREEN,Color.BLUE,Color.YELLOW)
        val order=listOf(intArrayOf(0,1,2,3),intArrayOf(2,0,3,1),intArrayOf(3,2,1,0),intArrayOf(1,3,0,2))
        for (rotated in listOf(false,true)) for (rotation in listOf(0,90,180,270)) {
            var observed: Bitmap?=null
            crop(fixture(rotated),rotation=rotation) { bitmap,report ->
                observed=bitmap
                val turns=(rotation/90+if(rotated) 1 else 0)%4
                assertEquals(if(turns%2==0) 320 else 240,report.sourceWidth)
                assertEquals(if(turns%2==0) 240 else 320,report.sourceHeight)
                assertEquals(240,bitmap.width);assertEquals(240,bitmap.height)
                assertEquals(PhotoAspectDisposition.APPLIED,report.disposition)
                assertEquals(0,report.outputOrientationDegrees)
                listOf(60 to 60,180 to 60,60 to 180,180 to 180).forEachIndexed { index,(x,y) ->
                    color(quadrants[order[turns][index]],bitmap.getPixel(x,y))
                }
            }
            assertTrue(requireNotNull(observed).isRecycled)
        }
        android.util.Log.i("HeicAspectProbe","nativeHEICdecode=true containerRotation2 frozenRotations4 quadrantChecks32 output0 allConsumedBitmapsRecycled=true")
    }
    @Test fun exactOddCustomCropAndInteriorMarkerProvePixelsAreNotResized() {
        crop(fixture(),PhotoAspectSelection(true,239,100)) { bitmap,report ->
            assertEquals(PhotoCropRect(40,70,239,100),report.crop)
            assertEquals(239,bitmap.width);assertEquals(100,bitmap.height)
        }
        crop(fixture()) { bitmap,report ->
            assertEquals(PhotoCropRect(40,0,240,240),report.crop)
            color(Color.MAGENTA,bitmap.getPixel(10,42)) // source50,42 minus x40; not a resize.
            color(Color.RED,bitmap.getPixel(1,60)) // sourcecyan strip x0..19 is outside.
        }
        android.util.Log.i("HeicAspectProbe","oddRatio239:100 actual239x100 centered=true markerExact=true outsideStripRemoved=true noResize=true")
    }
    @Test fun invalidInputsAndConsumerFailureNeverLeaveOwnedBitmapsLive() {
        val jpeg=ByteArrayOutputStream().also { output ->
            Bitmap.createBitmap(32,32,Bitmap.Config.ARGB_8888).let { bitmap ->
                try {assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG,95,output))} finally {bitmap.recycle()}
            }
        }.toByteArray()
        for (bytes in listOf(byteArrayOf(),byteArrayOf(1,2,3),jpeg,fixture().copyOf(40))) {
            assertThrows(Exception::class.java) {crop(bytes) {_,_->fail("Invalid source reached consumer")}}
        }
        assertThrows(IllegalArgumentException::class.java) {crop(fixture(),PhotoAspectSelection()) {_,_->fail()}}
        assertThrows(IllegalArgumentException::class.java) {crop(fixture(),rotation=45) {_,_->fail()}}
        var observed:Bitmap?=null
        val failure=IllegalStateException("Consumer failed")
        assertSame(failure,assertThrows(IllegalStateException::class.java) {
            crop(fixture()) {bitmap,_->observed=bitmap;throw failure}
        })
        assertTrue(requireNotNull(observed).isRecycled)
        crop(fixture()) {bitmap,_->assertEquals(240,bitmap.width)}
    }
    @Test fun latchedCancellationAtDecodeAndCropBoundariesHasNoDeliveryThenFreshDecodeSucceeds() {
        for (at in 1..7) {
            var count=0;var deliveries=0
            val cancelled=CancellationException("cancel HEIC checkpoint $at")
            assertSame(cancelled,assertThrows(CancellationException::class.java) {
                crop(fixture(),checkRunning={if(++count>=at) throw cancelled}) {_,_->deliveries++}
            })
            assertEquals(0,deliveries)
        }
        crop(fixture()) {bitmap,_->assertEquals(240,bitmap.height)}
    }
    @Test fun nativeEncoderPreservesHeicAndOddDimensionsOrReportsAbsentEncoderWithoutTemporaryOutput() {
        val dir=File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,"heic-native-probe")
        assertTrue(dir.isDirectory || dir.mkdirs())
        val before=dir.listFiles()?.map {it.name}?.toSet().orEmpty()
        val available=invoke("HeicBitmapEncoderKt","canEncodeHeicBitmap",239,100) as Boolean
        android.util.Log.i("HeicAspectProbe","encodeAvailable=$available encoders="+MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter {it.isEncoder}.joinToString {it.name+":"+it.supportedTypes.joinToString()})
        fun encode(): StillImagePayload = invoke("HeicAspectBitmapProcessorKt","cropPhotoAspectHeic",fixture(),
            PhotoAspectSelection(true,239,100),0,90,dir,{} as () -> Unit) as StillImagePayload
        if(!available) {
            assertThrows(UnsupportedOperationException::class.java) {encode()}
            assertEquals(before,dir.listFiles()?.map {it.name}?.toSet().orEmpty())
            android.util.Log.i("HeicAspectProbe","positiveEncoderAccepted=false reason=NO_COMPATIBLE_CQ_YUV_ENCODER codecFallbackToJPEG=false temporaryOutputs=0")
            return
        }
        val result=encode();assertEquals(StillImageKind.HEIC,result.kind)
        assertEquals(239,result.width);assertEquals(100,result.height)
        val report=requireNotNull(result.aspectReport);assertEquals(PhotoCropRect(40,70,239,100),report.crop)
        val bitmap=requireNotNull(BitmapFactory.decodeByteArray(result.bytes,0,result.byteCount))
        try {
            assertEquals(239,bitmap.width);assertEquals(100,bitmap.height)
            color(Color.RED,bitmap.getPixel(30,20));color(Color.GREEN,bitmap.getPixel(210,20))
            color(Color.BLUE,bitmap.getPixel(30,80));color(Color.YELLOW,bitmap.getPixel(210,80))
        } finally {bitmap.recycle()}
        assertEquals(before,dir.listFiles()?.map {it.name}?.toSet().orEmpty())
        android.util.Log.i("HeicAspectProbe","positiveEncoderAccepted=true actualHEIC239x100 pixelsDecoded=true bytes=${result.byteCount} codecMuxerRetired=true temporaryOutputs=0")
    }
}
