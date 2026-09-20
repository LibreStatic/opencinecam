/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.storage

/** Best-effort compensation across independent providers, not a crash-atomic transaction. */
internal fun <V : Any, A : Any> finalizeRecordingTake(
    success: Boolean,
    finishAudio: ((Boolean) -> A?)?,
    discardAudio: () -> Unit,
    finishVideo: (Boolean) -> V?,
): Pair<V, A?>? {
    try {
        val audio = finishAudio?.invoke(success)
        check(!success || finishAudio == null || audio != null) { "Requested audio has no completed output" }
        val video = finishVideo(success)
        check(!success || video != null) { "A successful take has no committed video output" }
        if (!success) {
            discardAudio()
            return null
        }
        return requireNotNull(video) to audio
    } catch (failure: Throwable) {
        // A failed audio completion must still abort video; video failure must revoke saved audio.
        for (cleanup in listOf<() -> Unit>({ finishVideo(false) }, discardAudio)) {
            try { cleanup() } catch (problem: Throwable) {
                if (problem !== failure) failure.addSuppressed(problem)
            }
        }
        throw failure
    }
}

/** Remove every owned row even if one deletion fails; retain only failed rows for a later retry. */
internal class OwnedOutputRows<T : Any>(private val delete: (T) -> Unit) {
    private val rows = linkedSetOf<T>()
    @Synchronized fun add(row: T) { rows.add(row) }
    @Synchronized fun deleteAll() {
        var failure: Throwable? = null
        val iterator = rows.iterator()
        while (iterator.hasNext()) {
            try { delete(iterator.next()); iterator.remove() } catch (problem: Throwable) {
                val first = failure
                if (first == null) failure = problem else if (first !== problem) first.addSuppressed(problem)
            }
        }
        failure?.let { throw it }
    }
}

/** Optional registration errors are data, never capture-compensation triggers. */
internal data class PreparedTakeResult<V : Any, A : Any>(
    val outputs: Pair<V, A?>?,
    val registrationFailures: List<Throwable>,
)

/**
 * Prepare every byte/identity before publication. Audio preparation precedes video metadata so
 * the latter can contain the final captured-audio timing. Registration callbacks may fail without
 * revoking valid originals. Only actual capture preparation/publication failures compensate rows.
 * This remains best-effort provider compensation, not a power-loss-atomic commit.
 */
internal fun <PV : Any, PA : Any, V : Any, A : Any> finalizePreparedRecordingTake(
    success: Boolean,
    prepareAudio: (() -> PA?)?,
    prepareVideo: () -> PV?,
    publishAudio: (PA) -> A?,
    publishVideo: (PV) -> V?,
    discardAudio: () -> Unit,
    discardVideo: () -> Unit,
    onPrepared: (PV, PA?) -> Unit = { _, _ -> },
    onPublished: (PV, PA?) -> Unit = { _, _ -> },
    onAborted: () -> Unit = {},
): PreparedTakeResult<V, A> {
    val registrationFailures = mutableListOf<Throwable>()
    fun register(action: () -> Unit) {
        try { action() } catch (failure: Throwable) { registrationFailures.add(failure) }
    }
    fun compensate(primary: Throwable? = null) {
        var failure = primary
        for (cleanup in listOf(discardAudio, discardVideo)) {
            try { cleanup() } catch (problem: Throwable) {
                val first = failure
                if (first == null) failure = problem else if (first !== problem) first.addSuppressed(problem)
            }
        }
        register(onAborted)
        failure?.let { primaryFailure ->
            registrationFailures.forEach { problem ->
                if (problem !== primaryFailure && primaryFailure.suppressed.none { it === problem }) primaryFailure.addSuppressed(problem)
            }
            throw primaryFailure
        }
    }
    if (!success) {
        compensate()
        return PreparedTakeResult(null, registrationFailures.toList())
    }
    val preparedAudio: PA?
    val preparedVideo: PV
    val outputs: Pair<V, A?>
    try {
        preparedAudio = prepareAudio?.invoke()
        check(prepareAudio == null || preparedAudio != null) { "Requested audio has no prepared output" }
        preparedVideo = requireNotNull(prepareVideo()) { "A successful take has no prepared video" }
        register { onPrepared(preparedVideo, preparedAudio) }
        val audio = preparedAudio?.let(publishAudio)
        check(preparedAudio == null || audio != null) { "Requested audio has no published output" }
        val video = requireNotNull(publishVideo(preparedVideo)) { "A successful take has no published video" }
        outputs = video to audio
    } catch (failure: Throwable) {
        compensate(failure)
        throw failure
    }
    // Deliberately outside the capture-compensation try: bookkeeping never changes SAVED to ERROR.
    register { onPublished(preparedVideo, preparedAudio) }
    return PreparedTakeResult(outputs, registrationFailures.toList())
}
