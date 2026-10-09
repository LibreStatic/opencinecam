# Capture screen frame drops: diagnosis

Status: fixes 1, 2 and 3 are implemented on `perf/capture-jank`. Fix 4 (onboarding) was left out by decision. Fix 5 (R8) was first reverted because the minified build crashed when a scope was toggled. The cause is an ART JIT bug, now worked around, and R8 is enabled (see "R8 and the scope crash").

## Symptom

No user report or video was supplied. The symptom comes from an emulator survey.

- Opening the mode sheet visibly hitches.
- The capture screen redraws continuously while nothing on it changes.
- The onboarding never stops animating.

## How it was measured

- **Build.** The `benchmark` build type: non-debuggable, `profileable`, not minified. This is the same code configuration as the Play release, which is also not minified (`app/build.gradle.kts:55`).
  - It was built from a separate worktree at `a28c0d6` (0.3.2-beta).
  - The only changes in that worktree are an `applicationIdSuffix`, debug signing, and log probes. None of them are in this repository.
- **Device.** Pixel 9 Pro emulator, API 36.1, 60 Hz, so the budget is 16.7 ms per frame.
- **Tools.**
  - `dumpsys gfxinfo framestats`, three runs per interaction.
  - Perfetto, with ftrace sched, atrace (`gfx view input am wm dalvik`), and frametimeline.
  - `trace_processor` SQL over the Perfetto traces.
- **Control.** Other builds were running on the host, so every block was measured next to a control: scrolling the system Settings app. Only quiet-host data is used below, where the load was about 5–10 and the control's p50 was 16 ms.
- **Composition tracing.** I tried `runtime-tracing` with `tracing-perfetto`. It does not turn on in a non-debuggable app (the receiver returns 0), so composables are attributed from the source and from `JANKDIAG` probes, not from named trace sections.

## Frame table (quiet host)

These are Perfetto main-thread `Choreographer#doFrame` durations from `t3`, taken on a cold launch with animations at 1x. The window for each interaction is 3 s.

| Interaction | Frames | Worst ms | > 16.7 ms | Avg ms |
|---|---|---|---|---|
| Capture idle (5 s) | 151 | 33.9 | 9 | 4.4 |
| Mode sheet open #1 | 82 | **112.2** | 19 | 12.7 |
| Mode sheet close #1 | 97 | 24.5 | 4 | 4.4 |
| Mode sheet open #2 | 90 | **84.8** (and 53.3 next) | 26 | 11.8 |
| Mode sheet close #2 | 104 | 25.7 | 3 | 4.1 |
| FOCUS panel open | 69 | 32.1 | 6 | 4.6 |
| FOCUS panel close | 71 | 10.9 | 0 | 2.1 |
| MONITOR panel open | 89 | 19.2 | 4 | 3.3 |
| MONITOR panel close | 74 | 13.6 | 0 | 2.1 |

The gfxinfo survey covers the full frame, including the RenderThread. Its quiet-host results agree with the table:

- Mode sheet open: worst 278–722 ms.
- Mode sheet close: worst 134–222 ms.
- Capture idle: 159–170 frames in 3 s, p50 26–32 ms.
- Onboarding idle: 327–335 frames in 3 s, p50 19–20 ms.
- Settings, Gallery and About: correctly static at idle (0 frames).

The emulator's RenderThread is inflated by the host GPU, so the gfxinfo worst frames are higher than a device would show. The main-thread numbers above are the reliable part.

Earlier, on a loaded host, the mode sheet close and the FOCUS and MONITOR panels hit 100–400 ms. On a quiet host they stay at 1–2 frames over budget, so they are load-sensitive rather than structural. The mode sheet open is the only interaction that hitches on a quiet host.

## Trace numbers

### Mode sheet open

The 112 ms first frame breaks down like this:

