---
plan_id: OCC-PLAN-074
title: "Raw microphone input and input equaliser"
status: InProgress
revision: 1
milestone: H3
intended_executor: Claude Code
execution_mode: implementation
depends_on:
  - OCC-PLAN-021
  - OCC-PLAN-054
blocks: []
requirements:
  - OCC-AUDIO-001
  - OCC-AUDIO-002
  - OCC-PRO-006
adrs:
  - ADR-0024
risks:
  - RISK-006
  - RISK-020
estimated_sessions: 5
expected_repo_state: buildable
created_by: Claude Code
---

# OCC-PLAN-074: Raw microphone input and input equaliser

## 1. Objective

A take recorded at a concert with the Razr Fold (1.0.0, default settings) was clipped beyond repair. The cause is upstream of the app, so its meter never warned:

- The default source, `UNPROCESSED`, uses the vendor path `handset-mic-unprocessed`, which adds 18 dB of codec gain and hard-clips. In stereo it is the same microphone twice.
- `MIC` and `VOICE_PERFORMANCE` are true stereo but carry a vendor limiter that sounds like a flanger.
- `CAMCORDER` in stereo is true stereo with the vendor's equalisation, but adds 13 to 19 dB and reaches full scale with desk speakers.
- A native AAudio stream in low-latency mode with the `UNPROCESSED` preset is true stereo (`handset-dual-mic`), at the level of the linear path and with no limiter. The Razr's `fast_input` grants `FAST` but not `RAW`; the stream keeps the fast input (performance mode low latency, 240-frame burst, 4096 frames of capacity) as long as the client asks for no larger buffer. A stream that asks for more (200 ms was tried) is refused the fast track, and the audio server reopens it on `primary_in` (`PCM_RECORD`, performance mode none, 960-frame burst), where `UNPROCESSED` is `handset-mic-unprocessed`: one microphone on both channels with the 18 dB of codec gain. The fast stream is the one measured here. It lacks the vendor's equalisation: the microphones peak by about 15 dB at 8 to 10 kHz. It publishes no capture timestamps (`AAudioStream_getTimestamp` answers `AAUDIO_ERROR_INVALID_STATE` on both clocks), so its capture epoch is the receipt estimate.

This plan gives the app a clean default input on every phone and puts the equalisation in the user's hands:

1. a native low-latency input, offered on every phone and made the default where a device profile says so;
2. an input equaliser applied before the meter and the encoder;
3. a Settings page to edit the equaliser over a live spectrum and to keep named profiles;
4. bundled equaliser profiles for the Razr Fold and the S24 Ultra;
5. a warning when the input saturates before it reaches the app.

## 2. Why This Plan Exists

The concert take in section 1 was lost with default settings, and the app's meter could not warn because the clipping happened before the samples reached it. No source the platform offers is clean on the Razr Fold, and the one that is (the native fast input) lacks the vendor's equalisation, so the app needs its own input path, its own equaliser and its own overload detector.

## 3. Prerequisites

- OCC-PLAN-021 (audio source and effect negotiation) and OCC-PLAN-054 (device profile schema, trust and invalidation).
- The Razr Fold for the device acceptance and the S24 Ultra for Delivery 3's measurements. Ask the user to connect the device before every device step; assume it is disconnected.

## 4. Required Reading

ADR-0012 (audio source, effects and time base), ADR-0024 (device profile quirk trust and invalidation), ADR-0029 (professional audio formats and controls), PLAN-021, PLAN-054, `AGENTS.md` (Compose Driver screens `InputEqualizer*`, `SettingsAudioEqualizer`, `SettingsAudioSync`, `CaptureAudioOverload*`, `CaptureListen*`).

## 5. Inputs

- The Razr Fold measurements of section 1 (sources, vendor paths, gains, fast input behaviour) and the pink-noise measurement behind the bundled curve (decision 8).
- The five read loops (`EmbeddedAac`, the WAV and FLAC sidecars, `RawAudioCapture`, `PreviewAudioMonitor`), `AudioLevelMeter`, `CaptureEpochClock`, `DirectRecorderTiming`, the camera-0 device profile and `LensCalibrationStore` (the store pattern).

## 6. Deliverables

