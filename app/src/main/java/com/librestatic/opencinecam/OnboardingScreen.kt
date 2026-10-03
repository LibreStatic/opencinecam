/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.toShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import com.librestatic.opencinecam.ui.theme.LocalCineColors
import com.librestatic.opencinecam.ui.theme.LocalReducedMotion
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.launch

// Theme roles under the names the wizard uses; the Cine palette gives the approved look.
private val OnbSurface: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.surfaceContainer
private val OnbSurfaceHigh: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.surfaceContainerHighest
private val OnbText: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onSurface
private val OnbAmber: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.primary
private val OnbMuted: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onSurfaceVariant
private val OnbDot: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.outlineVariant

/** MainActivity's splash fade: a one-frame delay plus 200 ms. */
private const val SplashHandoverMillis = 240L

/** The single-column wizard's text width on tablets: longer lines get hard to follow. */
private val PageMaxWidth = 560.dp

/** Bump when the tour gains content worth showing again to people who already finished it. */
internal const val ONBOARDING_VERSION = 1
private const val ONBOARDING_KEY = "onboarding-version"

class OnboardingStore internal constructor(private val preferences: SharedPreferences) {
    constructor(context: Context) : this(context.applicationContext.getSharedPreferences("camera-settings", Context.MODE_PRIVATE))

    fun isCompleted(): Boolean = preferences.getInt(ONBOARDING_KEY, 0) >= ONBOARDING_VERSION
    fun markCompleted() = preferences.edit().putInt(ONBOARDING_KEY, ONBOARDING_VERSION).apply()
    fun reset() = preferences.edit().remove(ONBOARDING_KEY).apply()
}

internal enum class OnboardingPage { WELCOME, FEATURES, TOUR, PERMISSIONS, OPEN_SOURCE, DONE }

/**
 * Where Skip (or the final button) leads. The camera is the only permission the app cannot run
 * without, so leaving is refused until it is granted and the user lands on the permissions page.
 */
internal fun onboardingExitTarget(cameraGranted: Boolean): OnboardingPage? =
    if (cameraGranted) null else OnboardingPage.PERMISSIONS

