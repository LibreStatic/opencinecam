/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

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

private val OnbCanvas = Color(0xFF090C0E)
private val OnbSurface = Color(0xFF12171A)
private val OnbSurfaceHigh = Color(0xFF1B2226)
private val OnbAmber = Color(0xFFFFB000)
private val OnbAmberDim = Color(0xFF3A2E12)
private val OnbGreen = Color(0xFF4BD28A)
private val OnbGreenDim = Color(0xFF10301F)
private val OnbRed = Color(0xFFF04444)
private val OnbMuted = Color(0xFF9CA6AA)
private val OnbDot = Color(0xFF394247)

/** Bump when the tour gains content worth showing again to people who already finished it. */
internal const val ONBOARDING_VERSION = 1
private const val ONBOARDING_KEY = "onboarding-version"

class OnboardingStore internal constructor(private val preferences: SharedPreferences) {
    constructor(context: Context) : this(context.applicationContext.getSharedPreferences("camera-settings", Context.MODE_PRIVATE))

    fun isCompleted(): Boolean = preferences.getInt(ONBOARDING_KEY, 0) >= ONBOARDING_VERSION
    fun markCompleted() = preferences.edit().putInt(ONBOARDING_KEY, ONBOARDING_VERSION).apply()
    fun reset() = preferences.edit().remove(ONBOARDING_KEY).apply()
}

internal enum class OnboardingPage { WELCOME, FEATURES, TOUR, PERMISSIONS, OPEN_SOURCE, READY }

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

@Composable
internal fun OnboardingScreen(
    onPermissionsChanged: () -> Unit,
    onFinished: () -> Unit,
) {
    val context = LocalContext.current
    val pages = OnboardingPage.entries
    val pagerState = rememberPagerState { pages.size }
    val scope = rememberCoroutineScope()
    var showLicenses by rememberSaveable { mutableStateOf(false) }

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

    fun goTo(page: OnboardingPage) = scope.launch { pagerState.animateScrollToPage(page.ordinal) }
    fun leave() {
        val target = onboardingExitTarget(cameraGranted)
        if (target == null) onFinished() else goTo(target)
    }

    if (showLicenses) {
        BackHandler { showLicenses = false }
        AboutScreen(onBack = { showLicenses = false })
        return
    }
    BackHandler(enabled = pagerState.currentPage > 0) { goTo(pages[pagerState.currentPage - 1]) }

    val page = pages[pagerState.currentPage]
    Column(
        modifier = Modifier.fillMaxSize().background(OnbCanvas).windowInsetsPadding(WindowInsets.safeDrawing).testTag("onboarding"),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.onb_page_counter, pagerState.currentPage + 1, pages.size) +
                    pageLabel(page)?.let { " · " + stringResource(it) }.orEmpty(),
                color = OnbMuted,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f),
            )
            if (page != OnboardingPage.READY) {
                FilledTonalButton(
                    onClick = ::leave,
                    colors = ButtonDefaults.filledTonalButtonColors(containerColor = OnbSurfaceHigh, contentColor = Color.White),
                    modifier = Modifier.heightIn(min = 48.dp).testTag("onboarding-skip"),
                ) { Text(stringResource(R.string.onb_skip), fontWeight = FontWeight.SemiBold) }
            } else {
                Spacer(Modifier.height(48.dp))
            }
        }
        HorizontalPager(state = pagerState, modifier = Modifier.weight(1f).fillMaxWidth()) { index ->
            BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
                val wide = maxWidth > maxHeight && maxHeight < 520.dp
                when (pages[index]) {
                    OnboardingPage.WELCOME -> HeroPage(wide, { WelcomeHero(it) }, R.string.onb_welcome_title, R.string.onb_welcome_body, large = true)
                    OnboardingPage.FEATURES -> FeaturesPage(wide)
                    OnboardingPage.TOUR -> TourPage(wide)
                    OnboardingPage.PERMISSIONS -> PermissionsPage(wide, kinds, granted, blocked, ::request)
                    OnboardingPage.OPEN_SOURCE -> OpenSourcePage(wide) { showLicenses = true }
                    OnboardingPage.READY -> HeroPage(wide, { ReadyHero(it) }, R.string.onb_ready_title, R.string.onb_ready_body, large = true)
                }
            }
        }
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PageDots(pages.size, pagerState.currentPage)
            when (page) {
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
                                Text(stringResource(R.string.onb_continue), color = Color.White, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    } else {
                        Text(stringResource(R.string.onb_camera_needed), color = OnbMuted, fontSize = 13.sp, modifier = Modifier.heightIn(min = 48.dp).padding(top = 14.dp))
                    }
                }
                OnboardingPage.READY -> PrimaryButton(stringResource(R.string.onb_start_shooting), testTag = "onboarding-finish", onClick = ::leave)
                OnboardingPage.WELCOME -> PrimaryButton(stringResource(R.string.onb_get_started)) { goTo(OnboardingPage.FEATURES) }
                else -> PrimaryButton(stringResource(R.string.onb_next)) { goTo(pages[page.ordinal + 1]) }
            }
        }
    }
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
        colors = ButtonDefaults.buttonColors(containerColor = OnbAmber, contentColor = Color.Black),
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

