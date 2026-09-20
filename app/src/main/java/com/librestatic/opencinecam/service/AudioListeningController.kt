/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRouting
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.librestatic.opencinecam.AudioListeningDevice
import com.librestatic.opencinecam.AudioListeningOutput
import com.librestatic.opencinecam.AudioListeningPhase
import com.librestatic.opencinecam.AudioListeningSettings
import com.librestatic.opencinecam.AudioListeningStatus
import com.librestatic.opencinecam.camera.PcmMeterEncoding
import com.librestatic.opencinecam.storage.releaseAudioResources
import java.nio.ByteBuffer
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Playback-only owner. No AudioRecord, audio focus, global mode, or global speaker routing. */
internal class AudioListeningController(
    context: Context,
    private val onStatus: (AudioListeningStatus) -> Unit,
    private val onDevices: (List<AudioListeningDevice>) -> Unit,
) {
    private data class Intent(val options: AudioListeningSettings = AudioListeningSettings(),
        val deviceId: Int? = null, val boundDeviceId: Int? = null,
        val armed: Boolean = false, val generation: Long = 0,
        val phase: AudioListeningPhase = AudioListeningPhase.DISABLED)
    private val manager = context.applicationContext.getSystemService(AudioManager::class.java)
    private val callbacks = Handler(Looper.getMainLooper())
    private val intent = AtomicReference(Intent())
    private val closed = AtomicBoolean()
    private val queue = ListeningPcmQueue()
    private val wake = Semaphore(0)
    private val wakePending = AtomicBoolean()
    private val devicesDirty = AtomicBoolean(true)
    private val dropped = AtomicLong()
    private val writtenFrames = AtomicLong()
    private val retired = CompletableFuture<Unit>()
    @Volatile private var currentTrack: AudioTrack? = null
    @Volatile private var targetId: Int? = null
    @Volatile private var routeConfirmed = false
    @Volatile private var trackGeneration = -1L
    private var targetName: String? = null
    private var trackRate = 0
    private var trackChannels = 0
    private var connectingSince = 0L
    private var volume = -1
    private val lastStatus = AtomicReference<AudioListeningStatus?>(null)
    private var lastStatusGeneration = -1L
    private val statusLock = Any()
    private var currentPacket: ListeningPcmPacket? = null
    private var currentBytes: ByteBuffer? = null
    private var registered = false
    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            devicesDirty.set(true); signal()
        }
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            val current = intent.get()
            if (removedDevices.any { it.id == current.boundDeviceId })
                disarm(AudioListeningPhase.DISCONNECTED, current.generation)
            devicesDirty.set(true); signal()
        }
    }
    private val routingCallback = AudioRouting.OnRoutingChangedListener { router ->
        if (router === currentTrack && !closed.get()) {
            val generation = trackGeneration
            val expected = targetId
            val confirmed = routeConfirmed
            val actual = runCatching { router.routedDevice?.id }.getOrNull()
            if (router === currentTrack && (confirmed || actual != null) && actual != expected)
                disarm(AudioListeningPhase.DISCONNECTED, generation)
            signal()
        }
    }
    private val worker = Thread(::runWorker, "OpenCineCamAudioListening").apply { start() }

    /** Persisted enable never grants a connection; volume-only updates preserve an explicit arm. */
    fun configure(options: AudioListeningSettings, id: Int?) {
        synchronized(statusLock) {
            if (closed.get()) return
            val old = intent.get()
            val destinationChanged = old.options.output != options.output || old.deviceId != id
            val sameRoute = old.options.enabled == options.enabled && !destinationChanged
            val next = old.copy(options = options, deviceId = id,
                boundDeviceId = if (destinationChanged) null else old.boundDeviceId,
                armed = sameRoute && options.enabled && old.armed,
                generation = old.generation + if (sameRoute) 0 else 1,
                phase = if (!options.enabled) AudioListeningPhase.DISABLED else if (!sameRoute) AudioListeningPhase.NEEDS_CONNECT else old.phase)
            intent.set(next)
            if (!sameRoute) { queue.clear(); emit(next.phase, expectedGeneration = next.generation) }
        }
        signal()
    }

    /** Only an explicit UI action calls this; discovery/configuration never retries a route. */
    fun reconnect() {
        synchronized(statusLock) {
            if (closed.get()) return
            val old = intent.get()
            if (!old.options.enabled) return
            val next = old.copy(armed = true, boundDeviceId = null, generation = old.generation + 1,
                phase = AudioListeningPhase.WAITING_PCM)
            intent.set(next)
            queue.clear()
            emit(AudioListeningPhase.WAITING_PCM, expectedGeneration = next.generation)
        }
        signal()
    }

    /** Never waits for AudioTrack or the worker; copies at most one reserved 64 KiB packet. */
    fun offer(buffer: ByteBuffer, byteCount: Int, encoding: PcmMeterEncoding, sampleRateHz: Int, channels: Int,
        isCurrent: () -> Boolean = { true }): Boolean {
        return try {
            val current = intent.get()
            // Snapshot first: a producer retired after this admission still carries its old generation.
            if (closed.get() || !current.armed || !current.options.enabled || !isCurrent()) return false
            val accepted = queue.offer(buffer, byteCount, encoding, sampleRateHz, channels, current.generation)
            if (!accepted) dropped.incrementAndGet()
            signal()
            if (closed.get()) { queue.clear(); false } else accepted
        } catch (_: Throwable) { false }
    }

    /** A producer replacement drops old PCM and retires the track, not the user's explicit arm. */
    fun clearProducer() {
        synchronized(statusLock) {
            if (closed.get()) return
            val old = intent.get()
            val next = old.copy(generation = old.generation + 1,
                phase = if (old.armed) AudioListeningPhase.WAITING_PCM else old.phase)
            intent.set(next) // Preserve the exact runtime device bound by explicit reconnect.
            queue.clear()
            emit(next.phase, expectedGeneration = next.generation)
        }
        signal()
    }

    fun closeAsync(): CompletableFuture<Unit> {
        synchronized(statusLock) {
            if (closed.compareAndSet(false, true)) {
                val old = intent.get()
                intent.set(old.copy(armed = false, generation = old.generation + 1, phase = AudioListeningPhase.RETIRING))
                queue.clear()
                emit(AudioListeningPhase.RETIRING)
                signal()
            }
        }
        return retired.thenApply { it }
    }

    /** Revalidate this exact runtime observation inside the service's state publication CAS. */
    fun isCurrentStatus(status: AudioListeningStatus): Boolean = synchronized(statusLock) {
        lastStatus.get() === status && lastStatusGeneration == intent.get().generation
    }

    private fun disarm(phase: AudioListeningPhase, expectedGeneration: Long? = null): Long? {
        val next = synchronized(statusLock) {
            if (closed.get()) return null
            val old = intent.get()
            if (!old.armed || expectedGeneration != null && old.generation != expectedGeneration) return null
            old.copy(armed = false, generation = old.generation + 1, phase = phase).also {
                intent.set(it)
                queue.clear()
                emit(phase, expectedGeneration = it.generation)
            }
        }
        signal()
        return next.generation
    }

    private fun signal() { if (wakePending.compareAndSet(false, true)) wake.release() }

    private fun runWorker() {
        var failure: Throwable? = null
        try {
            manager.registerAudioDeviceCallback(deviceCallback, callbacks)
            registered = true
            while (!closed.get()) {
                var cycleGeneration = intent.get().generation
                try {
                    if (devicesDirty.getAndSet(false)) publishDevices()
                    var current = intent.get()
                    cycleGeneration = current.generation
                    if (currentTrack != null && (trackGeneration != current.generation || !current.armed)) retireTrack()
                    if (current.armed && current.boundDeviceId == null) {
                        current = bindOutput(current) ?: continue
                    }
                    if (!current.armed) {
                        emit(current.phase, expectedGeneration = current.generation)
                    } else {
                        if (currentPacket == null) {
                            currentPacket = queue.poll()
                            currentPacket?.let { packet ->
                                if (packet.generation != current.generation) dropCurrent()
                                else currentBytes = listeningPcm16(packet)
                            }
                        }
                        val packet = currentPacket
                        if (packet != null) {
                            if (currentTrack != null && (trackRate != packet.sampleRateHz || trackChannels != packet.channels)) retireTrack()
                            if (currentTrack == null) createTrack(current, packet)
                            if (intent.get().generation == current.generation && intent.get().armed) writeCurrent(current)
                        } else if (currentTrack == null) emit(AudioListeningPhase.WAITING_PCM, expectedGeneration = current.generation)
                        else if (validateRoute(current, requireConfirmed = routeConfirmed)) applyVolume(current)
                    }
                } catch (problem: Throwable) {
                    val failedGeneration = disarm(AudioListeningPhase.FAILED, cycleGeneration)
                    try { retireTrack() } catch (cleanup: Throwable) {
                        if (cleanup !== problem) problem.addSuppressed(cleanup)
                        throw problem
                    }
                    dropCurrent()
                    if (failedGeneration != null) emit(AudioListeningPhase.FAILED, problem.message, failedGeneration)
                }
                wake.tryAcquire(if (currentTrack != null) 8 else 100, TimeUnit.MILLISECONDS)
                wakePending.set(false)
            }
        } catch (problem: Throwable) {
            failure = problem
            disarm(AudioListeningPhase.FAILED)
            emit(AudioListeningPhase.FAILED, problem.message)
        } finally {
            synchronized(statusLock) {
                closed.set(true)
                val old = intent.get()
                intent.set(old.copy(armed = false, generation = old.generation + 1, phase = AudioListeningPhase.RETIRING))
                emit(AudioListeningPhase.RETIRING)
            }
            var cleanupFailure: Throwable? = null
            fun cleanup(action: () -> Unit) {
                try { action() } catch (problem: Throwable) {
                    val first = cleanupFailure
                    if (first == null) cleanupFailure = problem else if (first !== problem) first.addSuppressed(problem)
                }
            }
            cleanup { retireTrack() }
            cleanup { if (registered) manager.unregisterAudioDeviceCallback(deviceCallback) }
            queue.clear(); dropCurrent()
            val problem = failure
            if (problem != null && cleanupFailure != null && cleanupFailure !== problem) problem.addSuppressed(cleanupFailure)
            if (cleanupFailure != null) failedOwners.add(this)
            val result = problem ?: cleanupFailure
            if (result == null) { emit(AudioListeningPhase.DISABLED); retired.complete(Unit) }
            else { emit(AudioListeningPhase.FAILED, result.message); retired.completeExceptionally(result) }
        }
    }

    private fun outputClass(device: AudioDeviceInfo): AudioListeningOutput? = when (device.type) {
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY -> AudioListeningOutput.WIRED_USB
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER -> AudioListeningOutput.BLUETOOTH
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> AudioListeningOutput.SPEAKER
        else -> null
    }

    private fun outputs(): List<AudioDeviceInfo> = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter { it.isSink }
    private fun publishDevices() {
        val devices = outputs().mapNotNull { device -> outputClass(device)?.let {
            AudioListeningDevice(device.id, device.productName.toString().take(160), it)
        } }.sortedBy { it.id }
        callbacks.post { if (!closed.get()) runCatching { onDevices(Collections.unmodifiableList(devices)) } }
    }

    /** Device discovery is outside the publication lock and runs once per explicit arm. */
    private fun bindOutput(current: Intent): Intent? {
        val eligible = outputs().filter { outputClass(it) == current.options.output }
        val device = if (current.deviceId != null) eligible.firstOrNull { it.id == current.deviceId }
            else eligible.minByOrNull { it.id }
        if (device == null) { disarm(AudioListeningPhase.NO_OUTPUT, current.generation); return null }
        return synchronized(statusLock) {
            val latest = intent.get()
            if (closed.get() || !latest.armed || latest.generation != current.generation) null
            else if (latest.boundDeviceId != null) latest
            else latest.copy(boundDeviceId = device.id).also { intent.set(it) }
        }
    }

    private fun createTrack(current: Intent, packet: ListeningPcmPacket) {
        if (closed.get() || !intent.get().armed || intent.get().generation != current.generation) return
        // A producer change never chooses a different device, even if the preference is automatic.
        val device = outputs().firstOrNull { it.id == current.boundDeviceId && outputClass(it) == current.options.output }
        if (device == null) { disarm(AudioListeningPhase.NO_OUTPUT, current.generation); dropCurrent(); return }
        val mask = if (packet.channels == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val minimum = AudioTrack.getMinBufferSize(packet.sampleRateHz, mask, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum in 1..524288) { "Listening PCM output is unsupported or exceeds its buffer budget" }
        check(trackOwner.compareAndSet(null, this)) { "Another listening AudioTrack is active or still retiring" }
        val track = try { AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(packet.sampleRateHz).setChannelMask(mask).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(minimum, packet.sampleRateHz * packet.channels * 2 / 10))
            .build()
        } catch (problem: Throwable) {
            // No handle was returned; Android's failed builder owns its internal unwind.
            trackOwner.compareAndSet(this, null)
            throw problem
        }
        currentTrack = track // Every acquired native owner is reachable before another call can fail.
        trackGeneration = current.generation
        targetId = device.id; targetName = device.productName.toString().take(160)
        trackRate = packet.sampleRateHz; trackChannels = packet.channels
        routeConfirmed = false; volume = -1
        check(track.state == AudioTrack.STATE_INITIALIZED) { "Listening AudioTrack initialization failed" }
        check(track.setVolume(0f) == AudioTrack.SUCCESS) { "Listening mute was rejected" }
        check(track.setPreferredDevice(device)) { "Listening preferred device was rejected" }
        track.addOnRoutingChangedListener(routingCallback, callbacks)
        connectingSince = SystemClock.elapsedRealtime()
        emit(AudioListeningPhase.CONNECTING, expectedGeneration = current.generation)
        track.play()
    }

    private fun validateRoute(current: Intent, requireConfirmed: Boolean): Boolean {
        val track = currentTrack ?: return false
        if (intent.get().generation != current.generation || !intent.get().armed) return false
        val actual = track.routedDevice?.id
        // API36 can report simultaneous outputs; every route must be the explicitly chosen device.
        val exact = actual == targetId && (Build.VERSION.SDK_INT < 36 || track.routedDevices.let { it.size == 1 && it.single().id == targetId })
        if (!exact) {
            if (requireConfirmed || actual != null || SystemClock.elapsedRealtime() - connectingSince > 2000) {
                disarm(AudioListeningPhase.DISCONNECTED, current.generation)
                check(track.setVolume(0f) == AudioTrack.SUCCESS) { "Listening reroute mute was rejected" }
            }
            return false
        }
        routeConfirmed = true
        return true
    }

    private fun writeCurrent(current: Intent) {
        val track = currentTrack ?: return
        if (!validateRoute(current, requireConfirmed = routeConfirmed)) {
            if (intent.get().armed) {
                // Initial route can be resolved only while playing. Never prime it with microphone PCM.
                val silence = ByteBuffer.allocateDirect(trackChannels * 2 * 128)
                check(track.write(silence, silence.remaining(), AudioTrack.WRITE_NON_BLOCKING) >= 0) { "Listening priming failed" }
            }
            return
        }
        applyVolume(current)
        val bytes = currentBytes ?: return
        if (intent.get().generation != current.generation || !intent.get().armed || closed.get()) return
        val count = track.write(bytes, bytes.remaining(), AudioTrack.WRITE_NON_BLOCKING)
        check(count >= 0 && count % (trackChannels * 2) == 0) { "Listening AudioTrack write failed: $count" }
        if (count > 0) {
            writtenFrames.addAndGet(count.toLong() / (trackChannels * 2))
            if (validateRoute(current, requireConfirmed = true)) emit(AudioListeningPhase.ACTIVE, expectedGeneration = current.generation)
        }
        if (!bytes.hasRemaining()) dropCurrent()
    }

    private fun applyVolume(current: Intent) {
        val track = currentTrack ?: return
        if (closed.get() || !intent.get().armed || intent.get().generation != current.generation) return
        if (volume != current.options.volumePercent) {
            check(track.setVolume(current.options.volumePercent / 100f) == AudioTrack.SUCCESS) { "Listening volume rejected" }
            volume = current.options.volumePercent
        }
    }

    private fun dropCurrent() {
        if (currentPacket != null) { currentPacket = null; currentBytes = null; queue.complete() }
    }

    private fun retireTrack() {
        val track = currentTrack ?: return
        emit(AudioListeningPhase.RETIRING)
        var released = false
        try {
            releaseAudioResources(listOf(
                { check(track.setVolume(0f) == AudioTrack.SUCCESS) { "Listening retirement mute failed" } },
                { track.pause() }, { track.flush() },
                { track.removeOnRoutingChangedListener(routingCallback) },
                { track.release(); released = true },
            ))
        } finally {
            if (released) {
                currentTrack = null; targetId = null; targetName = null; routeConfirmed = false
                check(trackOwner.compareAndSet(this, null)) { "Listening AudioTrack owner changed before retirement" }
            }
            dropCurrent()
        }
    }

    private fun emit(phase: AudioListeningPhase, message: String? = null, expectedGeneration: Long? = null) {
        synchronized(statusLock) {
            val current = intent.get()
            if (expectedGeneration != null && current.generation != expectedGeneration) return
            if (phase in setOf(AudioListeningPhase.ACTIVE, AudioListeningPhase.CONNECTING) &&
                (closed.get() || !current.armed || trackGeneration != current.generation || currentTrack == null)) return
            val effectivePhase = if (phase in setOf(AudioListeningPhase.ACTIVE, AudioListeningPhase.CONNECTING,
                    AudioListeningPhase.WAITING_PCM) && !current.armed) current.phase else phase
            val next = AudioListeningStatus(effectivePhase, current.deviceId,
                if (effectivePhase == AudioListeningPhase.ACTIVE) targetId else null,
                if (effectivePhase == AudioListeningPhase.ACTIVE) targetName else null,
                dropped.get(), writtenFrames.get(), message ?: lastStatus.get()?.takeIf { it.phase == effectivePhase }?.message)
            if (next != lastStatus.get() || lastStatusGeneration != current.generation) {
                lastStatusGeneration = current.generation
                lastStatus.set(next)
                // Deliver the latest observation, not a queued stale ACTIVE snapshot after reroute.
                callbacks.post { lastStatus.get()?.let { latest -> runCatching { onStatus(latest) } } }
            }
        }
    }

    companion object {
        // Process-wide native-file-independent claim; no waiting and no implicit reconnect.
        private val trackOwner = AtomicReference<AudioListeningController?>(null)
        // Cleanup failures never release the last strong native owner reference.
        private val failedOwners = ConcurrentHashMap.newKeySet<AudioListeningController>()
    }
}