private enum class PermissionKind(
    @param:StringRes val title: Int,
    @param:StringRes val body: Int,
    @param:DrawableRes val icon: Int,
    val required: Boolean,
) {
    CAMERA(R.string.onb_perm_camera, R.string.onb_perm_camera_body, R.drawable.ic_onb_camera, true),
    MICROPHONE(R.string.onb_perm_mic, R.string.onb_perm_mic_body, R.drawable.ic_onb_mic, false),
    LOCATION(R.string.onb_perm_location, R.string.onb_perm_location_body, R.drawable.ic_onb_location, false),
    NOTIFICATIONS(R.string.onb_perm_notifications, R.string.onb_perm_notifications_body, R.drawable.ic_onb_notifications, false),
    ;

    val manifestPermissions: Array<String>
        get() = when (this) {
            CAMERA -> arrayOf(Manifest.permission.CAMERA)
            MICROPHONE -> arrayOf(Manifest.permission.RECORD_AUDIO)
            LOCATION -> arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
            NOTIFICATIONS -> if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray()
        }

    fun granted(context: Context): Boolean = manifestPermissions.any {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** How the wizard arranges a step. */
internal enum class OnboardingLayout {
    /** Header, the step and the buttons stacked in one column. */
    SINGLE_COLUMN,

    /** The words and buttons in a column beside the step's cards or picture. */
    TWO_PANE,
}

/** Below this the words beside the content would leave either side a sliver. */
private const val TWO_PANE_MIN_WIDTH_DP = 520f

/**
 * Two panes when stacking would squeeze the step between the header and the buttons (a phone in
 * landscape) or leave half the screen empty (a foldable's inner screen, a tablet in landscape, a
 * desktop window). A tall window keeps one column, however wide.
 */
internal fun onboardingLayout(window: AdaptiveWindow): OnboardingLayout {
    val short = window.landscape && window.heightClass == WindowHeightClass.COMPACT
    val broad = window.widthClass != WindowWidthClass.COMPACT && window.heightDp < window.widthDp * 1.15f
    return if (window.widthDp >= TWO_PANE_MIN_WIDTH_DP && (short || broad)) OnboardingLayout.TWO_PANE else OnboardingLayout.SINGLE_COLUMN
}

/** Cards go side by side once the pane holding them is no longer a compact width. */
internal fun onboardingCardColumns(paneWidthDp: Float): Int =
    if (windowWidthClass(paneWidthDp) == WindowWidthClass.COMPACT) 1 else 2

/** The two-pane layout never stretches wider than this on a desktop or a large tablet. */
private val TwoPaneMaxWidth = 1120.dp

@Composable
internal fun OnboardingScreen(
    splash: SplashHandoff,
    onPermissionsChanged: () -> Unit,
    onFinished: () -> Unit,
    hinge: FoldHinge? = null,
) {
    val context = LocalContext.current
    val reducedMotion = LocalReducedMotion.current
    val pages = OnboardingPage.entries
    val pagerState = rememberPagerState { pages.size }
    val scope = rememberCoroutineScope()
    var showLicenses by rememberSaveable { mutableStateOf(false) }
    var tourSpot by rememberSaveable { mutableStateOf(TourSpot.RAIL) }

    // Re-read grants on resume: the user may have flipped one in the system settings.
    var grantEpoch by remember { mutableStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                grantEpoch++
                onPermissionsChanged()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val kinds = PermissionKind.entries.filter { it.manifestPermissions.isNotEmpty() }
    val granted = remember(grantEpoch) { kinds.associateWith { it.granted(context) } }
    val cameraGranted = granted[PermissionKind.CAMERA] == true
    // A kind asked for and refused without a rationale is permanently denied: only Settings can grant it.
    var blocked by remember { mutableStateOf(emptySet<PermissionKind>()) }
    var pendingKinds by remember { mutableStateOf(emptyList<PermissionKind>()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val activity = context.findActivity()
        val refused = pendingKinds.filter { kind ->
            !kind.granted(context) && activity != null &&
                kind.manifestPermissions.none { activity.shouldShowRequestPermissionRationale(it) }
        }
        blocked = blocked + refused
        pendingKinds = emptyList()
        grantEpoch++
        onPermissionsChanged()
    }
    fun request(requested: List<PermissionKind>) {
        val needed = requested.filter { granted[it] != true }
        if (needed.isEmpty()) return
        if (needed.size == 1 && needed.single() in blocked) {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return
        }
        pendingKinds = needed.filterNot { it in blocked }
        launcher.launch(pendingKinds.flatMap { it.manifestPermissions.asList() }.toTypedArray())
    }

    // Leaving through Skip: the content fades and the shapes scatter before the camera takes over.
    var finishing by rememberSaveable { mutableStateOf(false) }
    val contentAlpha = remember { Animatable(1f) }
    LaunchedEffect(finishing, reducedMotion) {
        if (!finishing) return@LaunchedEffect
        if (reducedMotion) contentAlpha.snapTo(0f) else contentAlpha.animateTo(0f, tween(300))
    }

    val pageSpec = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    fun goTo(page: OnboardingPage) {
        if (finishing) return
        scope.launch {
            if (reducedMotion) pagerState.scrollToPage(page.ordinal) else pagerState.animateScrollToPage(page.ordinal, animationSpec = pageSpec)
        }
    }
    fun leave() {
        if (finishing) return
        val target = onboardingExitTarget(cameraGranted)
        when {
            target != null -> goTo(target)
            // The done page has already scattered the shapes: nothing is left to wait for.
            pagerState.targetPage == OnboardingPage.DONE.ordinal -> onFinished()
            else -> finishing = true
        }
    }

    // The logo intro: once, on the first page of a start from scratch, taking over from the splash.
    var introPlayed by rememberSaveable { mutableStateOf(!splash.playIntro) }
    val intro = remember { Animatable(if (introPlayed || reducedMotion) 1f else 0f) }
    val currentSplash by rememberUpdatedState(splash)
    LaunchedEffect(Unit) {
        if (intro.value < 1f) {
            // Wait for the splash to start leaving (it hands over the icon bounds), but never forever.
            val handedOver = withTimeoutOrNull(2_000) { snapshotFlow { currentSplash.onScreen }.first { !it } } != null
            // The splash fades over an identical still logo; the logo only moves once it is gone.
            if (handedOver) delay(SplashHandoverMillis)
            // Linear: each phase of the intro applies its own easing.
            intro.animateTo(1f, tween(1_900, easing = LinearEasing))
        }
        introPlayed = true
    }

    // The rail follows the page the pager is heading to, not its offset: a target that moves every
    // frame kept restarting the spring, so the shapes trailed the page and crept on after it settled.
    val railProgress = remember(pagerState) { { pagerState.targetPage.toFloat() } }
    val railBeat = remember(pagerState) { { pagerState.targetPage } }
    val introProgress = remember(intro) { { intro.value } }
    val currentFinished by rememberUpdatedState(onFinished)
    val finishingState = rememberUpdatedState(finishing)
    val dispersed = finishing || pagerState.targetPage == OnboardingPage.DONE.ordinal

    if (showLicenses) {
        BackHandler { showLicenses = false }
        // Opened from the tour, so the way back names the tour rather than Settings.
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            HingeSafeSettingsPane(hinge, tag = "onboarding-pane") {
                Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                    AboutScreen(onBack = { showLicenses = false }, backLabel = stringResource(R.string.onb_licenses_back))
                }
            }
        }
        return
    }
    BackHandler(enabled = pagerState.currentPage > 0 && !finishing) { goTo(pages[pagerState.currentPage - 1]) }

    val page = pages[pagerState.currentPage]
    val doneActive = pagerState.targetPage == OnboardingPage.DONE.ordinal
    val arrivalShift = with(LocalDensity.current) { 24.dp.toPx() }
    val header: @Composable (Modifier) -> Unit = { modifier ->
        Row(modifier, verticalAlignment = Alignment.CenterVertically) {
            SharedAxis(page, reducedMotion, Modifier.weight(1f)) { shown ->
                Text(
                    stringResource(R.string.onb_page_counter, shown.ordinal + 1, pages.size) +
                        pageLabel(shown)?.let { " · " + stringResource(it) }.orEmpty(),
                    color = OnbMuted,
                    fontSize = 13.sp,
                )
            }
            if (page != OnboardingPage.DONE) {
                FilledTonalButton(
                    onClick = ::leave,
                    colors = ButtonDefaults.filledTonalButtonColors(containerColor = OnbSurfaceHigh, contentColor = OnbText),
                    modifier = Modifier.heightIn(min = 48.dp).testTag("onboarding-skip"),
                ) { Text(stringResource(R.string.onb_skip), fontWeight = FontWeight.SemiBold) }
            } else {
                Spacer(Modifier.height(48.dp))
            }
        }
    }
    val footer: @Composable (Modifier) -> Unit = { modifier ->
        Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            PageDots(pages.size, pagerState.currentPage)
            SharedAxis(page, reducedMotion, Modifier.fillMaxWidth()) { shown ->
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    when (shown) {
                        OnboardingPage.PERMISSIONS -> {
                            val allGranted = kinds.all { granted[it] == true }
                            if (!allGranted) {
                                PrimaryButton(stringResource(R.string.onb_allow_all), arrow = false) { request(kinds) }
                            }
                            if (cameraGranted) {
                                if (allGranted) {
                                    PrimaryButton(stringResource(R.string.onb_continue)) { goTo(OnboardingPage.OPEN_SOURCE) }
                                } else {
                                    TextButton(onClick = { goTo(OnboardingPage.OPEN_SOURCE) }, modifier = Modifier.heightIn(min = 48.dp)) {
                                        Text(stringResource(R.string.onb_continue), color = OnbText, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            } else {
                                Text(stringResource(R.string.onb_camera_needed), color = OnbMuted, fontSize = 13.sp, modifier = Modifier.heightIn(min = 48.dp).padding(top = 14.dp))
                            }
                        }
                        OnboardingPage.DONE -> PrimaryButton(stringResource(R.string.onb_start_shooting), testTag = "onboarding-finish", onClick = ::leave)
                        OnboardingPage.WELCOME -> PrimaryButton(stringResource(R.string.onb_get_started)) { goTo(OnboardingPage.FEATURES) }
                        else -> PrimaryButton(stringResource(R.string.onb_next)) { goTo(pages[shown.ordinal + 1]) }
                    }
                }
            }
        }
    }
    // Neighbours are composed ahead, so a page never pays its first composition mid-slide.
    val pager: @Composable (Modifier, @Composable (OnboardingPage) -> Unit) -> Unit = { modifier, content ->
        HorizontalPager(state = pagerState, userScrollEnabled = !finishing, beyondViewportPageCount = 1, modifier = modifier) { content(pages[it]) }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).testTag("onboarding")) {
        OnboardingBackdrop(
            progress = railProgress,
            beat = railBeat,
            intro = introProgress,
            logoBounds = splash.iconBounds,
            dispersed = dispersed,
            onDispersed = { if (finishingState.value) currentFinished() },
            modifier = Modifier.fillMaxSize(),
        )
        HingeSafeSettingsPane(hinge, tag = "onboarding-pane") {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        // The content arrives once the logo has scattered.
                        val arrival = FastOutSlowInEasing.transform(((intro.value - 0.65f) / 0.35f).coerceIn(0f, 1f))
                        alpha = contentAlpha.value * arrival
                        translationY = (1f - arrival) * arrivalShift
                    }
                    .windowInsetsPadding(WindowInsets.safeDrawing),
                contentAlignment = Alignment.TopCenter,
            ) {
                val window = AdaptiveWindow(maxWidth.value, maxHeight.value)
                if (onboardingLayout(window) == OnboardingLayout.TWO_PANE) {
                    // The words and the buttons stay put beside the step, so Allow and Next are
                    // always on screen however short the window is.
                    Row(
                        Modifier.widthIn(max = TwoPaneMaxWidth).fillMaxSize().padding(horizontal = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(32.dp),
                    ) {
                        Column(Modifier.weight(0.42f).fillMaxHeight()) {
                            header(Modifier.fillMaxWidth().padding(top = 8.dp))
                            SharedAxis(page, reducedMotion, Modifier.weight(1f).fillMaxWidth()) { shown ->
                                FadingScroll(Modifier.fillMaxSize(), Arrangement.Center) { PageHeading(shown, tourSpot) }
                            }
                            footer(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 16.dp))
                        }
                        pager(Modifier.weight(0.58f).fillMaxHeight()) { shown ->
                            BoxWithConstraints(Modifier.fillMaxSize().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                                val columns = onboardingCardColumns(maxWidth.value)
                                when (shown) {
                                    OnboardingPage.WELCOME -> Unit
                                    OnboardingPage.FEATURES -> FadingScroll(Modifier.fillMaxSize(), Arrangement.Center) { FeatureCards(columns) }
                                    OnboardingPage.TOUR -> MiniViewfinder(tourSpot, { tourSpot = it }, portrait = false, Modifier.aspectRatio(4f / 3f))
                                    OnboardingPage.PERMISSIONS -> FadingScroll(Modifier.fillMaxSize(), Arrangement.Center) {
                                        PermissionCards(columns, kinds, granted, blocked, ::request)
                                    }
                                    OnboardingPage.OPEN_SOURCE -> FadingScroll(Modifier.fillMaxSize(), Arrangement.Center) {
                                        OpenSourceCards(columns) { showLicenses = true }
                                    }
                                    OnboardingPage.DONE -> DoneShape(doneActive, Modifier.fillMaxSize())
                                }
                            }
                        }
                    }
                } else {
                    // Header, step and buttons share one width, so their edges line up on a tablet.
                    val column = Modifier.widthIn(max = PageMaxWidth + 48.dp).fillMaxWidth().padding(horizontal = 24.dp)
                    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                        header(column.padding(top = 8.dp, bottom = 12.dp))
                        pager(Modifier.weight(1f).fillMaxWidth()) { shown ->
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                                FadingScroll(column.fillMaxHeight().padding(top = 4.dp), if (shown == OnboardingPage.WELCOME) Arrangement.Center else Arrangement.Top) {
                                    when (shown) {
                                        OnboardingPage.WELCOME -> WelcomeText()
                                        OnboardingPage.FEATURES -> { PageTitle(R.string.onb_features_title); FeatureCards(1) }
                                        OnboardingPage.TOUR -> {
                                            PageTitle(R.string.onb_tour_title, R.string.onb_tour_body)
                                            MiniViewfinder(tourSpot, { tourSpot = it }, portrait = !window.landscape, Modifier.fillMaxWidth().aspectRatio(4f / 3f))
                                            Spacer(Modifier.height(6.dp))
                                            TourSpotCard(tourSpot)
                                        }
                                        OnboardingPage.PERMISSIONS -> {
                                            PageTitle(R.string.onb_permissions_title, R.string.onb_permissions_body)
                                            PermissionCards(1, kinds, granted, blocked, ::request)
                                        }
                                        OnboardingPage.OPEN_SOURCE -> {
                                            PageTitle(R.string.onb_open_source_title, R.string.onb_open_source_body)
                                            OpenSourceCards(1) { showLicenses = true }
                                        }
                                        OnboardingPage.DONE -> {
                                            DoneShape(doneActive, Modifier.fillMaxWidth().aspectRatio(1.25f))
                                            HeroText(R.string.onb_ready_title, R.string.onb_ready_body)
                                        }
                                    }
                                }
                            }
                        }
                        footer(column.padding(top = 12.dp, bottom = 16.dp))
                    }
                }
            }
        }
    }
}

