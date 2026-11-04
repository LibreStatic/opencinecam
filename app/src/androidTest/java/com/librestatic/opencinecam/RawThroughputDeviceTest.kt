/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import android.Manifest
import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.opencinecam.camera.RawThroughputBenchmark
import com.librestatic.opencinecam.rawvideo.RawFrameEncoders
import com.librestatic.opencinecam.rawvideo.RawQualificationPlan
import com.librestatic.opencinecam.rawvideo.RawQualificationStage
import com.librestatic.opencinecam.rawvideo.RawQualificationSpecs
import com.librestatic.opencinecam.rawvideo.RawQualificationTiming
import com.librestatic.opencinecam.rawvideo.RawVideoQualifier
import com.librestatic.opencinecam.storage.RawVideoQualificationStore
import java.io.File
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * RAW video qualification, protocol 3 (PLAN-071 decisions 9 and 10, staged), through the app's own
 * [RawVideoQualifier]: this test only maps instrumentation arguments onto it and logs its output. It
 * never promotes RAW video (no store is written).
 *
 * The stages: a storage sweep; long disk runs of the uncompressed configurations, heaviest stream
 * first, stopping at the first pass; a RAM-only camera check of every configuration no long run
 * judged; and an encode benchmark per codec from the RAM ring (no disk), from the heaviest
 * configuration the camera delivers down, from which every compressed configuration is judged by
 * pixel rate: block-pack (`+bp`, the "Save space" codec the app records), and LJ92 (`+lj92`) only
 * when a `rawConfigs` spec names it.
 *
 * Instrumentation arguments, all optional:
 * - `rawCameras`: comma-separated Camera2 ids (default: the first back camera with RAW10).
 * - `rawConfigs`: comma-separated `WxH@fps` (a centred crop) or `full@fps`, optionally `+bp` or
 *   `+lj92`; exactly these are judged ([RawQualificationPlan.explicit]): the uncompressed ones, and
 *   the uncompressed twin of each compressed one, take the long runs and camera checks; the
 *   compressed ones are judged from their codec's benchmark. Default: the app's plan
 *   ([RawQualificationPlan.DEFAULT]), every uncompressed configuration and its block-pack twin
 *   (twins only when the native codec loaded).
 * - `rawStages`: comma-separated stages to run, of `sweep,long,camera,encode` (`lj92` is an alias
 *   of `encode`; default all; `long` brings `sweep` along). Verdict rows and records come only from
 *   a run of every stage; with `encode` alone each benchmark runs on the heaviest configuration
 *   without a camera verdict.
 * - `rawEncodeThreads` (alias `rawLj92Threads`): comma-separated encoder thread counts, 1..64, for
 *   every codec; each benchmark runs once per entry (default: the production setting, 2 for
 *   block-pack and 4 for LJ92). Verdicts use the production setting when listed, else the first.
 * - `rawEncodeStrips` (alias `rawLj92Strips`): strips per frame, 1..64 (default 8). Threads above
 *   the strips cannot help.
 * - `rawEncodeBenchSeconds` (alias `rawLj92BenchSeconds`): measured seconds of each benchmark
 *   session (default 30).
 * - `rawCameraCheckSeconds`: measured seconds of each camera check, after a 2 s warm-up (default 8).
 * - `rawLiveSeconds`: measured seconds per long run, after a 3 s warm-up (default 40).
 * - `rawSustainedLiveSeconds`: measured seconds of long runs of at least 500 MB/s (default 80,
 *   never below `rawLiveSeconds`).
 * - `rawSweepSeconds`: storage sweep windows (default 60; below 60 the sweep fails rule v2 by design).
 * - `rawIgnoreRest`: `true` runs on after a sweep that found the write cache unrested (default `false`:
 *   the run stops after the sweep with `storage-not-rested`, a valid outcome, logged as a warning).
 * - `rawRestReference`: the rested burst median in bytes/s the sweep is compared with (default: the
 *   one the app stored for this build, if any).
 *
 * Every session uses one graph, the app's RAW_VIDEO session ([RawVideoQualifier.GRAPH]).
 *
 * Logcat under [TAG]: one `result=<json>` line per sweep, long run (`kind: live`), camera check
 * (`kind: camera`), benchmark session (`kind: encode-bench`, per codec and thread count: encode ms mean/p95/max
 * and per quarter, sustained fps, pixels/s, aggregate and worst-frame ratio, process CPU cores,
 * thermal status and battery temperature before and after, dropped frames) and staged verdict
 * (`kind: live`, `stage: verdict`), the full report as base64 `json-part=i/n:` lines, and a final
 * `status=` line. The report is also written to `getExternalFilesDir(null)/occ-plan-071-raw-throughput.json`.
 */