- Delivery 1: the `PcmInput` seam with `AudioRecordPcmInput` and `AAudioPcmInput`, the native `occ_aaudio` stream, `InputEqualizer` in the five loops, the new `AudioSourceSelection` values, the defaults and one-time migration, the Razr quirks and bundled profile, the high-speed PCM route with `Mp4AudioAppender`, the take record fields and the manual sync offset (decisions 1 to 12).
- Delivery 2: the Input equaliser page, user profiles (`EqProfile` store) and headphone listening (decision 13).
- Delivery 3: the S24 Ultra profile and the saturation warning (decision 14).

## 7. In Scope

Built-in microphone capture for video, LOG, HLG, RAW video and audio sidecars; the input equaliser and its profiles; the defaults and migration; constrained high-speed takes at real speed with AAC; the manual sync offset; headphone listening; the input-overload warning.

## 8. Out of Scope

Processing USB and other external inputs through the native input (they keep `AudioRecord`, decision 2); audio in off-speed slow motion; platform effects (NS, AEC, AGC) on the low-latency input; shifting separate WAV and FLAC files by the sync offset (decision 12); changing the device profile schema (decision 9).

## 9. Architecture

Order in every read loop: mute guard, input observation, equaliser, software AGC, recording gain, listening, meter (decision 6).

### Decisions

1. **Default input.**
   - With no device profile the default source is `CAMCORDER`, the source the vendor tunes for video, with a flat equaliser.
   - With a profile that declares the quirk `audio-default-low-latency-input`, the default is the low-latency input with the profile's bundled equaliser.
   - `UNPROCESSED`, `VOICE_RECOGNITION` and `MIC` stay selectable. A stored choice is kept, except on a phone whose profile declares `audio-unprocessed-source-clips`: there `UNPROCESSED` is not offered, and a stored `UNPROCESSED` loads as the low-latency input (Camcorder without the native library) on every load.
   - A stored source the phone does not offer falls back to the low-latency input where the profile makes it the default, then to Camcorder, then to the first source offered.
   - The audio quirks are read from camera `0`'s profile: they describe the phone, not a camera.
   - Channels default to stereo.
   - **Migration, once per install.** Settings are stored whole, so an install that never touched audio holds the old defaults. When the stored source is `UNPROCESSED` and the stored channel count is 1 or absent, or no source is stored at all (a fresh install), the audio input moves to the new default of the phone (source, stereo and the default equaliser profile), and AAC moves from 128 to 320 kb/s if it was 128 or absent: the default is the highest rate, and a phone whose encoder does not offer it gets the highest it does. Any other stored combination is a choice and is kept. The flag `audio-input-migrated-v1` records that the migration ran. It runs when `CaptureService` starts, after the device profile is resolved off the main thread and before the preview audio monitor starts, through the normal settings write path.
