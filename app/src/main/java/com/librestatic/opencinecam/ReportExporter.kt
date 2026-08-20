/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import com.librestatic.opencinecam.core.model.JsonReportRedactor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

enum class ReportExportMode {
    REDACTED,
    FULL,
}

sealed interface ReportExportOutcome {
    data object Written : ReportExportOutcome
    data class Rejected(val reason: String) : ReportExportOutcome
    data class Failed(val reason: String) : ReportExportOutcome
}

fun canExport(mode: ReportExportMode, fullConsent: Boolean): Boolean =
    mode == ReportExportMode.REDACTED || fullConsent

class ReportExporter(private val resolver: ContentResolver) {
    fun createDocumentIntent(fileName: String): Intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
        type = "application/json"
        putExtra(Intent.EXTRA_TITLE, fileName)
        addCategory(Intent.CATEGORY_OPENABLE)
    }

    fun export(uri: Uri, report: JsonElement, mode: ReportExportMode, fullConsent: Boolean): ReportExportOutcome {
        if (!canExport(mode, fullConsent)) {
            return ReportExportOutcome.Rejected("Full report export requires explicit privacy consent.")
        }
        val payload = when (mode) {
            ReportExportMode.REDACTED -> JsonReportRedactor.redact(report)
            ReportExportMode.FULL -> report
        }
        return runCatching {
            resolver.openOutputStream(uri)?.use { output ->
                output.write(Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), payload).toByteArray())
            } ?: return ReportExportOutcome.Failed("The selected destination could not be opened.")
            ReportExportOutcome.Written
        }.getOrElse { ReportExportOutcome.Failed("The report could not be written safely.") }
    }

    fun shareIntent(uri: Uri, mode: ReportExportMode, fullConsent: Boolean): Intent? {
        if (!canExport(mode, fullConsent)) return null
        return Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