/** The words of a step, shown beside its content in the two-pane layout. */
@Composable
private fun PageHeading(page: OnboardingPage, spot: TourSpot) {
    when (page) {
        OnboardingPage.WELCOME -> WelcomeText()
        OnboardingPage.FEATURES -> PageTitle(R.string.onb_features_title)
        OnboardingPage.TOUR -> {
            PageTitle(R.string.onb_tour_title, R.string.onb_tour_body)
            TourSpotCard(spot)
        }
        OnboardingPage.PERMISSIONS -> PageTitle(R.string.onb_permissions_title, R.string.onb_permissions_body)
        OnboardingPage.OPEN_SOURCE -> PageTitle(R.string.onb_open_source_title, R.string.onb_open_source_body)
        OnboardingPage.DONE -> HeroText(R.string.onb_ready_title, R.string.onb_ready_body)
    }
}

/** Fades over the last stretch of an edge where more content waits beyond it. */
private val FadeEdge = 28.dp

/**
 * A scrolling column for a step. An edge fades while there is more past it, and a "More below"
 * cue shows until the operator scrolls, so a card under the fold is never a secret.
 */
@Composable
private fun FadingScroll(
    modifier: Modifier,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: @Composable ColumnScope.() -> Unit,
) {
    val state = rememberScrollState()
    val scope = rememberCoroutineScope()
    val cueThreshold = with(LocalDensity.current) { 48.dp.toPx() }
    // maxValue reads Int.MAX_VALUE until the first layout, which would flash the cue on every page.
    val cue by remember(state, cueThreshold) {
        derivedStateOf { state.value == 0 && state.maxValue != Int.MAX_VALUE && state.maxValue > cueThreshold }
    }
    Box(modifier) {
        Column(
            Modifier
                .fillMaxSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    val edge = FadeEdge.toPx().coerceAtMost(size.height / 2f)
                    if (state.canScrollBackward) {
                        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black), 0f, edge), size = Size(size.width, edge), blendMode = BlendMode.DstIn)
                    }
                    if (state.canScrollForward) {
                        val top = size.height - edge
                        drawRect(Brush.verticalGradient(listOf(Color.Black, Color.Transparent), top, size.height), Offset(0f, top), Size(size.width, edge), blendMode = BlendMode.DstIn)
                    }
                }
                .verticalScroll(state),
            verticalArrangement = verticalArrangement,
            content = content,
        )
        AnimatedVisibility(cue, Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp), enter = fadeIn(), exit = fadeOut()) {
            MoreBelow { scope.launch { state.animateScrollTo(state.maxValue) } }
        }
    }
}

