/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import android.app.Activity
import android.app.ActivityManager
import android.content.Intent
import android.os.Bundle

/**
 * PLAN-072: what the system starts when a USB drive attaches and the operator answered its "which app" question with
 * OpenCineCam. It shows nothing and finishes at once, in a task of its own (an empty affinity in the manifest): started
 * in the app's task, it brought that task to the front on every attach, so a hub that drops and comes back kept pulling
 * a minimised camera forward. With the app open or in the background, whatever is in front stays as it was; with no
 * camera task yet, it opens the camera. The name is kept from the activity alias it replaces, so an "Always" answer
 * already given still applies.
 */
class UsbDriveAttachAlias : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!hasCameraTask()) startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    /** Whether a task of the app already holds [MainActivity]; an unreadable task list counts as one, so nothing opens. */
    private fun hasCameraTask(): Boolean = runCatching {
        getSystemService(ActivityManager::class.java).appTasks.any { it.taskInfo?.baseActivity?.className == MainActivity::class.java.name }
    }.getOrDefault(true)
}
