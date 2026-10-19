/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.librestatic.opencinecam.core.model.BenchmarkCancellationSource
import com.librestatic.opencinecam.core.model.BenchmarkResultDocument
import com.librestatic.opencinecam.core.model.StorageBenchmarkRunner
import com.librestatic.opencinecam.storage.ContentResolverBenchmarkDestination
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive

/**
 * Writes to a place the user picks and reports whether it keeps up with the current recording
 * settings (PLAN-012). The test file is deleted when the run ends; the result is kept on the phone.
 */
@Composable
internal fun StorageBenchmarkScreen(requiredBytesPerSecond: Long, onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember(context) { BenchmarkResultStore.inFilesDir(context.filesDir) }
    var last by remember { mutableStateOf(store.latest()) }
    var running by remember { mutableStateOf(false) }
    val cancellation = remember { mutableStateOf(BenchmarkCancellationSource()) }
    val scope = rememberCoroutineScope()
    // Leaving the page stops a run in progress; its test file is cleaned up by the runner.
    DisposableEffect(Unit) { onDispose { cancellation.value.cancel() } }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null && !running) {
            running = true
            val source = BenchmarkCancellationSource().also { cancellation.value = it }
            scope.launch {
                withContext(Dispatchers.IO) {
                    StorageBenchmarkRunner(repository = store.repository()).run(
                        benchmarkRequest(UUID.randomUUID().toString(), requiredBytesPerSecond),
                        listOf(ContentResolverBenchmarkDestination(context.contentResolver, "saf-destination", uri)),
                        source,
                    )
                }
                last = store.latest()
                running = false
            }
        }
    }
    StorageBenchmarkContent(
        requiredBytesPerSecond = requiredBytesPerSecond,
        running = running,
        last = last,
        onBack = onBack,
        onChoose = { picker.launch("opencinecam-benchmark.bin") },
        onCancel = { cancellation.value.cancel() },
    )
}

/** The page itself, stateless so Compose Driver can render it with a fake result. */
@Composable
internal fun StorageBenchmarkContent(
    requiredBytesPerSecond: Long,
    running: Boolean,
    last: BenchmarkResultDocument?,
    onBack: () -> Unit,
    onChoose: () -> Unit,
    onCancel: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val gutter = settingsSideGutterDp(maxWidth.value)
        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag("storage-benchmark"),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = gutter.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "header") { SettingsTopBar(stringResource(R.string.bench_title), onBack, backTag = "storage-benchmark-back") }
            item(key = "intro") { Text(stringResource(R.string.bench_intro), color = SettingsMuted, fontSize = 13.sp) }
            item(key = "required") {
                SettingsCard {
                    SettingsHeading(stringResource(R.string.bench_required), megabytesPerSecond(requiredBytesPerSecond), CineIcon.INFO)
                    Text(stringResource(R.string.bench_required_help), color = SettingsMuted, fontSize = 13.sp)
                }
            }
            item(key = "actions") {
                if (running) {
                    Text(stringResource(R.string.bench_running), color = SettingsAccent, modifier = Modifier.padding(bottom = 8.dp).testTag("storage-benchmark-running"))
                    OutlinedButton(onCancel, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("storage-benchmark-cancel")) {
                        Text(stringResource(android.R.string.cancel))
                    }
                } else {
                    OutlinedButton(onChoose, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("storage-benchmark-run")) {
                        Text(stringResource(R.string.bench_run))
                    }
                }
            }
            item(key = "result") {
                if (last == null) Text(stringResource(R.string.bench_no_result), color = SettingsMuted, modifier = Modifier.testTag("storage-benchmark-empty"))
                else BenchmarkResultCard(last)
            }
        }
    }
}

@Composable
private fun BenchmarkResultCard(result: BenchmarkResultDocument) {
    SettingsCard(Modifier.testTag("storage-benchmark-result")) {
        SettingsHeading(stringResource(R.string.bench_last), result.createdAt, CineIcon.CHECK)
        Text(stringResource(benchmarkOutcomeLabel(result.outcomeDetail)), color = MaterialTheme.colorScheme.onSurface,
            fontSize = 16.sp, modifier = Modifier.testTag("storage-benchmark-outcome"))
        result.qualificationMarginBytesPerSecond?.let { margin ->
            Text(stringResource(R.string.bench_margin, (if (margin >= 0) "+" else "-") + megabytesPerSecond(kotlin.math.abs(margin))),
                color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp, modifier = Modifier.testTag("storage-benchmark-margin"))
        }
        result.windows.forEach { window ->
            val index = (window["index"] as? JsonPrimitive)?.content?.toIntOrNull() ?: return@forEach
            val rate = (window["bytesPerSecond"] as? JsonPrimitive)?.content?.toLongOrNull() ?: return@forEach
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.bench_window, index + 1), color = SettingsMuted, fontSize = 13.sp)
                Text(megabytesPerSecond(rate), color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}
