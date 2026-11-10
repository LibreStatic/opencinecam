/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import org.junit.Assert.*
import org.junit.Test

class VolumeKeyGesturesTest {
    private val up = 24
    private val down = 25

    private class FakeScheduler : VolumeKeyScheduler {
        var time = 0L
        private var next = 0
        private val tasks = sortedMapOf<Pair<Long, Int>, () -> Unit>(compareBy<Pair<Long, Int>> { it.first }.thenBy { it.second })
        val pending: Int get() = tasks.size
        override fun now() = time
        override fun schedule(delayMs: Long, task: () -> Unit): () -> Unit {
            val id = (time + delayMs) to next++
            tasks[id] = task
            return { tasks.remove(id) }
        }
        fun advance(ms: Long) {
            val end = time + ms
            while (true) {
                val first = tasks.keys.firstOrNull()?.takeIf { it.first <= end } ?: break
                time = first.first
                tasks.remove(first)!!.invoke()
            }
            time = end
        }
    }

    private class Recorder : VolumeKeyHandler {
        val events = mutableListOf<String>()
        var available = true
        override fun perform(action: OperatorAction) { events += "perform:$action" }
        override fun step(pair: VolumeKeyPair, direction: Int, announce: Boolean): Boolean {
            events += "step:$pair:$direction:${if (announce) "first" else "repeat"}"; return available
        }
        override fun zoomContinuous(direction: Int, deltaSeconds: Float) { events += "zoom:$direction" }
        override fun haptic(event: HapticEvent) { events += "haptic:$event" }
        override fun adjustVolume(direction: Int) { events += "volume:$direction" }
    }

    private val clock = FakeScheduler()
    private val engine = VolumeKeyGestures(clock, longPressMs = 400, doubleTapMs = 300)
    private val handler = Recorder()
    private fun press(key: Int, prefs: OperatorPreferences?, repeat: Int = 0, cancelled: Boolean = false) =
        engine.dispatch(key, true, repeat, cancelled, prefs, handler)
    private fun release(key: Int, prefs: OperatorPreferences?, cancelled: Boolean = false) =
        engine.dispatch(key, false, 0, cancelled, prefs, handler)

