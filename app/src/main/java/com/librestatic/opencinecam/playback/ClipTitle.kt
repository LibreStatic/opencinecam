/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.playback

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import com.librestatic.opencinecam.R
import com.librestatic.opencinecam.storage.LocalMediaTake
import java.text.DateFormat
import java.util.Date

/** Slate facts worth naming a clip by; blank slate text is dropped. */
data class ClipTitleParts(val scene: String?, val take: String?, val reel: String?, val camera: String?, val modifiedSeconds: Long)

fun clipTitleParts(take: LocalMediaTake): ClipTitleParts {
    val slate = take.slate
    return ClipTitleParts(
        scene = slate?.scene?.trim()?.ifEmpty { null },
        take = slate?.takeNumber?.toString(),
        reel = slate?.reel?.trim()?.ifEmpty { null },
        camera = slate?.camera?.trim()?.ifEmpty { null },
        modifiedSeconds = take.primary.modifiedSeconds,
    )
}

/** (title, subtitle): "Scene 3 · Take 2" over the capture date, or the capture date over reel/camera. */
@Composable
fun rememberClipTitle(take: LocalMediaTake): Pair<String, String?> {
    val parts = remember(take) { clipTitleParts(take) }
    val locale = LocalConfiguration.current.locales[0]
    val date = remember(parts.modifiedSeconds, locale) {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale).format(Date(parts.modifiedSeconds * 1000))
    }
    val reel = parts.reel?.let { stringResource(R.string.playback_info_reel, it) }
    val camera = parts.camera?.let { stringResource(R.string.playback_info_camera, it) }
    val scene = parts.scene ?: return date to listOfNotNull(reel, camera).joinToString(" · ").ifEmpty { null }
    val title = if (parts.take != null) stringResource(R.string.playback_title_scene_take, scene, parts.take)
        else stringResource(R.string.playback_title_scene, scene)
    return title to listOfNotNull(date, reel, camera).joinToString(" · ")
}
