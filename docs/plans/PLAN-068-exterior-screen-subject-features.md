---
plan_id: OCC-PLAN-068
title: "Exterior screen subject features and Razr Fold qualification"
status: InProgress
revision: 3
milestone: H2
intended_executor: Claude Code (Opus 5.5 subagents, parallel waves)
execution_mode: implementation
depends_on:
  - OCC-PLAN-064
blocks: []
requirements:
  - OCC-PRO-003
  - OCC-PRO-004
  - OCC-PRO-009
adrs:
  - ADR-0031
risks:
  - RISK-004
estimated_sessions: 6
expected_repo_state: buildable
created_by: Claude Code
---
# OCC-PLAN-068: Exterior screen subject features and Razr Fold qualification

## 1. Objective

Make the exterior (cover) screen useful to the person being filmed. Add tally and countdown, a self-monitor preview, a fill light, take review, an interview question queue, a digital slate with a sync marker, and an out-of-frame warning. Finish by qualifying all subject roles, those from OCC-PLAN-064 and the new ones, on the physical Razr Fold.

## 2. Why This Plan Exists

The user selected these features on 2026-10-02 from a foldable feature review. OCC-PLAN-064 implemented STATUS, TELEPROMPTER and PREVIEW subject content, self-recording transfer, hinge panes and close policy, but it ran them only on an API 30 emulator. That emulator advertised no rear window area and no hinge sensor. The physical device is now attached and reports what those features need (section 5), By the user's decision, physical qualification runs last, as one pass covering the existing and new modes together.

## 3. Prerequisites

OCC-PLAN-064 source (the subject session, generation tokens, GPU subject output and self-recording timer). The H3 E13 independent subject LUT. The H4 production slate and timecode (`ProductionSlateSettings`, `TimecodeContinuation`). The H4 SDR viewer and OCLog2 playback for review.

## 4. Required Reading

ADR-0031; proposal sections FOLD-02, FOLD-03, FOLD-05 and 9.3; `FoldDisplayCoordinator.kt`, `FoldDisplayState.kt`, `FoldDisplaySettings.kt`, `SubjectCameraPreview.kt`, `ProductionSlate*.kt`, `MediaPlaybackDialog.kt`; memory notes on Razr device testing (install with `adb -s`, never `installDebug`).

## 5. Inputs

Device inventory taken on 2026-10-02 over adb (`192.168.0.250`, product `blanc_g`, model `motorola_razr_fold`, API 36):

| Fact | Observed value |
|---|---|
| Cover display | 1080×2520, 24–165 Hz, max 1500 nits (HDR caps), punch-hole top centre, faces the same side as the rear cameras |
| Inner display | 2232×2484, 24–120 Hz, punch-hole top right |
| Device states | CLOSED, TENT, STAND, LAPTOP, OPENED, REAR_DISPLAY_MODE, CONCURRENT_INNER_DEFAULT, REAR_DISPLAY_OUTER_DEFAULT, HALF_OPENED_INNER |
| App-accessible states | CLOSED, LAPTOP, OPENED, REAR_DISPLAY_MODE, CONCURRENT_INNER_DEFAULT, REAR_DISPLAY_OUTER_DEFAULT, HALF_OPENED_INNER (TENT and STAND are not) |
| Sensors | Public `android.sensor.hinge_angle` and a vendor `com.motorola.sensor.hinge_posture` |

`CONCURRENT_INNER_DEFAULT` suggests that simultaneous presentation is supported, and `REAR_DISPLAY_MODE` that transfer is. These are system state names, not WindowAreaController capabilities. U8 must confirm both through the public API.

## 6. Deliverables

Physical qualification evidence for the OCC-PLAN-064 roles. New subject modes FILL_LIGHT, REVIEW, INTERVIEW and SLATE, plus tally/countdown overlays and the self-monitor preview options. An optional capability-gated out-of-frame warning. Settings catalog entries for every new preference, with tests and localized strings.

## 7. In Scope

Units U1–U8 below, on the cover screen in presentation (dual) and transfer (self-recording) roles where each applies.

