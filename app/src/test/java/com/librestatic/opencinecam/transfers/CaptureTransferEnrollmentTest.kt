/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.transfers

import org.junit.Assert.*
import org.junit.Test

class CaptureTransferEnrollmentTest {
    private val value = CaptureTransferEnrollment("00000000-0000-0000-0000-000000000001", "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", 3, 7)
    private val encoded get() = CaptureTransferEnrollmentCodec.encode(value).toString(Charsets.UTF_8)

    @Test fun canonicalRoundTripHasOnlyEnrollmentIdentityAndConsentFields() {
        assertEquals("{\"version\":1,\"bundleId\":\"${value.bundleId}\",\"endpointId\":\"${value.endpointId}\",\"endpointRevision\":3,\"consentRevision\":7}", encoded)
        assertEquals(value, CaptureTransferEnrollmentCodec.decode(encoded.toByteArray()))
    }

    @Test fun longRevisionsRemainExactAboveTheDoubleIntegerBoundary() {
        for (revision in listOf(0L, 9_007_199_254_740_991L, 9_007_199_254_740_992L, 9_007_199_254_740_993L, Long.MAX_VALUE)) {
            val enrollment = value.copy(endpointRevision = revision, consentRevision = revision)
            val bytes = CaptureTransferEnrollmentCodec.encode(enrollment)
            assertTrue(bytes.toString(Charsets.UTF_8).contains("\"endpointRevision\":$revision"))
            assertEquals(enrollment, CaptureTransferEnrollmentCodec.decode(bytes))
        }
    }

    @Test fun identifiersMustBeCanonicalUuids() {
        for (invalid in listOf("", "../enrollment", "1-1-1-1-1", value.endpointId.uppercase(), " ${value.bundleId}")) {
            assertThrows(IllegalArgumentException::class.java) { value.copy(bundleId = invalid) }
            assertThrows(IllegalArgumentException::class.java) { value.copy(endpointId = invalid) }
        }
    }

    @Test fun revisionsMustBeNonnegative() {
        assertThrows(IllegalArgumentException::class.java) { value.copy(endpointRevision = -1) }
        assertThrows(IllegalArgumentException::class.java) { value.copy(consentRevision = Long.MIN_VALUE) }
    }

    @Test fun futureOrCoercedSchemaVersionsAreRejected() {
        for (version in listOf("2", "\"1\"", "1.0", "1e0", "null", "true")) reject(encoded.replace("\"version\":1", "\"version\":$version"))
    }

    @Test fun revisionStringsFloatsExponentsAndOverflowAreRejected() {
        for (revision in listOf("\"3\"", "3.0", "3e0", "-1", "9223372036854775808", "null", "false")) {
            reject(encoded.replace("\"endpointRevision\":3", "\"endpointRevision\":$revision"))
            reject(encoded.replace("\"consentRevision\":7", "\"consentRevision\":$revision"))
        }
    }

    @Test fun duplicateEvenEqualKeysAreRejectedRatherThanDiscardedByTheParser() {
        reject(encoded.replace("\"version\":1", "\"version\":1,\"version\":1"))
        reject(encoded.replace("\"consentRevision\":7", "\"consentRevision\":2,\"consentRevision\":7"))
    }

    @Test fun unknownMissingAndNestedFieldsAreRejected() {
        reject(encoded.replace("\"version\":1,", ""))
        reject(encoded.dropLast(1) + ",\"authorizeUpload\":true}")
        reject(encoded.replace("\"endpointRevision\":3", "\"endpointRevision\":{\"value\":3}"))
    }

    @Test fun writerOwnedFormatRejectsWhitespaceReorderingAndTrailingData() {
        reject(" $encoded")
        reject(encoded.replace("\"version\":1,", "").dropLast(1) + ",\"version\":1}")
        reject(encoded + "\n")
        reject(encoded + "{}")
    }

    @Test fun malformedUtf8AndOversizedBytesAreRejected() {
        assertThrows(CaptureTransferEnrollmentCorruptData::class.java) { CaptureTransferEnrollmentCodec.decode(byteArrayOf(0xc3.toByte(), 0x28)) }
        assertThrows(CaptureTransferEnrollmentCorruptData::class.java) { CaptureTransferEnrollmentCodec.decode(ByteArray(CaptureTransferEnrollmentCodec.MAX_BYTES + 1)) }
        reject("")
        reject("{partial")
    }

    @Test fun depthGuardRejectsShortDeepDocumentsBeforeRecursiveParsing() {
        reject("[".repeat(400) + "0" + "]".repeat(400))
        reject("{\"x\":[]}")
    }

    @Test fun tamperedUuidOrIdentifierTypeIsRejected() {
        reject(encoded.replace(value.endpointId, value.endpointId.uppercase()))
        reject(encoded.replace("\"${value.bundleId}\"", "null"))
    }

    private fun reject(document: String) {
        assertThrows(CaptureTransferEnrollmentCorruptData::class.java) { CaptureTransferEnrollmentCodec.decode(document.toByteArray()) }
    }
}
