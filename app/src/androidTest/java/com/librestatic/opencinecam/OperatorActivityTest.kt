/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.Manifest
import android.app.AlertDialog
import android.os.SystemClock
import android.view.KeyEvent
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

/** Real Activity dispatch → current mapping → shared service → SettingsRepository. */
class OperatorActivityTest {
    @Test fun volumeChangesSharedMonitoringOnceAndCannotActThroughADialogOrPause() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
        val repository = SettingsRepositories.get(context)
        val original = repository.states.value
        val activity = AtomicReference<MainActivity?>()
        val dialog = AtomicReference<AlertDialog?>()
        var scenario: ActivityScenario<MainActivity>? = null
        fun waitFor(condition: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 15_000
            while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(30)
            assertTrue("Activity condition timed out", condition())
        }
        fun focused(): Boolean {
            val focused = AtomicBoolean(false)
            instrumentation.runOnMainSync { focused.set(activity.get()?.hasWindowFocus() == true && activity.get()?.operatorAction != null) }
            return focused.get()
        }
        fun event(key: Int, down: Boolean, repeat: Int = 0): Boolean {
            val consumed = AtomicBoolean(false)
            instrumentation.runOnMainSync {
                val event = KeyEvent(0, SystemClock.uptimeMillis(), if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, key, repeat)
                val owner = requireNotNull(activity.get())
                consumed.set(if (down) owner.onKeyDown(key, event) else owner.onKeyUp(key, event))
            }
            return consumed.get()
        }
        try {
            instrumentation.runOnMainSync { repository.set(CameraSettings(audioEnabled = false, operation = OperatorPreferences(volumeUp = OperatorAction.ZEBRA, volumeDown = OperatorAction.NONE))) }
            scenario = ActivityScenario.launch(MainActivity::class.java)
            scenario.onActivity { activity.set(it) }
            waitFor(::focused)
            instrumentation.sendKeySync(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP))
            waitFor { repository.states.value.zebraEnabled }
            repeat(5) { assertTrue(event(KeyEvent.KEYCODE_VOLUME_UP, true, it + 1)) }
            assertTrue(event(KeyEvent.KEYCODE_VOLUME_UP, false)); assertTrue(repository.states.value.zebraEnabled)
            assertTrue(event(KeyEvent.KEYCODE_VOLUME_DOWN, true)); assertTrue(event(KeyEvent.KEYCODE_VOLUME_DOWN, false))
            instrumentation.runOnMainSync { dialog.set(AlertDialog.Builder(requireNotNull(activity.get())).setMessage("Editing fixture").setPositiveButton("Close", null).show()) }
            waitFor { !focused() }
            event(KeyEvent.KEYCODE_VOLUME_UP, true); event(KeyEvent.KEYCODE_VOLUME_UP, false)
            assertTrue(repository.states.value.zebraEnabled)
            instrumentation.runOnMainSync { dialog.getAndSet(null)?.dismiss() }
            waitFor(::focused)
            scenario.moveToState(Lifecycle.State.STARTED)
            event(KeyEvent.KEYCODE_VOLUME_UP, true); event(KeyEvent.KEYCODE_VOLUME_UP, false)
            assertTrue(repository.states.value.zebraEnabled)
            scenario.moveToState(Lifecycle.State.RESUMED); waitFor(::focused)
            assertTrue(event(KeyEvent.KEYCODE_VOLUME_UP, true)); assertTrue(event(KeyEvent.KEYCODE_VOLUME_UP, false))
            waitFor { !repository.states.value.zebraEnabled }
            android.util.Log.i("OperatorInputProbe", "windowKeyDown=true firstDownZebra=true repeats=5 repeatToggles=0 dialogToggles=0 pausedToggles=0 resumedZebra=false")
        } finally {
            instrumentation.runOnMainSync { dialog.getAndSet(null)?.dismiss() }
            scenario?.close()
            instrumentation.runOnMainSync { repository.set(original) }
        }
    }
}