## 8. Out of Scope

A second camera opened for the subject, voice-driven prompter scrolling, pose generation, ML face models, HDMI outputs, TENT/STAND posture layouts (the states are not app accessible; they can be revisited from hinge angle later) and the hinge-as-gesture idea.

## 9. Architecture

Everything stays inside the ADR-0031 model: the Activity owns the presentation window, CaptureService owns capture, and subject content receives immutable status and preferences. New modes extend `SubjectDisplayMode`. Mode-specific preferences go in `SubjectDisplaySettings` with a backward-compatible persistence migration. The subject window never gains capture or settings actions. INTERVIEW advancing and REVIEW selection are operator commands that flow through the coordinator, like the existing operator cue.

### Execution model: Opus 5.5 subagents in parallel waves

The plan is executed by Claude Code subagents running Opus 5.5 (`model: opus`), parallelized as far as the shared files allow. The main session coordinates, reviews and merges. It does not write unit code itself.

| Wave | Agents (parallel) | Work |
|---|---|---|
| W0 | 1 | Foundation: every new `SubjectDisplayMode` value, all new `SubjectDisplaySettings` fields with defaults, the persistence migration (unknown mode → STATUS), settings-catalog entries and string keys in all locales, each new mode rendering a placeholder. This removes the merge hotspots before fan-out. |
| W1 | 6 | A: U1 + U2 (they share the subject overlay layer). B: U3. C: U4. D: U5. E: U6. F: U7. |
| W2 | 1 | Integration: merge the W1 branches in order A → F, resolve conflicts, run the full host, lint and emulator regression, and update docs. |
| W3 | main session | U8 on the Razr Fold with the user. |

Rules for the subagents:

- **Isolation.** Each W1 agent works in its own git worktree (`isolation: "worktree"`) from the W0 commit. It touches only its unit's new files plus the minimal hooks in the subject composable and coordinator. It does not rename or reformat shared code.
- **Self-contained prompts.** Each prompt includes this plan's section for its unit, the ADR-0031 constraints, the authorship rule (`--author="FacuM <facumo.fm@gmail.com>"`, no AI attribution trailers) and the host-test rules below.
- **Gradle runs in a queue.** The host must never run two Gradle invocations at once, because that exhausted the 46 GB host before. Every agent wraps each Gradle call in `flock ~/.cache/claude-tmp/opencinecam/gradle.lock`. Each call covers one module, with `--no-daemon -Dorg.gradle.parallel=false --max-workers=2 --rerun`. Agents write and review code in parallel and queue only for builds.
- **Emulator tests in a queue.** Instrumented tests use the single isolated test emulator under the same lock, never the Razr and never another project's emulator. Agents do not install on physical devices.
- **Memory check.** Before launching W1, the coordinator checks `free -h`. With less than about 8 GB available plus swap pressure, it lowers W1 to 3 concurrent agents (A, B, C, then D, E, F).
- **Report.** Each agent returns its branch, commits, tests run with their exit codes, and any open physical checks for U8.

## 10. Implementation Steps

### U1 — Self-monitor preview (user request)

PREVIEW already shows a clean image to the subject. U1 makes it work as a self-monitor:

- mirror on by default for the subject, independent of the file;
- optional bands marking the recorded area and aspect ratio, so the visible frame equals the recorded frame;
- an optional rule-of-thirds or safe-area guide;
- an optional small audio level meter;
- the stale-frame badge, kept as it is.

Overlays draw on the subject surface only and never reach the encoder.

### U2 — Tally and giant countdown

A full-bleed red tally border in every subject mode while the confirmed state is RECORDING. Amber marks preparation and finalization, never "saved". A full-screen countdown numeral shows during the timer or pre-roll. Each element can be toggled, and tally brightness follows the subject brightness request. The take state comes from the service, so a disappearing window never shows a stale REC.

### U3 — Fill light

A new FILL_LIGHT mode turns the cover into a uniform soft light:

