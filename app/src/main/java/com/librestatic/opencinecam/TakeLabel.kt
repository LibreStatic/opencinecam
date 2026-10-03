/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import android.content.Context
import com.librestatic.opencinecam.storage.LocalMediaTake
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale

/**
 * What the gallery calls a take: the slate's scene and take when the operator filled them in,
 * otherwise when it was shot. Only the label changes; no file is ever renamed for it.
 */
internal sealed interface TakeTitle {
    data class SceneTake(val scene: String, val take: Int) : TakeTitle
    data class Take(val take: Int) : TakeTitle
    data object CaptureTime : TakeTitle
}

/**
 * Every capture carries the current slate, so an untouched one (no scene, take 1, no automatic
 * numbering) says nothing about the take and the capture time names it instead.
 */
internal fun takeTitle(slate: ProductionSlateSettings?): TakeTitle {
    if (slate == null) return TakeTitle.CaptureTime
    val scene = slate.scene.trim()
    if (scene.isNotEmpty()) return TakeTitle.SceneTake(scene, slate.takeNumber)
    return if (slate.autoIncrementTake || slate.takeNumber > 1) TakeTitle.Take(slate.takeNumber) else TakeTitle.CaptureTime
}

/** A CLDR skeleton for "Oct 2, 14:31"; the year joins only when it is not the current one. */
internal fun takeTimeSkeleton(epochSeconds: Long, nowSeconds: Long, zone: ZoneId, hour24: Boolean): String {
    val year = if (takeDay(epochSeconds, zone).year != takeDay(nowSeconds, zone).year) "y" else ""
    return year + "MMMd" + if (hour24) "Hmm" else "hmm"
}

internal fun takeDay(epochSeconds: Long, zone: ZoneId): LocalDate = Instant.ofEpochSecond(epochSeconds).atZone(zone).toLocalDate()

internal enum class TakeDayKind { TODAY, YESTERDAY, OTHER }

internal fun takeDayKind(day: LocalDate, today: LocalDate): TakeDayKind = when (day) {
    today -> TakeDayKind.TODAY
    today.minusDays(1) -> TakeDayKind.YESTERDAY
    else -> TakeDayKind.OTHER
}

/** Skeleton of a day header such as "Thu, Oct 2". */
internal fun takeDaySkeleton(day: LocalDate, today: LocalDate): String = if (day.year != today.year) "yMMMEd" else "MMMEd"

/** Consecutive takes shot on the same local day, in the catalog's own order. */
internal fun <T> groupByTakeDay(items: List<T>, zone: ZoneId, epochSeconds: (T) -> Long): List<Pair<LocalDate, List<T>>> {
    val groups = mutableListOf<Pair<LocalDate, MutableList<T>>>()
    for (item in items) {
        val day = takeDay(epochSeconds(item), zone)
        if (groups.lastOrNull()?.first == day) groups.last().second += item else groups += day to mutableListOf(item)
    }
    return groups
}

/** "0:42" or "1:02:03"; null for a missing or nonsensical duration. */
internal fun formatTakeDuration(durationMs: Long?): String? {
    if (durationMs == null || durationMs <= 0) return null
    val total = (durationMs + 500) / 1000
    val hours = total / 3600
    val minutes = total % 3600 / 60
    val seconds = total % 60
    return if (hours > 0) "%d:%02d:%02d".format(Locale.ROOT, hours, minutes, seconds) else "%d:%02d".format(Locale.ROOT, minutes, seconds)
}

/** "4K" from a 3840-wide long edge, otherwise the short edge as "1080p"; null when unknown. */
internal fun resolutionBadge(width: Int?, height: Int?): String? {
    if (width == null || height == null || width <= 0 || height <= 0) return null
    val long = maxOf(width, height)
    val short = minOf(width, height)
    return if (long >= 3840) "4K" else "${short}p"
}

private fun formatSkeleton(skeleton: String, epochSeconds: Long, locale: Locale): String =
    android.icu.text.DateFormat.getInstanceForSkeleton(skeleton, locale).format(Date(epochSeconds * 1000))

/** "Oct 2, 14:31" in the device's language and clock format. */
internal fun takeTimeText(context: Context, epochSeconds: Long): String {
    val locale = context.resources.configuration.locales[0] ?: Locale.getDefault()
    val skeleton = takeTimeSkeleton(epochSeconds, System.currentTimeMillis() / 1000, ZoneId.systemDefault(),
        android.text.format.DateFormat.is24HourFormat(context))
    return formatSkeleton(skeleton, epochSeconds, locale)
}

/** The take's name in the gallery: "Scene 12 · Take 3", "Take 4" or the capture time. */
internal fun takeTitleText(context: Context, take: LocalMediaTake): String = when (val title = takeTitle(take.slate)) {
    is TakeTitle.SceneTake -> context.getString(R.string.media_take_scene_take, title.scene, title.take)
    is TakeTitle.Take -> context.getString(R.string.gallery_take_number, title.take)
    TakeTitle.CaptureTime -> takeTimeText(context, take.primary.modifiedSeconds)
}

/** "Today", "Yesterday" or "Thu, Oct 2". */
internal fun takeDayText(context: Context, day: LocalDate): String {
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    return when (takeDayKind(day, today)) {
        TakeDayKind.TODAY -> context.getString(R.string.media_today)
        TakeDayKind.YESTERDAY -> context.getString(R.string.media_yesterday)
        TakeDayKind.OTHER -> formatSkeleton(takeDaySkeleton(day, today), day.atTime(12, 0).atZone(zone).toEpochSecond(),
            context.resources.configuration.locales[0] ?: Locale.getDefault())
    }
}
