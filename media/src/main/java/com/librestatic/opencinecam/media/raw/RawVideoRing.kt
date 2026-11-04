// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 OpenCineCam contributors

package com.librestatic.opencinecam.media.raw

import com.librestatic.opencinecam.media.ocraw.OcrawFrameHeader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport

/**
 * Preallocated ring of direct [ByteBuffer] slots between one producer (the
 * camera callback thread) and one consumer (the writer thread).
 *
 * Every slot is allocated up front, so nothing is allocated per frame. Slots
 * move through the ring in order: the producer [tryAcquire]s the next free slot,
 * fills `slot.buffer` from position 0 and [publish]es it; the consumer [take]s
 * published slots in the same order and [release]s each one when its bytes are
 * on disk. A full ring makes [tryAcquire] return null: that is backpressure,
 * and PLAN-071 stops the take rather than dropping a frame.
 *
 * The hand-off is lock-free (atomic counters); only an idle consumer parks.
 * Exactly one thread may call the producer methods and exactly one the
 * consumer methods; occupancy getters are safe from any thread.
 *
 * [allocate] makes each slot buffer. The default is a direct buffer, which on
 * ART lives on the Java heap; a ring larger than the heap uses
 * [SharedMemorySlots] instead.
 */
class RawVideoRing(
    val slotBytes: Int,
    val slotCount: Int,
    allocate: (Int) -> ByteBuffer = ByteBuffer::allocateDirect,
) {
    init {
        require(slotBytes > 0) { "slotBytes must be positive" }
        require(slotCount >= 2) { "the ring needs at least two slots" }
    }

    /** One preallocated frame buffer. */
    class Slot internal constructor(val index: Int, val buffer: ByteBuffer) {
        /** Header given to [publish]; valid while the consumer holds the slot. */
        var header: OcrawFrameHeader? = null
            internal set

        /** Bytes published (`buffer.limit()` once published). */
        var length: Int = 0
            internal set
    }

    private val slots = Array(slotCount) { index ->
        Slot(index, allocate(slotBytes).order(ByteOrder.LITTLE_ENDIAN))
    }

    private val acquired = AtomicLong()
    private val published = AtomicLong()
    private val taken = AtomicLong()
    private val released = AtomicLong()
    private val peak = AtomicLong()

    @Volatile private var closed = false

    @Volatile private var waitingConsumer: Thread? = null

    /** Total preallocated bytes. */
    val capacityBytes: Long get() = slotBytes.toLong() * slotCount

    /** Slots acquired by the producer and not yet released by the consumer. */
    val usedSlots: Int get() = (acquired.get() - released.get()).toInt()

    /** Reserved bytes: [usedSlots] times [slotBytes]. */
    val usedBytes: Long get() = usedSlots.toLong() * slotBytes

    /** Highest [usedSlots] seen since creation. */
    val peakSlots: Int get() = peak.get().toInt()

    val peakBytes: Long get() = peakSlots.toLong() * slotBytes

    /** Published and not yet taken. */
    val queuedSlots: Int get() = (published.get() - taken.get()).toInt()

    val usedFraction: Double get() = usedSlots.toDouble() / slotCount

    val peakFraction: Double get() = peakSlots.toDouble() / slotCount

    val isClosed: Boolean get() = closed

    /** True when at least [fraction] of the slots are in use (PLAN-071 stops the take at 0.9). */
    fun isAboveHighWater(fraction: Double): Boolean {
        require(fraction in 0.0..1.0) { "fraction must be within 0..1" }
        return usedSlots >= fraction * slotCount
    }

    /**
     * Producer: the next free slot, its buffer cleared, or null when the ring
     * is full or closed. Call [publish] before acquiring again.
     */
    fun tryAcquire(): Slot? {
        if (closed) return null
        val next = acquired.get()
        check(next == published.get()) { "publish the acquired slot before acquiring another" }
        if (next - released.get() >= slotCount) return null
        val slot = slots[(next % slotCount).toInt()]
        slot.buffer.clear()
        slot.header = null
        slot.length = 0
        acquired.set(next + 1)
        val used = next + 1 - released.get()
        if (used > peak.get()) peak.set(used)
        return slot
    }

    /**
     * Producer: hands [slot] to the consumer with `header.dataLength` bytes
     * from position 0. Throws [IllegalStateException] after [close].
     */
    fun publish(slot: Slot, header: OcrawFrameHeader) {
        val sequence = published.get()
        check(acquired.get() == sequence + 1 && slots[(sequence % slotCount).toInt()] === slot) {
            "only the acquired slot can be published"
        }
        check(!closed) { "ring is closed" }
        require(header.dataLength <= slotBytes) { "frame of ${header.dataLength} bytes exceeds the slot" }
        slot.header = header
        slot.length = header.dataLength
        slot.buffer.limit(header.dataLength).position(0)
        published.set(sequence + 1)
        waitingConsumer?.let(LockSupport::unpark)
    }

    /**
     * Producer: gives back an acquired slot without publishing it, e.g. when
     * the image could not be copied.
     */
    fun cancel(slot: Slot) {
        val sequence = published.get()
        check(acquired.get() == sequence + 1 && slots[(sequence % slotCount).toInt()] === slot) {
            "only the acquired slot can be cancelled"
        }
        acquired.set(sequence)
    }

    /**
     * Consumer: the oldest published slot, waiting up to [timeoutMs]. Returns
     * null on timeout, or once the ring is closed and drained. The buffer
     * spans `0 until slot.length`.
     */
    fun take(timeoutMs: Long): Slot? {
        val next = taken.get()
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs.coerceAtLeast(0))
        while (true) {
            if (published.get() > next) {
                taken.set(next + 1)
                return slots[(next % slotCount).toInt()]
            }
            if (closed) {
                // A publish that raced with close() is still drained.
                if (published.get() > next) continue
                return null
            }
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) return null
            waitingConsumer = Thread.currentThread()
            try {
                if (published.get() == next && !closed) LockSupport.parkNanos(this, remaining)
            } finally {
                waitingConsumer = null
            }
        }
    }

    /** Consumer: returns the oldest taken slot to the producer. */
    fun release(slot: Slot) {
        val sequence = released.get()
        check(taken.get() > sequence && slots[(sequence % slotCount).toInt()] === slot) {
            "slots must be released in the order they were taken"
        }
        slot.header = null
        released.set(sequence + 1)
    }

    /**
     * Stops new frames: [tryAcquire] returns null and [publish] throws. The
     * consumer is woken and keeps taking what was published, then gets null.
     */
    fun close() {
        closed = true
        waitingConsumer?.let(LockSupport::unpark)
    }
}

