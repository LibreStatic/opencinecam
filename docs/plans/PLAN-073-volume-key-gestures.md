---
plan_id: OCC-PLAN-073
title: "Volume-key gestures and −/+ pairs"
status: InProgress
revision: 1
milestone: H3
intended_executor: Claude Code
execution_mode: implementation
depends_on:
  - OCC-PLAN-065
blocks: []
requirements:
  - OCC-PRO-006
  - OCC-UI-002
adrs:
  - ADR-0030
risks:
  - RISK-020
estimated_sessions: 2
expected_repo_state: buildable
created_by: Claude Code
---

# OCC-PLAN-073: Volume-key gestures and −/+ pairs

## 1. Objective

Let the volume keys run the camera the way stock camera apps do. Each key can have a press, a hold and a double press action, and both keys together can have one more. Instead, the two keys can work as a −/+ pair for zoom, EV, ISO, shutter or manual focus: a press moves one step and a hold keeps changing the value.

Before this plan, each key had one `OperatorAction` that fired once on key-down (`OperatorKeyLatch`).

## 2. Why This Plan Exists

Each volume key had a single action fired on key-down, while stock camera apps let a press, a hold and a double press do different things and let the keys step a value. OCC-PRO-006 asks for programmable controls; this plan extends the volume keys to zoom, exposure and focus without losing native volume where the operator keeps it.

## 3. Prerequisites

- OCC-PLAN-065 (professional control intent and operation): `OperatorAction`, `OperatorPreferences` and the exposure and focus intents.
- An emulator for the instrumented test and the Razr Fold for the device checks. Ask the user to connect the device before every device step; assume it is disconnected.

## 4. Required Reading

ADR-0030 (shared settings), PLAN-065, `MainActivity` key handling, `ZoomMath.rockerFactor`, `ExposureAvailability.kt`, `AGENTS.md` (Compose Driver screen `SettingsVolumeKeys`).

## 5. Inputs

`OperatorKeyLatch` (replaced) and its eligibility rule, `ViewConfiguration` long-press and double-tap timeouts, the exposure slider's ISO and shutter logic, `setZoomRatio` and the lens lock, the operator haptics (ZoomAnchor, Limit), and preset version 26.

## 6. Deliverables

- `VolumeKeyGestures` with `VolumeKeyScheduler`, wired in `MainActivity`.
- Pair stepping for zoom, EV, ISO, shutter and focus, with `exposureWithIso` and `exposureWithShutter` extracted into `ExposureAvailability.kt`.
- The new `OperatorPreferences` fields, preset version 27, and the Operator controls rows.
- The tests of section 13 and the Compose Driver screen `SettingsVolumeKeys`.

## 7. In Scope

Volume up and down while the capture activity is focused and resumed: per-key press, hold and double press actions, a Both keys action, and −/+ pair mode for zoom, EV, ISO, shutter and manual focus; their settings and presets.

## 8. Out of Scope

Other hardware keys; volume behaviour outside the capture screen (the gallery and playback keep it unchanged, section 16); key handling while the soft keyboard is shown or the window is not focused.

## 9. Architecture

`MainActivity` forwards volume key events to `VolumeKeyGestures`, a state machine with no Android in it; it returns whether the key is consumed and dispatches `OperatorAction`s or pair steps. Pair steps go through the same intents as the on-screen controls.

### Decisions

1. **One engine, no Android in it.** `VolumeKeyGestures` is a state machine with an injected clock and scheduler (`VolumeKeyScheduler`). `MainActivity` backs the scheduler with `Handler(mainLooper)` and takes its thresholds from `ViewConfiguration` (long-press and double-tap timeouts). It replaces `OperatorKeyLatch` and keeps the latch's eligibility rule: no soft keyboard, window focused, RESUMED, and a mapping installed.
2. **A key that only presses keeps zero latency.** In action mode, a key with no hold or double action, while Both keys is None, fires on key-down as before. If its press is System volume, the key is not consumed at all, so native volume and its repeat keep working.
3. **Otherwise the single press waits.**
   - A hold fires at the threshold, while the key is still down, with a haptic. Its release adds nothing.
   - With a double action set, a release opens the double-tap window. A second down fires the double action; the window's timeout fires the press.
   - With no double action, the press fires on key-up.
   - A consumed System volume press is emulated with `AudioManager.adjustSuggestedStreamVolume(…, USE_DEFAULT_STREAM_TYPE, FLAG_SHOW_UI)`, one step per press.
   - A pending single press is fired at once when the other key goes down.
