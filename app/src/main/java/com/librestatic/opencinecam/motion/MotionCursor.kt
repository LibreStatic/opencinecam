/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.motion

import java.io.Closeable

/**
 * A pull cursor over one vector stream: each [next] that returns true moves to the following sample, whose [time],
 * [x], [y] and [z] hold until the next call. Spool cursors give sensor-clock nanoseconds, resampled ones file
 * microseconds. Once [next] returns false it keeps doing so. The render runs through cursors so that a take of any
 * length costs one sample of state per stream, never the stream itself.
 */
internal interface VectorCursor : Closeable {
    fun next(): Boolean

    val time: Long
    val x: Float
    val y: Float
    val z: Float

    override fun close() {}
}

/** A cursor over parallel arrays, as given. */
internal class ArrayVectorCursor(
    private val times: LongArray,
    private val xs: FloatArray,
    private val ys: FloatArray,
    private val zs: FloatArray,
) : VectorCursor {
    private var at = -1

    init {
        require(xs.size == times.size && ys.size == times.size && zs.size == times.size) { "series arrays differ in size" }
    }

    override fun next(): Boolean {
        if (at + 1 >= times.size) {
            at = times.size
            return false
        }
        at++
        return true
    }

    override val time: Long get() = times[at]
    override val x: Float get() = xs[at]
    override val y: Float get() = ys[at]
    override val z: Float get() = zs[at]
}

/** Counts the samples it hands on, so a stream's size is known after a single pass. */
internal class CountingCursor(private val source: VectorCursor) : VectorCursor by source {
    var count: Long = 0
        private set

    override fun next(): Boolean = source.next().also { if (it) count++ }

    /** Runs to the end, so that [count] is the whole stream's. */
    fun drain() {
        while (next()) Unit
    }

    override fun close() = source.close()
}

internal fun MappedSeries.cursor(): VectorCursor = ArrayVectorCursor(fileUs, x, y, z)

/** The rest of the cursor as arrays; for tests and small streams, as it holds the whole stream. */
internal fun VectorCursor.collect(): MappedSeries {
    var times = LongArray(INITIAL_CAPACITY)
    var xs = FloatArray(INITIAL_CAPACITY)
    var ys = FloatArray(INITIAL_CAPACITY)
    var zs = FloatArray(INITIAL_CAPACITY)
    var size = 0
    while (next()) {
        if (size == times.size) {
            val capacity = times.size * 2
            times = times.copyOf(capacity)
            xs = xs.copyOf(capacity)
            ys = ys.copyOf(capacity)
            zs = zs.copyOf(capacity)
        }
        times[size] = time
        xs[size] = x
        ys[size] = y
        zs[size] = z
        size++
    }
    return MappedSeries(times.copyOf(size), xs.copyOf(size), ys.copyOf(size), zs.copyOf(size))
}

private const val INITIAL_CAPACITY = 1024