- requested brightness up to 1.0 through the window attribute;
- colour temperature from 2700 to 6500 K, mapped to sRGB white, with an optional tint;
- a configurable timeout;
- dimming on `PowerManager` thermal status MODERATE or above, with a visible notice.

When the mode is turned on, the operator sees a suggestion to lock AE/AWB, because a light that changes during a take makes exposure and white balance drift. The app never locks them automatically. Requested brightness is labelled as a request, not measured luminance.

### U4 — Review

A new REVIEW mode lets the operator pick a take or photo on the inner screen and play it on the cover. Playback is muted by default, and LOG clips use the existing view transform. The gallery is never exposed automatically. Starting REC stops review and returns the subject to the previous live mode. Playback reuses the H4 viewer components, so it needs no second decoder pipeline when another one is already running.

### U5 — Interview question queue

A new INTERVIEW mode holds a bounded list of questions (at most 50 entries of 300 characters each, stored locally). The cover shows the current question in large type. The operator advances or goes back from a compact inner control, and the subject sees "n / N". The list is kept separate from the teleprompter script and reuses its font-size preference.

### U6 — Digital slate and sync marker

A new SLATE mode shows the project, scene, take, camera, reel and a running timecode, taken from `ProductionSlateSettings` and the timecode source. It is meant to be filmed by other cameras pointed at the subject. An optional sync marker at REC start shows a full-white frame on the cover and, when enabled, plays a short beep through the phone's own speaker. The beep also lands in this phone's recording, which gives an audio sync spike. Its monotonic time is written to the take sidecar. The beep is off by default because it alters the recorded audio. The slate never alters `takeNumber` numbering rules.

### U7 — Out-of-frame warning (capability-gated)

Where `STATISTICS_FACE_DETECT_MODE` SIMPLE or FULL is available on the active capture graph, an option warns on the subject screen and on the operator HUD when a face leaves the recorded area or none is found for a configurable time. Face rectangles stay in memory and are never persisted or written to metadata. The option is hidden or disabled when the capability is absent, and it is not offered on the HFR route if the graph rejects it.

### U8 — Physical qualification on the Razr Fold (last)

1. This step runs in the main session, not in a subagent, because the user handles the hardware. Before starting, ask the user to connect the Razr. Assume it is disconnected, and do not reuse the 2026-10-02 adb address. Re-detect the device with `adb devices -l` once the user confirms. Run `FoldDisplayProbeTest` on the device. Record the presentation and transfer capability, the window areas, the extension version and the hinge sensor.
2. Install the debug APK with `adb -s <razr> install -r`, then walk through manually, with the user handling the hardware:
   - STATUS, TELEPROMPTER and PREVIEW on the cover during a dual presentation, plus every U1–U7 mode and overlay;
   - self-recording transfer with 0/3/5/10 s timers, the minimal deck, lens switching and the microphone dialog;
   - a 20-cycle open/close rehearsal during a 10-minute take;
   - LOG with PREVIEW active;
   - the HFR route with the exterior active (CHS feeds non-encoder surfaces at about 30 fps, so expect reduced exterior cadence or an explicit rejection);
   - the stop-on-close policy using the real hinge sensor;
   - thermal state after 10 minutes of dual output.
3. Fix the defects found, adding tests where they reproduce. Device-side U1–U7 acceptance items are checked here.
4. Record each supported or unsupported branch in GOAL-PROGRESS.md and in the OCC-PLAN-064 acceptance list.

## 11. State and Data

New persistent preferences: tally/countdown toggles; self-monitor guides, bands and meter; fill-light Kelvin, tint and timeout; interview questions; slate fields shown and the sync marker/beep; the out-of-frame toggle and delay. Not persistent: the review selection, the current interview index during a session, and face data. The sync marker time goes into the sidecar.

## 12. Failure Handling

An unavailable presentation disables subject modes and offers transfer only if it is available. A thermal throttle dims the fill light before capture is affected. A review decoder failure returns the subject to STATUS. A missing face-detection capability hides U7. No subject-mode failure stops or alters a take.

## 13. Tests

