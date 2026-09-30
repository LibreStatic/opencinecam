/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeContrastTest {
    private val semantics = mapOf("cine" to CineSemantic, "you-light" to YouLightSemantic, "you-dark" to YouDarkSemantic)

    @Test
    fun successPairsReadAsBodyText() {
        semantics.forEach { (name, colors) ->
            val ratio = contrastRatio(colors.success.container, colors.success.onContainer)
            assertTrue("$name success pair is $ratio:1", ratio >= 4.5f)
        }
    }

    @Test
    fun recordPairIsAtLeastAUiComponentContrast() {
        semantics.forEach { (name, colors) ->
            val ratio = contrastRatio(colors.record, colors.onRecord)
            assertTrue("$name record pair is $ratio:1", ratio >= 3f)
        }
    }

    @Test
    fun cineTextColoursReadOnTheCineSurfaces() {
        val surfaces = listOf(CineColorScheme.background, CineColorScheme.surface, CineColorScheme.surfaceContainer, CineColorScheme.surfaceContainerHigh)
        val texts = listOf(CineColorScheme.onSurface, CineColorScheme.onSurfaceVariant, CineSemantic.pending, CineSemantic.ok, CineSemantic.verified)
        surfaces.forEach { surface -> texts.forEach { text ->
            val ratio = contrastRatio(surface, text)
            assertTrue("$text on $surface is $ratio:1", ratio >= 4.5f)
        } }
    }

    @Test
    fun youLightStatusTextReadsOnWhite() {
        listOf(YouLightSemantic.pending, YouLightSemantic.ok, YouLightSemantic.verified).forEach {
            assertTrue("$it on white", contrastRatio(Color.White, it) >= 4.5f)
        }
    }

    @Test
    fun amoledKeepsBlackFloorAndContainerOrder() {
        val amoled = CineColorScheme.toAmoled()
        assertEquals(Color.Black, amoled.background)
        assertEquals(Color.Black, amoled.surface)
        val order = listOf(amoled.surfaceContainerLowest, amoled.surfaceContainerLow, amoled.surfaceContainer, amoled.surfaceContainerHigh, amoled.surfaceContainerHighest)
        order.zipWithNext().forEach { (lower, higher) ->
            assertTrue("$lower should be darker than $higher", contrastRatio(Color.Black, lower) <= contrastRatio(Color.Black, higher))
        }
    }

    @Test
    fun contrastRatioMatchesTheWcagExtremes() {
        assertEquals(21f, contrastRatio(Color.Black, Color.White), 0.01f)
        assertEquals(1f, contrastRatio(Color.Red, Color.Red), 0.0001f)
    }
}
