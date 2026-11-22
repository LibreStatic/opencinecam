---
plan_id: OCC-PLAN-076
title: "RAW video: manual crop"
status: InProgress
revision: 1
milestone: H3
intended_executor: Claude Code
execution_mode: implementation
depends_on:
  - OCC-PLAN-071
blocks: []
requirements:
  - OCC-RAW-001
  - OCC-CAM-001
adrs:
  - ADR-0019
risks:
  - RISK-001
  - RISK-004
estimated_sessions: 3
expected_repo_state: buildable
created_by: Claude Code
---

# OCC-PLAN-076: RAW video: manual crop

## 1. Objective

Let the operator place and size the window of the sensor a RAW video take records. A smaller window writes fewer bytes per second, so a take lasts longer and reaches rates the storage cannot hold at the full sensor: for example 428 × 240 at 120 fps for manual slow motion, where the camera delivers that rate.

Before this plan a RAW take recorded the full sensor or one of two fixed sizes, always centred.

## 2. Why This Plan Exists

RAW video is limited by the storage, not by the camera: the sensor always reads out in full and the recorder copies a window of each frame into the ring (PLAN-071 decision 5). The fixed list of sizes left most of that freedom unused. A window of any size and position needs no new session graph and no format change, only a way to draw it, a rule that says whether it can be recorded, and a guide that shows it.

## 3. Prerequisites

- OCC-PLAN-071: the RAW10 session, the recorder with its RAM ring, the qualification and the gate.
- The Razr Fold for the device checks. Ask the user to connect it before every device step.

## 4. Required Reading

