/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import android.app.Application
import com.librestatic.opencinecam.storage.DriveDescriptorWatch

/**
 * The process's entry point. It only hands the application context to [DriveDescriptorWatch] (PLAN-072, ADR-0037), so
 * that any component of the process, a background worker included, has a descriptor it holds on the USB drive released
 * when the drive leaves. Nothing is started here; the watch begins with the first such descriptor.
 */
class OpenCineCamApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        DriveDescriptorWatch.install(this)
    }
}
