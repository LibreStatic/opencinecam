/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.Manifest
import android.content.ContentUris
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.opencinecam.camera.*
import com.librestatic.opencinecam.storage.*
import com.librestatic.opencinecam.media.audio.AudioBitDepth
import com.librestatic.opencinecam.media.audio.AudioSourceSelection
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LosslessSharedPauseTest {
    @Test fun wavRepeatedPauseCutsTheSameSourceWindowsAsVideo() = exercise(false,false,false)
    @Test fun flacRepeatedPauseCutsTheSameSourceWindowsAsVideo() = exercise(true,false,false)
    @Test fun floatStereoWavStopsWhilePaused() = exercise(false,true,true)
    @Test fun stereoFlacStopsWhilePaused() = exercise(true,true,false)
    @Test fun unknownCameraClockRecordsWithoutAdvertisingSharedPause() = exercise(false,false,false,false)

    private fun exercise(flac: Boolean, stopPaused: Boolean, floating: Boolean, comparable: Boolean = true) {
        val instrumentation=InstrumentationRegistry.getInstrumentation();val context=instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName,Manifest.permission.RECORD_AUDIO)
        val rate=if(stopPaused)44100 else 48000;val channels=if(stopPaused)2 else 1
        val depth=if(floating)AudioBitDepth.PCM_FLOAT else AudioBitDepth.PCM_16
        val stem="LOSSLESS_${if(flac) "FLAC" else "WAV"}_${if(stopPaused) "STOP_PAUSED" else if(comparable) "REPEATED" else "UNKNOWN"}"
        val video=File(context.cacheDir,"$stem.mp4");val videoName="${stem}_${System.nanoTime()}.mp4"
        val clock=CaptureEpochClock(comparable,rate);val levels=AtomicInteger()
        val settings=CameraSettings(audioSampleRateHz=rate,audioChannels=channels,audioBitDepth=depth,
            audioSource=AudioSourceSelection.MIC,automaticGainControlEnabled=false,noiseSuppressorEnabled=false,acousticEchoCancelerEnabled=false)
        val audio:AudioSidecarRecorder=if(flac)FlacAudioSidecarRecorder.create(context,videoName,settings,clock){levels.incrementAndGet()}
            else WavAudioSidecarRecorder.create(context,videoName,settings,clock){levels.incrementAndGet()}
        val statuses=CopyOnWriteArrayList<TimelapsePauseStatus>();val stopped=CountDownLatch(1)
        val saved=AtomicBoolean();val evidence=AtomicReference<OpenCineLogRecordingEvidence?>()
        try { SubjectPreviewGpuTest.Fixture(embeddedAudio=true,comparableEpoch=comparable).use { fixture ->
            val frame=RecordingFrameSize(128,96);val geometry=RecordingGeometry(RecordingGeometryMode.COMPATIBLE,0,0,0,0,0,frame,frame,frame)
            ParcelFileDescriptor.open(video,ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_READ_WRITE).use { output ->
                assertTrue(fixture.pipeline.startRecording(output,2000000,geometry,separateAudioClock=clock,
                    onTimelapsePauseChanged={statuses+=it},onStarted={audio.start()},
                    onStopped={ok,report->saved.set(ok);evidence.set(report);stopped.countDown()}))
            }
            val sourcePts=mutableListOf<Long>()
            fun frames(ms:Long){val end=SystemClock.elapsedRealtime()+ms;while(SystemClock.elapsedRealtime()<end){
                val ns=SystemClock.elapsedRealtimeNanos();sourcePts+=ns;fixture.sourceFrame(ns);SystemClock.sleep(34)
            }}
            if(comparable){
                val deadline=SystemClock.elapsedRealtime()+3000
                while(statuses.isEmpty()&&SystemClock.elapsedRealtime()<deadline)frames(70)
                assertTrue("Missing shared pause: ${fixture.failures}",statuses.isNotEmpty())
                fun pause(value:Boolean){val ack=CountDownLatch(1);val changed=AtomicBoolean()
                    assertTrue(fixture.pipeline.setTimelapsePaused(value){changed.set(it);ack.countDown()})
                    assertTrue(ack.await(2,TimeUnit.SECONDS));assertTrue(changed.get());assertEquals(value,statuses.last().paused)
                }
                frames(400)
                repeat(2){pause(true);val analysis=fixture.analysis.get();val meter=levels.get();frames(600)
                    assertTrue(fixture.analysis.get()>analysis);assertTrue(levels.get()>meter);pause(false);frames(400)}
                if(stopPaused){pause(true);frames(350)}
            }else{frames(1200);assertTrue(statuses.isEmpty());assertFalse(fixture.pipeline.setTimelapsePaused(true) {})}
            assertTrue(fixture.pipeline.stopRecording());assertTrue(stopped.await(10,TimeUnit.SECONDS))
            assertTrue(fixture.failures.toString(),saved.get())
            val result=requireNotNull(audio.finish(true));val timing=requireNotNull(result.captureTiming)
            val report=clock.report(evidence.get()!!.avTiming!!.videoEncoderFirstPtsUs,null,null)
                .copy(submittedPcmFrames=result.frames,audioStorage="SEPARATE_${result.container}")
            assertEquals(result.frames,timing.writtenFrames);assertEquals(timing.capturedFrames,report.capturedPcmFrames)
            if(comparable){val cut=requireNotNull(report.sharedPause)
                assertEquals(if(stopPaused)3 else 2,cut.windows.size);assertNotNull(cut.stopFrame)
                assertEquals(result.frames,cut.retainedPcmFrames);assertTrue(timing.capturedFrames-result.frames>rate)
                assertTrue(statuses.last().finished);assertEquals(stopPaused,statuses.last().paused)
            }else{assertNull(report.sharedPause);assertEquals(timing.capturedFrames,result.frames)}
            val resolver=context.contentResolver
            val metadataName=videoName.removeSuffix(".mp4")+".audio.json"
            val metadataUri=requireNotNull(resolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,arrayOf("_id"),
                "${MediaStore.MediaColumns.DISPLAY_NAME} = ?",arrayOf(metadataName),null)).use{
                assertEquals(1,it.count);assertTrue(it.moveToFirst());ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI,it.getLong(0))}
            val json=JSONObject(requireNotNull(resolver.openInputStream(metadataUri)).bufferedReader().use{it.readText()})
            assertEquals(result.frames,json.getJSONObject("sharedTiming").getLong("submittedPcmFrames"))
            requireNotNull(resolver.openInputStream(result.uri)).use{input->File(context.cacheDir,stem+if(flac)".flac" else ".wav").outputStream().use{input.copyTo(it)}}
            json.put("videoTiming",captureEpochJson(report)).put("sourceVideoPtsNs",JSONArray(sourcePts))
                .put("previewFrames",fixture.analysis.get()).put("meterUpdates",levels.get()).put("stopPaused",stopPaused)
            File(context.cacheDir,"$stem.json").writeText(json.toString(2))
        }}finally{audio.discard()}
    }
}
