/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.storage

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts

/**
 * PLAN-072: picks the recording folder with the system DocumentsUI. Third-party file managers also
 * answer `ACTION_OPEN_DOCUMENT_TREE` and hand back trees of their own, so the intent names the system
 * picker when one resolves. The tree is still validated on adoption ([validateRecordingTree]).
 */
internal class RecordingFolderPicker : ActivityResultContracts.OpenDocumentTree() {
    override fun createIntent(context: Context, input: Uri?): Intent {
        val intent = super.createIntent(context, input)
        documentsUiPackage { candidate -> resolves(context.packageManager, Intent(intent).setPackage(candidate)) }?.let(intent::setPackage)
        return intent
    }

    private fun resolves(packageManager: PackageManager, intent: Intent): Boolean =
        runCatching { packageManager.resolveActivity(intent, 0) != null }.getOrDefault(false)
}

/** The system DocumentsUI packages, Google's build first. */
internal val DOCUMENTS_UI_PACKAGES = listOf("com.google.android.documentsui", "com.android.documentsui")

/** The first DocumentsUI package that [resolves] the picker intent, or null to leave the choice to the system. */
internal fun documentsUiPackage(resolves: (String) -> Boolean): String? = DOCUMENTS_UI_PACKAGES.firstOrNull(resolves)