/** Portrait stacks the hero above the text; short landscape windows put them side by side. */
@Composable
private fun SplitPage(wide: Boolean, hero: (@Composable (Modifier) -> Unit)?, content: @Composable () -> Unit) {
    if (wide && hero != null) {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
            hero(Modifier.weight(1f).fillMaxHeight().padding(vertical = 8.dp))
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.Center) { content() }
        }
    } else {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            hero?.invoke(Modifier.fillMaxWidth().aspectRatio(1.25f))
            content()
        }
    }
}

@Composable
private fun HeroPage(wide: Boolean, hero: @Composable (Modifier) -> Unit, @StringRes title: Int, @StringRes body: Int, large: Boolean = false) {
    SplitPage(wide, hero) {
        Text(stringResource(title), color = Color.White, fontSize = if (large) 34.sp else 26.sp, fontWeight = FontWeight.Bold, lineHeight = 38.sp, modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
        Text(stringResource(body), color = OnbMuted, fontSize = 15.sp, lineHeight = 22.sp)
    }
}

@Composable
private fun PageTitle(@StringRes title: Int, @StringRes body: Int? = null) {
    Text(stringResource(title), color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold, lineHeight = 31.sp, modifier = Modifier.padding(top = 8.dp, bottom = 6.dp))
    if (body != null) Text(stringResource(body), color = OnbMuted, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.padding(bottom = 10.dp))
}

@Composable
private fun InfoCard(
    @DrawableRes icon: Int,
    title: String,
    body: String,
    iconTint: Color = OnbAmber,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
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
            Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Text(body, color = OnbMuted, fontSize = 13.sp, lineHeight = 18.sp)
        }
        trailing?.invoke()
    }
}

@Composable
private fun FeaturesPage(wide: Boolean) {
    val features = listOf(
        Triple(R.drawable.ic_onb_tune, R.string.onb_feature_manual, R.string.onb_feature_manual_body),
        Triple(R.drawable.ic_onb_movie, R.string.onb_feature_log, R.string.onb_feature_log_body),
        Triple(R.drawable.ic_onb_scopes, R.string.onb_feature_scopes, R.string.onb_feature_scopes_body),
        Triple(R.drawable.ic_onb_mic, R.string.onb_feature_audio, R.string.onb_feature_audio_body),
        Triple(R.drawable.ic_onb_timer, R.string.onb_feature_timecode, R.string.onb_feature_timecode_body),
    )
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        PageTitle(R.string.onb_features_title)
        if (wide) {
            features.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { (icon, title, body) ->
                        Box(Modifier.weight(1f)) { InfoCard(icon, stringResource(title), stringResource(body)) }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        } else {
            features.forEach { (icon, title, body) -> InfoCard(icon, stringResource(title), stringResource(body)) }
        }
    }
}

private enum class TourSpot(@param:StringRes val title: Int, @param:StringRes val body: Int) {
    STATUS(R.string.onb_tour_status, R.string.onb_tour_status_body),
    RAIL(R.string.onb_tour_rail, R.string.onb_tour_rail_body),
    RECORD(R.string.onb_tour_record, R.string.onb_tour_record_body),
}

@Composable
private fun TourPage(wide: Boolean) {
    var spot by rememberSaveable { mutableStateOf(TourSpot.RAIL) }
    SplitPage(wide, hero = if (wide) { m -> MiniViewfinder(spot, { spot = it }, m) } else null) {
        PageTitle(R.string.onb_tour_title, R.string.onb_tour_body)
        if (!wide) MiniViewfinder(spot, { spot = it }, Modifier.fillMaxWidth().aspectRatio(4f / 3f))
        Spacer(Modifier.height(6.dp))
        InfoCard(R.drawable.ic_onb_touch, stringResource(spot.title), stringResource(spot.body))
    }
}