| Slice | ms |
|---|---|
| `traversal` | 101.3 |
| `VRI relayoutWindow#first=true` (a new window) | 18.9 |
| `draw-VRI` (first draw of that window) | 32.9 |
| `measure` + `layout` | 9.9 |
| `Recomposer:recompose` + `animation` | 10.8 + 10.8 |
| `Compose:applyChanges` | 4.4 |

The second open has the same shape: 84.8 ms, with `relayoutWindow#first=true` and 37.7 ms of draw, followed by another 53 ms frame.

Composition is a small share of the cost. Most of it is creating, laying out and first-drawing a **new dialog window**.

### Capture idle

Over 5 s, nothing on screen changes:

- 151 `doFrame`, about 30 per second.
- 115 `Recomposer:recompose`, about 23 per second.
- 756 recomposed scopes, about 151 per second.
- 666 ms of main-thread frame work, about 13% of the main thread.

An earlier 10 s trace (`t2`) showed the same rate: 194 recomposes and 1284 scopes. The `JANKDIAG` probe logged `CameraUiState` emissions at about 6 Hz:

- the analysis fields at 4 Hz;
- the metadata fields at 2 Hz.

## Causes, ranked by impact

1. **The mode sheet is a `ModalBottomSheet`, which is a dialog window built in one frame.**
   - `CaptureModeSheet.kt:149` builds the sheet, and `CameraScreen.kt:2226` composes it with `if (modeSheet) CaptureModeBottomSheet(…)`.
   - Opening it creates a window (`relayoutWindow#first=true`), composes the sheet and its content, measures it, and does the window's first draw, all in one frame: 85–112 ms on the main thread.
   - This is the classic "construct everything in one frame" pattern, made worse by the extra window.

2. **The whole capture chrome recomposes about 4 times a second for nothing.**
   - `rememberScopeAnalysisFresh` (`CameraScreen.kt:1076-1086`) runs a `while (true) { delay(250) }` clock and writes it to a `mutableStateOf` that the function reads.
   - Because the function returns a value, it has no restart scope of its own, so the clock invalidates the caller's scope.
   - It is called in three places:
     - `CameraScreen.kt:939`, in `MonitoringOverlay`.
     - `CameraScreen.kt:1872`, inside the content lambda of the chrome's `BoxWithConstraints` (`CameraScreen.kt:1385`). That lambda covers roughly lines 1385–2228, so the **entire chrome** recomposes every 250 ms.
     - `CameraScreen.kt:2505`, in the instrument stack.
   - The clock ticks even when no scope is shown and when the result cannot change.

3. **Every `CameraUiState` emission recomposes every child that takes `state`.**
   - `CameraUiState` (`CameraUiState.kt:55`) is a single data class that also carries live data: the analysis at 4 Hz (`service/CaptureService.kt:1138-1139`) and the metadata at 2 Hz (`service/CaptureService.kt:884-907`, `METADATA_PERIOD_MS = 500`).
   - Each emission is a new instance, so strong skipping cannot skip any composable that receives `state`, and most chrome children do.
   - Together with cause 2, this explains the 23 recompositions and 151 scopes per second at idle.

4. **The onboarding backdrop never idles.**
   - `OnboardingBackdrop.kt:164-166` runs `while (true) withFrameNanos { … }` while the screen is shown and reduced motion is off.
   - That drives about 110 submitted frames per second, all over budget on the emulator.
   - It is intentional ambient motion on a one-time screen, so it costs battery and heat more than it causes visible jank.

## Checked and not a cause

- **Springs without a `visibilityThreshold`.** `OnboardingScreen.kt:957` and `:959` animate a `Float` `Animatable`, which already has a sensible default threshold. The IntOffset spring at `:606` sets one. No animation was seen failing to settle.
- **`MotionScheme.expressive()`** (`OpenCineCamTheme.kt:219`). No long-running spring showed up in the traces after interactions.
- **Predictive back.** The app targets SDK 37, where the system back animation is on by default. Panels use `BackHandler`, which gives no progress animation; that is cosmetic, not jank.
- **Measurement.** Nested `BoxWithConstraints` (`CameraScreen.kt:571`, `653`, `1385`) shows only as 5–11 ms of measure in the sheet-open frame. It matters here mainly because its content lambda is the scope that cause 2 invalidates.
- **Bottom navigation, gallery, settings, About, and photo capture.** All were within 1–2 frames of the control.