@Composable
private fun MoreBelow(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = OnbSurfaceHigh,
        contentColor = OnbText,
        modifier = Modifier.testTag("onboarding-more-below"),
    ) {
        Row(
            Modifier.heightIn(min = 40.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(stringResource(R.string.onb_more_below), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            CineGlyph(CineIcon.EXPAND, OnbText, Modifier.size(16.dp))
        }
    }
}

/**
 * Shared-axis swap of the bar contents between steps: the new one slides in from the side the
 * wizard moves towards while the old one fades quickly. Reduced motion only crossfades.
 */
@Composable
private fun SharedAxis(page: OnboardingPage, reducedMotion: Boolean, modifier: Modifier, content: @Composable (OnboardingPage) -> Unit) {
    val slide = spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow, visibilityThreshold = IntOffset.VisibilityThreshold)
    AnimatedContent(
        targetState = page,
        modifier = modifier,
        transitionSpec = {
            if (reducedMotion) {
                fadeIn(snap()) togetherWith fadeOut(snap())
            } else {
                val sign = if (targetState.ordinal > initialState.ordinal) 1 else -1
                (slideInHorizontally(slide) { sign * it / 4 } + fadeIn(tween(220, delayMillis = 90, easing = LinearOutSlowInEasing))) togetherWith
                    (slideOutHorizontally(slide) { -sign * it / 4 } + fadeOut(tween(90, easing = FastOutLinearInEasing))) using
                    SizeTransform(clip = false)
            }
        },
        contentAlignment = Alignment.Center,
        label = "onboarding-axis",
    ) { content(it) }
}