@Composable
private fun MiniViewfinder(selected: TourSpot, onSelect: (TourSpot) -> Unit, modifier: Modifier) {
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
        TourHotspot(TourSpot.RAIL, selected, onSelect, Modifier.align(Alignment.CenterEnd).padding(10.dp)) {
            Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                listOf("ISO", "180°", "5600K", "AF").forEach { Text(it, color = Color.White, fontSize = 10.sp, fontFamily = FontFamily.Monospace) }
            }
        }
        TourHotspot(TourSpot.RECORD, selected, onSelect, Modifier.align(Alignment.BottomCenter).padding(10.dp), shape = CircleShape) {
            Box(Modifier.padding(5.dp).size(38.dp).clip(CircleShape).background(OnbRed))
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
private fun PermissionsPage(
    wide: Boolean,
    kinds: List<PermissionKind>,
    granted: Map<PermissionKind, Boolean>,
    blocked: Set<PermissionKind>,
    onRequest: (List<PermissionKind>) -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        PageTitle(R.string.onb_permissions_title, R.string.onb_permissions_body)
        val cards: @Composable (PermissionKind) -> Unit = { kind -> PermissionCard(kind, granted[kind] == true, kind in blocked) { onRequest(listOf(kind)) } }
        if (wide) {
            kinds.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { Box(Modifier.weight(1f)) { cards(it) } }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        } else {
            kinds.forEach { cards(it) }
        }
    }
}

@Composable
private fun PermissionCard(kind: PermissionKind, granted: Boolean, blocked: Boolean, onAllow: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 5.dp).clip(RoundedCornerShape(24.dp)).background(OnbSurface).padding(14.dp).testTag("perm-${kind.name.lowercase()}"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomEnd = 16.dp, bottomStart = 6.dp))
                    .background(if (granted) OnbGreenDim else OnbSurfaceHigh),
                contentAlignment = Alignment.Center,
            ) { Icon(painterResource(kind.icon), contentDescription = null, tint = if (granted) OnbGreen else OnbAmber, modifier = Modifier.size(22.dp)) }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(kind.title), color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    Text(
                        stringResource(if (kind.required) R.string.onb_required else R.string.onb_optional),
                        color = if (kind.required) OnbAmber else OnbMuted,
                        fontSize = 11.sp,
                        modifier = Modifier.clip(CircleShape).background(if (kind.required) OnbAmberDim else OnbSurfaceHigh).padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
                Text(stringResource(kind.body), color = OnbMuted, fontSize = 13.sp, lineHeight = 18.sp)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            if (granted) {
                Row(
                    Modifier.heightIn(min = 40.dp).clip(CircleShape).background(OnbGreenDim).padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(painterResource(R.drawable.ic_onb_check), contentDescription = null, tint = OnbGreen, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.onb_allowed), color = OnbGreen, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                }
            } else {
                FilledTonalButton(
                    onClick = onAllow,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = if (kind.required) OnbAmber else OnbSurfaceHigh,
                        contentColor = if (kind.required) Color.Black else OnbAmber,
                    ),
                    modifier = Modifier.heightIn(min = 40.dp),
                ) { Text(stringResource(if (blocked) R.string.onb_open_settings else R.string.onb_allow), fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}

@Composable
private fun OpenSourcePage(wide: Boolean, onViewLicenses: () -> Unit) {
    val context = LocalContext.current
    val componentCount = remember(context) { runCatching { loadThirdPartyComponents(context).size }.getOrDefault(0) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        PageTitle(R.string.onb_open_source_title, R.string.onb_open_source_body)
        val license: @Composable () -> Unit = {
            Column(
                Modifier.fillMaxWidth().padding(vertical = 5.dp)
                    .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp, bottomEnd = 28.dp, bottomStart = 8.dp))
                    .background(OnbSurface).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(painterResource(R.drawable.ic_onb_license), contentDescription = null, tint = OnbAmber, modifier = Modifier.size(22.dp))
                    Text(stringResource(R.string.onb_license_title), color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                }
                Text(stringResource(R.string.onb_license_body), color = OnbMuted, fontSize = 13.sp, lineHeight = 18.sp)
                if (componentCount > 0) Text(stringResource(R.string.onb_components, componentCount), color = OnbMuted, fontSize = 13.sp, lineHeight = 18.sp)
            }
        }
        val authors: @Composable () -> Unit = {
            InfoCard(R.drawable.ic_onb_group, stringResource(R.string.onb_authors), stringResource(R.string.about_developed_by))
        }
        val all: @Composable () -> Unit = {
            InfoCard(R.drawable.ic_onb_license, stringResource(R.string.onb_view_licenses), stringResource(R.string.onb_view_licenses_body), onClick = onViewLicenses) {
                Icon(painterResource(R.drawable.ic_onb_arrow), contentDescription = null, tint = OnbMuted, modifier = Modifier.size(20.dp))
            }
        }
        if (wide) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f)) { license() }
                Column(Modifier.weight(1f)) { authors(); all() }
            }
        } else {
            license(); authors(); all()
        }
    }
}