@RunWith(AndroidJUnit4::class)
class RawThroughputDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val arguments = InstrumentationRegistry.getArguments()

    // The adopted shell identity is process-wide; leaving it set breaks later cases that need the app's own uid.
    @After fun cleanup() {
        instrumentation.uiAutomation.dropShellPermissionIdentity()
    }

    /** The `rawEncode<suffix>` argument, else its older name `rawLj92<suffix>`. */
    private fun encodeArgument(suffix: String): String? =
        arguments.getString("rawEncode$suffix") ?: arguments.getString("rawLj92$suffix")

    @Test
    fun rawVideoQualification() {
        instrumentation.uiAutomation.adoptShellPermissionIdentity(Manifest.permission.CAMERA)
        val liveSeconds = arguments.getString("rawLiveSeconds")?.toIntOrNull()?.coerceAtLeast(1)
            ?: RawQualificationTiming.DEFAULT_LIVE_SECONDS
        val sustainedSeconds = arguments.getString("rawSustainedLiveSeconds")?.toIntOrNull()?.coerceAtLeast(1)
            ?: maxOf(RawQualificationTiming.DEFAULT_SUSTAINED_LIVE_SECONDS, liveSeconds)
        val sweepSeconds = arguments.getString("rawSweepSeconds")?.toIntOrNull()?.coerceAtLeast(1)
            ?: RawThroughputBenchmark.SWEEP_MIN_WINDOWS
        val cameraCheckSeconds = arguments.getString("rawCameraCheckSeconds")?.toIntOrNull()?.coerceAtLeast(1)
            ?: RawQualificationTiming.DEFAULT_CAMERA_CHECK_SECONDS
        val encodeBenchSeconds = encodeArgument("BenchSeconds")?.toIntOrNull()?.coerceAtLeast(1)
            ?: RawQualificationTiming.DEFAULT_ENCODE_BENCH_SECONDS
        val stages = arguments.getString("rawStages")?.let { text ->
            requireNotNull(RawQualificationStage.parse(text)?.takeIf { it.isNotEmpty() }) { "rawStages must list sweep, long, camera or encode: $text" }
        } ?: RawQualificationStage.ALL
        val threads = RawQualificationSpecs.split(encodeArgument("Threads"))?.map { value ->
            requireNotNull(value.toIntOrNull()?.takeIf { it in RawFrameEncoders.SETTING_RANGE }) { "rawEncodeThreads entries must be 1..64: $value" }
        }?.distinct()?.takeIf { it.isNotEmpty() }
        val strips = encodeArgument("Strips")?.let { value ->
            requireNotNull(value.trim().toIntOrNull()?.takeIf { it in RawFrameEncoders.SETTING_RANGE }) { "rawEncodeStrips must be 1..64: $value" }
        } ?: RawFrameEncoders.DEFAULT_STRIPS
        val basePlan = RawQualificationSpecs.split(arguments.getString("rawConfigs"))?.takeIf { it.isNotEmpty() }
            ?.let(RawQualificationPlan::explicit) ?: RawQualificationPlan.DEFAULT
        val plan = basePlan.copy(stages = stages, encodeThreads = threads, encodeStrips = strips)
        // `rawPrivate=files` (internal files dir) or `external` (Android/data): plain files that bypass FUSE, for an A/B against MediaStore.
        val privateDirectory = when (arguments.getString("rawPrivate")?.trim()) {
            null, "", "false" -> null
            "files" -> File(context.filesDir, "occ-qualify")
            "external" -> File(requireNotNull(context.getExternalFilesDir(null)) { "external files directory unavailable" }, "occ-qualify")
            else -> error("rawPrivate must be files or external")
        }
        val destinationId = if (privateDirectory != null) RawVideoQualifier.DESTINATION_APP_PRIVATE else RawVideoQualifier.DESTINATION_ID
        val qualifier = RawVideoQualifier(
            context = context,
            cameraIds = RawQualificationSpecs.split(arguments.getString("rawCameras")),
            plan = plan,
            timing = RawQualificationTiming(
                liveSeconds = liveSeconds,
                sustainedLiveSeconds = sustainedSeconds,
                sweepWindows = sweepSeconds,
                cameraCheckSeconds = cameraCheckSeconds,
                encodeBenchSeconds = encodeBenchSeconds,
            ),
            listener = object : RawVideoQualifier.Listener {
                override fun onResult(result: JSONObject) { Log.i(TAG, "result=$result") }
            },
            ringTargetBytes = arguments.getString("rawRingBytes")?.toLongOrNull()?.coerceAtLeast(1L),
            // The rest reference: `rawRestReference` (bytes/s), else the one the app stored for this build.
            restReferenceBytesPerSecond = arguments.getString("rawRestReference")?.toLongOrNull()?.takeIf { it > 0 }
                ?: runCatching { RawVideoQualificationStore.forContext(context).restReference(destinationId) }.getOrNull(),
            ignoreStorageRest = arguments.getString("rawIgnoreRest")?.trim()?.toBoolean() ?: false,
            privateDirectory = privateDirectory,
        )
        qualifier.estimate()?.let { Log.i(TAG, "estimate durationMs=${it.durationMs} freeBytesNeeded=${it.freeBytesNeeded} runs=${it.runCount}") }
        val report = qualifier.run().report
        val text = report.toString(2) + "\n"
        val directory = requireNotNull(context.getExternalFilesDir(null)) { "external files directory unavailable" }
        val file = File(directory, JSON_NAME)
        file.writeText(text, Charsets.UTF_8)
        val encoded = Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val parts = (encoded.length + LOG_PART_BYTES - 1) / LOG_PART_BYTES
        encoded.chunked(LOG_PART_BYTES).forEachIndexed { index, part -> Log.i(TAG, "json-part=${index + 1}/$parts:$part") }
        Log.i(TAG, "status=${report.optString("status")} reasonCode=${report.optString("reasonCode")} file=${file.path}")
        if (report.optString("reasonCode") == RawVideoQualifier.REASON_STORAGE_NOT_RESTED) {
            // A valid outcome: the sweep found the write cache still flushing, and the run stopped after it.
            Log.w(TAG, "storage not rested: ${report.optJSONObject("sweep")?.optJSONObject("rest")}; let the phone rest, " +
                "or pass -e rawIgnoreRest true to run on")
        }
        assertTrue("RAW throughput evidence was not written", file.isFile)
        // Unsupported, failed or skipped branches are valid evidence; an exception is not.
        assertTrue("RAW throughput fixture crashed", report.optString("reasonCode") != RawVideoQualifier.REASON_EXCEPTION)
    }

    private companion object {
        const val TAG = "OCC_RAW_THROUGHPUT"
        const val JSON_NAME = "occ-plan-071-raw-throughput.json"
        const val LOG_PART_BYTES = 3000
    }
}
