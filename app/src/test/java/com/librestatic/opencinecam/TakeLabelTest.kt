/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam

import com.librestatic.opencinecam.storage.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

class TakeLabelTest {
    private val utc: ZoneId = ZoneOffset.UTC
    private fun epoch(year: Int, month: Int, day: Int, hour: Int = 12, zone: ZoneId = utc) =
        ZonedDateTime.of(year, month, day, hour, 0, 0, 0, zone).toEpochSecond()

    @Test fun sceneFromTheSlateNamesTheTake() {
        assertEquals(TakeTitle.SceneTake("12", 3), takeTitle(ProductionSlateSettings(scene = "12", takeNumber = 3)))
        assertEquals(TakeTitle.SceneTake("12A", 1), takeTitle(ProductionSlateSettings(scene = "  12A ")))
    }

    @Test fun aTakeNumberWithoutSceneStillNamesTheTake() {
        assertEquals(TakeTitle.Take(4), takeTitle(ProductionSlateSettings(takeNumber = 4)))
        assertEquals(TakeTitle.Take(1), takeTitle(ProductionSlateSettings(autoIncrementTake = true)))
    }

    @Test fun noSlateOrAnUntouchedOneFallsBackToCaptureTime() {
        assertEquals(TakeTitle.CaptureTime, takeTitle(null))
        assertEquals(TakeTitle.CaptureTime, takeTitle(ProductionSlateSettings()))
        // Project, camera or reel alone do not identify a take.
        assertEquals(TakeTitle.CaptureTime, takeTitle(ProductionSlateSettings(project = "Feature", camera = "A", scene = "   ")))
    }

    @Test fun timeSkeletonAddsTheYearOnlyForAnotherYearAndFollowsTheClockFormat() {
        val now = epoch(2026, 10, 2)
        assertEquals("MMMdHmm", takeTimeSkeleton(epoch(2026, 1, 5), now, utc, hour24 = true))
        assertEquals("MMMdhmm", takeTimeSkeleton(epoch(2026, 1, 5), now, utc, hour24 = false))
        assertEquals("yMMMdHmm", takeTimeSkeleton(epoch(2025, 12, 31), now, utc, hour24 = true))
    }

    @Test fun daysAreLocalToTheZone() {
        val lateUtc = epoch(2026, 10, 2, hour = 23)
        assertEquals(LocalDate.of(2026, 10, 2), takeDay(lateUtc, utc))
        assertEquals(LocalDate.of(2026, 10, 3), takeDay(lateUtc, ZoneOffset.ofHours(3)))
    }

    @Test fun dayKindAndHeaderSkeleton() {
        val today = LocalDate.of(2026, 10, 2)
        assertEquals(TakeDayKind.TODAY, takeDayKind(today, today))
        assertEquals(TakeDayKind.YESTERDAY, takeDayKind(today.minusDays(1), today))
        assertEquals(TakeDayKind.OTHER, takeDayKind(today.minusDays(2), today))
        assertEquals(TakeDayKind.OTHER, takeDayKind(today.plusDays(1), today))
        assertEquals("MMMEd", takeDaySkeleton(LocalDate.of(2026, 1, 1), today))
        assertEquals("yMMMEd", takeDaySkeleton(LocalDate.of(2025, 12, 31), today))
    }

    @Test fun groupingKeepsCatalogOrderAndSplitsOnlyWhenTheDayChanges() {
        val items = listOf("a" to epoch(2026, 10, 2, 18), "b" to epoch(2026, 10, 2, 9), "c" to epoch(2026, 10, 1, 22),
            "d" to epoch(2026, 10, 2, 8))
        val groups = groupByTakeDay(items, utc) { it.second }
        assertEquals(listOf(LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 2)), groups.map { it.first })
        assertEquals(listOf(listOf("a", "b"), listOf("c"), listOf("d")), groups.map { group -> group.second.map { it.first } })
        assertTrue(groupByTakeDay(emptyList<Long>(), utc) { it }.isEmpty())
    }

    @Test fun durationsRoundToTheSecondAndRejectNonsense() {
        assertNull(formatTakeDuration(null)); assertNull(formatTakeDuration(0)); assertNull(formatTakeDuration(-5))
        assertEquals("0:01", formatTakeDuration(600))
        assertEquals("0:42", formatTakeDuration(42_000))
        assertEquals("1:00", formatTakeDuration(59_600))
        assertEquals("1:02:03", formatTakeDuration(3_723_000))
    }

    @Test fun resolutionBadgeUsesTheShortEdgeExceptFor4k() {
        assertEquals("1080p", resolutionBadge(1920, 1080))
        assertEquals("1080p", resolutionBadge(1080, 1920))
        assertEquals("720p", resolutionBadge(1280, 720))
        assertEquals("4K", resolutionBadge(3840, 2160))
        assertEquals("4K", resolutionBadge(4096, 2160))
        assertNull(resolutionBadge(null, 1080)); assertNull(resolutionBadge(1920, 0))
    }

    @Test fun logAndAttentionFlags() {
        val primary = LocalMediaArtifact("content://media/external_primary/video/media/1", "OCC_1.mp4", "video/mp4", 10, 1)
        fun take(vararg metadata: String) = LocalMediaTake("1", primary, listOf(primary),
            metadata.mapIndexed { index, name -> LocalMediaArtifact("content://media/external_primary/downloads/$index", name, "application/json", 1, 1) },
            LocalMediaKind.VIDEO, null, LocalMediaRelationStatus.DECLARED)
        assertTrue(takeIsLog(take("OCC_1.json", "OCC_1.oclog.json")))
        assertFalse(takeIsLog(take("OCC_1.json")))
        assertFalse(takeNeedsAttention(LocalMediaRelationStatus.DECLARED))
        assertFalse(takeNeedsAttention(LocalMediaRelationStatus.LEGACY))
        for (status in listOf(LocalMediaRelationStatus.MISSING_METADATA, LocalMediaRelationStatus.INVALID_METADATA,
            LocalMediaRelationStatus.INCOMPLETE)) assertTrue(takeNeedsAttention(status))
    }
}
