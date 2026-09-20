/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

import java.util.Collections

enum class StillPhotoFormat { JPEG, RAW_JPEG, HEIC, DNG }
enum class StillImageKind { JPEG, HEIC, DNG }

class StillImagePayload private constructor(
    val kind: StillImageKind, private val content: ByteArray,
    val width: Int, val height: Int, @Suppress("UNUSED_PARAMETER") owned: Unit,
    val aspectReport: PhotoAspectReport?,
) {
    constructor(kind: StillImageKind, bytes: ByteArray, width: Int, height: Int, aspectReport: PhotoAspectReport? = null) :
        this(kind, checkedCopy(kind, bytes), width, height, Unit, aspectReport)
    val bytes: ByteArray get() = content.copyOf()
    val byteCount: Int get() = content.size
    init {
        require(content.isNotEmpty() && content.size <= maximumBytes(kind) && width > 0 && height > 0)
        aspectReport?.let { report ->
            require(report.resultWidth == width && report.resultHeight == height)
            require((report.disposition == PhotoAspectDisposition.RAW_UNCHANGED) == (kind == StillImageKind.DNG))
            if (report.disposition == PhotoAspectDisposition.APPLIED) require(kind == StillImageKind.JPEG || kind == StillImageKind.HEIC)
        }
    }
    companion object {
        const val MAX_COMPRESSED_BYTES = 128 * 1024 * 1024
        const val MAX_DNG_BYTES = 256 * 1024 * 1024
        fun maximumBytes(kind: StillImageKind): Int = if (kind == StillImageKind.DNG) MAX_DNG_BYTES else MAX_COMPRESSED_BYTES
        private fun checkedCopy(kind: StillImageKind, bytes: ByteArray): ByteArray {
            require(bytes.isNotEmpty() && bytes.size <= maximumBytes(kind))
            return bytes.copyOf()
        }
        /** Engine-only ownership transfer avoids recopying a fresh encoded image. */
        internal fun owned(kind: StillImageKind, bytes: ByteArray, width: Int, height: Int, aspectReport: PhotoAspectReport? = null): StillImagePayload =
            StillImagePayload(kind, bytes, width, height, Unit, aspectReport)
    }
}

/** One native request/result and its entire timestamp-matched output set, never a partial pair. */
class CapturedStill(
    val captureId: Long,
    val sensorTimestampNs: Long,
    val orientationDegrees: Int,
    val quality: Int,
    val flashReport: PhotoFlashReport,
    images: List<StillImagePayload>,
    val aspectSelection: PhotoAspectSelection = PhotoAspectSelection(),
) {
    val images: List<StillImagePayload> = Collections.unmodifiableList(images.toList())
    init {
        require(captureId > 0 && sensorTimestampNs > 0)
        require(orientationDegrees in setOf(0, 90, 180, 270) && quality in 1..100)
        require(flashReport.sensorTimestampNs == sensorTimestampNs)
        val kinds = this.images.map { it.kind }
        require(kinds.size == kinds.toSet().size)
        require(kinds.size == 1 || kinds.toSet() == setOf(StillImageKind.JPEG, StillImageKind.DNG))
        this.images.forEach { image ->
            if (aspectSelection.enabled) require(image.aspectReport?.requested == aspectSelection)
            else require(image.aspectReport == null || image.aspectReport.requested == aspectSelection)
        }
    }
}

internal val StillPhotoFormat.requiredKinds: Set<StillImageKind> get() = when (this) {
    StillPhotoFormat.JPEG -> setOf(StillImageKind.JPEG)
    StillPhotoFormat.RAW_JPEG -> setOf(StillImageKind.JPEG, StillImageKind.DNG)
    StillPhotoFormat.HEIC -> setOf(StillImageKind.HEIC)
    StillPhotoFormat.DNG -> setOf(StillImageKind.DNG)
}

/** An output from another frame cannot complete any part of a paired exposure. */
internal fun StillPhotoFormat.matchesCompleteFrame(sensorTimestampNs: Long, parts: Map<StillImageKind, Long>): Boolean =
    sensorTimestampNs > 0 && parts.keys == requiredKinds && parts.values.all { it == sensorTimestampNs }

/** Bounds producer allocations, queued callbacks and retained encoded images together. */
internal class StillImageHandoff<T>(private val maximumBytes: Int) {
    internal class Ticket<T> internal constructor(internal val bytes: Int) {
        internal var state = State.PRODUCING
        internal var value: T? = null
    }
    internal enum class State { PRODUCING, READY, CLAIMED, CANCELLED_PRODUCER, RELEASED }
    private val tickets = mutableSetOf<Ticket<T>>()
    private var used = 0L
    init { require(maximumBytes > 0) }
    @Synchronized fun usedBytes(): Long = used
    @Synchronized fun reserve(bytes: Int, stillCurrent: () -> Boolean = { true }): Ticket<T>? {
        require(bytes > 0)
        if (!stillCurrent()) return null
        if (bytes.toLong() > maximumBytes.toLong() - used) return null
        return Ticket<T>(bytes).also { tickets += it; used += bytes }
    }
    @Synchronized fun publish(ticket: Ticket<T>, value: T): Boolean {
        if (ticket !in tickets) return false
        if (ticket.state == State.CANCELLED_PRODUCER) { release(ticket); return false }
        check(ticket.state == State.PRODUCING)
        ticket.value = value
        ticket.state = State.READY
        return true
    }
    @Synchronized fun take(ticket: Ticket<T>): T? {
        if (ticket !in tickets || ticket.state != State.READY) return null
        return ticket.value.also { ticket.value = null; ticket.state = State.CLAIMED }
    }
    @Synchronized fun release(ticket: Ticket<T>) {
        if (!tickets.remove(ticket)) return
        ticket.value = null
        ticket.state = State.RELEASED
        used -= ticket.bytes
    }
    @Synchronized fun cancelAll() {
        tickets.toList().forEach { ticket ->
            // A producer may still be allocating/copying. Its finally/publish must retire
            // the reservation before another producer can reuse the same byte budget.
            if (ticket.state == State.PRODUCING) ticket.state = State.CANCELLED_PRODUCER
            else if (ticket.state != State.CANCELLED_PRODUCER) release(ticket)
        }
    }
}
