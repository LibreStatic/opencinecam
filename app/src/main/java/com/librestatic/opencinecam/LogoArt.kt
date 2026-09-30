/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.vector.PathParser
import kotlin.math.hypot

/**
 * What the system splash hands to the first screen. [iconBounds] is the splash icon view in window
 * pixels, known once the splash starts to leave; [playIntro] is true only on a start from scratch
 * that lands on the wizard.
 */
@Immutable
data class SplashHandoff(val onScreen: Boolean, val playIntro: Boolean = false, val iconBounds: Rect? = null)

/** Viewport of ic_launcher_foreground.xml and splash_logo_animated.xml. */
internal const val LogoViewport = 1000f

/** splash_logo_animated.xml scales the logo by this around the centre to fit the splash icon mask. */
internal const val LogoSplashScale = 0.6f

/**
 * The splash icon view shows the 72 dp safe zone of the 108 dp drawable, so the whole viewport is
 * 1.5 times the view.
 */
internal const val SplashIconViewportScale = 108f / 72f

/** Which theme role a logo part takes once the intro tints it. */
internal enum class LogoRole { PRIMARY, SECONDARY, OUTLINE, RECORD }

/**
 * One piece of the logo as it scatters. [stroke] is the stroke width for arcs (null for fills).
 * Iris blades carry the uncut [blade] and the [aperture] it is clipped to, so the dark separator
 * line is drawn exactly as the icon draws it (only between blades, never along the aperture edge).
 * [direction] is the unit vector the piece leaves along, from the logo centre.
 */
internal class LogoPiece(
    val path: Path,
    val brand: Color,
    val role: LogoRole,
    val stroke: Float? = null,
    val blade: Path? = null,
    val aperture: Path? = null,
    val direction: Offset,
    val spin: Float,
)

/** The launcher logo split into the pieces the onboarding intro scatters. Same paths as ic_launcher_foreground.xml. */
internal object LogoArt {
    val BrandAmber = Color(0xFFFFB300)
    val BrandCyan = Color(0xFF45D6E8)
    val BrandGrey = Color(0xFF9CA6AA)
    val BrandRecord = Color(0xFFE23A3A)
    val BrandSeparator = Color(0xFF0B0D0E)
    const val ArcStroke = 12f
    const val SeparatorStroke = 8f

    private fun parse(d: String): Path = PathParser().parsePathString(d).toPath()

    private val center = Offset(LogoViewport / 2f, LogoViewport / 2f)

    private fun directionOf(path: Path): Offset {
        val d = path.getBounds().center - center
        val length = hypot(d.x, d.y)
        return if (length < 1f) Offset.Zero else d / length
    }

    val pieces: List<LogoPiece> by lazy {
        val aperture = parse("M500,285 A215,215 0,1 1,500 715 A215,215 0,1 1,500 285")
        val blades = listOf(
            "M564.95,537.50 L500,575 L-149.52,950 L564.95,1287.50 Z",
            "M500,575 L435.05,537.50 L-214.47,162.50 L-149.52,950 Z",
            "M435.05,537.50 L435.05,462.50 L435.05,-287.50 L-214.47,162.50 Z",
            "M435.05,462.50 L500,425 L1149.52,50 L435.05,-287.50 Z",
            "M500,425 L564.95,462.50 L1214.47,837.50 L1149.52,50 Z",
            "M564.95,462.50 L564.95,537.50 L564.95,1287.50 L1214.47,837.50 Z",
        ).map(::parse)
        val ring = parse("M697.99,697.99 L768.70,768.70 A380,380 0,1 1,768.70 231.30 L697.99,302.01 A280,280 0,1 0,697.99 697.99 Z")
        val grey = parse("M627.50,720.84 A255,255 0,1 1,627.50 279.16")
        val cyanTop = parse("M646.26,291.12 A255,255 0,0 1,746.31 434.00")
        val cyanBottom = parse("M746.31,566.00 A255,255 0,0 1,646.26 708.88")
        val light = parse("M755,484 A16,16 0,1 1,755 516 A16,16 0,1 1,755 484")
        buildList {
            add(LogoPiece(ring, BrandAmber, LogoRole.PRIMARY, direction = Offset(-0.6f, 0f), spin = -50f))
            add(LogoPiece(grey, BrandGrey, LogoRole.OUTLINE, stroke = ArcStroke, direction = directionOf(grey), spin = 70f))
            add(LogoPiece(cyanTop, BrandCyan, LogoRole.SECONDARY, stroke = ArcStroke, direction = directionOf(cyanTop), spin = -90f))
            add(LogoPiece(cyanBottom, BrandCyan, LogoRole.SECONDARY, stroke = ArcStroke, direction = directionOf(cyanBottom), spin = 90f))
            add(LogoPiece(light, BrandRecord, LogoRole.RECORD, direction = directionOf(light), spin = 0f))
            blades.forEachIndexed { index, blade ->
                // The icon clips the blades to the aperture; cutting each one out lets it leave whole.
                val piece = Path().apply { op(blade, aperture, PathOperation.Intersect) }
                add(
                    LogoPiece(
                        piece, BrandAmber, LogoRole.PRIMARY, blade = blade, aperture = aperture,
                        direction = directionOf(piece), spin = if (index % 2 == 0) 120f else -120f,
                    ),
                )
            }
        }
    }
}