PLAN-071 (decisions 5 and 14 to 16, the qualification), ADR-0019, `docs/formats/opencine-raw-v1.md` (the frame header's crop origin and the `crop` key), `AGENTS.md` (the `CaptureRawVideoCrop*` driver screens).

## 5. Inputs

`RawVideoCrop` and `RawVideoConfig`, `RawVideoConfigKeys`, the gate in `RawVideoGate.kt`, `RawQualificationStaging` (`sweepRuledOut`, the storage model), `RawQualificationRisk.estimateBurstSeconds`, the recorder's `arm`, `MonitoringOverlay` and its image rectangle, and the RES/FPS chooser `RawVideoConfigDial`.

## 6. Deliverables

- A configuration key with an optional origin, and `RawVideoCrop.snapped`.
- `rawVideoCustomVerdict`: the rate-based rule for a window of the operator's.
- The recorder, the service and the state carrying the window; `RawCropStore` for named windows.
- A RAW_VIDEO viewfinder with the RAW output's aspect.
- Qualification candidates at 90 and 120 fps.
- The guide over the viewfinder, the crop editor, the chooser's custom group and the Settings row.
- A key that enlarges the viewfinder to the window.
- The gallery inspector's crop row.
- The tests of section 13 and the Compose Driver screens.

## 7. In Scope

RAW video on the back cameras: a window of free size and position on the RAW10 output, at any rate the camera was shown to hold; windows saved by name on the phone; the guide, the editor and the enlarged view.

## 8. Out of Scope

- A sensor readout smaller than the full output. The camera still reads every row, so a window does not raise the rate the camera can reach.
- A change to the `.ocraw` format, to the preset version or to the qualification protocol version.
- Rates the camera cannot be asked for. The Razr Fold fails every capture at 240 fps and its camera service restarts, so 240 fps is never a candidate.
- Scaling. A window is a part of the sensor at full resolution.

## 9. Architecture

The configuration (`RawVideoConfig`: size, rate, encoding) stays the identity of a qualification record. The origin travels beside it as a `RawVideoCrop`, because it does not change the data rate. The service resolves the stored key to a configuration and a window, judges it with the records and the storage sweep of the destination in use, and arms the recorder with both. Every reader already takes the origin from the frame headers.

### Decisions

1. **The window.** Left and width are multiples of 4, so RAW10 packing groups stay whole. Top and height are even, so the colour filter phase is kept and the block-pack codec gets whole row pairs. The minimum is 128 × 96. `RawVideoCrop.snapped` brings any rectangle to the nearest window that meets this inside the sensor.
2. **The key.** `RawVideoConfigKeys` accepts an origin in the style of an X11 geometry: `1920x816+1080+1122@24`, with `+bp` after it for block-pack. Without an origin the window is centred, so every earlier key means what it meant. The canonical key of a centred window has no origin. The value lives in the existing preset key `raw-video-config`: an older version, or a sensor the window does not fit, reads it as invalid and falls back to Automatic.
3. **The verdict is by rate.** `rawVideoCustomVerdict` judges a window with what the qualification measured:
   - **Camera.** The rate needs a usable configuration, or one left out for storage alone. Otherwise the window is refused with that rate's reason (camera limit, heat, not qualified).
   - **Passed.** A usable, non-risky configuration at the same rate and encoding writes at least as many bytes per second. A reduced-rate mark is inherited from it.
   - **Risky.** No passed configuration covers it, but a risky one does, or the stored sweep does not rule it out. The burst comes from `RawQualificationRisk.estimateBurstSeconds` and must reach the minimum risky burst.
   - **Compressed.** A `+bp` window also needs a usable `+bp` configuration at that rate with at least as many pixels per second, because the cost of encoding grows with the pixels.
   - Anything else is refused for storage.
4. **Automatic never picks a window of the operator's,** and prefers a rate of 60 fps or less. A high-rate candidate is chosen by hand.
5. **An origin alone does not reopen the session.** A change of origin re-arms the ring; the session is rebuilt only when the size, the rate or the encoding changes (`RawVideoConfigKeys.sessionChanged`).
6. **Saved windows.** `RawCropStore` keeps up to 64 named windows in `filesDir/raw-crops.json`, each with the RAW output size it was drawn on. They stay on the phone and are not part of presets; the selection in use travels in the key.
7. **The viewfinder has the RAW output's aspect.** The RAW_VIDEO preview stream was the nearest 16:9 size, which shows only the central band of a 4:3 output. It is now the preview size of the output's aspect nearest 1080p (`rawPreviewSizeIndex`; 1440 × 1080 on the Razr Fold), so the picture is the whole recorded field and a window maps onto it linearly. The qualifier opens the same stream (`rawQualificationPreviewSize`). The protocol version does not move: the stream is lighter, and the Razr Fold holds 60.0 fps with either.
8. **High-rate candidates.** The qualification adds `1280x720@90` and `1280x720@120` (and their block-pack twins). They establish that the camera holds the rate under a light storage load; the operator's windows at those rates then follow decision 3.
   - A rate the camera cannot be asked for (no AE range contains it and the vendor frame-rate override does not apply) fails as the camera's limit without a session.
   - A phone qualified before this plan has no records for them. Nothing changes for it until *Qualify again*.
   - Measured on the Razr Fold with the full RAW10 output and the vendor override: 90 fps is clean; a request for 120 fps delivers about 92 with skipped frames.
9. **The guide.** In RAW video with a window smaller than the output, the viewfinder dims the outside and frames the window. It also marks the fixed centre crops, which had no mark.
10. **The editor.** Opened from the RES/FPS panel: the window is dragged and resized on the picture, with aspect shortcuts (free, 16:9, 2:1, 2.39:1, 4:3, 1:1), the rates of decision 3, the compressed switch where block-pack is qualified, and a live line with the data rate, the saving against the full sensor and the verdict. It applies, saves under a name, or cancels. It is closed during a take.
11. **The enlarged view.** A key scales the viewfinder until the window fills it, for framing and focus. The recording is unchanged.

## 10. Implementation Steps

1. Model and gate: the key, `snapped`, `rawVideoCustomVerdict` (decisions 1 to 4).
2. Service and recorder: `arm` with a window, the resolved selection, the state, `RawCropStore` (decisions 5 and 6).
3. Viewfinder aspect and high-rate candidates (decisions 7 and 8).
4. Guide, editor, chooser and Settings, with the driver screens (decisions 9 and 10).
5. The enlarged view (decision 11).
6. Gallery inspector row and documentation.

## 11. State and Data

- `CameraSettings.rawVideoConfig` holds the key, origin included. No preset version change.
- `filesDir/raw-crops.json`: the saved windows (`id`, `name`, `sensorWidth`, `sensorHeight`, `left`, `top`, `width`, `height`), written atomically.
- `CameraUiState`: `rawVideoCrop` (the selected window on the RAW output), `rawVideoCustomRates`, `rawVideoSavedCrops`.
- `.ocraw`: unchanged. The origin is in each frame header and in the static `crop` key, as before.

## 12. Failure Handling

- A key that is not a writable window on the current sensor is not selected; the selection falls back to Automatic.
- A refused window cannot be applied; the editor says why.
- A damaged or missing `raw-crops.json` reads as empty and is left alone until the next save. An entry that is not a writable window on its own sensor is dropped on read.
- A risky window records with the existing risky notice and the ring's stop rule (ADR-0019).
- Losing a recording drive mid-take with *Continue on the phone*: the continuation is judged with the phone's records by the same verdict.

## 13. Tests

- `RawVideoMetadataTest`: `snapped`, alignment, bounds, minimum size.
- `RawVideoGateTest`: the verdict (passed, risky with a burst, each refusal, compressed windows), the custom rates, the high-rate candidates, and a record set without them.
- `CameraPresetTest`: a key with an origin round-trips; an invalid one falls back.
- `RawVideoRecorderTest`: an off-centre window in the frame headers and the static `crop`.
- `RawCropStoreTest`: save, rename, delete, damaged files, invalid entries, the limit.
- `FocusPeakingTest` and `RawVideoQualificationPlanTest`: the RAW preview size and its mirror; the plan with ten specs.
- `RawVideoSessionTest`: 90 and 120 fps accepted with the vendor override, refused without it.
- The crop geometry: screen and sensor round trips in every rotation, aspect lock, clamping.
- `RawVideoConfigKeysSessionTest`: a moved window keeps the camera session; a size, rate or encoding change does not.
- `GalleryTakeDetailsTest`: the Crop row of a take whose window is not the whole output.
- Compose Driver screens `CaptureRawVideoCrop`, `CaptureRawVideoCropSaved`, `CaptureRawVideoCropEditor`, `CaptureRawVideoCropRisky`, `CaptureRawVideoCropSlowMotion`, `CaptureRawVideoCropView`, `SettingsRawVideoCrop` and `GalleryRawCropInspector`.

## 14. Documentation Updates

This plan, PLAN-071 (decision 5), `docs/formats/opencine-raw-v1.md` (the origin's coordinate space), `AGENTS.md` (the driver screens), `docs/plans/manifest.yaml` and `docs/plans/README.md`, and the strings in every locale.

## 15. Commands to Run

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk ANDROID_HOME=$HOME/Android/Sdk
./gradlew --no-daemon -Dorg.gradle.parallel=false --max-workers=2 :camera:testDebugUnitTest
./gradlew --no-daemon -Dorg.gradle.parallel=false --max-workers=2 :app:testDebugUnitTest --tests '*RawVideo*' --tests '*RawCrop*' --tests '*RawQualification*' --tests '*CameraPresetTest'
python3 tools/validate_plan_system.py
tools/compose-driver.sh start CaptureRawVideoCropEditor
```

## 16. Acceptance Criteria

- [ ] Every unit test above passes.
- [ ] The driver screens render on `phone`, `landscape`, `inner` and `de-w384dp-h832dp-port-xxhdpi`.
- [ ] On the Razr: the RAW viewfinder shows the whole 4:3 field, and tap to focus lands where tapped.
- [ ] On the Razr: an off-centre window at 24 fps records, and the take shows what the guide framed, in the app's review and in a CinemaDNG export.
- [ ] On the Razr: a risky window shows its notice.
- [ ] On the Razr: after *Qualify again*, a small window at 90 fps records at 90 fps.
- [ ] On the Razr: the enlarged view fills the viewfinder with the window.

## 17. Evidence to Record

Unit suite exit codes, the driver trees and screenshots, and for the Razr checks the device fingerprint, the take's frame index (rate, missing frames) and a frame of each take beside a capture of the guide.

- Evidence: the Razr Fold's rates above 60 fps were probed on 2026-11-20 with `RawSixtyProbeDeviceTest` (full RAW10 output, vendor override): 90.0 fps without late frames, about 92 fps for a request of 120, every capture failing at 240. The acceptance checks are open.

## 18. Rollback and Recovery

Without a window of the operator's selected, behaviour is as before except for the viewfinder's aspect in RAW video and the two added qualification candidates. The change can be reverted as one unit; a stored key with an origin then reads as invalid and falls back to Automatic, and `raw-crops.json` is ignored.

## 19. Risks and Mitigations

- **The guide and the recording disagree.** The guide assumes the viewfinder shows the RAW output's whole field. The preview takes the output's aspect (decision 7), and the Razr check compares a recorded frame with the guide.
- **A window judged by extrapolation drops frames.** Only a window covered by a passed configuration is offered as ready; anything beyond is marked risky with its burst, and the ring's stop rule ends the take cleanly.
- **A high-rate request the camera cannot hold.** The candidates go through the qualification like any other, so a camera that falls short is left out or marked reduced-rate; Automatic never picks them.
- **A longer qualification.** Two more camera checks and long runs at a light data rate.

## 20. Completion Update

Not complete (status InProgress).

## 21. Execution Record

- 2026-11-20 Model, gate, service, recorder, saved windows, viewfinder aspect and high-rate candidates implemented on `feat/raw-crop`.
- 2026-11-20 Guide, editor, chooser section, settings row, enlarged view (`capture-raw-crop-view`) and the gallery's Crop row implemented, with their driver screens. The enlarged view scales the viewfinder by the frame's own transform, so the surface is not laid out again during a take; its clipping on a device is among the open Razr checks.