@StringRes
private fun pageLabel(page: OnboardingPage): Int? = when (page) {
    OnboardingPage.FEATURES -> R.string.onb_features_label
    OnboardingPage.TOUR -> R.string.onb_tour_label
    OnboardingPage.PERMISSIONS -> R.string.onb_permissions_label
    OnboardingPage.OPEN_SOURCE -> R.string.onb_open_source_label
    else -> null
}

@Composable
private fun PrimaryButton(text: String, arrow: Boolean = true, testTag: String? = null, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary),
        shape = RoundedCornerShape(28.dp),
        modifier = Modifier.fillMaxWidth().height(56.dp).let { if (testTag != null) it.testTag(testTag) else it },
    ) {
        Text(text, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        if (arrow) {
            Spacer(Modifier.width(8.dp))
            Icon(painterResource(R.drawable.ic_onb_arrow), contentDescription = null, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun PageDots(count: Int, current: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(count) { index ->
            val width by animateDpAsState(if (index == current) 24.dp else 8.dp, label = "dot")
            val color by animateColorAsState(if (index == current) OnbAmber else OnbDot, label = "dot-color")
            Box(Modifier.size(width = width, height = 8.dp).clip(CircleShape).background(color))
        }
    }
}

@Composable
private fun HeroText(@StringRes title: Int, @StringRes body: Int) {
    Text(stringResource(title), color = OnbText, fontSize = 34.sp, fontWeight = FontWeight.Bold, lineHeight = 38.sp, modifier = Modifier.padding(top = 28.dp, bottom = 10.dp))
    Text(stringResource(body), color = OnbMuted, fontSize = 15.sp, lineHeight = 22.sp)
}

@Composable
private fun PageTitle(@StringRes title: Int, @StringRes body: Int? = null) {
    Text(stringResource(title), color = OnbText, fontSize = 26.sp, fontWeight = FontWeight.Bold, lineHeight = 31.sp, modifier = Modifier.padding(top = 8.dp, bottom = 6.dp))
    if (body != null) Text(stringResource(body), color = OnbMuted, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.padding(bottom = 10.dp))
}

/** Lays cards out in [columns], each row as tall as its tallest card so the edges line up. */
@Composable
private fun <T> CardGrid(items: List<T>, columns: Int, card: @Composable (T, Modifier) -> Unit) {
    if (columns <= 1) {
        items.forEach { card(it, Modifier) }
        return
    }
    items.chunked(columns).forEach { row ->
        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            row.forEach { Box(Modifier.weight(1f).fillMaxHeight()) { card(it, Modifier.fillMaxHeight()) } }
            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun InfoCard(
    @DrawableRes icon: Int,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    iconTint: Color = OnbAmber,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(OnbSurface)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomEnd = 16.dp, bottomStart = 6.dp)).background(OnbSurfaceHigh),
            contentAlignment = Alignment.Center,
        ) { Icon(painterResource(icon), contentDescription = null, tint = iconTint, modifier = Modifier.size(22.dp)) }
        Column(Modifier.weight(1f)) {
            Text(title, color = OnbText, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Text(body, color = OnbMuted, fontSize = 13.sp, lineHeight = 18.sp)
        }
        trailing?.invoke()
    }
}

private val Features = listOf(
    Triple(R.drawable.ic_onb_tune, R.string.onb_feature_manual, R.string.onb_feature_manual_body),
    Triple(R.drawable.ic_onb_movie, R.string.onb_feature_log, R.string.onb_feature_log_body),
    Triple(R.drawable.ic_onb_scopes, R.string.onb_feature_scopes, R.string.onb_feature_scopes_body),
    Triple(R.drawable.ic_onb_mic, R.string.onb_feature_audio, R.string.onb_feature_audio_body),
    Triple(R.drawable.ic_onb_timer, R.string.onb_feature_timecode, R.string.onb_feature_timecode_body),
)

@Composable
private fun FeatureCards(columns: Int) {
    CardGrid(Features, columns) { (icon, title, body), modifier -> InfoCard(icon, stringResource(title), stringResource(body), modifier) }
}

/** The tour's hotspots; [RAIL] is the camera settings, kept under its old name for the test tag. */
private enum class TourSpot(@param:StringRes val title: Int, @param:StringRes val body: Int) {
    STATUS(R.string.onb_tour_status, R.string.onb_tour_status_body),
    RAIL(R.string.onb_tour_rail, R.string.onb_tour_rail_body),
    RECORD(R.string.onb_tour_record, R.string.onb_tour_record_body),
}

@Composable
private fun TourSpotCard(spot: TourSpot) {
    InfoCard(R.drawable.ic_onb_touch, stringResource(spot.title), stringResource(spot.body))
}

/**
 * A drawn stand-in for the capture screen. Like the real one, a [portrait] window puts the camera
 * settings in a strip along the bottom and a landscape window in a column at the end, next to REC.
 */
@Composable
private fun MiniViewfinder(selected: TourSpot, onSelect: (TourSpot) -> Unit, portrait: Boolean, modifier: Modifier) {
    val settings = listOf("180°", "ISO 400", "5600K", "AF")
    val record: @Composable (Modifier) -> Unit = { m ->
        TourHotspot(TourSpot.RECORD, selected, onSelect, m, shape = CircleShape) {
            Box(Modifier.padding(5.dp).size(38.dp).clip(CircleShape).background(LocalCineColors.current.record))
        }
    }
    Box(modifier.clip(RoundedCornerShape(24.dp)).border(1.dp, OnbSurfaceHigh, RoundedCornerShape(24.dp))) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(Brush.verticalGradient(listOf(Color(0xFF1C2A3A), Color(0xFF6B3E1A), Color(0xFF1A1410))))
            drawCircle(Color(0xFFFFC46B).copy(alpha = 0.55f), radius = size.minDimension * 0.12f, center = Offset(size.width * 0.38f, size.height * 0.52f))
            val ridge = Path().apply {
                moveTo(0f, size.height * 0.72f)
                lineTo(size.width * 0.22f, size.height * 0.5f)
                lineTo(size.width * 0.4f, size.height * 0.66f)
                lineTo(size.width * 0.6f, size.height * 0.44f)
                lineTo(size.width, size.height * 0.7f)
                lineTo(size.width, size.height)
                lineTo(0f, size.height)
                close()
            }
            drawPath(ridge, Color(0xFF0E1216))
            // Thirds grid, as the real viewfinder shows it.
            val grid = Color.White.copy(alpha = 0.12f)
            for (i in 1..2) {
                drawLine(grid, Offset(size.width * i / 3f, 0f), Offset(size.width * i / 3f, size.height), 1f)
                drawLine(grid, Offset(0f, size.height * i / 3f), Offset(size.width, size.height * i / 3f), 1f)
            }
        }
        TourHotspot(TourSpot.STATUS, selected, onSelect, Modifier.align(Alignment.TopStart).padding(10.dp)) {
            Text("00:12:45:08 · 4K 24 · 256 GB", color = Color.White, fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp))
        }
        if (portrait) {
            Row(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TourHotspot(TourSpot.RAIL, selected, onSelect, Modifier.weight(1f)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                        settings.forEach { Text(it, color = Color.White, fontSize = 10.sp, fontFamily = FontFamily.Monospace, maxLines = 1) }
                    }
                }
                record(Modifier)
            }
        } else {
            Row(
                Modifier.align(Alignment.CenterEnd).padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TourHotspot(TourSpot.RAIL, selected, onSelect, Modifier) {
                    Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        settings.forEach { Text(it, color = Color.White, fontSize = 10.sp, fontFamily = FontFamily.Monospace, maxLines = 1) }
                    }
                }
                record(Modifier)
            }
        }
    }
}