2. **`AudioSourceSelection`** gains `CAMCORDER` and `LOW_LATENCY`. `LOW_LATENCY` is offered when the native library loads and the input is a built-in microphone; USB and other external inputs keep `AudioRecord`.
3. **PCM source seam.** A `PcmInput` interface in `:media` covers what the read loops use: start, blocking read into a direct buffer, frame-position timestamp on the boot-time clock, routed device, stop and release. `AudioRecordPcmInput` wraps today's `AudioRecord`; `AAudioPcmInput` wraps the native stream. The five read loops take a `PcmInput`: `EmbeddedAac`, the WAV and FLAC sidecars, `RawAudioCapture` and `PreviewAudioMonitor`.
4. **Native stream.** `occ_aaudio` in `:media` (CMake, links `aaudio`): input, 48 kHz, PCM16, shared, performance mode low latency, the platform's own buffer capacity (a larger one costs the fast input on the Razr), input preset `UNPROCESSED` (on the Razr the `CAMCORDER` preset moves a low-latency stream to a path with a bass cut); blocking `AAudioStream_read` from the app's feeder thread, `AAudioStream_getTimestamp(CLOCK_BOOTTIME)` for the capture epoch where the stream publishes it (the Razr's fast input does not). A stream that opens in another performance mode, rate or channel count is refused and the take falls back to the default `AudioRecord` source with a notice. `RECORD_AUDIO` is checked in Kotlin before the open. Platform effects (NS, AEC, AGC) are not attached to this input; its rows read "not available with this input", and the software AGC still applies when AGC is on.
5. **Bit depth.** The Razr's built-in microphones are 16-bit on every route: both input ports declare `INT_16_BIT`, and a float stream holds only multiples of 1/32768 (measured). The low-latency input is therefore read as PCM16 and widened to the take's bit depth before the equaliser, which works in double precision, so a 24-bit or float WAV or FLAC take keeps the equaliser's and the gain's full result. The take record states the capture depth.
6. **Equaliser engine.** `InputEqualizer` in `:camera`, pure Kotlin, no allocation per buffer:
   - a free list of points, none fixed: the user adds a point anywhere on the curve and removes it again; up to 24 points, a limit set by the processing cost and not by the editor;
   - each point is a biquad (RBJ cookbook): bell, low shelf, high shelf, high-pass, low-pass or notch, with any frequency from 20 Hz to 20 kHz, gain ±18 dB and width (Q) 0.1 to 10; plus an output trim of ±12 dB;
   - double-precision state per channel, transposed direct form II, coefficients swapped at a buffer boundary;
   - PCM16, 24-bit packed, 32-bit and float buffers, saturating on output;
   - a flat or disabled equaliser is bit-exact.
   Order in every loop: mute guard, input observation, **equaliser**, software AGC, recording gain, listening, meter.
7. **Soft mode.** The simple editor is the same engine with three preset points (low shelf 120 Hz, bell 1 kHz Q 0.7, high shelf 8 kHz). Switching to the full editor keeps them as ordinary points. A new profile starts with no points.
8. **Profiles.** `EqProfile` (id, name, bands, trim, bundled flag) in an atomic JSON store, like `LensCalibrationStore`. Bundled profiles ship as assets, keyed by device, read-only and duplicable. `CameraSettings` stores the active profile id and a copy of its curve (`audio-eq-curve`), so the read loops and the take record need no store; presets move to the next version and older ones decode to the default.
   - A bundled profile's id starts with `bundled-` and its curve corrects the raw microphones, so the read loops apply it only with the low-latency input (`appliedAudioEqCurve`), and only when that input opened (`appliedEqCurve` on the opened path: its `AudioRecord` fallback and an external input leave it out, and the take record shows no equaliser); with any other source, which the vendor equalises, the stored curve waits. A user's curve applies with every source. The stored copy of a bundled curve follows the one the build ships, keeping only its bypass.
   - The Razr's bundled curve is its second measurement (pink noise against the Camcorder source, 2026-11-15): bells at 10 kHz (−9.5 dB, Q 1.1) and 8.1 kHz (−9.5 dB, Q 1.8) and a high shelf at 13.5 kHz (−3.5 dB), within 1 dB rms of Camcorder from 1 to 15 kHz. The editor of Delivery 2 shows it as the phone's reference profile.
   - An input without timestamps (the Razr's fast input) takes its frame zero from when its reads returned (`PcmReadEpochEstimate`: the earliest return time minus the frames read, over the first second after the first read; half a second on the GPU path, whose muxer waits for it), not from the moment it was started, which a clap take showed to be 60 to 85 ms before the first sample.
9. **Device profiles.** The audio route is two new quirk effects in the existing camera-0 profile, so the schema does not change. The bundled equaliser is an asset beside it, not part of the signed profile.
10. **High-speed takes.** Slow motion (off-speed) records no audio and does not change. A constrained high-speed take at real speed with AAC records its audio through `MediaRecorder`, which the app cannot process: it ignores the input choice, the equaliser and the gain. That take changes as follows.
    - **During the take** `MediaRecorder` records video only, and the audio goes through the app's PCM path into a WAV member of the take's recovery group (`WavAudioSidecarRecorder`, 16-bit), with the same input, equaliser and gain as any other take. Manual gain no longer sends the take to the GPU route.
    - **When the take ends**, on the storage thread and before the video is prepared, the WAV is encoded to AAC and appended to the MP4 in place (`Mp4AudioAppender` in `:media`): the audio samples go in a new `mdat` at the end of the file, followed by a new `moov` that holds the video track unchanged plus the audio track, and only then is the old `moov` retyped to `free`, a four-byte write. Nothing is copied, no second file exists, and the file is a valid MP4 before and after that write. The WAV is then withdrawn from the take (`RecordingRecoveryGroup.withdraw`: its rows are deleted and the group commits with the video alone; dropping the member any other way would abort the whole take) and the take publishes as one MP4 with embedded AAC.
    - **Failure.** If the encode or the append fails, or the take is incomplete, the MP4 stays as recorded and the WAV publishes beside it as separate audio; the saved message names the WAV. A WAV that cannot be removed after the append is published as well. If the process dies before the append ends, crash recovery finds the video and the WAV in the same group and publishes both.
    - **Alignment.** The stop drains the session before the recorder stops, so the last video sample is the last captured frame. The video's time zero on the sensor clock is that frame's sensor timestamp minus its presentation time in the file; the audio's is the PCM capture epoch. Their difference becomes the audio track's edit list. The engine keeps those frame anchors for the direct route (`DirectRecorderTiming`), and `placeAppendedAudio` believes the measured zero only when the sensor clock is boot time, the session drained, the audio epoch is timestamp-backed and the zero lies within 250 ms before and 1 s after the recorder's start; the take record then reads `SHARED_BOOTTIME_CAPTURE_ANCHORS`. Otherwise the file's zero is taken to be the first frame after the recorder started, or the recorder's start, and the record reads `BOOTTIME_ESTIMATED_VIDEO_START`.
    - WAV and FLAC high-speed takes keep the route they have today.
11. **Take record.** The clip and audio sidecars record the input (API, preset, performance mode, routed device) and the equaliser profile with its bands.
12. **Manual sync offset.** For microphones whose latency the app cannot measure (Bluetooth, some USB), Settings › Audio has a device-local `audioSyncOffsetMs` from −500 to 500 ms (default 0; positive places the audio later, for sound that comes before the picture); it is not a portable preset key, so applying a preset keeps it and the preset version stays 29, and a take reads it at its start. It applies where the audio is placed, never to the measured capture epoch: the GPU path's `CaptureEpochClock` adds it to the audio origin it places the AAC track against, `placeAppendedAudio` adds it to a high-speed take's audio start, and a RAW take's `RawPcmChunker` adds it to every chunk time (`audioSyncOffsetNs` in the closing metadata). The measured `audioFrameZeroNs`, the shared pause and the drift diagnostics keep the audio's own clock, and the take record names the offset as `audioSyncOffsetUs` (optional when read, so older records read as 0). Separate WAV and FLAC files are not shifted; their sidecar only records `syncOffsetMs`.
13. **Editor.** Settings › Audio › Input equaliser opens a page: a log-frequency curve in the manner of the ToneBoosters equaliser: tapping an empty spot of the curve adds a point there, each point is a draggable node (drag for frequency and gain, pinch or the Q control for its width, shown as the shaded area of its own response), and a point is removed from its strip or by a long press, the summed response, a live spectrum from `PreviewAudioMonitor` before and after the equaliser, a band strip (type, frequency, gain, Q, on/off), bypass, and the profile row (pick, save as, rename, delete, reset). Every node action has an accessible equivalent in the band strip.
    - *Headphone listening.* The page's listen card and the capture screen offer one entry per headset: SCO outputs are never offered (call-quality mono), and a Bluetooth headset's A2DP and LE Audio outputs collapse into one by address. Identical names are told apart as "(2)", "(3)" by id.
    - *On the capture screen.* In the modes that record sound (video, LOG, HLG and RAW video, with audio on) a monitoring key (`capture-listen`, "MON") leads the lock row when headphones are connected or listening is armed. A tap stops listening, starts it on the remembered or only headset, or opens the listen panel (`listen-panel`, `CaptureInitialPane.LISTEN`) when there is a choice; a long press always opens the panel. The key is lit while sound plays and amber while armed but silent.
    - *RAW video.* The preview monitor does not run in RAW video, so an armed route stays silent, the key amber, until a take starts feeding it; the panel says so.
    - *Leaving Settings.* Going back to the capture screen from Settings (the Capture tab or back) while listening is armed asks "Keep listening?": Keep leaves it on, Stop switches it off, and dismissing stays in Settings.
14. **Saturation warning.** `AudioLevelMeter` gains an input-overload detector for clipping below full scale: runs of flat samples at the take's own ceiling and a collapsed crest factor. The capture screen shows it as a notice with the suggestion to switch input.

## 10. Implementation Steps

### Deliveries

1. **Input and engine.** Decisions 1 to 12 without the editor: the seam, the native input, the engine in the five loops, the settings and the Razr quirks with its bundled profile.
2. **Editor and user profiles.** Decision 13.
3. **S24 Ultra profile and saturation warning.** Decision 14, after measuring the S24 Ultra's paths.

## 11. State and Data

- `CameraSettings`: the audio source, channels, the active equaliser profile id and a copy of its curve (`audio-eq-curve`); presets move to the next version and older ones decode to the default (decision 8).
- The device-local `audioSyncOffsetMs` (−500 to 500 ms), not a portable preset key (decision 12).
- The one-time flag `audio-input-migrated-v1` (decision 1).
- `EqProfile` user profiles in an atomic JSON store; bundled profiles as read-only assets keyed by device (`app/src/main/profiles/eq-profiles/`).
- Take records: the input (API, preset, performance mode, routed device), the equaliser profile and bands, `audioSyncOffsetUs`, and the capture depth (decisions 5, 11 and 12).

## 12. Failure Handling

- A native stream that opens in another performance mode, rate or channel count is refused, and the take falls back to the default `AudioRecord` source with a notice (decision 4).
- A stored source the phone does not offer falls back in a fixed order (decision 1).
- A high-speed take whose AAC encode or append fails, or that is incomplete, keeps its MP4 as recorded and publishes the WAV beside it; crash recovery publishes both (decision 10).
- A damaged profile store falls back to flat (section 13).
- Clipping below full scale shows the overload notice with the suggestion to switch input (decision 14).

## 13. Tests

- `InputEqualizerTest`: each filter type against its analytic response, flat is bit-exact, saturation, stereo state independence, coefficient swap without a click, every PCM encoding.
- `EqProfileCodecTest`, `EqProfileStoreTest`: round trip, bundled profiles read-only, a damaged file falls back to flat.
- `AudioSourceDefaultsTest`: defaults with and without each quirk, the stored `UNPROCESSED` remap, the one-time migration (old defaults move, any other stored combination stays, it runs once), the high-speed PCM route.
- `CameraPresetTest` (new version round trip, previous version decoding), `SettingsCatalogTest`, `DeviceProfilesTest` (new quirk effects).
- `PcmInputContractTest` with a fake input for each loop's ordering and timestamps.
- Sync offset: `CaptureEpochClockTest` and `DirectRecorderTimingTest` (placement moves both ways, measured anchors and policy unchanged), `RawAudioCaptureTest` (chunk times shifted, residual measured), `HighSpeedAudioAppendTest`, `CaptureEpochMetadataTest`, `ProxySidecarMetadataTest` (records with and without `audioSyncOffsetUs`), `AudioSyncOffsetSettingsTest` (default, store round trip, coercion, range, pending during a take, search) and `CameraPresetTest` (local, not in the snapshot).
- Instrumented, on a device: `AAudioPcmInputDeviceTest` (opens, reads, timestamps advance, refusal path).
- Compose Driver screens: the editor (full, soft, bypassed, a bundled profile, a user profile), the audio settings rows and the saturation notice.

In the tree the `PcmInput` contract is covered by `PcmInputTest` and `PcmInputsTest` in `:media`; there is no `AAudioPcmInputDeviceTest` file. Further host tests for this plan include `AudioLevelMeterTest`, `BundledEqProfilesTest`, `BundledDeviceQuirksTest`, `InputEqualizerModelTest`, `AudioInputSettingsTest`, `AudioInputMetadataTest`, `AudioInputLiveUpdatesTest`, `AudioInputNoticeUiTest`, `AudioInputRoutingTest`, `Mp4AudioAppenderTest`, `AudioListeningDevicesTest`, `AudioListeningSettingsTest`, `AudioListenModelTest`, `ListeningPcmQueueTest` and `PcmListeningSinkTest`.

## 14. Documentation Updates

This plan, `AGENTS.md` (screens), `SettingsHelp` and strings in six locales, the Razr device profile and its bundled equaliser asset, `docs/plans/manifest.yaml` and `docs/plans/README.md`.

## 15. Commands to Run

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk ANDROID_HOME=$HOME/Android/Sdk
flock ~/.cache/claude-tmp/opencinecam/gradle.lock ./gradlew --no-daemon -Dorg.gradle.parallel=false --max-workers=2 -Dorg.gradle.caching=false :core:model:test
flock ~/.cache/claude-tmp/opencinecam/gradle.lock ./gradlew --no-daemon -Dorg.gradle.parallel=false --max-workers=2 -Dorg.gradle.caching=false :media:testDebugUnitTest
flock ~/.cache/claude-tmp/opencinecam/gradle.lock ./gradlew --no-daemon -Dorg.gradle.parallel=false --max-workers=2 -Dorg.gradle.caching=false :camera:testDebugUnitTest
flock ~/.cache/claude-tmp/opencinecam/gradle.lock ./gradlew --no-daemon -Dorg.gradle.parallel=false --max-workers=2 -Dorg.gradle.caching=false :app:testDebugUnitTest :app:lintDebug
./tools/check_format.sh
python3 tools/validate_plan_system.py
tools/compose-driver.sh start InputEqualizer
tools/compose-driver.sh reset SettingsAudioEqualizer
tools/compose-driver.sh reset CaptureAudioOverload
```

Host Gradle runs one module at a time with parallelism off. The Razr checks read logcat for `Native input open` and `Low-latency input not used`.

## 16. Acceptance Criteria

- [ ] Every host test above passes.
- [ ] On the Razr Fold, with defaults: a video take, a RAW video take and a WAV take use the low-latency input (logcat shows `Native input open: performance mode 12, burst 240 frames` and no `Low-latency input not used` fallback; the audio HAL picks `handset-dual-mic`), in true stereo: the two channels of the file differ.
- [ ] On the Razr Fold, with defaults: loud music beside the phone does not reach full scale and shows no limiter.
- [ ] On the Razr Fold, with defaults: the spectrum of an equalised take matches a `CAMCORDER` take within 3 dB from 100 Hz to 12 kHz, measured with pink noise.
- [ ] On the Razr Fold, with defaults: a clap board take is in sync within one frame, at 30 and 60 fps, at the start and after ten minutes.
- [ ] On the Razr Fold, with defaults: a constrained high-speed take at real speed with AAC records through the same input and equaliser (decision 10; an off-speed slow-motion take records no audio).
- [ ] On the Razr Fold, with defaults: an install with the old defaults opens with the low-latency input, stereo and the bundled equaliser, and one that had chosen another source keeps it.
- [ ] On a phone with no profile, defaults record through `CAMCORDER` with a flat equaliser.
- [ ] The editor changes the live spectrum as the nodes move, and a saved profile survives a restart.

## 17. Evidence to Record

Host suite exit codes, Compose Driver trees and screenshots, and for the Razr checks the device fingerprint, logcat of the input open, the takes' channel difference, pink-noise spectra against `CAMCORDER`, and clap-board offsets at the start and after ten minutes.

- Evidence: none recorded yet; the Razr checks are open.

## 18. Rollback and Recovery

Each delivery can be reverted on its own. With the native library absent the low-latency input is not offered and Camcorder is the default; a flat or disabled equaliser is bit-exact; the sync offset defaults to 0. The migration runs once and only moves installs that held the old defaults; reverting leaves the migrated settings as an ordinary stored choice.

## 19. Risks and Mitigations

- OEM audio paths differ per phone and route (RISK-006): defaults come from device-profile quirks, and a phone with no profile uses Camcorder with a flat equaliser.
- The fast input can lose its fast track if it asks for a larger buffer: the stream keeps the platform's own capacity (decision 4).
- No capture timestamps on the Razr's fast input: the epoch comes from read returns (`PcmReadEpochEstimate`), with a manual sync offset for inputs the app cannot measure.
- Migration changing a user's deliberate choice (RISK-020): only the exact old defaults move, once, and the flag records it.

## 20. Completion Update

Not complete (status InProgress). The plan merged to main via PR #11 (32361fe6, 2026-11-16) and shipped in OpenCineCam 2.0.0 (Google Play production, 2026-11-19). The Razr checks of section 16 remain open. The tree bundles only the Razr Fold's equaliser profile (`app/src/main/profiles/eq-profiles/motorola-blanc.json`); no S24 Ultra profile is present.

## 21. Execution Record

- 2026-11-15 The Razr's bundled curve measured a second time with pink noise against the Camcorder source (decision 8).
- 2026-11-16 Merged to main via PR #11 (32361fe6).
- 2026-11-19 Shipped in OpenCineCam 2.0.0 (Google Play production).
