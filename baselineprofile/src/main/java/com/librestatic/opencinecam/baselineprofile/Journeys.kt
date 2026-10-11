// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 OpenCineCam contributors

package com.librestatic.opencinecam.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until

/** The app under test, from the `targetAppId` instrumentation argument set in build.gradle.kts. */
val targetPackage: String
    get() = InstrumentationRegistry.getArguments().getString("targetAppId")
        ?: error("targetAppId instrumentation argument is missing; see baselineprofile/build.gradle.kts")

private const val ShortWaitMillis = 3_000L
private const val ChromeWaitMillis = 15_000L

/** Grants the runtime permissions up front so the journeys never stop at a system dialog. */
fun MacrobenchmarkScope.grantPermissions() {
    listOf(
        "android.permission.CAMERA",
        "android.permission.RECORD_AUDIO",
        "android.permission.POST_NOTIFICATIONS",
    ).forEach { permission ->
        runCatching { device.executeShellCommand("pm grant $packageName $permission") }
    }
}

/** Cold start up to a live capture chrome, skipping onboarding when it shows. */
fun MacrobenchmarkScope.startToCamera() {
    pressHome()
    startActivityAndWait()
    // A fresh install gets the system's one-time "Viewing full screen" notice over the app.
    device.wait(Until.findObject(By.res("com.android.systemui", "ok")), ShortWaitMillis)?.click()
    device.wait(Until.findObject(By.res("onboarding-skip")), ShortWaitMillis)?.click()
    // The gallery thumbnail on the capture chrome is a reliable "chrome is up" signal. Failing
    // here keeps a stuck journey from silently recording a profile of the wrong screen.
    check(device.wait(Until.hasObject(By.res("media-action")), ChromeWaitMillis)) { "capture chrome never showed" }
}

/** Opens and closes the mode sheet; the button is absent on some layouts, which is tolerated. */
fun MacrobenchmarkScope.openModeSheet() {
    val button = device.findObject(By.res("capture-mode-button")) ?: return
    button.click()
    device.wait(
        Until.hasObject(By.res("capture-mode-sheet").pkg(packageName)),
        ShortWaitMillis,
    ) || device.wait(Until.hasObject(By.res("capture-mode-content")), ShortWaitMillis)
    device.pressBack()
    device.waitForIdle()
}

/** Opens the gallery, scrolls it both ways, visits Settings, then goes back to the capture chrome. */
fun MacrobenchmarkScope.browseGallery() {
    val action = device.findObject(By.res("media-action")) ?: return
    action.click()
    check(device.wait(Until.hasObject(By.res("gallery-types")), ShortWaitMillis)) { "gallery never showed" }
    // A device with no takes shows gallery-empty and has nothing to scroll.
    device.findObject(By.res("gallery-list"))?.takeUnless { device.hasObject(By.res("gallery-empty")) }?.let { list ->
        list.setGestureMargin(device.displayWidth / 5)
        list.fling(Direction.DOWN)
        device.waitForIdle()
        list.fling(Direction.UP)
        device.waitForIdle()
    }
    // Settings sits on the same bottom bar; the capture tab leads back to the chrome.
    device.findObject(By.res("nav-settings"))?.let {
        it.click()
        device.wait(Until.hasObject(By.res("settings-categories")), ShortWaitMillis)
        device.findObject(By.res("nav-capture"))?.click() ?: device.pressBack()
    } ?: device.pressBack()
    device.wait(Until.hasObject(By.res("media-action")), ShortWaitMillis)
}
