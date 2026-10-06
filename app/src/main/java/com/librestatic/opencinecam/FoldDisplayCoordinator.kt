/* SPDX-License-Identifier: Apache-2.0 */
@file:OptIn(androidx.window.core.ExperimentalWindowApi::class)

package com.librestatic.opencinecam

import android.view.WindowManager
import androidx.activity.ComponentActivity
import com.librestatic.opencinecam.ui.theme.AppTheme
import com.librestatic.opencinecam.ui.theme.OpenCineCamTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.window.area.*
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

internal val LocalFoldDisplayCoordinator = staticCompositionLocalOf<FoldDisplayCoordinator?> { null }

/** Owns windows only. It has no camera-open, record or stop command. */

private const val LAYOUT_RETRY_MS = 1_000L

internal class FoldDisplayCoordinator(private val activity: ComponentActivity) : AutoCloseable {
    private val machine = FoldSessionStateMachine()
    private val mutableState = MutableStateFlow(machine.state)
    val states = mutableState.asStateFlow()
    private val subject = MutableStateFlow(CameraUiState())
    private val previewPort = MutableStateFlow<SubjectPreviewPort?>(null)
    private val cues = MutableStateFlow(SubjectSessionCues())
    /** Operator commands (review pick, interview position) reach the subject only through these cues. */
    val subjectCues = cues.asStateFlow()
    private val preferences = SettingsRepositories.get(activity)
    /** OCC-PLAN-068 U4: the operator's review pick, stopped by a take start or the end of the session. */
    val review = SubjectReviewController(preferences, activity.lifecycleScope) { uri -> updateSubjectCues { it.copy(reviewUri = uri) } }
    private val executor = ContextCompat.getMainExecutor(activity)
    private val controller = runCatching { WindowAreaController.getOrCreate() }.getOrNull()
    private var area: WindowAreaInfo? = null
    private var session: WindowAreaSession? = null
    private var subjectView: ComposeView? = null
    private var disposed = false
    private var activityBrightnessOverridden = false
    private var selfRoleObserver: ((Boolean) -> Unit)? = null
    private val observation: Job
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val fillLightOutput = MutableStateFlow<FillLightOutput?>(null)
    /** OCC-PLAN-068 U3: the fill light's current dim policy, null unless FILL_LIGHT is on an active presentation. */
    val fillLight = fillLightOutput.asStateFlow()
    private val fillLightMonitor = SubjectFillLightMonitor(
        thermal = activity.getSystemService(android.os.PowerManager::class.java)?.let { PowerManagerThermalSource(it, executor) },
        clock = android.os.SystemClock::elapsedRealtime,
        schedule = { delayMs, block -> val task = Runnable(block); mainHandler.postDelayed(task, delayMs); { mainHandler.removeCallbacks(task) } },
        onOutput = { fillLightOutput.value = it; applyBrightness() },
    )
    private val fillLightObservation: Job
    /** OCC-PLAN-068 U6: REC-start flash/beep, driven by observed service state only. */
    private val syncMarker = SubjectSyncMarkerController(activity)