4. **Both keys.** The second key going down while the first is held, and the first has not acted yet, fires Both keys. It also stops any running loop and swallows both keys until both are up. In pair mode the first key's step has already happened; that is accepted.
5. **Pair mode.** A key-down steps once at once.
   - After the long-press timeout the step repeats until key-up: zoom every 16 ms through `ZoomMath.rockerFactor` (about one octave per second), the others every 150 ms.
   - Up means zoom in, EV +, ISO up, a longer shutter (brighter) and focus farther. *Invert direction* swaps the keys.
   - The per-key press, hold and double actions are hidden and ignored in pair mode.
6. **Steps.**
   - **Zoom:** a press moves a third of a stop and stops on the first optical anchor it crosses. A hold feels like the on-screen rocker. Zoom goes through `setZoomRatio`, which honours the lens lock.
   - **EV:** one compensation index.
   - **ISO and shutter:** a third of a stop, through the same intent the exposure slider sets. The slider's logic was extracted (`exposureWithIso`, `exposureWithShutter` in `ExposureAvailability.kt`) and the slider now calls it too. From auto, the first step starts from the value the camera reports. An angle steps within 0.1° to 360°.
   - **Focus:** a fortieth of the lens' diopter range. With AF on, the first step starts from the distance the lens reports.
   - Haptics: ZoomAnchor when a zoom reaches an anchor, Limit at either end.
7. **Unavailable steps.** New reasons: `NO_ZOOM`, `NO_EV`, `NO_MANUAL_EXPOSURE` and `NO_MANUAL_FOCUS`. Existing reasons are reused: LOCKED, NOT_READY, SELF_MINIMAL and HIGH_SPEED (ISO and shutter in a constrained high-speed session). The toast shows at most once per press. When the first step cannot act, the hold loop never starts.
8. **Settings.**
   - New `OperatorPreferences` fields: `volumeUpLong`, `volumeDownLong`, `volumeUpDouble`, `volumeDownDouble`, `volumeBoth`, `volumePair` and `volumePairInverted`.
   - The gesture fields are never System volume: `init` rejects it, and a stored value loads as None.
   - Presets move to version 27. Older presets still decode, to the defaults.
9. **Settings UI** (Controls › Operator controls):
   - a *Volume keys* pill row: Separate actions, Zoom, EV, ISO, Shutter, Focus;
   - Invert direction in pair mode;
   - Press, Hold and Double press per key in action mode;
   - Both keys in both modes;
   - one help line about the latency hold and double press add.

   Hold and Double press rows are always shown; a press left on System volume then moves the volume itself, one step per press.

## 10. Implementation Steps

1. Gesture engine (`VolumeKeyGestures`, `VolumeKeyScheduler`) replacing `OperatorKeyLatch`, with `MainActivity` backing the scheduler (decisions 1 to 4).
2. Pair mode and steps (decisions 5 to 7), extracting the slider's exposure logic so the slider and the keys share it.
3. Settings and presets (decision 8): `OperatorPreferences` fields, preset version 27.
4. Settings UI (decision 9), strings and the `SettingsVolumeKeys` driver screen.
5. Tests (section 13), then the emulator and Razr checks (section 16).

## 11. State and Data

New `OperatorPreferences` fields `volumeUpLong`, `volumeDownLong`, `volumeUpDouble`, `volumeDownDouble`, `volumeBoth`, `volumePair` and `volumePairInverted`; the gesture fields are never System volume (`init` rejects it, a stored value loads as None). Presets move to version 27 and older presets decode to the defaults. In memory: the per-key gesture state, the pending single press, the double-tap window and the hold loop.