    @Test fun immediatePressFiresOnDownAndIgnoresRepeatsAndMappingChanges() {
        val prefs = OperatorPreferences(volumeUp = OperatorAction.CAPTURE)
        assertTrue(press(up, prefs))
        assertEquals(listOf("perform:CAPTURE"), handler.events)
        repeat(10) { assertTrue(press(up, prefs.copy(volumeUp = OperatorAction.TORCH), repeat = it + 1)) }
        clock.advance(2_000)
        assertTrue(release(up, null))
        assertEquals(listOf("perform:CAPTURE"), handler.events)
        assertTrue(press(up, prefs.copy(volumeUp = OperatorAction.TORCH)))
        assertEquals(listOf("perform:CAPTURE", "perform:TORCH"), handler.events)
        assertEquals(0, clock.pending)
    }
    @Test fun keysHaveIndependentImmediatePresses() {
        val prefs = OperatorPreferences(volumeUp = OperatorAction.ZEBRA, volumeDown = OperatorAction.ZEBRA)
        assertTrue(press(up, prefs)); assertTrue(press(down, prefs))
        assertEquals(2, handler.events.size)
        engine.clear()
        assertFalse(press(up, prefs, repeat = 3)); assertEquals(2, handler.events.size)
    }
    @Test fun noneIsConsumedButDoesNothing() {
        val prefs = OperatorPreferences(volumeUp = OperatorAction.NONE)
        assertTrue(press(up, prefs)); assertTrue(release(up, prefs))
        assertTrue(handler.events.isEmpty())
    }
    @Test fun longPressFiresAtTheThresholdAndNotAgainOnRelease() {
        val prefs = OperatorPreferences(volumeUp = OperatorAction.TORCH, volumeUpLong = OperatorAction.CAPTURE)
        assertTrue(press(up, prefs))
        clock.advance(399); assertTrue(handler.events.isEmpty())
        clock.advance(1); assertEquals(listOf("haptic:Limit", "perform:CAPTURE"), handler.events)
        clock.advance(1_000); assertTrue(release(up, prefs))
        clock.advance(1_000)
        assertEquals(listOf("haptic:Limit", "perform:CAPTURE"), handler.events)
    }
    @Test fun shortPressWithOnlyALongActionFiresOnRelease() {
        val prefs = OperatorPreferences(volumeUp = OperatorAction.TORCH, volumeUpLong = OperatorAction.CAPTURE)
        assertTrue(press(up, prefs)); clock.advance(100)
        assertTrue(handler.events.isEmpty())
        assertTrue(release(up, prefs))
        assertEquals(listOf("perform:TORCH"), handler.events)
        clock.advance(1_000); assertEquals(1, handler.events.size)
    }
    @Test fun doublePressFiresTheDoubleActionOnly() {
        val prefs = OperatorPreferences(volumeUp = OperatorAction.TORCH, volumeUpDouble = OperatorAction.ZEBRA)
        assertTrue(press(up, prefs)); clock.advance(80); assertTrue(release(up, prefs))
        clock.advance(200); assertTrue(handler.events.isEmpty())
        assertTrue(press(up, prefs)); assertEquals(listOf("perform:ZEBRA"), handler.events)
        clock.advance(600); assertTrue(release(up, prefs))
        clock.advance(1_000)
        assertEquals(listOf("perform:ZEBRA"), handler.events)
    }
    @Test fun singlePressFiresAfterTheDoubleWindow() {
        val prefs = OperatorPreferences(volumeUp = OperatorAction.TORCH, volumeUpDouble = OperatorAction.ZEBRA)
        assertTrue(press(up, prefs)); assertTrue(release(up, prefs))
        clock.advance(299); assertTrue(handler.events.isEmpty())
        clock.advance(1); assertEquals(listOf("perform:TORCH"), handler.events)
        // The next press starts a new gesture.
        assertTrue(press(up, prefs)); assertTrue(release(up, prefs)); clock.advance(300)
        assertEquals(listOf("perform:TORCH", "perform:TORCH"), handler.events)
    }
    @Test fun theOtherKeyFlushesAPendingPressFirst() {
        val prefs = OperatorPreferences(volumeUp = OperatorAction.TORCH, volumeUpDouble = OperatorAction.ZEBRA, volumeDown = OperatorAction.PEAKING)
        assertTrue(press(up, prefs)); assertTrue(release(up, prefs))
        assertTrue(press(down, prefs))
        assertEquals(listOf("perform:TORCH", "perform:PEAKING"), handler.events)
        clock.advance(1_000); assertEquals(2, handler.events.size)
    }
    @Test fun overlappingPressesBothFireTheirPress() {
        val prefs = OperatorPreferences(volumeUp = OperatorAction.TORCH, volumeUpDouble = OperatorAction.ZEBRA,
            volumeDown = OperatorAction.SYSTEM_VOLUME, volumeDownDouble = OperatorAction.PEAKING)
        assertTrue(press(up, prefs)); assertTrue(press(down, prefs))
        assertTrue(release(down, prefs)); assertTrue(handler.events.isEmpty())
        assertTrue(release(up, prefs))
        assertEquals(listOf("volume:-1"), handler.events)
        clock.advance(300)
        assertEquals(listOf("volume:-1", "perform:TORCH"), handler.events)
        assertEquals(0, clock.pending)
    }
    @Test fun bothKeysFireTheChordAndSwallowBothUntilBothAreUp() {
        val prefs = OperatorPreferences(volumeUp = OperatorAction.TORCH, volumeDown = OperatorAction.PEAKING,
            volumeUpLong = OperatorAction.CAPTURE, volumeBoth = OperatorAction.CONTROL_LOCK)
        assertTrue(press(up, prefs)); clock.advance(50)
        assertTrue(press(down, prefs))
        assertEquals(listOf("perform:CONTROL_LOCK"), handler.events)
        clock.advance(1_000) // the long press of the first key was cancelled
        assertTrue(release(up, prefs))
        assertTrue(press(up, prefs)) // pressed again while the other is still down: still the chord
        assertTrue(release(down, prefs)); assertTrue(release(up, prefs))
        clock.advance(1_000)
        assertEquals(listOf("perform:CONTROL_LOCK"), handler.events)
        // Afterwards each key is on its own again.
        assertTrue(press(down, prefs)); assertTrue(release(down, prefs))
        assertEquals(listOf("perform:CONTROL_LOCK", "perform:PEAKING"), handler.events)
    }
    @Test fun aKeyThatAlreadyFiredItsLongPressStartsNoChord() {
        val prefs = OperatorPreferences(volumeUp = OperatorAction.TORCH, volumeUpLong = OperatorAction.CAPTURE,
            volumeDown = OperatorAction.PEAKING, volumeBoth = OperatorAction.CONTROL_LOCK)
        assertTrue(press(up, prefs)); clock.advance(500)
        assertTrue(press(down, prefs)); assertTrue(release(down, prefs))
        assertEquals(listOf("haptic:Limit", "perform:CAPTURE", "perform:PEAKING"), handler.events)
    }
    @Test fun systemVolumePassesThroughUnlessAGestureNeedsTheKey() {
        val plain = OperatorPreferences()
        assertFalse(press(up, plain)); assertFalse(release(up, plain))
        assertFalse(press(down, plain, repeat = 4))
        assertTrue(handler.events.isEmpty())
        // A long action on the key means the press has to wait, so it is emulated once on release.
        val held = OperatorPreferences(volumeUpLong = OperatorAction.CAPTURE)
        assertTrue(press(up, held)); assertTrue(press(up, held, repeat = 1)); assertTrue(release(up, held))
        assertEquals(listOf("volume:1"), handler.events)
        // The chord alone also holds both presses back.
        val chord = OperatorPreferences(volumeBoth = OperatorAction.CAPTURE)
        assertTrue(press(down, chord)); assertTrue(release(down, chord))
        assertEquals(listOf("volume:1", "volume:-1"), handler.events)
    }
    @Test fun pairStepsOnDownThenRunsContinuouslyUntilRelease() {
        val prefs = OperatorPreferences(volumePair = VolumeKeyPair.ISO)
        assertTrue(press(up, prefs))
        assertEquals(listOf("step:ISO:1:first"), handler.events)
        clock.advance(399); assertEquals(1, handler.events.size)
        clock.advance(1); assertEquals("step:ISO:1:repeat", handler.events.last())
        clock.advance(300); assertEquals(4, handler.events.size)
        assertTrue(release(up, prefs)); clock.advance(1_000)
        assertEquals(4, handler.events.size); assertEquals(0, clock.pending)
        // A short press is one step and nothing more.
        assertTrue(press(down, prefs.copy(volumePairInverted = true))); assertTrue(release(down, prefs)); clock.advance(1_000)
        assertEquals("step:ISO:1:first", handler.events.last()); assertEquals(5, handler.events.size)
    }
    @Test fun pairZoomTicksEveryFrameWithElapsedTime() {
        val prefs = OperatorPreferences(volumePair = VolumeKeyPair.ZOOM)
        assertTrue(press(down, prefs))
        clock.advance(400 + 16 * 5)
        assertEquals(listOf("step:ZOOM:-1:first") + List(5) { "zoom:-1" }, handler.events)
        assertTrue(release(down, prefs)); clock.advance(1_000)
        assertEquals(6, handler.events.size)
    }
    @Test fun anUnavailablePairStepIsAnnouncedOnceAndDoesNotRepeat() {
        val prefs = OperatorPreferences(volumePair = VolumeKeyPair.FOCUS)
        handler.available = false
        assertTrue(press(up, prefs)); clock.advance(2_000)
        assertEquals(listOf("step:FOCUS:1:first"), handler.events)
        assertTrue(release(up, prefs))
    }
    @Test fun pairChordCancelsTheLoopAndStepsNoMore() {
        val prefs = OperatorPreferences(volumePair = VolumeKeyPair.EXPOSURE_COMPENSATION, volumeBoth = OperatorAction.AUTO_FOCUS)
        assertTrue(press(up, prefs)); clock.advance(600)
        val before = handler.events.size
        assertTrue(press(down, prefs))
        assertEquals("perform:AUTO_FOCUS", handler.events.last())
        clock.advance(1_000)
        assertEquals(before + 1, handler.events.size)
        assertTrue(release(up, prefs)); assertTrue(release(down, prefs)); assertEquals(0, clock.pending)
    }
    @Test fun clearCancelsPendingPressesAndLoops() {
        val pair = OperatorPreferences(volumePair = VolumeKeyPair.ZOOM)
        assertTrue(press(up, pair)); clock.advance(500)
        engine.clear(); val count = handler.events.size
        clock.advance(1_000); assertEquals(count, handler.events.size)
        assertFalse(release(up, pair))
        val tap = OperatorPreferences(volumeUp = OperatorAction.TORCH, volumeUpDouble = OperatorAction.ZEBRA, volumeUpLong = OperatorAction.CAPTURE)
        assertTrue(press(up, tap)); assertTrue(release(up, tap)); assertTrue(press(down, OperatorPreferences(volumeDown = OperatorAction.NONE)))
        engine.clear()
        assertTrue(press(up, tap)); engine.clear(); clock.advance(1_000)
        assertEquals(count + 1, handler.events.size) // only the flushed TORCH from the other key
        assertEquals(0, clock.pending)
    }
    @Test fun ineligibleOrCancelledKeysAreNeverConsumed() {
        val prefs = OperatorPreferences(volumeUp = OperatorAction.CAPTURE, volumePair = VolumeKeyPair.ZOOM)
        assertFalse(press(up, null))
        assertFalse(press(up, prefs, cancelled = true))
        assertFalse(press(up, prefs, repeat = 1))
        assertFalse(engine.dispatch(up, true, 0, false, prefs, null))
        assertFalse(release(up, prefs))
        assertTrue(handler.events.isEmpty())
    }
    @Test fun aCancelledReleaseNeverFiresThePress() {
        val prefs = OperatorPreferences(volumeUp = OperatorAction.TORCH, volumeUpLong = OperatorAction.CAPTURE)
        assertTrue(press(up, prefs)); assertTrue(release(up, prefs, cancelled = true))
        clock.advance(1_000); assertTrue(handler.events.isEmpty())
    }
}
