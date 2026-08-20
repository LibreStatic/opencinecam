/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonReportRedactorTest {
    @Test
    fun redactionRemovesSensitiveKeysRecursively() {
        val report = Json.parseToJsonElement("""{"device":{"buildFingerprint":"secret","sdkInt":37},"nested":[{"uri":"content://private","ok":true}]}""")
        val redacted = JsonReportRedactor.redact(report).jsonObject
        assertFalse(redacted["device"]!!.jsonObject.containsKey("buildFingerprint"))
        assertFalse(redacted["nested"]!!.toString().contains("content://private"))
        assertTrue(redacted["device"]!!.jsonObject.containsKey("sdkInt"))
    }
}