@Composable
private fun TourHotspot(
    spot: TourSpot,
    selected: TourSpot,
    onSelect: (TourSpot) -> Unit,
    modifier: Modifier,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(12.dp),
    content: @Composable () -> Unit,
) {
    val active = spot == selected
    val border by animateColorAsState(if (active) OnbAmber else Color.White.copy(alpha = 0.35f), label = "hotspot")
    Box(
        modifier
            .clip(shape)
            .background(Color.Black.copy(alpha = 0.45f))
            .border(if (active) 2.dp else 1.dp, border, shape)
            .clickable { onSelect(spot) }
            .testTag("tour-${spot.name.lowercase()}"),
    ) { content() }
}

@Composable
private fun PermissionCards(
    columns: Int,
    kinds: List<PermissionKind>,
    granted: Map<PermissionKind, Boolean>,
    blocked: Set<PermissionKind>,
    onRequest: (List<PermissionKind>) -> Unit,
) {
    CardGrid(kinds, columns) { kind, modifier -> PermissionCard(kind, granted[kind] == true, kind in blocked, modifier) { onRequest(listOf(kind)) } }
}

@Composable
private fun PermissionCard(kind: PermissionKind, granted: Boolean, blocked: Boolean, modifier: Modifier, onAllow: () -> Unit) {
    val success = LocalCineColors.current.success
    // SpaceBetween keeps Allow on the bottom edge when a neighbouring card makes the row taller.
    Column(
        modifier.fillMaxWidth().padding(vertical = 5.dp).clip(RoundedCornerShape(24.dp)).background(OnbSurface).padding(14.dp).testTag("perm-${kind.name.lowercase()}"),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(Modifier.padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomEnd = 16.dp, bottomStart = 6.dp))
                    .background(if (granted) success.container else OnbSurfaceHigh),
                contentAlignment = Alignment.Center,
            ) { Icon(painterResource(kind.icon), contentDescription = null, tint = if (granted) success.onContainer else OnbAmber, modifier = Modifier.size(22.dp)) }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(kind.title), color = OnbText, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    Text(
                        stringResource(if (kind.required) R.string.onb_required else R.string.onb_optional),
                        color = if (kind.required) MaterialTheme.colorScheme.onPrimaryContainer else OnbMuted,
                        fontSize = 11.sp,
                        modifier = Modifier.clip(CircleShape).background(if (kind.required) MaterialTheme.colorScheme.primaryContainer else OnbSurfaceHigh).padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
                Text(stringResource(kind.body), color = OnbMuted, fontSize = 13.sp, lineHeight = 18.sp)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            if (granted) {
                Row(
                    Modifier.heightIn(min = 40.dp).clip(CircleShape).background(success.container).padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(painterResource(R.drawable.ic_onb_check), contentDescription = null, tint = success.onContainer, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.onb_allowed), color = success.onContainer, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                }
            } else {
                FilledTonalButton(
                    onClick = onAllow,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = if (kind.required) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = if (kind.required) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer,
                    ),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(stringResource(if (blocked) R.string.onb_open_settings else R.string.onb_allow), fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}

@Composable
private fun OpenSourceCards(columns: Int, onViewLicenses: () -> Unit) {
    val context = LocalContext.current
    val componentCount = remember(context) { runCatching { loadThirdPartyComponents(context).size }.getOrDefault(0) }
    val license: @Composable (Modifier) -> Unit = { modifier ->
        Column(
            modifier.fillMaxWidth().padding(vertical = 5.dp)
                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp, bottomEnd = 28.dp, bottomStart = 8.dp))
                .background(OnbSurface).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(painterResource(R.drawable.ic_onb_license), contentDescription = null, tint = OnbAmber, modifier = Modifier.size(22.dp))
                Text(stringResource(R.string.onb_license_title), color = OnbText, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            }
            Text(stringResource(R.string.onb_license_body), color = OnbMuted, fontSize = 13.sp, lineHeight = 18.sp)
            if (componentCount > 0) Text(stringResource(R.string.onb_components, componentCount), color = OnbMuted, fontSize = 13.sp, lineHeight = 18.sp)
        }
    }
    val others: @Composable () -> Unit = {
        InfoCard(R.drawable.ic_onb_group, stringResource(R.string.onb_authors), stringResource(R.string.about_developed_by))
        InfoCard(R.drawable.ic_onb_license, stringResource(R.string.onb_view_licenses), stringResource(R.string.onb_view_licenses_body), onClick = onViewLicenses) {
            Icon(painterResource(R.drawable.ic_onb_arrow), contentDescription = null, tint = OnbMuted, modifier = Modifier.size(20.dp))
        }
    }
    if (columns > 1) {
        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            license(Modifier.weight(1f).fillMaxHeight())
            Column(Modifier.weight(1f)) { others() }
        }
    } else {
        license(Modifier)
        others()
    }
}