- Host tests for the new mode models, persistence migration, Kelvin mapping, question bounds and the sync-marker sidecar field.
- UI tests for each subject mode at 200% font.
- Emulator presentation tests where a simulated window exists.
- Physical checklists for U1–U7 on the Razr Fold, run in U8 and recorded as NOT_RUN until then.

## 14. Documentation Updates

OCC-PRO-009, proposal FOLD-05 status, manifest, README, TRACEABILITY, GOAL-PROGRESS.md and the OCC-PLAN-064 acceptance boxes resolved by U8.

## 15. Commands to Run

```bash
rtk proxy env ANDROID_HOME=$HOME/Android/Sdk JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew --no-daemon --dependency-verification=strict :app:testDebugUnitTest :app:lintDebug
rtk proxy env ANDROID_HOME=$HOME/Android/Sdk ./gradlew --no-daemon :app:assembleDebug
adb -s <razr-serial> install -r app/build/outputs/apk/debug/app-debug.apk
rtk proxy ./tools/check_format.sh
python3 tools/validate_plan_system.py
```

## 16. Acceptance Criteria

- [ ] U1: the visible subject frame matches the recorded frame, mirroring is independent of the file, and no overlay pixel reaches the file.
- [ ] U2: tally is red only while the service confirms RECORDING and is readable from 3 m.
- [ ] U3: fill-light colour/brightness requests apply, and the thermal dim and timeout work.
- [ ] U4: review is muted by default, never automatic, and stops when REC starts.
- [ ] U5: the operator advances questions without touching the subject screen; the bounds are enforced.
- [ ] U6: the slate shows correct fields and timecode, and the sync marker time is in the sidecar, verified against the beep in the decoded audio.
- [ ] U7: present only with capability, warns within the configured delay, and persists no face data.
- [x] Every new preference is in the settings catalog and searchable (host catalog tests and SettingsHubTest, W2).
- [ ] U8: public capabilities recorded on the Razr Fold; every OCC-PLAN-064 and U1–U7 role is qualified or recorded as unsupported, with evidence.
- [ ] U8: 20 open/close cycles in a 10-minute take with no file cut, lens change or geometry change.

## 17. Evidence to Record

Device/firmware/API, the probe JSON, manual checklist results with photographs or screen recordings of both screens, thermal readings, decoded-audio sync offsets, test reports and source hashes under `build/implementation-plan068/`.

## 18. Rollback and Recovery

Each unit adds an enum value and preferences behind a migration. Rolling back a unit maps an unknown stored mode to STATUS. Closing any subject session never stops capture or deletes media.

## 19. Risks and Mitigations

- The public WindowAreaController may not expose what the device states suggest. Record that branch and fall back to transfer-only features.
- Fill light at full brightness heats the device during long takes. Mitigate with the thermal dim and the timeout.
- Beep sync pollutes the audio. It is off by default and documented.
- Face detection availability varies by graph. U7 is capability-gated.

## 20. Completion Update

Mark Done when all acceptance boxes pass on the physical device or are recorded as an explicit unsupported branch.

## 21. Execution Record

Created 2026-10-02 from the user's foldable feature selection. Revision 2 (same day, user decision): physical qualification moved last as U8, and execution assigned to parallel Opus 5.5 subagents in waves W0–W3.

Revision 3 (2026-10-02): W0–W2 done; status InProgress until U8.