## Proposed fixes, ranked by impact

1. **Stop the clock from invalidating the chrome** (cause 2). This is small and low-risk.
   - Compute the deadline when the current analysis sample goes stale, and `delay` until that deadline instead of ticking every 250 ms.
   - Skip the loop entirely when no scope or overlay needs the result.
   - Expose the result as a `State<Boolean>` read only where it is used, so a flip recomposes the scopes panel and not the whole `BoxWithConstraints` content.
   - One shared clock replaces the three.
   - Expected result: idle recompositions drop from about 23/s to roughly the `CameraUiState` emission rate (6/s).

2. **Build the mode sheet without a new window, and avoid building it all in one frame** (cause 1).
   - Option A, recommended: render the compact-portrait modes in-window, as an overlay sheet in the chrome with `AnimatedVisibility`, a scrim, and `BackHandler`/Esc. It reuses `modesContent` and keeps the current look. The docked pane already shows the same content in-window on larger layouts.
   - Option B, smaller: keep `ModalBottomSheet`, but keep it composed with `SheetValue.Hidden` instead of `if (modeSheet)`. This removes the composition from the tap frame but not the window creation, so it gives less gain.
   - Expected result: the open frame drops from 85–112 ms to near the panel opens, about 20–30 ms.

3. **Narrow what each `CameraUiState` emission recomposes** (cause 3). This is a medium-sized refactor.
   - Pass the high-rate fields (analysis, metadata readouts, audio levels) as lambdas or `State` to the composables that display them, or split them into their own `StateFlow`.
   - Then the chrome's slots, rails and panels can skip.
   - It touches the service-to-UI contract, so it should be its own change after fix 1, with tests.

4. **Throttle the onboarding backdrop** (cause 4). Cap the loop at about 30 fps, or pause it while the page is idle. Low priority.

5. **Separately: enable R8 minification for release** (`app/build.gradle.kts`).
   - This is a general speed-up for Compose code. It needed no extra keep rules, but it exposed an ART JIT bug, described in "R8 and the scope crash" below.
   - It is now enabled for release and for the benchmark build.

## How to verify

Use the same benchmark build, the same emulator and the same procedure, with a Settings-scroll control before each block.

1. **Idle.** Record a 5 s Perfetto trace on capture idle and count the main-thread `doFrame`, `Recomposer:recompose` and `Compose:recompose` slices. The target for fix 1 is ≤ 8 recompositions per second, down from 23.
2. **Sheet.** Open and close the mode sheet twice in a trace. Check that the worst open frame and the `relayoutWindow#first=true` slice are gone (fix 2A) or smaller (fix 2B).
3. **gfxinfo.** Run three gfxinfo runs per interaction, compared with the survey table in `S1`.
4. **Regression.** Run `:app:testDebugUnitTest`. Use Compose Driver `CaptureModeSheet` and `CapturePortrait` to check the sheet's look and Back/Esc behaviour.
5. **Not verifiable here.** 120 Hz behaviour, real GPU draw cost, and thermal effects need the Razr.

## Results

The fixes were measured with the same procedure, one at a time and in combination: the same emulator, the same benchmark build setup, cold launch, and a Perfetto trace of the fixed scenario. Each build was measured twice, next to the Settings-scroll control, whose p50 was 16 ms in every block.

In the table:
- **Idle** is per 5 s: frames, recomposed scopes, and the worst main-thread frame.
- **Sheet open** is the worst main-thread `doFrame` per open.
- **Panels** are the worst frame among the FOCUS and MONITOR open and close.

