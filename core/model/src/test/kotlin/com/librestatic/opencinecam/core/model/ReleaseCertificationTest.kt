/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseCertificationTest {
    private val hash = "a".repeat(64)
    private val artifact = ReleaseArtifactIdentity("release-1", 1, "unsigned", hash, hash)
    private val target = CertificationTarget("device/fingerprint", 36, "protocol-1")

    @Test
    fun exactArtifactTargetAndAllChecksCertify() {
        val result = ReleaseCertification.evaluate(
            artifact,
            target,
            mapOf("build" to true, "privacy" to true, "sustained" to true),
        )
        assertEquals(CertificationStatus.CERTIFIED, result.status)
        assertEquals(listOf("build", "privacy", "sustained"), result.passedChecks)
        assertTrue(result.failedChecks.isEmpty())
    }

    @Test
    fun missingTargetAndFailedChecksNeverCertify() {
        assertEquals(CertificationStatus.NOT_RUN,
            ReleaseCertification.evaluate(artifact, target.copy(fingerprint = null), mapOf("build" to true)).status)
        val failed = ReleaseCertification.evaluate(artifact, target,
            mapOf("build" to true, "device" to false))
        assertEquals(CertificationStatus.FAILED, failed.status)
        assertEquals(listOf("device"), failed.failedChecks)
    }

    @Test
    fun lifecycleExposesCancellationDuplicateAndCleanup() {
        val session = CertificationSession()
        assertEquals(CertificationCommandStatus.CANCELLED, session.cancel("cancel"))
        assertEquals(CertificationCommandStatus.CANCELLED,
            session.run("cancel", artifact, target, mapOf("build" to true)))
        assertEquals(CertificationCommandStatus.STARTED,
            session.run("one", artifact, target, mapOf("build" to true)))
        assertEquals(CertificationCommandStatus.DUPLICATE,
            session.run("one", artifact, target, mapOf("build" to true)))
        session.close()
        assertEquals(CertificationCommandStatus.CLOSED,
            session.run("two", artifact, target, mapOf("build" to true)))
    }
}
