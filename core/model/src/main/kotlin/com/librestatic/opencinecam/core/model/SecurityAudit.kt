/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.core.model

enum class AuditSeverity { INFO, WARNING, ERROR }

data class AuditFinding(
    val findingId: String,
    val severity: AuditSeverity,
    val passed: Boolean,
    val detail: String,
)

data class SecurityAuditInput(
    val permissions: Set<String>,
    val exportedComponents: Map<String, Boolean>,
    val launcherComponent: String?,
    val allowBackup: Boolean,
    val sharingRequiresExplicitConsent: Boolean,
    val diagnosticsRedactedByDefault: Boolean,
    val fullExportRequiresExplicitConsent: Boolean,
    val nativeInputsValidated: Boolean,
    val analyticsOrRemoteCrashEnabled: Boolean,
    val touchTargetDp: List<Int>,
    val semanticLabels: List<String>,
)

data class SecurityAuditReport(
    val findings: List<AuditFinding>,
    val passed: Boolean,
) {
    init {
        require(findings.map { it.findingId }.distinct().size == findings.size) {
            "audit finding IDs must be unique"
        }
    }
}

object SecurityPrivacyAccessibilityAuditor {
    private val allowedPermissions = setOf(
        "android.permission.CAMERA",
        "android.permission.RECORD_AUDIO",
        "android.permission.POST_NOTIFICATIONS",
        "android.permission.FOREGROUND_SERVICE",
        "android.permission.FOREGROUND_SERVICE_CAMERA",
        "android.permission.FOREGROUND_SERVICE_MICROPHONE",
    )

    fun audit(input: SecurityAuditInput): SecurityAuditReport {
        val findings = listOf(
            check(
                "SEC-NETWORK",
                input.permissions.none { it == "android.permission.INTERNET" },
                "Internet permission is absent",
                "Internet permission would permit data exfiltration",
            ),
            check(
                "SEC-PERMISSIONS",
                input.permissions.all { it in allowedPermissions },
                "permissions are in the least-privilege allowlist",
                "unexpected broad or network permission declared",
            ),
            check(
                "SEC-BACKUP",
                !input.allowBackup,
                "application backup is disabled",
                "backup could copy local diagnostics outside the app boundary",
            ),
            check(
                "SEC-COMPONENTS",
                input.exportedComponents.all { (name, exported) ->
                    !exported || name == input.launcherComponent
                },
                "only the deliberate launcher activity is exported",
                "non-launcher component is exported",
            ),
            check(
                "PRIV-CONSENT",
                input.sharingRequiresExplicitConsent,
                "sharing requires explicit user action",
                "sharing is automatic or implicit",
            ),
            check(
                "PRIV-REDACTION",
                input.diagnosticsRedactedByDefault && input.fullExportRequiresExplicitConsent,
                "diagnostics redact by default and gate full export",
                "diagnostics could expose identifiers without consent",
            ),
            check(
                "PRIV-NATIVE",
                input.nativeInputsValidated,
                "native inputs have bounded validation",
                "native boundary accepts unvalidated input",
            ),
            check(
                "PRIV-TELEMETRY",
                !input.analyticsOrRemoteCrashEnabled,
                "analytics and remote crash collection are disabled",
                "remote collection violates local-only policy",
            ),
            check(
                "A11Y-TOUCH",
                input.touchTargetDp.isNotEmpty() && input.touchTargetDp.all { it >= 48 },
                "all controls meet the 48dp touch target",
                "a control is smaller than 48dp",
            ),
            check(
                "A11Y-SEMANTICS",
                input.semanticLabels.isNotEmpty() && input.semanticLabels.all { it.isNotBlank() },
                "controls expose non-color semantic labels",
                "missing semantics would make controls inaccessible",
            ),
        )
        return SecurityAuditReport(findings, findings.all { it.passed })
    }

    private fun check(id: String, passed: Boolean, success: String, failure: String): AuditFinding =
        AuditFinding(id, if (passed) AuditSeverity.INFO else AuditSeverity.ERROR, passed, if (passed) success else failure)
}