| Build | Idle frames | Idle scopes | Idle worst ms | Sheet open worst ms | Sheet close worst ms | Panels worst ms |
|---|---|---|---|---|---|---|
| Baseline (a28c0d6) | 153 / 112 | 756 / 707 | 38 / 138 | 107, 94 / 120, 120 | 26, 22 / 37, 23 | 34 / 55 |
| + Fix 1 (freshness clock) | 107 / 98 | 694 / 622 | 29 / 26 | 120, 86 / 97, 60 | 52 / 34 | 40 / 32 |
| + Fix 2 (in-window sheet) | 108 / 117 | 697 / 728 | 44 / 91 | 79, 46 / 89, 58 | 72 / 56 | 146 / 109 |
| + Fix 3 (live-state split) | 82 / 84 | 108 / 112 | 4 / 28 | 41, 22 / 26, 22 | 7 / 127 | 54 / 305 |
| + Fix 5 (R8) | 84 / 80 | 108 / 104 | 5 / 10 | 20, 14 / 20, 18 | 4 / 6 | 30 / 25 |

Notes on the data:

- **Fix 2.** Its row was measured while a Gradle compile was running on the host, which shows in its control (p90 22 ms). The structural result does not depend on that noise: the sheet-open frames no longer contain `relayoutWindow#first=true`. That slice appeared in every baseline open and in none after the fix.
- **Fix 3, run 2.** Its 305 ms and 127 ms frames coincide with a host-load spike and do not reproduce in the R8 runs.
- **With fixes 1–3 (what ships):**
  - idle recomposition drops by about 85% (scopes 756 → 108 per 5 s);
  - the idle frame rate falls from 112–153 to about 83 per 5 s;
  - the sheet opens in 22–41 ms, down from 94–120 ms.
- **With R8 as well,** every interaction stayed at or under 30 ms and the x86_64 APK shrank from 39 MB to 8 MB. The first R8 build crashed; see below.

### R8 and the scope crash

The first R8 build crashed when a scope (waveform, vectorscope or false colour) was toggled: `IllegalArgumentException: Failed requirement` on the `OpenCineCamImage` thread, in `MonitoringScopeFrame.<init>` (`camera/.../MonitoringAnalysis.kt`) called from `analyzeMonitoringRgb`. The failing check was the second `init` block's repeat of the first block's size check (`sampledWidth > 0 && … && sampledWidth.toLong() * sampledHeight == sampleCount.toLong()`).

**Cause: an ART optimizing-JIT bug, triggered by a code shape R8 produces.**

1. R8 9.3.16 (AGP 9.3.1) merged `MonitoringScopeFrame` horizontally with Media3 and Compose classes into one class, and common-subexpression elimination made the second check reuse the first check's `cmp-long` result (register v12). The second check also had calls between it and the first (`toList`, `unmodifiableList`). The bytecode is correct.
2. ART's `InstructionSimplifier` folds the `HCompare` into the `HCondition` that tests it and then calls `RemoveEnvironmentUsers()` on the compare (`compiler/optimizing/instruction_simplifier.cc`, `VisitCondition`). The guard `HasAnyEnvironmentUseBefore` only looks for deoptimization points before that first condition, not between it and a later reuse.
3. So deoptimization points after the first check (the inlined calls) no longer record v12. When the JIT code deopts there ("Single-frame deopting … due to JIT inline cache"), the interpreter resumes with v12 holding ART's dead-value filler instead of 0, and `if-nez v12` throws.

**Evidence** (emulator, Android 16 `BE4B.251210.005`, x86_64):

- The R8 APK crashes on the first toggle after warm-up when it runs under the JIT (`cmd package compile -m verify -f`). Compiled AOT with `-m speed` (no inline-cache deopts), it survives 8 rounds in two runs.
- A standalone repro calling the merged constructor through reflection under `dalvikvm64` fails 3/3. It passes with `-Xusejit:false`, and it passes 3/3 with `-Xcompiler-option --inline-max-code-units=0` (no inlined calls, so no deopt point inside the window).
- `--dump-cfg` of the JIT-compiled `<init>` shows v12 in every environment until `instruction_simplifier$after_gvn`, where it becomes `_` (dropped) in the environments of the later inlined calls.
- `-verbose:deopt` shows the inline-cache deopt in `<init>` right before the throw.

