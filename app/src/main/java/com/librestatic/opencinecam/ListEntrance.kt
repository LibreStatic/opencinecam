/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import com.librestatic.opencinecam.ui.theme.LocalReducedMotion
import kotlinx.coroutines.flow.first

internal const val EntranceStepMillis = 40f
internal const val EntranceMaxDelayMillis = 480f
internal const val EntranceTileMillis = 320f
private const val EntranceClockMillis = EntranceMaxDelayMillis + EntranceTileMillis

/** Diagonal stagger: row plus column steps, so a grid fills from its top-left corner. */
internal fun entranceDelayMillis(slot: Int, columns: Int): Float {
    val s = slot.coerceAtLeast(0)
    return ((s / columns + s % columns) * EntranceStepMillis).coerceAtMost(EntranceMaxDelayMillis)
}

/** Progress 0..1 of the tile in [slot] once the entrance clock reads [elapsedMillis]. */
internal fun entranceProgress(elapsedMillis: Float, slot: Int, columns: Int): Float =
    ((elapsedMillis - entranceDelayMillis(slot, columns)) / EntranceTileMillis).coerceIn(0f, 1f)

/**
 * The first paint of a list or grid: the visible items fade and grow in one after another. One
 * clock runs once per composition from scratch; anything composed later (scrolling, paging) is
 * already past the clock and shows at once.
 */
@Stable
internal class ListEntrance(val columns: Int) {
    internal var firstIndex by mutableIntStateOf(0)
    internal var started by mutableStateOf(false)
    internal val elapsed = Animatable(0f)

    fun progress(index: Int): Float = if (!started) 0f else entranceProgress(elapsed.value, index - firstIndex, columns)
}

/** Null when motion is reduced: nothing animates and nothing is hidden. */
@Composable
internal fun rememberListEntrance(state: LazyListState, columns: Int = 1, hasItems: () -> Boolean): ListEntrance? {
    if (LocalReducedMotion.current) return null
    val entrance = remember(columns) { ListEntrance(columns) }
    val currentHasItems by rememberUpdatedState(hasItems)
    LaunchedEffect(entrance) {
        snapshotFlow { currentHasItems() && state.layoutInfo.visibleItemsInfo.isNotEmpty() }.first { it }
        entrance.firstIndex = state.firstVisibleItemIndex
        entrance.started = true
        entrance.elapsed.animateTo(EntranceClockMillis, tween(EntranceClockMillis.toInt(), easing = LinearEasing))
    }
    return entrance
}

/** Fade plus a 0.85→1 scale, read in the draw phase only. */
internal fun Modifier.listEntrance(entrance: ListEntrance?, index: Int): Modifier =
    if (entrance == null) this else graphicsLayer {
        val p = FastOutSlowInEasing.transform(entrance.progress(index))
        alpha = p
        scaleX = 0.85f + 0.15f * p
        scaleY = 0.85f + 0.15f * p
    }
