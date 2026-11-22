---
plan_id: OCC-PLAN-075
title: "IMU motion data and gyro stabilisation"
status: InProgress
revision: 1
milestone: H3
intended_executor: Claude Code
execution_mode: implementation
depends_on:
  - OCC-PLAN-066
  - OCC-PLAN-071
blocks: []
requirements:
  - OCC-PRO-006
  - OCC-PRO-007
  - OCC-PRIV-001
  - OCC-RAW-001
adrs:
  - ADR-0019
  - ADR-0027
  - ADR-0033
risks:
  - RISK-010
  - RISK-018
  - RISK-019
estimated_sessions: 6
expected_repo_state: buildable
created_by: Claude Code
---

# OCC-PLAN-075: IMU motion data and gyro stabilisation

## 1. Objective

Record the phone's motion sensors (gyroscope, accelerometer, magnetometer) in sync with every kind of video take, so the take can be stabilised in post with Gyroflow, and then use the same samples to stabilise the viewfinder and the recorded file live.

The work has two deliveries:

1. **Motion data.** An opt-in Gyroflow log (`.gcsv`) beside each take and a CAMM track inside its MP4; `IMU_SAMPLES` chunks inside a RAW video take.
2. **Live stabilisation.** A gyro-driven warp in the GPU pipeline, applied to the viewfinder and to the encoder. It starts with a device probe and is designed only after its numbers are in (section 9, decision 12).

## 2. Why This Plan Exists

Before this plan the app wrote a Gyroflow log only for plain GPU takes without a pause. It left out slow motion, off-speed, time lapse and RAW video, dropped the log when the take had a pause or did not finalise, lost it when the process died, had no magnetometer and no embedded track, sampled at 200 Hz at most, and called a take "unsynced" whenever the camera clock was not the sensor clock. Nothing used the gyroscope live. OCC-PRO-006 asks for stabilisation the operator controls, and OCC-PRO-007 for takes whose every track sits on one timeline.

## 3. Prerequisites

- OCC-PLAN-066 (capture and project timelines): the per-frame presentation times and the muxer's rebase.
- OCC-PLAN-071 (RAW video): the `.ocraw` container, its writer thread and the CinemaDNG export.
- The Razr Fold for the device checks. Ask the user to connect it before every device step; assume it is disconnected. Do not use the torch.

## 4. Required Reading

ADR-0027 (permissions), ADR-0033 (capture and project timelines), ADR-0019 (RAW), `docs/formats/opencine-raw-v1.md`, the Gyroflow `.gcsv` 1.3 format, the Street View CAMM specification, `media/mux/Mp4AudioAppender.kt`, `camera/OpenCineLogGpuPipeline.kt` (`MuxTimestampNormalizer`), `camera/DirectRecorderTiming.kt`, `storage/VideoOutput.kt`, and `AGENTS.md` (Compose Driver).

## 5. Inputs

The previous recorder (`storage/MotionLogRecorder.kt`, removed), `storage/GyroflowLog.kt`, `SharedCapturePause`, `CaptureEpochClock`, `RawAudioClock`, `ProjectMp4Timing`, the recovery journal (`RecordingCaptureRecovery`), `DriveDescriptors`, the clip sidecar schema 1.1.0 and the capability report schema.

## 6. Deliverables

- `motion/`: `MotionSampleSource`, the spool format (`MotionSpoolFormat`), `MotionTimeline`, `MotionResampler`, `MotionRender`, `MotionTakeRecorder`, `MotionTake`, `MotionCammBudget`.
- `media/mux/Mp4CammAppender` on the shared `Mp4TrackAppend`, and MP4 readers that ignore tracks that are neither video nor audio.
- `storage/TakeCammAppend.kt` and the `VideoOutput` seal and attach steps; the capture wiring for the GPU and MediaRecorder routes.
- The `.ocraw` chunk `IMU_SAMPLES` (`0x0050`) with its metadata keys, the recorder that writes it, and the `.gcsv` written by the CinemaDNG export and by `desktop-tools/ocraw_to_dng.py`.
- Crash recovery of the log and the orphan sweep.
- The `HIGH_SAMPLING_RATE_SENSORS` permission with its audit entry, the setting's text in seven locales, the clip sidecar `motion` block (schema 1.2.0), the gallery inspector's Motion group, the capability report's motion sensors, and the privacy and store texts.
- Delivery 2: the probe report, then the live stabilisation its numbers justify.