/** The first page: the logo intro has just scattered over the backdrop, so only the words come in. */
@Composable
private fun WelcomeText() {
    Text(stringResource(R.string.onb_welcome_title), color = OnbText, fontSize = 34.sp, fontWeight = FontWeight.Bold, lineHeight = 38.sp, modifier = Modifier.padding(bottom = 10.dp))
    Text(stringResource(R.string.onb_welcome_body), color = OnbMuted, fontSize = 15.sp, lineHeight = 22.sp)
}

/**
 * The closing mark: one expressive shape in the success colours. It spins in when the page becomes
 * the target, the check pops in with a bounce, then it keeps a slow turn and a gentle breath.
 * Reduced motion shows it still.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DoneShape(active: Boolean, modifier: Modifier) {
    val reducedMotion = LocalReducedMotion.current
    val success = LocalCineColors.current.success
    val entrance = remember { Animatable(if (reducedMotion) 1f else 0f) }
    val check = remember { Animatable(if (reducedMotion) 1f else 0f) }
    LaunchedEffect(active, reducedMotion) {
        if (reducedMotion) {
            entrance.snapTo(1f); check.snapTo(1f)
            return@LaunchedEffect
        }
        if (!active) return@LaunchedEffect
        launch { entrance.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessLow)) }
        delay(280)
        check.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium))
    }
    val loop = rememberInfiniteTransition(label = "done")
    val turn by loop.animateFloat(0f, if (reducedMotion) 0f else 360f, infiniteRepeatable(tween(24_000, easing = LinearEasing)), label = "turn")
    val breath by loop.animateFloat(1f, if (reducedMotion) 1f else 1.05f, infiniteRepeatable(tween(1_800, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breath")
    val shape = MaterialShapes.Cookie12Sided.toShape()
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val side = minOf(220.dp, maxWidth * 0.8f, maxHeight * 0.8f)
        Box(
            Modifier.size(side).graphicsLayer {
                val e = entrance.value
                scaleX = e * breath; scaleY = e * breath
                rotationZ = (1f - e) * -150f + turn
            }.background(success.container, shape),
        )
        Icon(
            painterResource(R.drawable.ic_onb_check),
            contentDescription = null,
            tint = success.onContainer,
            modifier = Modifier.size(side * 0.47f).graphicsLayer {
                val c = check.value
                scaleX = c; scaleY = c
                alpha = c.coerceIn(0f, 1f)
            },
        )
    }
}
