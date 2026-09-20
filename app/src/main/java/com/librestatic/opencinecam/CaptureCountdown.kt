/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

/** Main-thread deadline state. It never captures by itself or sleeps; the service owns dispatch. */
class CaptureCountdown<T> {
    data class Ticket<T>(val generation: Long, val startedAtMs: Long, val deadlineMs: Long, val context: T)
    data class Tick(val seconds: Int = 0, val fire: Boolean = false)
    private var generation = 0L
    var active: Ticket<T>? = null
        private set

    fun start(nowMs: Long, seconds: Int, context: T): Ticket<T> {
        require(seconds in setOf(3, 5, 10))
        require(nowMs in 0..Long.MAX_VALUE - 10_000)
        return Ticket(++generation, nowMs, nowMs + seconds * 1_000L, context).also { active = it }
    }

    fun cancel() { generation++; active = null }

    fun tick(ticket: Ticket<T>, nowMs: Long, currentContext: T, permitted: Boolean): Tick {
        if (active != ticket || ticket.generation != generation) return Tick()
        if (!permitted || currentContext != ticket.context || nowMs < ticket.startedAtMs ||
            (nowMs > ticket.deadlineMs && nowMs - ticket.deadlineMs > 1_000)) {
            cancel()
            return Tick()
        }
        val remaining = ticket.deadlineMs - nowMs
        if (remaining <= 0) { cancel(); return Tick(fire = true) }
        return Tick(seconds = ((remaining + 999) / 1_000).coerceAtMost(10).toInt())
    }
}

/** A microphone response authorizes only the role/setup that requested it, never a later session. */
data class CaptureActionTicket(val selfRole: Boolean, val generation: Long) {
    fun isCurrent(role: Boolean, currentGeneration: Long): Boolean = selfRole == role && generation == currentGeneration
}