## 7. In Scope

Video, LOG and HLG takes, takes with pauses, off-speed and time lapse (GPU route), constrained high-speed slow motion (MediaRecorder route), RAW video, takes on the phone and on a USB recording drive, and takes cut short by a process death (log only).

## 8. Out of Scope

Photos; lens profiles for Gyroflow (the user calibrates there); optical flow sync; stabilising MediaRecorder or RAW takes live; uploading motion data anywhere but the user's own WebDAV server (the outbox uploads an MP4 take's `.gcsv` beside its video, named after the uploaded video, as the share sheet shares it). Proxies drop the CAMM track. A renamed take keeps its log under the attachment name (`<stem>-motion-01.gcsv`), which Gyroflow does not pick up by itself; the CAMM track is unaffected.

## 9. Architecture

During a take one `MotionSampleSource` reads the three sensors at their fastest rate on its own thread. `MotionTakeRecorder` spools the raw samples, each encoded frame's camera timestamp and presentation time, and the exposure changes to `filesDir/motion/<take id>.ocmo`, with the take's description in `<take id>.json`. At the stop, after every other post-process of the file, `VideoOutput.sealMotion` builds a `MotionTimeline` from what the stop knows, renders the log to a temporary file and appends the CAMM track in place; publication attaches the log as the take's auxiliary row and deletes the spool.

### Decisions

1. **Spool raw, render at the stop.** The mapping from sensor time to file time needs facts that exist only at the stop (pauses, the muxer's offset, MediaRecorder's file). The spool is in `filesDir`, flushed about once a second without fsync, so a process death loses at most the last second.
2. **One timeline rule per route.**
   - *GPU:* a frame's file time is its encoder presentation time minus the first frame's, plus the muxer's constant (`CaptureEpochReport.videoMuxOffsetUs`, non-zero only with embedded audio). The timeline is piecewise linear between frames, so pauses, off-speed and time lapse need no special case.
   - *MediaRecorder:* a drained stop pins the last frame to the file's last sample and the first frame to where the frame count puts it; otherwise the first frame is taken at file time 0 and the slope is the file's frame duration over the capture interval, and the log's note says "sync estimated: recorder timing not measured". The file's frame duration is measured from its video track, so a stretched slow-motion file and a real-time one are one case.
3. **Rates follow the file.** Where the file runs at another speed than the capture, angular rates are scaled by the local factor so that their integral over file time is the real rotation, and samples are averaged in 1 ms bins of file time. Acceleration and magnetic field are averaged without scaling. About 0.16 % of the rotation is lost at a pause edge; that is accepted.
4. **Clock.** A camera with `SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME` shares the sensor clock: offset 0, synced. Otherwise the boot-minus-monotonic offset is measured once at the start and the note says "sync estimated: camera clock is not the sensor clock".
5. **CAMM.** One `camm` sample entry in a `meta` track, timescale 1 000 000, little-endian packets of type 2 (gyroscope), 3 (acceleration), 7 (magnetic field) and 1 (exposure time and rolling shutter skew, written only when they change), in camera axes. The packets go into a new `mdat` at the end and a new `moov` follows; the old `moov` is retyped `free` last, so the file is valid at every step. It runs after the timing, audio window, `mdcv` and audio append steps.
6. **CAMM budget.** The new `moov` is built in memory. A take carries at most 1 200 000 packets: the track is resampled to 2, 4, 8 or 16 ms bins when 1 ms does not fit, and left out when 16 ms does not. An `OutOfMemoryError` in the render or the append costs the motion data, never the take. The log always keeps 1 ms bins.
7. **Best effort.** Motion data only observes. No failure of it fails, delays or marks a take; the log is an attachment, not a prepared artifact.
8. **RAW video.** The recorder writes `IMU_SAMPLES` chunks of about a second from its writer thread, on the frame clock, and the metadata keys `imuOrientation`, `imuReadoutDirection`, `rollingShutterSkewNs`, `imuClock` and `takeStartUnixMs`. The frame path has priority: a batch that cannot be queued is dropped and counted. The container version does not change, because readers skip unknown chunk types. The CinemaDNG export, on the phone and on the desktop, writes `<take>.gcsv` beside the sequence.
9. **Recovery.** A GPU take recovered after a process death gets its log rendered from the orphaned spool, with offset 0 and no CAMM track; a take with embedded audio is then a few tens of milliseconds off. A MediaRecorder take cannot be placed and gets none. The render runs only in the full pass, off the main thread; a take that starts before it leaves an interrupted phone take whose spool was never tried journalled and queues a full pass, which renders the log and resolves the take with or without it (a spool tried once in the process is not waited for again). Orphaned spools and temporary logs are swept.
10. **Permission.** `HIGH_SAMPLING_RATE_SENSORS` lifts the 200 Hz cap. It is a normal permission, justified in the manifest audit by the opt-in feature `MOTION_DATA`; without it the source falls back to 200 Hz.
11. **Records.** The clip sidecar gains an optional `motion` block (sensors, nominal and measured rates, sample counts, sync, rate scale, CAMM, log name); the schema moves to 1.2.0 and older readers ignore the block. The setting keeps its stored key (`gyroflow-log-enabled`), is local to the device and off by default.
12. **Delivery 2 starts with a probe.** On the Razr: the real gyroscope rate, the latency from a sample to the frame it covers, the gyro-to-camera time offset, `LENS_INTRINSIC_CALIBRATION` per lens, and whether OIS can be turned off per lens. The intended design, subject to those numbers, is a per-frame homography (per row band for the rolling shutter) from the integrated orientation, causal smoothing with a fixed crop margin of about 10 %, applied where the camera texture is sampled for both the viewfinder and the encoder, as a new `StabilizationMode` that turns the vendor's OIS and EIS off. A stabilised take says so in its sidecar and in the log's note, so post does not correct it twice. The user is consulted with the probe's numbers before it is implemented.