/**
 * Copies a crop of an Android `ImageFormat.RAW10` plane into [dst] as tightly
 * packed rows (`cropWidth * 5 / 4` bytes each, no stride padding), starting
 * at `dst.position()` and advancing it. [src] is read from its position, which
 * is the first byte of row 0, and is left as it was.
 *
 * RAW10 packs four pixels into five bytes, so [cropLeft] and [cropWidth] must
 * be multiples of 4; [cropTop] must be even to keep the CFA phase. Each row is
 * one bulk `put`. Returns the bytes written.
 */
fun copyRaw10Rows(
    src: ByteBuffer,
    srcRowStride: Int,
    width: Int,
    height: Int,
    cropLeft: Int,
    cropTop: Int,
    cropWidth: Int,
    cropHeight: Int,
    dst: ByteBuffer,
): Int {
    require(width > 0 && height > 0) { "frame dimensions must be positive" }
    require(width % 4 == 0) { "RAW10 width must be a multiple of 4" }
    require(cropLeft >= 0 && cropTop >= 0 && cropWidth > 0 && cropHeight > 0) { "invalid crop" }
    require(cropLeft % 4 == 0 && cropWidth % 4 == 0) { "RAW10 crop left and width must be multiples of 4" }
    require(cropTop % 2 == 0) { "crop top must be even to keep the CFA phase" }
    require(cropLeft + cropWidth <= width && cropTop + cropHeight <= height) { "crop exceeds the frame" }
    val fullRowBytes = width / 4 * 5
    require(srcRowStride >= fullRowBytes) { "row stride $srcRowStride is shorter than $fullRowBytes" }
    val rowBytes = cropWidth / 4 * 5
    val skip = cropLeft / 4 * 5
    val base = src.position()
    val lastRowEnd = base.toLong() + (cropTop + cropHeight - 1).toLong() * srcRowStride + skip + rowBytes
    require(lastRowEnd <= src.limit()) { "source plane is too short for the crop" }
    val total = rowBytes.toLong() * cropHeight
    require(total <= dst.remaining()) { "destination holds ${dst.remaining()} of $total bytes" }
    val savedLimit = src.limit()
    try {
        if (srcRowStride == rowBytes) {
            // Full-width crop of a plane without padding: one contiguous copy.
            val start = base + cropTop * srcRowStride
            src.limit(start + total.toInt()).position(start)
            dst.put(src)
        } else {
            for (row in 0 until cropHeight) {
                val start = base + (cropTop + row) * srcRowStride + skip
                src.limit(start + rowBytes).position(start)
                dst.put(src)
            }
        }
    } finally {
        src.limit(savedLimit).position(base)
    }
    return total.toInt()
}
