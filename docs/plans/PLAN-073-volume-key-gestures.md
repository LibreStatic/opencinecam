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

## 2. Decisions

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

## 3. Tests

- `VolumeKeyGesturesTest`: immediate press, repeats ignored, hold at the threshold, double press, single press after the window, both keys, the pair loop and its stop, System volume passed through or emulated, `clear()`, and ineligible keys never consumed.
- `VolumeKeyPairsTest`: zoom third-stops, anchor snapping and clamping; EV clamping; ISO and shutter from auto, manual kept, angle stepping and clamping; focus seeding and clamping; every unavailable reason.
- `OperatorPreferencesTest`, `CameraPresetTest` (v27 round trip, v26 decoding) and `SettingsCatalogTest`.
- `OperatorActivityTest` (instrumented): a hold and a double press with real key events.
- Compose Driver screen `SettingsVolumeKeys`.

## 4. Acceptance

1. Every unit test above passes, and the instrumented test passes on an emulator.
2. On the Razr:
   - a held zoom pair crosses lens switches smoothly;
   - a hold mapped to Capture starts a video take;
   - volume behaviour in the gallery and in playback is unchanged.