@Composable
private fun HeroFrame(modifier: Modifier, glow: Color, content: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(topStart = 40.dp, topEnd = 40.dp, bottomEnd = 40.dp, bottomStart = 12.dp))
            .background(Brush.radialGradient(listOf(glow, OnbSurface))),
    ) { Canvas(Modifier.fillMaxSize(), onDraw = content) }
}

@Composable
private fun WelcomeHero(modifier: Modifier) = HeroFrame(modifier, Color(0xFF3A2A00)) {
    val c = Offset(size.width * 0.58f, size.height * 0.52f)
    val r = size.minDimension * 0.34f
    // Lens barrel: concentric rings with an amber focus ring.
    drawCircle(Color(0xFF1B2226), r, c)
    drawCircle(OnbAmber, r * 0.92f, c, style = Stroke(r * 0.07f))
    listOf(0.78f, 0.62f).forEach { drawCircle(Color(0xFF2E383D), r * it, c, style = Stroke(r * 0.05f)) }
    drawCircle(Brush.radialGradient(listOf(Color(0xFF45D6E8).copy(alpha = 0.55f), Color(0xFF0B0D0E)), c, r * 0.5f), r * 0.48f, c)
    drawCircle(Color.White.copy(alpha = 0.35f), r * 0.1f, Offset(c.x - r * 0.18f, c.y - r * 0.18f))
    // Clapperboard leaning against the lens.
    val w = size.minDimension * 0.46f
    val h = w * 0.62f
    val origin = Offset(size.width * 0.1f, size.height * 0.56f)
    drawRoundRect(Color(0xFF232B30), origin, Size(w, h), CornerRadius(12f))
    rotate(-14f, pivot = origin) {
        val stickHeight = h * 0.24f
        val stickTop = Offset(origin.x, origin.y - stickHeight - 4f)
        drawRoundRect(Color(0xFFEEF2F3), stickTop, Size(w, stickHeight), CornerRadius(8f))
        for (i in 0 until 5) {
            val x = stickTop.x + w * (i * 0.2f + 0.04f)
            drawLine(Color(0xFF12171A), Offset(x, stickTop.y + stickHeight), Offset(x + w * 0.1f, stickTop.y), w * 0.06f)
        }
    }
    drawLine(OnbAmber, Offset(origin.x + w * 0.1f, origin.y + h * 0.45f), Offset(origin.x + w * 0.9f, origin.y + h * 0.45f), 4f, StrokeCap.Round)
    drawLine(OnbMuted, Offset(origin.x + w * 0.1f, origin.y + h * 0.7f), Offset(origin.x + w * 0.6f, origin.y + h * 0.7f), 4f, StrokeCap.Round)
}

@Composable
private fun ReadyHero(modifier: Modifier) = HeroFrame(modifier, Color(0xFF0E3322)) {
    val side = size.minDimension * 0.5f
    val topLeft = Offset((size.width - side) / 2f, (size.height - side) / 2f)
    drawRoundRect(OnbGreen.copy(alpha = 0.18f), Offset(topLeft.x - 18f, topLeft.y - 18f), Size(side + 36f, side + 36f), CornerRadius(side * 0.34f))
    drawRoundRect(OnbGreen, topLeft, Size(side, side), CornerRadius(side * 0.28f))
    val check = Path().apply {
        moveTo(topLeft.x + side * 0.26f, topLeft.y + side * 0.52f)
        lineTo(topLeft.x + side * 0.43f, topLeft.y + side * 0.69f)
        lineTo(topLeft.x + side * 0.76f, topLeft.y + side * 0.34f)
    }
    drawPath(check, Color(0xFF06130C), style = Stroke(side * 0.1f, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
}
