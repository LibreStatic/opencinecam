/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera
import org.junit.Assert.*
import org.junit.Test
class EncodedRecordingFinalizationTest {
    @Test fun successfulContainerClosesBeforeTimelineFinalization() {
        val calls=mutableListOf<String>()
        assertNull(finalizeEncodedRecording(true,null,true,{calls+="stop"},{calls+="release"},{calls+="timeline"}))
        assertEquals(listOf("stop","release","timeline"),calls)
    }
    @Test fun failedStopStillReleasesAndNeverRetimes() {
        val calls=mutableListOf<String>();val first=IllegalStateException("stop")
        assertSame(first,finalizeEncodedRecording(true,null,true,{calls+="stop";throw first},{calls+="release"},{calls+="timeline"}))
        assertEquals(listOf("stop","release"),calls)
    }
    @Test fun failedReleaseIsARecordingFailureNotSuccessfulPublication() {
        var retimed=false;val failure=IllegalStateException("release")
        assertSame(failure,finalizeEncodedRecording(true,null,true,{}, {throw failure},{retimed=true}))
        assertFalse(retimed)
    }
    @Test fun incompleteOrUnstartedMuxerNeverRetimesButAlwaysReleases() {
        var stopped=false;var released=false;var retimed=false
        assertNotNull(finalizeEncodedRecording(false,null,false,{stopped=true},{released=true},{retimed=true}))
        assertFalse(stopped);assertTrue(released);assertFalse(retimed)
    }
    @Test fun firstFailureAndAllCleanupFailuresAreRetainedInOrder() {
        val first=IllegalStateException("encoder");val second=IllegalStateException("stop");val third=IllegalStateException("release")
        assertSame(first,finalizeEncodedRecording(false,first,true,{throw second},{throw third},{fail("retimed")}))
        assertArrayEquals(arrayOf(second,third),first.suppressed)
    }
    @Test fun partialTimelineWriteFailureIsReturnedAndNeverHidden() {
        var bytesChanged=0;val error=IllegalStateException("write")
        assertSame(error,finalizeEncodedRecording(true,null,true,{}, {},{bytesChanged=4;throw error}))
        assertEquals(4,bytesChanged)
    }
}
