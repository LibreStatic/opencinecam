/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.*
import org.junit.Test

class CaptureNamingTest {
    private val id = "12345678-1234-1234-1234-123456789abc"
    private val slate = ProductionSlateSettings(project = "Shoot Ñ", camera = "A", scene = "12B", reel = "R03", takeNumber = 7)
    private fun snapshot(template: String) = CaptureNameSnapshot(CaptureNamingSettings(true, template), slate, 0)
    @Test fun disabledAndAbsentNamingPreserveLegacyNamesExactly() {
        assertEquals("OCC_$id", captureFileStem(id))
        assertEquals("OCC_$id", captureFileStem(id, CaptureNameSnapshot(CaptureNamingSettings(), slate, 0)))
    }
    @Test fun everyTokenRendersFromOneSnapshotWithStableUtcClockAndTakePadding() {
        assertEquals("Shoot_Ñ_A_12B_0007_R03_19700101_000000000_$id",
            captureFileStem(id, snapshot("{project}_{camera}_{scene}_{take}_{reel}_{date}_{time}")))
    }
    @Test fun defaultLocaleAndTimezoneCannotAlterTheCaptureName() {
        val locale = Locale.getDefault(); val zone = TimeZone.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ar"));TimeZone.setDefault(TimeZone.getTimeZone("GMT-11"))
            assertEquals("19700101_000000000_0007_$id",captureFileStem(id,snapshot("{date}_{time}_{take}")))
        } finally { Locale.setDefault(locale);TimeZone.setDefault(zone) }
    }
    @Test fun unknownTokensMalformedBracesPathsAndInvalidUnicodeAreRejectedEvenWhenDisabled() {
        for (template in listOf("", " ", ".", "..", "x/../y", "x\\y", "{unknown}", "{PROJECT}", "{take", "{{take}}", "{take}}", "x\n", "x\u202e", "x".repeat(129), "\uD800", "ending.", "ending ")) {
            assertTrue(template,runCatching { CaptureNamingSettings(false,template) }.isFailure)
        }
        assertEquals("x".repeat(128),CaptureNamingSettings(template="x".repeat(128)).template)
    }
    @Test fun editorialCharactersAreNormalizedOnlyForTheNameNeverForTheSlate() {
        val original=slate.copy(project="../Shoot \"Ñ\"",scene="A/1",camera="Cafe\u0301")
        val snap=CaptureNameSnapshot(CaptureNamingSettings(true,"{project}_{camera}_{scene}"),original,0)
        assertEquals("Shoot__Ñ__Café_A_1_$id",captureFileStem(id,snap))
        assertEquals(original,snap.slate)
        assertEquals("Café",validateCaptureNameTemplate("Cafe\u0301"))
    }
    @Test fun fullIdSeparatesIndependentTakesEvenWhenAllEditorialValuesMatch() {
        val other="12345678-1234-1234-1234-123456789abd"
        assertNotEquals(captureFileStem(id,snapshot("Fixed")),captureFileStem(other,snapshot("Fixed")))
        assertTrue(captureFileStem(id,snapshot("Fixed")).endsWith(id))
        assertTrue(runCatching { captureFileStem("123",snapshot("Fixed")) }.isFailure)
    }
    @Test fun unicodePrefixIsBoundedByBytesWithoutSplittingCodepointsAndLeavesSuffixRoom() {
        val snap=CaptureNameSnapshot(CaptureNamingSettings(true,"{project}_{scene}"),slate.copy(project="🎬".repeat(64),scene="Ñ".repeat(128)),0)
        val stem=captureFileStem(id,snap)
        assertTrue(Charsets.UTF_8.newEncoder().canEncode(stem))
        assertEquals(160+1+36,stem.toByteArray(Charsets.UTF_8).size)
        assertTrue((stem+".accumulation.json").toByteArray(Charsets.UTF_8).size<=255)
    }
    @Test fun emptyRenderedFieldsHaveAnExplicitBoundedFallbackNotAnEmptyFilename() {
        val snap=CaptureNameSnapshot(CaptureNamingSettings(true,"{project}_{scene}"),ProductionSlateSettings(),0)
        assertEquals("untitled_$id",captureFileStem(id,snap))
    }
    @Test fun immutableSnapshotRetainsItsTemplateSlateAndClockAfterNextIntentIsEdited() {
        val original=snapshot("{scene}_T{take}_{time}")
        val next=original.copy(settings=CaptureNamingSettings(true,"Next"),slate=slate.copy(scene="Next",takeNumber=8),epochMillis=1000)
        assertEquals("12B_T0007_000000000_$id",captureFileStem(id,original))
        assertEquals("Next_$id",captureFileStem(id,next))
        assertTrue(runCatching { original.copy(epochMillis=-1) }.isFailure)
    }
}
