/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.net.Uri
import android.provider.MediaStore
import android.system.Os
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.opencinecam.camera.finalizeEncodedRecording
import com.librestatic.opencinecam.storage.VideoOutput
import org.junit.Assert.*
import org.junit.Test

class RecordingOutputFailureTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun rows(uri: Uri): Int = requireNotNull(context.contentResolver.query(uri,arrayOf("_id"),null,null,null)).use { it.count }
    @Test fun discardedOutputNeverReturnsADeletedUriFromALaterSuccessFlag() {
        val output=VideoOutput.create(context)
        assertEquals(1,rows(output.uri));assertNull(output.finish(false));assertEquals(0,rows(output.uri))
        assertNull(output.finish(true));assertNull(output.finish(false));assertEquals(0,rows(output.uri))
        android.util.Log.i("OutputCommitProbe","discardThenSuccess=null rows=0")
    }
    @Test fun failedMuxerReleaseSkipsTimingAndDeletesTheActualPendingRow() {
        val output=VideoOutput.create(context);var retimed=false
        val error=java.io.IOException("Injected muxer release failure")
        val failure=finalizeEncodedRecording(true,null,true,{}, {throw error},{retimed=true})
        assertSame(error,failure);assertFalse(retimed)
        assertNull(output.finish(failure==null));assertEquals(0,rows(output.uri))
        android.util.Log.i("OutputCommitProbe","releaseFailure=true retimed=false rows=0")
    }
    @Test fun partialTimelineMutationCannotPublishAnActualMediaStoreOutput() {
        val output=VideoOutput.create(context);var changed=0
        val failure=finalizeEncodedRecording(true,null,true,{}, {},{
            changed=Os.pwrite(output.descriptor.fileDescriptor,byteArrayOf(0,0,0,8,109,111,111,118),0,8,0)
            throw java.io.IOException("Injected failure after a timing write")
        })
        assertNotNull(failure);assertEquals(8,changed)
        assertNull(output.finish(failure==null));assertNull(output.finish(true));assertEquals(0,rows(output.uri))
        android.util.Log.i("OutputCommitProbe","partialWriteBytes=$changed successfulUri=null rows=0")
    }
    @Test fun missingVideoPublicationRollsBackItsCreatedSidecar() {
        val output=VideoOutput.create(context)
        val sidecarName=output.displayName.removeSuffix(".mp4")+".oclog.json"
        fun sidecars()=requireNotNull(context.contentResolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,arrayOf("_id"),
            "${MediaStore.Downloads.DISPLAY_NAME} = ?",arrayOf(sidecarName),null)).use { it.count }
        assertEquals(0,sidecars());assertEquals(1,context.contentResolver.delete(output.uri,null,null))
        val failure = assertThrows(RuntimeException::class.java) { output.finish(true,sidecarJson="{}") }
        // API30 revokes access to a deleted item; other providers can instead return zero rows.
        assertTrue(failure is SecurityException || failure is IllegalStateException)
        android.util.Log.i("OutputCommitProbe", "missingVideoFailure=${failure.javaClass.simpleName} suppressed=${failure.suppressed.size}")
        assertEquals(0,rows(output.uri));assertEquals(0,sidecars());assertNull(output.finish(true))
        android.util.Log.i("OutputCommitProbe","missingVideoPublishRejected=true sidecarRows=0 videoRows=0 laterSuccess=null")
    }
}