## 10. Implementation Steps

1. Pure core: spool format, timeline, resampler, render, `GyroflowLog` with the magnetometer.
2. `Mp4CammAppender` and the tolerant MP4 readers.
3. Capture wiring: source, recorder, seal and attach in `VideoOutput`, both routes, pauses.
4. RAW: the chunk and its codec, the recorder, the exports.
5. Recovery, sweep, share.
6. Permission, texts, sidecar, inspector, capability report, privacy, driver screens.
7. Device checks of delivery 1 (section 16).
8. Delivery 2: probe, consultation, implementation, device checks.

## 11. State and Data

- On disk during a take: `filesDir/motion/<take id>.ocmo` (magic `OCMO`, version 1, 21-byte records) and `<take id>.json` (`opencinecam.motion-take.v1`); at the stop `cacheDir/gyroflow/<take id>.gcsv` until it is attached.
- In the take: `<video>.gcsv` (Gyroflow 1.3, with `mx,my,mz`), the CAMM track, and the clip sidecar's `motion` block.
- In a RAW take: `IMU_SAMPLES` chunks and the metadata keys of decision 8.
- Settings: the existing device-local boolean; no preset change.

## 12. Failure Handling

- No gyroscope, or the sensors refuse: no motion data, silently.
- A spool write, frame or exposure callback fails: motion data stops for that take.
- The render fails or runs out of memory: no log and no track.
- The file cannot take the track (several `moov`, open-ended box, trailing bytes, an existing CAMM track), or the append fails before the last write: the file stays as it was and keeps its log.
- The provider refuses the log's row: the row is removed and the take is published without it.
- A take that failed early keeps no motion data; a take salvaged as incomplete keeps the log without the track.

## 13. Tests

- `:app` `motion/*Test`: spool round trip and torn tail, timeline (offset, pauses, speed factor, estimated clock), resampler, render, `MotionTakeTest` (both routes, drained or not), `MotionCammBudgetTest`.
- `:app` `storage/`: `GyroflowLogTest`, `TakeCammAppendTest` (spool to render to append), the recovery and sweep tests, `ProxyMediaProbeTest`.
- `:media`: `Mp4CammAppenderTest`, `Mp4AudioAppenderTest` on the shared appender, `OcrawImuChunkTest`, `OcrawInteropTest`.
- `:camera`: `ProjectMp4TimingTest`, `Mp4AacSourceWindowTest`, `Mp4MdcvPatchTest` with a CAMM track present; `MuxTimestampNormalizerTest`.
- `:core:model`: `SecurityAuditTest`, `SecurityAuditManifestTest`, the clip sidecar tests.
- `tools/tests/test_ocraw_to_dng.py`.
- Compose Driver: `CapabilitiesMotionSensors`, `GalleryInspector` (Motion group), the Settings row.

## 14. Documentation Updates