- W0 foundation: `aaa260a` (modes, device-local preferences, unknown mode → STATUS) and `978ebda` (subject content, overlay and settings scaffold).
- W1 units, one worktree branch each: `b09ca6a` A (U1 + U2), `1834da2` B (U3), `dbfcd73` C (U4), `96196d9` D (U5), `b027576` E (U6), `e715246` F (U7).
- W2 integration: merge commits `5926464` (A), `76b2a18` (B), `7a0fbd5` (C), `1137b36` (D), `10e06cd` (E), `287a234` (F), then the integration fix commit `1aa1e92`. Conflicts were confined to `FoldDisplayCoordinator.kt` (C after B and E). Every unit's behaviour was kept: the subject composition nests the fill-light and review providers and receives the slate timecode rate and sync flash; `updateCameraState` feeds both the review controller and the sync marker; `ended`/`closeSession` stop review; `close` releases the fill-light monitor and the sync marker.
- Integration fixes: FILL_LIGHT drew its own countdown badge under the giant numeral, so the subject saw two counts; it now uses the shared badge rule. The cover review player waits, bounded to 1.5 s, until the closing inner viewer's `MediaPlaybackSession` has closed and retired its exact reader, so the two decoders do not overlap after "Show to subject". If the operator leaves another viewer open past that bound, both still run (a recorded limit).
- Review findings without change: the subject window still has no capture or settings action. `recordSubjectSyncMarker` only stores bounded evidence for the current take (rejected outside a take, once per take, never delays or stops it). With U7 off, `applyFaceDetect` leaves every builder untouched and `setSubjectFramingEnabled(false)` issues no request. With U7 on, the face-statistics key is added to the repeating request; the constrained high-speed graph never carries it, and a graph that ignores or fails it falls back without it. Face rectangles are reduced on the camera thread to a Boolean, an edge and a timestamp; nothing is logged, persisted or written to a sidecar. Z-order on the cover, bottom to top: mode content (including the full-bleed fill light), giant countdown, out-of-frame banner, tally border, sync flash. The operator interview control sits under the top bar in the preview pane and the out-of-frame chip sits in the top bar, so they do not overlap. Unknown stored modes still load as STATUS.
- Decisions: a paused take shows an amber tally, not red. The fill light uses the shared subject window brightness request, capped by its timeout/thermal policy. The review LOG view follows `previewViewAssist` (REC709 when on, flat otherwise). The slate shows dashes for timecode while idle. Presets do not export the new subject keys (device-local).
- Known limits: a REC start while REVIEW is on restores the previous mode in the same update, so a slate that was the previous mode does not fire the sync marker for that take. Out-of-frame banner and the fill-light thermal notice share the bottom centre and can overlap while both show. All cover output, brightness, decoder, thermal, audio and face-detect behaviour on real hardware is unqualified until U8.
- Verification (W2): see the test record below; physical acceptance items remain unchecked for U8.

### W2 verification record

All Gradle calls ran one at a time under `flock ~/.cache/claude-tmp/opencinecam/gradle.lock` with `--no-daemon -Dorg.gradle.parallel=false --max-workers=2`.

| Command | Exit | Result |
|---|---|---|
| `:core:model:test --rerun` | 0 | 53 tests, 0 failures |
| `:media:testDebugUnitTest --rerun` | 0 | 54 tests, 0 failures |
| `:camera:testDebugUnitTest --rerun` | 0 | 700 tests, 0 failures |
| `:app:testDebugUnitTest --rerun` | 0 | 1,231 tests, 0 failures |
| `:app:lintDebug` | 0 | 0 errors, 58 warnings, 2 hints; none in PLAN-068 files |
| `./tools/check_format.sh` | 0 | 1,135 files |
| `python3 tools/validate_plan_system.py` | 0 | 68 plans, traceability 100% |
| Instrumented, API 36 `Pixel_9_Pro` read-only emulator on port 5570 (`adb -s` install, `am instrument`) | 0 | 75 tests: Subject Overlay/FillLight/Review/Interview/Slate/OutOfFrame/Preview, FoldDisplay, FoldPane, SettingsHub, SettingsPersistence, CaptureAdaptive. 74 passed on the first run. |
| Rerun `SubjectReviewUiTest` after the test fix | 0 | 5 of 5 passed |
| `MediaPlaybackDeviceTest`, `MediaPlaybackExactSurfaceDeviceTest` (touched session) | 0 | 8 of 8 passed |

The single first-run failure was `SubjectReviewUiTest.operatorBarIsAbsentWithoutACoordinator`. It came from W1 C, not from the merge: the test expected the how-to text, but `SettingsHelp` starts collapsed. The test now opens the help before asserting. No physical device was used.