## 12. Failure Handling

An ineligible key (soft keyboard shown, window not focused, not RESUMED, no mapping) is never consumed. A step that cannot act shows its reason (`NO_ZOOM`, `NO_EV`, `NO_MANUAL_EXPOSURE`, `NO_MANUAL_FOCUS`, LOCKED, NOT_READY, SELF_MINIMAL, HIGH_SPEED) at most once per press, and the hold loop never starts. A press left on System volume passes through or is emulated with `adjustSuggestedStreamVolume`.

## 13. Tests

- `VolumeKeyGesturesTest`: immediate press, repeats ignored, hold at the threshold, double press, single press after the window, both keys, the pair loop and its stop, System volume passed through or emulated, `clear()`, and ineligible keys never consumed.
- `VolumeKeyPairsTest`: zoom third-stops, anchor snapping and clamping; EV clamping; ISO and shutter from auto, manual kept, angle stepping and clamping; focus seeding and clamping; every unavailable reason.
- `OperatorPreferencesTest`, `CameraPresetTest` (v27 round trip, v26 decoding) and `SettingsCatalogTest`.
- `OperatorActivityTest` (instrumented): a hold and a double press with real key events.
- Compose Driver screen `SettingsVolumeKeys`.

## 14. Documentation Updates

This plan, `AGENTS.md` (the `SettingsVolumeKeys` screen), `SettingsHelp` and strings in six locales, `docs/plans/manifest.yaml` and `docs/plans/README.md`.

## 15. Commands to Run

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk ANDROID_HOME=$HOME/Android/Sdk
flock ~/.cache/claude-tmp/opencinecam/gradle.lock ./gradlew --no-daemon -Dorg.gradle.parallel=false --max-workers=2 -Dorg.gradle.caching=false :app:testDebugUnitTest --tests '*VolumeKey*' --tests '*OperatorPreferencesTest' --tests '*CameraPresetTest' --tests '*SettingsCatalogTest'
flock ~/.cache/claude-tmp/opencinecam/gradle.lock ./gradlew --no-daemon -Dorg.gradle.parallel=false --max-workers=2 -Dorg.gradle.caching=false :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.librestatic.opencinecam.OperatorActivityTest
./tools/check_format.sh
python3 tools/validate_plan_system.py
tools/compose-driver.sh start SettingsVolumeKeys
```

## 16. Acceptance Criteria

- [ ] Every unit test above passes, and the instrumented test passes on an emulator.
- [ ] On the Razr: a held zoom pair crosses lens switches smoothly.
- [ ] On the Razr: a hold mapped to Capture starts a video take.
- [ ] On the Razr: volume behaviour in the gallery and in playback is unchanged.

## 17. Evidence to Record

Unit and instrumented suite exit codes, the `SettingsVolumeKeys` tree and screenshot, and for the Razr checks the device fingerprint and a short note or recording per check.

- Evidence: none recorded yet; the emulator and Razr checks are open.

## 18. Rollback and Recovery

The defaults keep the previous behaviour: Separate actions, no hold, double or Both keys action, so each key fires on key-down as before (decision 2). The change can be reverted as one unit; presets of version 27 would then decode with the older reader's rules.

## 19. Risks and Mitigations

- Added latency on a key that waits for a hold or double press: a key that only presses keeps zero latency, and the help line explains the cost (decisions 2 and 9).
- Native volume lost by consuming keys (RISK-020): System volume passes through when nothing else is mapped, and is emulated one step per press otherwise.
- A pair hold running away: it stops on key-up, on Both keys, and never starts when the first step cannot act.

## 20. Completion Update

Not complete (status InProgress). The gesture engine, pair stepping, settings and tests merged to main as part of PR #4 (via PR #3) and shipped in OpenCineCam 2.0.0 (Google Play production, 2026-11-19). The emulator and Razr checks of section 16 remain open.

## 21. Execution Record

- Implemented on PR #3 and merged to main as part of PR #4 (b8456498, 2026-11-12).
- 2026-11-19 Shipped in OpenCineCam 2.0.0 (Google Play production).
