/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.ui.theme

import android.content.Context
import android.content.SharedPreferences
import android.database.ContentObserver
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.expressiveLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView

/** Built-in looks. CINE is the approved graphite/amber palette; YOU follows the wallpaper and the system light/dark mode. */
enum class AppTheme { CINE, YOU }

/** The chosen look, kept apart from [com.librestatic.opencinecam.CameraSettings] so presets never change it. */
class AppThemeStore(private val prefs: SharedPreferences) {
    constructor(context: Context) : this(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE))

    fun load(): AppTheme = prefs.getString(KEY, null)?.let { name -> AppTheme.entries.firstOrNull { it.name == name } } ?: AppTheme.CINE

    fun save(theme: AppTheme) {
        prefs.edit().putString(KEY, theme.name).apply()
    }

    fun register(listener: SharedPreferences.OnSharedPreferenceChangeListener) = prefs.registerOnSharedPreferenceChangeListener(listener)
    fun unregister(listener: SharedPreferences.OnSharedPreferenceChangeListener) = prefs.unregisterOnSharedPreferenceChangeListener(listener)

    companion object {
        const val PREFS = "camera-settings"
        const val KEY = "app-theme"
    }
}

/** A container colour and the content colour drawn on it; both are checked for contrast in tests. */
@Immutable
data class ColorPair(val container: Color, val onContainer: Color)

/**
 * Meanings the Material roles do not carry. Red is only for REC and errors, amber for pending or
 * selected, green for OK, cyan for verified hardware truth.
 */
@Immutable
data class CineSemanticColors(
    val record: Color,
    val onRecord: Color,
    val success: ColorPair,
    /** Green text or glyphs on the surface ("allowed", "OK"). */
    val ok: Color,
    /** Amber text on the surface for pending changes and warnings. */
    val pending: Color,
    val verified: Color,
)

internal val CineGraphite = Color(0xFF0B0D0E)

/** The approved dark cine palette; the only scheme that does not follow the system. */
val CineColorScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFFFFB300),
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF3A2E12),
    onPrimaryContainer = Color(0xFFFFCF66),
    secondary = Color(0xFF45D6E8),
    onSecondary = Color.Black,
    // Material uses this pair for selected chips and for the inactive part of a slider, so a
    // dim amber reads as "selected" on a chip and as the unfilled track on a slider.
    secondaryContainer = Color(0xFF4A3A12),
    onSecondaryContainer = Color(0xFFFFCF66),
    tertiary = Color(0xFF7FD9E4),
    onTertiary = Color.Black,
    tertiaryContainer = Color(0xFF0F3A40),
    onTertiaryContainer = Color(0xFFA6EEF6),
    error = Color(0xFFFF8A80),
    onError = Color.Black,
    errorContainer = Color(0xFF5C1414),
    onErrorContainer = Color(0xFFFFDAD6),
    background = CineGraphite,
    onBackground = Color.White,
    surface = Color(0xFF101417),
    onSurface = Color.White,
    surfaceVariant = Color(0xFF1B2226),
    onSurfaceVariant = Color(0xFFAAB4BA),
    surfaceDim = CineGraphite,
    surfaceBright = Color(0xFF2A3237),
    surfaceContainerLowest = Color(0xFF07090A),
    surfaceContainerLow = Color(0xFF0E1214),
    surfaceContainer = Color(0xFF12171A),
    surfaceContainerHigh = Color(0xFF1B2226),
    surfaceContainerHighest = Color(0xFF232B30),
    inverseSurface = Color(0xFFE6EBEE),
    inverseOnSurface = Color(0xFF101417),
    inversePrimary = Color(0xFF7A5900),
    outline = Color(0xFF41494C),
    outlineVariant = Color(0xFF263036),
    scrim = Color.Black,
)

val CineSemantic = CineSemanticColors(
    record = Color(0xFFE23A3A),
    onRecord = Color.White,
    success = ColorPair(container = Color(0xFF10301F), onContainer = Color(0xFF4BD28A)),
    ok = Color(0xFF4BD28A),
    pending = Color(0xFFFFCF66),
    verified = Color(0xFF45D6E8),
)

/** Tone 90 on tone 10 and tone 30 on tone 90 of one green, so the pair reads the same in both modes. */
val YouLightSemantic = CineSemanticColors(
    record = Color(0xFFC62828),
    onRecord = Color.White,
    success = ColorPair(container = Color(0xFFB8F397), onContainer = Color(0xFF042100)),
    ok = Color(0xFF386A20),
    pending = Color(0xFF7A5900),
    verified = Color(0xFF006874),
)

val YouDarkSemantic = CineSemanticColors(
    record = Color(0xFFE23A3A),
    onRecord = Color.White,
    success = ColorPair(container = Color(0xFF1F5108), onContainer = Color(0xFFB8F397)),
    ok = Color(0xFF9DD67F),
    pending = Color(0xFFFFCF66),
    verified = Color(0xFF4FD8EB),
)

