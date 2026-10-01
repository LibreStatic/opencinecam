/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.core.content.ContextCompat
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real ActivityResult and PermissionController UI, not a simulated Content callback.
 * Runner preconditions: API 33+, CAMERA granted; COARSE/FINE revoked with user-set/user-fixed
 * flags cleared before instrumentation, so one denial still permits a second Android prompt.
 * Uses standard Android PermissionController resource IDs, independent of translated labels.
 * The grant remains after this case; the runner restores permissions outside instrumentation.
 */
class GeotaggingPermissionUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun realPermissionDenialRetryAndLateGrantNeverOverrideExplicitOptOut() {
        val repository = SettingsRepositories.get(context)
        val before = repository.states.value
        val initial = before.copy(geotaggingEnabled = false, audioEnabled = false,
            audioSource = com.librestatic.opencinecam.media.audio.AudioSourceSelection.VOICE_RECOGNITION)
        val accessibilityFlags = instrumentation.uiAutomation.serviceInfo.flags
        try {
            instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            }
            assertTrue("Runner requires API 33+", Build.VERSION.SDK_INT >= 33)
            // Granting never restarts the process, so CAMERA is granted here; revoking would kill it,
            // so a location grant left by an earlier run can only be skipped.
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
            assertEquals("CAMERA grant failed", PackageManager.PERMISSION_GRANTED,
                ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA))
            org.junit.Assume.assumeTrue("Revoke location before running: adb shell pm revoke ${context.packageName} " +
                "android.permission.ACCESS_FINE_LOCATION (and ACCESS_COARSE_LOCATION)", locationPermission(context) == null)
            instrumentation.runOnMainSync { repository.set(initial) }
            compose.activityRule.scenario.recreate() // MainActivity started before CAMERA was granted.
            compose.waitUntil(15_000) {
                compose.onAllNodesWithContentDescription(context.getString(R.string.settings_tab)).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithContentDescription(context.getString(R.string.settings_tab)).performClick()
            compose.onNodeWithTag("settings-search").performTextInput("geotagging")
            node("enabled").performScrollTo().assertIsOff()
            node("retry").assertDoesNotExist()
            assertEquals(initial, repository.states.value)

            node("enabled").performScrollTo().performClick().assertIsOn()
            assertEquals(initial.copy(geotaggingEnabled = true), repository.states.value)
            assertNull(locationPermission(context))
            node("retry").performScrollTo().performClick()
            clickPermissionController("permission_deny_button")
            compose.waitUntil(15_000) {
                compose.onAllNodesWithTag("geotagging-denied", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            node("denied").performScrollTo().assertTextEquals(context.getString(R.string.geotagging_denied))
            node("app-settings").performScrollTo().assertIsEnabled()
            assertNull(locationPermission(context))
            assertEquals(initial.copy(geotaggingEnabled = true), repository.states.value)

            node("retry").performScrollTo().performClick()
            // Wait for the second real dialog before changing consent. No permission result has
            // been dispatched yet. Another local settings writer can opt out while it is open.
            awaitPermissionController("permission_allow_foreground_only_button").recycleNode()
            instrumentation.runOnMainSync {
                repository.update { it.copy(geotaggingEnabled = false) }
                CaptureLocations.get(context).refreshAccess()
            }
            assertEquals(initial, repository.states.value)
            assertNull(CaptureLocations.get(context).snapshot())
            clickPermissionController("permission_allow_foreground_only_button")
            compose.waitUntil(15_000) { locationPermission(context) != null }
            compose.waitUntil(15_000) {
                val precision = locationPermission(context)
                val label = context.getString(if (precision == LocationPermissionPrecision.PRECISE)
                    R.string.geotagging_permission_precise else R.string.geotagging_permission_approximate)
                compose.onAllNodes(hasTestTag("geotagging-permission") and hasText(label), useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
            node("enabled").performScrollTo().assertIsOff()
            node("status").performScrollTo().assertTextEquals(context.getString(R.string.geotagging_status_disabled))
            node("retry").assertDoesNotExist()
            assertEquals(initial, repository.states.value)
            assertNull(CaptureLocations.get(context).snapshot())

            // Permission is now granted, but only this later explicit toggle enables recording.
            node("enabled").performScrollTo().performClick().assertIsOn()
            assertEquals(initial.copy(geotaggingEnabled = true), repository.states.value)
            node("retry").assertDoesNotExist()
            node("enabled").performClick().assertIsOff()
            assertEquals(initial, repository.states.value)
            assertNotNull(locationPermission(context))
            assertNull(CaptureLocations.get(context).snapshot())
            android.util.Log.i("E16GeotagPermissionProbe",
                "realMainActivity=true realActivityResult=true systemDenial=true systemRetryGrant=true optedOutBeforeGrant=true grantDidNotEnableConsent=true laterExplicitToggle=true")
        } finally {
            try { compose.activityRule.scenario.close() }
            finally {
                try { instrumentation.runOnMainSync { repository.set(before); CaptureLocations.get(context).refreshAccess() } }
                finally {
                    instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply { flags = accessibilityFlags }
                }
            }
        }
    }

    private fun node(tag: String) = compose.onNodeWithTag("geotagging-$tag", useUnmergedTree = true)

    private fun clickPermissionController(id: String) {
        val target = awaitPermissionController(id)
        try {
            assertTrue("PermissionController rejected the single click on $id",
                target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        } finally { target.recycleNode() }
    }

    private fun awaitPermissionController(id: String): AccessibilityNodeInfo {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        var last = "no active accessibility window"
        var dismissedOnboarding = false
        while (SystemClock.elapsedRealtime() < deadline) {
            val root = instrumentation.uiAutomation.rootInActiveWindow
            if (root != null) {
                var match: AccessibilityNodeInfo? = null
                var onboarding: AccessibilityNodeInfo? = null
                var count = 0
                val observed = mutableListOf<String>()
                fun visit(node: AccessibilityNodeInfo, depth: Int) {
                    check(depth <= 32 && ++count <= 512) { "Permission dialog accessibility tree exceeds bounded inspection" }
                    val resource = node.viewIdResourceName.orEmpty()
                    if (resource.isNotEmpty() && observed.size < 32) observed += resource
                    if (node.packageName?.toString() == "android" && resource == "android:id/ok" && node.isVisibleToUser && node.isClickable) {
                        @Suppress("DEPRECATION")
                        val copy = AccessibilityNodeInfo.obtain(node)
                        onboarding = copy
                    }
                    val trustedPackage = node.packageName?.toString() in setOf(
                        "com.android.permissioncontroller", "com.google.android.permissioncontroller")
                    if (trustedPackage && resource.endsWith(":id/$id") && node.isVisibleToUser && node.isEnabled && node.isClickable) {
                        check(match == null) { "Multiple visible PermissionController actions for $id" }
                        @Suppress("DEPRECATION")
                        val copy = AccessibilityNodeInfo.obtain(node)
                        match = copy
                    }
                    for (index in 0 until node.childCount) {
                        val child = node.getChild(index) ?: continue
                        try { visit(child, depth + 1) } finally { child.recycleNode() }
                    }
                }
                try {
                    visit(root, 0)
                    last = "package=${root.packageName} nodes=$count ids=${observed.joinToString()}"
                    // Android's first immersive-mode tutorial can cover the actual permission
                    // dialog. Dismiss only this positively identified OS tutorial, once; this
                    // does not grant/deny location or replace the PermissionController click.
                    if (!dismissedOnboarding && "android:id/immersive_cling_title" in observed && onboarding != null) {
                        check(onboarding!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                        dismissedOnboarding = true
                        android.util.Log.i("E16GeotagPermissionProbe", "androidImmersiveOnboardingDismissed=true")
                    }
                    match?.let { found -> match = null; return found }
                } finally { match?.recycleNode(); onboarding?.recycleNode(); root.recycleNode() }
            }
            // Bounded observation only: each expected Android action is clicked exactly once.
            Thread.sleep(50)
        }
        error("Expected actual Android permission action $id within 15s; $last")
    }

    @Suppress("DEPRECATION")
    private fun AccessibilityNodeInfo.recycleNode() = recycle()
}