**Fix.** The second `init` no longer repeats the dimension check; the first `init` already enforces it before any field is set. With no second test of the same compare, R8 has nothing to reuse. The fixed APK passes the `dalvikvm64` repro 3/3 while still deopting at the same place, and the app survives 10 rounds of toggles, three cold relaunches with three rounds each, and the Monitor > Show scopes > rail-toggle path. `MonitoringAnalysisTest` still covers mismatched dimensions.

**Release regression pass** (benchmark build with R8, emulator, clean install): onboarding with every permission, photo, burst, F1–F3, the Monitor pane, scopes (enlarge, hide, toggle), switch camera, Displays and foldables, every Settings category and search, About with the license list, the gallery (filters, refresh, saved proxies, review with next/previous file and take, details), relaunch after force-stop. No crash and no R8 linkage error (`ClassNotFoundException`, `NoSuchMethodError` and so on). RAW photo and video fail the same way on the debug build without R8, because the emulator lacks the metadata and the AVC encoder; they were checked on the Razr (below).

**On the Razr** (motorola razr fold, Android 16 `W3WB36.36-123-2`, arm64, ART module 372042580, newer than the emulator's 361153460):

- An arm64 build with the repeated check restored fails the `dalvikvm64` repro 3/3 at the same round, and passes with `-Xusejit:false` and with inlining off. The bug is in ART, not in the emulator image.
- The fixed build passes the repro 3/3.
- The fixed R8 app, installed beside the Play build under a temporary package name and forced to JIT (`cmd package compile -m verify -f`), survived 45 scope toggles in photo and video mode. Photo, RAW (DNG), a 5-frame burst, a 20 s AVC video, a 10 s Log take (HEVC Main 10, BT.2020), video playback and clip details, Settings and Displays and foldables all worked, with no crash.

**Residual risk.**

- The ART bug applies to any app and any compare result reused across a deopt point; it is not specific to this class. A scan of the release dex found 80 places where one compare result feeds two or more branches, 28 of them with a call in between. Most are in libraries (Media3, Compose, Kotlin, Guava `LongMath`).
- Ours are `CameraCapabilityAudit.kt:110`, `Mp4AacSourceWindow.kt:45, 233, 344` and `SubjectPreviewPort.kt:23`. A wrong branch there would weaken a check or affect a display, and they run rarely, so they are unlikely to reach optimized JIT code. They were left alone.
- Code compiled AOT has no inline-cache deopts, so it did not crash here; JIT-compiled code is exposed. ART updates through its Mainline module, and a newer module (below) still has the bug.

### What changed

1. **The scope-freshness check** (`rememberScopeAnalysisFresh`, `CameraScreen.kt`) waits for the moment the current sample goes stale instead of ticking every 250 ms.
2. **The compact mode sheet** (`CaptureModeSheet`, `CaptureModeSheet.kt`) draws in the activity window:
   - a scrim, a slide-in sheet with a handle, drag-down to dismiss, and pane-title semantics;
   - Back and Esc keep going through the chrome's existing handlers;
   - `modesContent` was hoisted so the sheet can sit outside the inset chrome box and reach the screen edges.
3. **The screen root observes `CameraUiState.withoutLiveSamples()`**, a structural-equality `derivedStateOf`. The full state is provided through `LocalLiveCameraState`, and only the leaves that draw samples read it, through `liveCameraState(state)`:
   - the monitoring overlay, histogram and scopes;
   - the instrument stack;
   - the audio meters and audio settings.

   The cover display still receives every sample, through a `snapshotFlow` outside composition.

### Not verified

- 120 Hz behaviour, real GPU draw cost and thermal effects on the Razr.
- A real camera's analysis, where the histograms, zebra and peaking change on every sample. The design covers them, but only the emulator's virtual scene was measured.
- Predictive back and the sheet's look on a physical device with real insets.