This plan, `docs/plans/manifest.yaml` and `README.md`, `AGENTS.md` (driver screens), `docs/formats/opencine-raw-v1.md`, `docs/schemas/` (clip sidecar, capability report and their examples), `docs/user-guide.md`, `docs/privacy/index.md`, `docs/security-and-privacy.md`, `docs/release/security-privacy-accessibility.md`, `store/play/privacy-policy.md`, `store/play/declarations.md`, `desktop-tools/README.md` and `README.md`.

## 15. Commands to Run

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk ANDROID_HOME=$HOME/Android/Sdk
G="flock $HOME/.cache/claude-tmp/opencinecam/gradle.lock ./gradlew --no-daemon -Dorg.gradle.parallel=false --max-workers=2 -Dorg.gradle.caching=false"
$G :core:model:test
$G :media:testDebugUnitTest
$G :camera:testDebugUnitTest
$G :app:testDebugUnitTest
$G :app:lintDebug
./tools/check_format.sh
python3 tools/validate_plan_system.py
python3 -m unittest discover -s tools/tests
tools/compose-driver.sh start GalleryInspector
```

## 16. Acceptance Criteria

- [ ] Every host suite above passes.
- [ ] On the Razr, for a Video, an HLG, a paused, a 120 fps, a 240 fps, a time lapse and a USB drive take: the `.gcsv` is beside the take, Gyroflow loads it by itself and its sync offset is near zero.
- [ ] On the Razr: Gyroflow reads the CAMM track of those takes, and the files play in the app, in the system gallery and in DaVinci Resolve.
- [ ] On the Razr: the logged gyroscope rate is above 200 Hz.
- [ ] On the Razr: a RAW take carries `IMU_SAMPLES`, drops no frame because of them, and its CinemaDNG export has a `.gcsv` that Gyroflow syncs.
- [ ] On the Razr: a take whose app was killed is recovered with its log.
- [ ] A take over ten minutes gets a CAMM track at a coarser bin, and an hour-long take still gets its log.
- [ ] Delivery 2: the probe report is recorded and the user has chosen the design.

## 17. Evidence to Record

Suite exit codes; the driver trees and screenshots; for each device check the device fingerprint, the take's `Motion data:` log line, Gyroflow's reported offset, and the players that opened the file.

- Evidence: none recorded yet; the device checks are open.

## 18. Rollback and Recovery

The setting is off by default, so a build with it off behaves as before, apart from the tolerant MP4 readers. A file with a CAMM track stays a valid MP4 for older builds, whose project timing and audio window steps had already run. The `IMU_SAMPLES` chunk and the sidecar block are additive. The change can be reverted as one unit; the old recorder comes back with it.

## 19. Risks and Mitigations

- A player or editor rejects the `camm` entry: checked on the device before release (section 16); the track can be turned off without touching the log.
- Memory in long takes (RISK-010 for the take's integrity): the CAMM budget, and the render's `OutOfMemoryError` guard. The render streams the spool (about 80 MB per hour at 1 kHz): a scan keeps only the frames and exposures (16 bytes each, about 2 MB per hour at 30 fps, held twice while the timeline is built), and each sensor is read, resampled and paired through its own cursor. The CAMM packets of every candidate bin are counted before the series is built, so only the chosen series is allocated, within the budget. A take of any length keeps its log; the cost is time, about ten passes over the spool at the stop.
- MediaRecorder timing is inferred when the stop was not drained: the note says so and Gyroflow's optical sync covers the rest.
- A time-lapse log scaled to the file's speed may be of little use to Gyroflow: checked on the device.
- Schema growth (RISK-018): both additions are optional and versioned.
- Sensor access (RISK-019): opt-in, local, declared in the privacy texts and in the manifest audit.

## 20. Completion Update

Not complete (status InProgress). Delivery 1 is implemented with its host tests; its device checks and all of delivery 2 are open.

## 21. Execution Record

- 2026-11-21 Delivery 1 implemented on `feat/imu-motion-data`: core, CAMM appender, capture wiring, RAW chunk and exports, recovery, permission, records and texts.
- 2026-11-21 The render streams the spool in bounded memory: scan pass for frames, exposures and sensor stats, one cursor per sensor (a sample not after the previous one of its sensor is dropped instead of sorted), streaming resampler and nearest pairing, CAMM bin chosen from counted packets before the one series is built. A 3-hour 500 Hz host test renders all rows and picks 16 ms CAMM bins.