    init {
        // The fill light, its thermal listener and its timeout live only while FILL_LIGHT is on an active presentation.
        fillLightObservation = activity.lifecycleScope.launch {
            combine(preferences.states, mutableState) { settings, display ->
                settings.subjectDisplay to (display.phase == DisplaySessionPhase.ACTIVE && display.operation == DisplayOperation.PRESENT &&
                    settings.subjectDisplay.mode == SubjectDisplayMode.FILL_LIGHT)
            }.collect { (subjectSettings, active) -> fillLightMonitor.update(active, subjectSettings) }
        }
        observation = activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    try {
                        controller?.windowAreaInfos?.collect { infos ->
                            area = infos.firstOrNull { it.type == WindowAreaInfo.Type.TYPE_REAR_FACING }
                            machine.capabilities(
                                area?.getCapability(WindowAreaCapability.Operation.OPERATION_PRESENT_ON_AREA)?.status.toCapability(),
                                area?.getCapability(WindowAreaCapability.Operation.OPERATION_TRANSFER_ACTIVITY_TO_AREA)?.status.toCapability(),
                            )
                            publish()
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { closeSession(failure.message) }
                }
                launch {
                    // A failed layout stream would otherwise freeze the posture at its last value; resubscribe.
                    while (true) try {
                        WindowInfoTracker.getOrCreate(activity).windowLayoutInfo(activity).collect { layout ->
                            val fold = layout.displayFeatures.filterIsInstance<FoldingFeature>().firstOrNull()
                            val posture = when {
                                fold == null -> FoldPosture.NONE_REPORTED
                                fold.state == FoldingFeature.State.HALF_OPENED -> if (fold.orientation == FoldingFeature.Orientation.HORIZONTAL) FoldPosture.TABLETOP else FoldPosture.BOOK
                                fold.isSeparating -> FoldPosture.SEPARATING
                                else -> FoldPosture.FLAT
                            }
                            val hinge = fold?.takeIf { it.isSeparating || it.state == FoldingFeature.State.HALF_OPENED }?.let {
                                FoldHinge(it.bounds.left, it.bounds.top, it.bounds.right, it.bounds.bottom, it.orientation == FoldingFeature.Orientation.HORIZONTAL)
                            }
                            machine.posture(posture, hinge)
                            publish()
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { closeSession(failure.message); delay(LAYOUT_RETRY_MS) }
                }
            }
        }
    }

    fun restartFillLight() = fillLightMonitor.restart()

    fun updatePreviewPort(port: SubjectPreviewPort?) { previewPort.value = port }

    fun updateSelfRoleObserver(observer: ((Boolean) -> Unit)?) {
        selfRoleObserver?.invoke(false)
        selfRoleObserver = observer
        observer?.invoke(machine.state.phase == DisplaySessionPhase.ACTIVE && machine.state.operation == DisplayOperation.TRANSFER)
    }

    fun updateCameraState(state: CameraUiState) {
        subject.value = state
        review.onCameraState(state)
        val subjectSettings = preferences.states.value.subjectDisplay
        val presented = machine.state.phase == DisplaySessionPhase.ACTIVE && machine.state.operation == DisplayOperation.PRESENT && subjectView != null
        syncMarker.observe(state, SubjectSyncArming(subjectSettings.slateSyncFlash, subjectSettings.slateSyncBeep,
            presented && subjectSettings.mode == SubjectDisplayMode.SLATE, presented && machine.state.visible))
    }

    /** Where sync-marker evidence goes: the bound service's optional take-sidecar field. */
    fun updateSyncMarkerSink(sink: ((SubjectSyncMarkerReport) -> Boolean)?) { syncMarker.sink = sink }

    fun updateSubjectCues(transform: (SubjectSessionCues) -> SubjectSessionCues) { cues.value = transform(cues.value) }

    fun start(operation: DisplayOperation) {
        if (disposed) return
        val backend = controller ?: return
        val info = area ?: return
        val token = machine.begin(operation) ?: return
        publish()
        try {
            if (operation == DisplayOperation.PRESENT) {
                backend.presentContentOnWindowArea(info.token, activity, executor, object : WindowAreaPresentationSessionCallback {
                    override fun onSessionStarted(session: WindowAreaSessionPresenter) {
                        if (disposed || !machine.started(token)) { session.close(); return }
                        this@FoldDisplayCoordinator.session = session
                        try {
                            val view = ComposeView(session.context).apply {
                                setViewTreeLifecycleOwner(activity)
                                setViewTreeSavedStateRegistryOwner(activity)
                                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                                setContent {
                                    val current by subject.collectAsState()
                                    val settings by preferences.states.collectAsState()
                                    val output by previewPort.collectAsState()
                                    val windowState by mutableState.collectAsState()
                                    val sessionCues by cues.collectAsState()
                                    val fill by fillLightOutput.collectAsState()
                                    val syncFlash by syncMarker.flash.collectAsState()
                                    // The subject faces the Cine palette whatever the operator's theme: black, amber cues, red only for REC.
                                    OpenCineCamTheme(AppTheme.CINE, forceDark = true) {
                                        CompositionLocalProvider(
                                            LocalSubjectFillLightOutput provides fill,
                                            LocalSubjectReviewFeed provides review,
                                        ) {
                                            SubjectDisplayScreen(current, settings.subjectDisplay, output.takeIf { windowState.visible },
                                                cues = sessionCues, productionSlate = settings.productionSlate,
                                                timecodeRate = settings.slateTimecodeRate(), syncFlash = syncFlash)
                                        }
                                    }
                                }
                            }
                            subjectView = view
                            session.setContentView(view)
                            applyBrightness()
                            publish()
                        } catch (failure: Exception) { closeSession(failure.message) }
                    }
                    override fun onSessionEnded(t: Throwable?) = ended(token, t)
                    override fun onContainerVisibilityChanged(isVisible: Boolean) {
                        machine.visibility(token, isVisible)
                        publish()
                    }
                })
            } else {
                backend.transferActivityToWindowArea(info.token, activity, executor, object : WindowAreaSessionCallback {
                    override fun onSessionStarted(session: WindowAreaSession) {
                        if (disposed || !machine.started(token)) { session.close(); return }
                        this@FoldDisplayCoordinator.session = session
                        machine.visibility(token, true)
                        publish()
                    }
                    override fun onSessionEnded(t: Throwable?) = ended(token, t)
                })
            }
        } catch (failure: Exception) { ended(token, failure) }
    }

    fun applyBrightness() {
        // Transfer moves this activity's own window to the cover: it carries the request while there and
        // drops it on return, so the inner screen is never left at the exterior level.
        val transferRequest = transferBrightnessRequest(machine.state, preferences.states.value.subjectDisplay.brightness)
        if (transferRequest != null || activityBrightnessOverridden) {
            val value = transferRequest ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            activity.window.attributes = activity.window.attributes.apply { screenBrightness = value }
            activityBrightnessOverridden = transferRequest != null
        }
        val window = (session as? WindowAreaSessionPresenter)?.window ?: return
        // Fill light replaces the request with its timeout/thermal-capped level; it is still only a request.
        val requested = fillLightMonitor.output?.windowBrightness ?: preferences.states.value.subjectDisplay.brightness
        window.attributes = window.attributes.apply { screenBrightness = requested }
    }

    private fun ended(token: Long, failure: Throwable?) {
        if (!machine.ended(token, failure?.message)) return
        session = null
        review.onSessionEnded()
        subjectView?.disposeComposition()
        subjectView = null
        publish()
    }

    fun closeSession(failure: String? = null) {
        val previous = session
        session = null
        machine.close(failure)
        review.onSessionEnded()
        subjectView?.disposeComposition()
        subjectView = null
        publish()
        runCatching { previous?.close() }
    }

    private fun publish() {
        mutableState.value = machine.state
        applyBrightness()
        // The interview position belongs to one session: a new session starts at the first question.
        if (machine.state.phase == DisplaySessionPhase.IDLE) cues.value = cues.value.copy(interviewIndex = 0)
        selfRoleObserver?.invoke(machine.state.phase == DisplaySessionPhase.ACTIVE && machine.state.operation == DisplayOperation.TRANSFER)
    }

    override fun close() {
        disposed = true
        observation.cancel()
        fillLightObservation.cancel()
        fillLightMonitor.stop()
        syncMarker.close()
        closeSession()
    }
}

private fun WindowAreaCapability.Status?.toCapability(): DisplayCapability = when (this) {
    WindowAreaCapability.Status.WINDOW_AREA_STATUS_AVAILABLE -> DisplayCapability.AVAILABLE
    WindowAreaCapability.Status.WINDOW_AREA_STATUS_ACTIVE -> DisplayCapability.ACTIVE
    WindowAreaCapability.Status.WINDOW_AREA_STATUS_UNAVAILABLE -> DisplayCapability.UNAVAILABLE
    WindowAreaCapability.Status.WINDOW_AREA_STATUS_UNSUPPORTED, null -> DisplayCapability.UNSUPPORTED
    else -> DisplayCapability.UNKNOWN
}

/**
 * Forwards every operator state to the subject window. A key whitelist here once left the cover
 * with a stale subjectFraming (Razr U8: "out of frame" forever while the HAL saw the face) and a
 * frozen audio meter, so nothing is filtered: the window reads whatever fields it needs.
 */
@Composable
internal fun SubjectStateForwarder(state: CameraUiState, onState: (CameraUiState) -> Unit) {
    LaunchedEffect(state) { onState(state) }
}

/**
 * Brightness request for the activity window while it is transferred to the cover, or null when this
 * activity is not there (inner screen, or a presentation that carries its own window).
 */
internal fun transferBrightnessRequest(state: FoldDisplayState, brightness: Float): Float? =
    brightness.takeIf { state.phase == DisplaySessionPhase.ACTIVE && state.operation == DisplayOperation.TRANSFER }