/**
 * The dark scheme on a true-black floor. Containers are pulled towards black but keep their order,
 * so raised surfaces still read as raised.
 */
fun ColorScheme.toAmoled(): ColorScheme = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceDim = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = lerp(Color.Black, surfaceContainerLow, 0.45f),
    surfaceContainer = lerp(Color.Black, surfaceContainer, 0.6f),
    surfaceContainerHigh = lerp(Color.Black, surfaceContainerHigh, 0.75f),
    surfaceContainerHighest = lerp(Color.Black, surfaceContainerHighest, 0.85f),
    surfaceBright = lerp(Color.Black, surfaceBright, 0.85f),
    inverseOnSurface = Color.Black,
    scrim = Color.Black,
)

val LocalCineColors = staticCompositionLocalOf { CineSemantic }
val LocalAppTheme = staticCompositionLocalOf { AppTheme.CINE }

/** True when the system animation scales are 0: every motion must then be static or instant. */
val LocalReducedMotion = staticCompositionLocalOf { false }

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun schemeFor(theme: AppTheme, dark: Boolean): ColorScheme {
    val context = LocalContext.current
    val dynamic = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    return when {
        theme == AppTheme.CINE -> CineColorScheme
        dark -> (if (dynamic) dynamicDarkColorScheme(context) else darkColorScheme()).toAmoled()
        else -> if (dynamic) dynamicLightColorScheme(context) else expressiveLightColorScheme()
    }
}

private fun semanticFor(theme: AppTheme, dark: Boolean): CineSemanticColors = when {
    theme == AppTheme.CINE -> CineSemantic
    dark -> YouDarkSemantic
    else -> YouLightSemantic
}

/** Whether [theme] draws dark; CINE is always dark. */
@Composable
@ReadOnlyComposable
fun isDarkTheme(theme: AppTheme): Boolean = theme == AppTheme.CINE || isSystemInDarkTheme()

/**
 * The app theme. [forceDark] keeps the capture chrome dark over the viewfinder whatever the system
 * mode is; the rest of the app follows it.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun OpenCineCamTheme(theme: AppTheme, forceDark: Boolean = false, content: @Composable () -> Unit) {
    val dark = forceDark || isDarkTheme(theme)
    val scheme = schemeFor(theme, dark)
    val reducedMotion = rememberReducedMotion()
    CompositionLocalProvider(
        LocalAppTheme provides theme,
        LocalCineColors provides semanticFor(theme, dark),
        LocalReducedMotion provides reducedMotion,
    ) {
        MaterialExpressiveTheme(colorScheme = scheme, motionScheme = MotionScheme.expressive(), content = content)
    }
}

/** The capture screen: the chosen theme, always dark. */
@Composable
fun CaptureTheme(content: @Composable () -> Unit) = OpenCineCamTheme(LocalAppTheme.current, forceDark = true, content = content)

/**
 * Keeps the window background equal to the theme background, so a resize, a crossfade or a frame
 * Compose has not drawn yet shows the same colour instead of the XML window colour.
 */
@Composable
fun SyncWindowBackground() {
    val view = LocalView.current
    val color = MaterialTheme.colorScheme.background.toArgb()
    SideEffect {
        (view.context as? ComponentActivity)?.window?.setBackgroundDrawable(ColorDrawable(color))
    }
}

/** The theme as state, updated when Settings changes it. */
@Composable
fun rememberAppTheme(): State<AppTheme> {
    val context = LocalContext.current
    val store = remember(context) { AppThemeStore(context) }
    val state = remember(store) { mutableStateOf(store.load()) }
    DisposableEffect(store) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == AppThemeStore.KEY) state.value = store.load()
        }
        store.register(listener)
        onDispose { store.unregister(listener) }
    }
    return state
}

private val AnimationScaleSettings = listOf(
    Settings.Global.ANIMATOR_DURATION_SCALE,
    Settings.Global.TRANSITION_ANIMATION_SCALE,
    Settings.Global.WINDOW_ANIMATION_SCALE,
)

private fun animationScale(context: Context): Float = AnimationScaleSettings.minOf { name ->
    runCatching { Settings.Global.getFloat(context.contentResolver, name, 1f) }.getOrDefault(1f)
}.coerceAtLeast(0f)

/** Reads the system animation scales and follows them live, without recreating the activity. */
@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    val scale = remember(context) { mutableFloatStateOf(animationScale(context)) }
    DisposableEffect(context) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                scale.floatValue = animationScale(context)
            }
        }
        AnimationScaleSettings.forEach { name ->
            context.contentResolver.registerContentObserver(Settings.Global.getUriFor(name), false, observer)
        }
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }
    return scale.floatValue <= 0f
}

/** WCAG 2.x contrast ratio between two opaque colours. */
fun contrastRatio(a: Color, b: Color): Float {
    fun channel(c: Float): Double = if (c <= 0.03928f) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
    fun luminance(c: Color): Double = 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)
    val la = luminance(a)
    val lb = luminance(b)
    return ((maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)).toFloat()
}
