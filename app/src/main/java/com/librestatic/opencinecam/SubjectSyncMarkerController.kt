/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

private const val TAG = "SubjectSyncMarker"
private const val BEEP_RELEASE_DELAY_MS = 400L

/**
 * Operator-side owner of the U6 sync marker. It observes service state (fed by the coordinator),
 * raises the flash frame for the subject window and plays the beep off the main thread. The single
 * [sink] call is the only route to the take sidecar; it is evidence, not a capture command.
 */
internal class SubjectSyncMarkerController(
    private val context: Context,
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
) : AutoCloseable {
    private val policy = SubjectSyncMarkerPolicy()
    private val mutableFlash = MutableStateFlow(false)
    val flash: StateFlow<Boolean> = mutableFlash.asStateFlow()
    private val endFlash = Runnable { mutableFlash.value = false }
    private val beeper = SubjectSyncBeeper(context)

    /** Hands the report to the active take's sidecar; null when no service is bound. */
    @Volatile var sink: ((SubjectSyncMarkerReport) -> Boolean)? = null

    fun observe(state: CameraUiState, arming: SubjectSyncArming) {
        val decision = policy.observe(state.phase, state.recordingFinalizing, state.recordingElapsedMs, arming) ?: return
        val trigger = SystemClock.elapsedRealtimeNanos()
        if (decision.flash == SubjectSyncMarkerOutcome.FIRED) {
            mainHandler.removeCallbacks(endFlash)
            mutableFlash.value = true
            mainHandler.postDelayed(endFlash, SUBJECT_SYNC_FLASH_MS)
        }
        if (!decision.beep) deliver(SubjectSyncMarkerReport(trigger, decision.flash, SubjectSyncMarkerOutcome.NOT_REQUESTED))
        else beeper.play { beepStart, failure ->
            deliver(SubjectSyncMarkerReport(trigger, decision.flash,
                if (beepStart != null) SubjectSyncMarkerOutcome.FIRED else SubjectSyncMarkerOutcome.FAILED,
                beepStart, boundedSyncFailure(failure)))
        }
    }

    private fun deliver(report: SubjectSyncMarkerReport) {
        val accepted = try { sink?.invoke(report) == true } catch (failure: RuntimeException) {
            Log.w(TAG, "Sync marker evidence was not delivered", failure)
            false
        }
        if (!accepted) Log.w(TAG, "Sync marker evidence was not admitted for a take: $report")
    }

    override fun close() {
        sink = null
        mainHandler.removeCallbacks(endFlash)
        mutableFlash.value = false
        beeper.close()
    }
}

/** Plays the 1 kHz beep through the built-in speaker on its own thread; it never throws to callers. */
internal class SubjectSyncBeeper(context: Context) : AutoCloseable {
    private val audio = context.applicationContext.getSystemService(AudioManager::class.java)
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "subject-sync-beep").apply { isDaemon = true }
    }

    /** [onResult] receives the playback start time, or null and a reason. */
    fun play(onResult: (Long?, String?) -> Unit) {
        try {
            executor.execute { playNow(onResult) }
        } catch (_: RejectedExecutionException) {
            onResult(null, "Beep player is closed")
        }
    }

    private fun playNow(onResult: (Long?, String?) -> Unit) {
        var track: AudioTrack? = null
        val started = try {
            check(audio == null || audio.getStreamVolume(AudioManager.STREAM_MUSIC) > 0) { "Media volume is muted" }
            val pcm = subjectSyncBeepPcm()
            val created = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SUBJECT_SYNC_BEEP_SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.size * 2)
                .build()
            track = created
            check(created.state == AudioTrack.STATE_INITIALIZED) { "Beep track did not initialize" }
            check(created.write(pcm, 0, pcm.size) == pcm.size) { "Beep samples were not written" }
            // The beep is meant to reach this phone's microphone, so prefer the speaker over headphones.
            audio?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                ?.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                ?.let(created::setPreferredDevice)
            val start = SystemClock.elapsedRealtimeNanos()
            created.play()
            start
        } catch (failure: Exception) {
            Log.w(TAG, "Sync beep failed; the take continues", failure)
            runCatching { track?.release() }
            onResult(null, failure.message ?: failure.javaClass.simpleName)
            return
        }
        val playing = requireNotNull(track)
        try {
            executor.schedule({ runCatching { playing.stop() }; playing.release() }, BEEP_RELEASE_DELAY_MS, TimeUnit.MILLISECONDS)
        } catch (_: RejectedExecutionException) {
            playing.release()
        }
        onResult(started, null)
    }

    override fun close() {
        // Pending releases still run; only new beeps are refused.
        executor.shutdown()
    }
}

/** Full-bleed white frame drawn above every subject layer while the flash is up. */
@Composable
internal fun SubjectSyncFlash(modifier: Modifier = Modifier) {
    Box(modifier.background(Color.White).testTag("subject-sync-flash"))
}
