/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

/** Fixed leases, one producer and one consumer. No rendering or waiting while holding the monitor. */
internal class LatestFrameExchange<T>(val capacity: Int = 3) {
    init { require(capacity in 2..3) }
    private enum class Owner { FREE, PRODUCER, PENDING, CONSUMER, RETURNED }
    private val owners = MutableList(capacity) { Owner.FREE }
    private val values = mutableMapOf<Int, T>()
    private var pending: Int? = null
    private var closed = false

    @Synchronized fun acquire(): Int? {
        if (closed) return null
        val slot = owners.indexOf(Owner.FREE).takeIf { it >= 0 } ?: return null
        owners[slot] = Owner.PRODUCER
        return slot
    }

    /** Superseded frames return to the producer, which must poll their GPU fence before reuse. */
    @Synchronized fun publish(slot: Int, value: T): Boolean {
        check(owners[slot] == Owner.PRODUCER)
        values[slot] = value
        if (closed) { owners[slot] = Owner.RETURNED; return false }
        val superseded = pending != null
        pending?.let { owners[it] = Owner.RETURNED }
        pending = slot
        owners[slot] = Owner.PENDING
        return superseded
    }

    @Synchronized fun takeLatest(): Pair<Int, T>? {
        if (Owner.CONSUMER in owners) return null
        val slot = pending ?: return null
        pending = null
        owners[slot] = Owner.CONSUMER
        return slot to values.getValue(slot)
    }

    @Synchronized fun returned(): List<Pair<Int, T>> = owners.indices
        .filter { owners[it] == Owner.RETURNED }.map { it to values.getValue(it) }

    @Synchronized fun hasPending(): Boolean = pending != null

    /** Cleanup only, after both workers have stopped touching these leases. */
    @Synchronized fun closedValues(): List<T> {
        check(closed)
        return values.values.toList()
    }

    @Synchronized fun release(slot: Int) {
        check(owners[slot] == Owner.RETURNED)
        owners[slot] = Owner.FREE
        values.remove(slot)
    }

    @Synchronized fun complete(slot: Int, value: T) {
        check(owners[slot] == Owner.CONSUMER)
        owners[slot] = Owner.RETURNED
        values[slot] = value
    }

    @Synchronized fun cancelWrite(slot: Int) {
        check(owners[slot] == Owner.PRODUCER)
        owners[slot] = Owner.FREE
    }

    @Synchronized fun close() {
        closed = true
        pending?.let { owners[it] = Owner.RETURNED }
        pending = null
    }
}

/** Receipt/submission timestamps, not physical panel scan-out or camera-sensor capture time. */
enum class SubjectPreviewFailure { OUTPUT, BUSY }

data class SubjectPreviewStatus(
    val sourceReceivedAtMs: Long? = null,
    val submittedAtMs: Long? = null,
    val droppedFrames: Long = 0,
    val failure: String? = null,
    val failureKind: SubjectPreviewFailure? = if (failure != null) SubjectPreviewFailure.OUTPUT else null,
    /** Exact LUT used in the last successfully submitted frame, null before any successful swap. */
    val lutStatus: OperatorLutStatus? = null,
) {
    fun isFresh(nowMs: Long, maximumAgeMs: Long = 500): Boolean {
        require(maximumAgeMs >= 0)
        val source = sourceReceivedAtMs ?: return false
        val submitted = submittedAtMs ?: return false
        return failure == null && submitted >= source && nowMs >= submitted && nowMs - source <= maximumAgeMs
    }
}

data class SubjectPreviewOptions(
    val displayRotationDegrees: Int = 0,
    val mirror: Boolean = false,
    val viewAssist: Boolean = true,
    val squeezeFactor: Float = 1f,
    /** Highest submission rate the subject display should receive; 0 forwards every camera frame. */
    val maxFrameRate: Float = 0f,
) {
    init {
        require(displayRotationDegrees in setOf(0, 90, 180, 270))
        require(squeezeFactor.isFinite() && squeezeFactor in 1f..3f)
        require(maxFrameRate.isFinite() && maxFrameRate >= 0f)
    }
}