## 22. U8 consolidated physical checklist

Before anything else, ask the user to connect the Razr Fold. Assume it is disconnected, never reuse an earlier adb address, and re-detect it with `adb devices -l` after the user confirms. Install only with `adb -s <razr> install -r`; never `installDebug`. Record each item as PASS, FAIL (with the fix) or UNSUPPORTED (with evidence).

Platform (OCC-PLAN-064 carry-over):

- [ ] `FoldDisplayProbeTest` on the device: presentation and transfer capability, window areas, extension version, hinge sensor.
- [ ] Dual presentation with STATUS, TELEPROMPTER and PREVIEW on the cover.
- [ ] Self-recording transfer: 0/3/5/10 s timers, minimal deck, lens switching, microphone dialog.
- [ ] 20 open/close cycles during a 10-minute take: no file cut, lens change or geometry change.
- [ ] LOG with PREVIEW active; HFR with the exterior active (reduced cadence or explicit rejection).
- [ ] Stop-on-close policy with the real hinge sensor.
- [ ] Thermal state after 10 minutes of dual output.

U1/U2 overlays:

- [ ] Tally legible at 3 m; amber clearly distinct from red; red never stuck after stop.
- [ ] Recorded-area bands match the decoded file for VIDEO and LOG, portrait and landscape, anamorphic; about 1 px band alignment on the 720 px subject buffer.
- [ ] Guides and meter never appear in the file; mirror affects only the cover; meter reacts to sound.
- [ ] Giant countdown at 3/5/10 s with TalkBack; overlays realign after rotation or aspect change; tally clears the punch-hole.

U3 fill light:

- [ ] Kelvin and tint visibly correct against a reference swatch; brightness request honoured (or recorded as capped by the system).
- [ ] Timeout at 60 s and "turn back on"; thermal dim during a 20–30 minute 4K take with no take change; thermal listener released after leaving the mode.
- [ ] AE/AWB suggestion shown, never auto-locked; 200% font.

U4 review:

- [ ] "Show to subject" only during a presentation; SDR plays fitted and looping, no touch; no audio and other apps' audio not paused.
- [ ] OCLog2 709/flat matches the inner viewer; photo and DNG display.
- [ ] REC stops review and restores the mode; a still photo does not; stop bar works; a new session starts clean.
- [ ] Corrupt clip returns to STATUS; opening the same item inside stops the cover; no decoder overlap after "Show to subject"; 10-minute thermal.

U5 interview:

- [ ] "1 / 5" large and touch-inert on the cover; operator control placement in all layouts; buttons disable at the ends, with TalkBack.
- [ ] Advancing during REC causes no cut; 300 characters at 72 sp and 200% font fit; shrinking the list clamps the index; a new session starts at 1.
- [ ] More than 50 questions or 300 characters rejected; persistence; no control in transfer or other modes; legibility.

U6 slate and sync marker:

- [ ] Slate legible at 3 m by eye and on a second camera, at 100% and 200%.
- [ ] Timecode ticks and matches the sidecar first/last frame TC for RECORD_RUN, REGEN, FREE_RUN and 29.97 DF; dashes while idle; holds while paused.
- [ ] Flash about 3 frames, once per take, not on reconnect or countdown.
- [ ] Beep at 1 kHz present in the decoded audio near `beepOffsetFromTakeStartUs`; measure the offset; plays on the speaker with headphones connected.
- [ ] Media volume 0 records FAILED and the take is saved; no sidecar node when disarmed.

U7 out-of-frame:

- [ ] Face-detect modes per camera id (SIMPLE preferred).
- [ ] Warning after the delay on both screens for PREVIEW/VIDEO/LOG, clears on return; with 4:3 visible, a face outside the 16:9 recorded area counts as out.
- [ ] HFR shows unavailable and its request is untouched; a graph ignoring the key falls back.
- [ ] File, cadence and metadata identical with the option on and off for SDR, LOG and HFR, with no face fields.
- [ ] Arrows correct in all orientations and cameras; heat and battery impact; logcat contains no coordinates.
