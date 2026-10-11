// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 OpenCineCam contributors

package com.librestatic.opencinecam.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Records the startup, capture chrome, mode sheet, gallery and settings journeys into the baseline profile. */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    /** Launch to a live capture chrome; the only journey that also shapes the startup (dex layout) profile. */
    @Test
    fun startup() = rule.collect(packageName = targetPackage, includeInStartupProfile = true) {
        grantPermissions()
        startToCamera()
    }

    @Test
    fun journeys() = rule.collect(packageName = targetPackage) {
        grantPermissions()
        startToCamera()
        openModeSheet()
        browseGallery()
    }
}
