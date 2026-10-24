# Device Validation Protocol

## Identity and preparation

Record app commit/version, validation protocol, schema version, Build.FINGERPRINT, security patch, SDK, thermal baseline, battery/power, available storage, camera-characteristics digest, codec name, destination, and graph ID. Cold-start the process for each empirical run. A commercial model name never substitutes for API evidence.

## Core sequence

1. Re-probe camera, stream, manual, dynamic-range, codec, audio, and destination capabilities.
2. Create the exact graph and record session/first-frame evidence.
3. Run encoded modes for 10 minutes and RAW candidates for 60 seconds while capturing cadence, queues, thermal, audio, and storage windows.
4. Finalize and validate tracks, timestamps, duration, orientation, codec/profile, color signaling, and sidecar links with Android and independent tools.
5. Repeat three cold-start runs for empirical promotion.
6. For certification, run 30 minutes plus Activity recreation, background/return, fold/unfold where available, surface loss, camera availability loss, audio removal, storage disconnect/low space, thermal escalation, forced stop, and recovery.

## Mode gates

RAW requires zero lost frames, no ImageReader saturation, and destination p01 throughput at least 1.25× measured stream rate. HLG requires Main10/BT.2020/HLG file proof; effective precision remains a distinct controlled gradient result. APV requires MP4 extract/decode interoperability. UNPROCESSED selection and disabled platform effects are reported without claiming absence of OEM DSP.

A failed run records the exact stage and blocks promotion. Unsupported hardware executes the plan's fail branch and produces an unsupported-state report, not fabricated success.

## OCLog2 exact-tuple qualification

OCLog2 uses `tools/qualify_oclog2.py` and the protocol in
`docs/color/opencine-log-v2.md`. Record at least 30 seconds with zero dropped frames for each
candidate fingerprint/camera/size/FPS/source tuple. Hash the clip, OCLog2 sidecar, and parseable
`ffprobe` JSON; then execute forward/inverse checks through FFmpeg and an independent editor.
The DaVinci Resolve editor workflow, fixture and comparator are specified in
`docs/color/oclog2-resolve-protocol.md`. All six reference paths (`cpu`, `gpu`, `lut1d`, `lut3d`, `ocio`, `dctl`) must remain within
`2e-5` absolute error. Qualification of one source branch never broadens to another.

An emulator, missing editor, missing fixture, hash mismatch, stale shader, or different tuple
cannot promote a profile. `NOT_RUN` keeps it experimental; `FAILED` records the rejected bundle.

The physical Android harnesses are:

- `com.librestatic.opencinecam.OCLog2GpuNumericTest`, which requires a non-software GLES renderer
  and writes `occ-plan-061-oclog2-gpu-numeric.json` to the app external-files directory.
- `com.librestatic.opencinecam.OpenCineLogSustainedRecordingDeviceTest`, which exercises the
  production HLG-derived OCLog2 graph, waits for three consecutive 500 ms effective-FPS samples
  within ±2% of the requested cadence, records at least 30 seconds, and writes MP4, sidecar, and
  result JSON to the same directory.

Run them on an explicitly selected physical serial; never allow a second connected device or an
emulator to satisfy the result. Validate the pulled MP4 independently with `ffprobe` frame PTS and
an FFmpeg full decode. The cadence barrier establishes the measurement window; it does not erase
or reclassify any gap found inside that window.

On the reference device, three separate process cold starts of the cadence-gated HLG-derived
1920x1080@30 protocol each produced 961/961 frames over a 32-second PTS span. The encoder-side
maximum interval was 33,334 µs and no interval exceeded 1.5× the target period. A pulled clip was
also checked independently as HEVC Main10/yuv420p10le at 30/1 with a complete FFmpeg decode.

## HDR10 (PQ) video in HLG mode

HLG mode can record PQ instead of HLG when the camera advertises HDR10 and a hardware HEVC encoder
advertises `HEVCProfileMain10HDR10` for the size and rate. The camera still delivers HLG10; the GPU
graph converts it to BT.2020 PQ (HLG inverse OETF, 1000 cd/m2 OOTF, ST 2084 inverse EOTF) and the
encoder runs in the HDR10 profile. `Hdr10Video.ENABLED` is the kill switch. Host tests cover the
maths, gating, selection and tag plumbing only. On the Razr Fold, before trusting a clip, check:

1. `ffprobe` shows HEVC Main 10, `bt2020nc`, `smpte2084`, `bt2020` and (when the muxer writes it) mastering display / content light level side data.
2. The SPS VUI transfer is 16 (the take's sidecar records `encoderVuiTransfer` and `containerVuiTransfer`).
3. A grey card at HLG 75% signal reads about 203 cd/m2 (PQ code about 580 of 1023 on a limited-range scope).
4. The encoder accepts the HDR10 profile on a Surface input at every offered size and fps; if it throws, narrow the offer in `Camera2PreviewEngine` (the HDR10 flag of `Camera2LogProfile`).
5. `EGL_EXT_gl_colorspace_bt2020_pq` is reported by the driver; without it the surface keeps the default colourspace and only the codec keys and SPS carry the PQ tag.
6. Playback in the gallery and a PQ-capable external player tone maps sensibly; the viewfinder and view assist are unchanged because they read the HLG source, not the encoded output.
7. The container's mastering display minimum is 0.005 cd/m2 (`ffprobe -show_frames -read_intervals %+#1` side data, or the `mdcv` box: last int32 = 50). Android's `MPEG4Writer::writeMdcvAndClliBoxes` multiplies the KEY_HDR_STATIC_INFO minimum by 10000 although it is already in 0.0001 cd/m2, writing 50 cd/m2; `Mp4MdcvPatch` rewrites that one field after the muxer stops, only when it holds exactly the bugged value, and logs `HDR10 container mdcv: PATCHED` (tag `OpenCineLogGpuPipeline`). The HEVC SEI was always correct (min 0.005, max 1000, MaxCLL 1000, MaxFALL 400 on the Razr, 2026-10-10).

## Qualcomm exposure metering

The Metering setting drives `org.codeaurora.qcamera3.exposure_metering.exposure_metering_mode`
(int32 request and result key, tag 0x80960000). It is offered only when that request key exists and
`...exposure_metering.available_modes` (int32[N], tag 0x80960001) lists known values. The Razr Fold
(`dumpsys media.camera`, every camera id) advertises `[0 1 2]`. The values follow Qualcomm's
`cam_auto_exposure_mode_type`: 0 frame average, 1 centre weighted, 2 spot; other values are ignored.
The mode is sent only while exposure is automatic (including AE compensation and ISO or shutter
priority). Spot reads `CONTROL_AE_REGIONS`, which carries the tap-to-meter point while one is active;
otherwise the HAL meters the frame centre. The result key, when reported, is shown in the capabilities
readback table. To verify on a device: pick each mode and check the readback line
`org.codeaurora.qcamera3.exposure_metering.exposure_metering_mode` follows the request, then tap to
meter in Spot and confirm the exposure follows the tapped point.

On the Razr Fold the Qualcomm vendor tags are in the vendor tag descriptor and the static metadata (`CameraCharacteristics.getKeys()`) but are NOT listed in `availableCaptureRequestKeys` / `availableCaptureResultKeys`. `VendorKeys.kt` therefore resolves them by name (`CaptureRequest.Key(name, type)`, API 29+) whenever the feature's static gate key (`exposure_metering.available_modes`) is present, and logs `Vendor key <name> resolved by name (not advertised)` once per camera. Advertised keys still win, and a camera without the gate key is never touched.

## ISP statistics histogram (Qualcomm)

`org.codeaurora.qcamera3.histogram.*` (Razr Fold `dumpsys media.camera`, vendor tag table):
`enable` byte, `buckets`, `max_count`, `stats`, `stats_type` int32. Camera 0 statics report
`buckets = [256]`, `max_count = [256]`. The same dump lists neither `enable` nor `stats` in
`android.request.availableRequestKeys` / `availableResultKeys`, and Motorola's camera app has no
reference to them. `IspHistogram.kt` therefore enables the block only where the enable key is
advertised and a result carries `stats` with exactly `buckets` entries; on the Razr it is expected
to stay dormant (frame histogram) until a device probe shows otherwise.

When active it replaces only the HUD luma histogram, and falls back to the frame analysis when the
ISP result is older than 500 ms. `stats_type` 1 (YUV) is labelled `ISP`; 0 or absent is labelled
`ISP RAW`, because the Bayer-domain statistics are pre tone map and do not match the display.
Which channel(s) `stats` holds is not documented by the device. Check logcat for
`ISP histogram: N buckets, stats_type=…` on a device that advertises the keys.

On the Razr Fold the Qualcomm vendor tags are in the vendor tag descriptor and the static metadata (`CameraCharacteristics.getKeys()`) but are NOT listed in `availableCaptureRequestKeys` / `availableCaptureResultKeys`. `VendorKeys.kt` therefore resolves them by name (`CaptureRequest.Key(name, type)`, API 29+) whenever the feature's static gate key (`histogram.buckets`) is present, and logs `Vendor key <name> resolved by name (not advertised)` once per camera. Advertised keys still win, and a camera without the gate key is never touched.

## Qualcomm picture controls (saturation, sharpness)

Evidence from `dumpsys media.camera` on the Razr Fold, camera 0 (the code finds every key by exact name and clamps to the advertised range):

| Static range key | Type and values | Request key (also reported in results) | Template result |
|---|---|---|---|
| `org.codeaurora.qcamera3.saturation.range` | int32[4] = min, max, default, step = `[0 10 5 1]` | `...saturation.use_saturation` int32[1] | 5 |
| `org.codeaurora.qcamera3.sharpness.range` | int32[2] = min, max = `[0 6]` | `...sharpness.strength` int32[1] | 2 |

Saturation advertises its default, so moving the slider back to it clears the override. Sharpness advertises none (the template reported 2 here, but that is device-specific), so the slider marks the midpoint as "device" and an explicit value is always sent. The controls are not applied in LOG, HLG, RAW or constrained HFR, and sharpness is skipped while `EDGE_MODE` is OFF. Verify on the device: both sliders appear in Settings > Image processing, the capabilities page lists both keys under requested/reported, and neither appears on a camera without the keys.

On the Razr Fold the Qualcomm vendor tags are in the vendor tag descriptor and the static metadata (`CameraCharacteristics.getKeys()`) but are NOT listed in `availableCaptureRequestKeys` / `availableCaptureResultKeys`. `VendorKeys.kt` therefore resolves them by name (`CaptureRequest.Key(name, type)`, API 29+) whenever the feature's static gate key (`saturation.range` / `sharpness.range`) is present, and logs `Vendor key <name> resolved by name (not advertised)` once per camera. Advertised keys still win, and a camera without the gate key is never touched.

## Qualcomm instant AEC

`org.codeaurora.qcamera3.instant_aec.instant_aec_mode` (request, int32) with static `instant_aec_available_modes` (0x80c90001, int32[3] = `[0 1 2]` on the Razr Fold, every camera id). Values follow the QTI enum: 0 normal, 1 aggressive, 2 fast. The engine boosts when a preview session starts (aggressive, else fast) and when `LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID` changes, i.e. zoom crossing a lens switchover. Inside a take only a lens switch boosts, only with fast, for at most 500 ms. It restores 0 on `CONTROL_AE_STATE` CONVERGED/LOCKED (counted only on a result whose request carried the boost) or on the timeout; never with AE lock, manual exposure or constrained high-speed. Check `adb logcat -s Camera2PreviewEngine` for `Instant AEC engaged ... trigger=` / `released ... timeToConvergeMs=`. To judge it in takes, record a zoom sweep across the switchover with and without the boost and compare the exposure step in both clips; if the HAL's own lens matching already hides it, keep the boost to the viewfinder.

On the Razr Fold the Qualcomm vendor tags are in the vendor tag descriptor and the static metadata (`CameraCharacteristics.getKeys()`) but are NOT listed in `availableCaptureRequestKeys` / `availableCaptureResultKeys`. `VendorKeys.kt` therefore resolves them by name (`CaptureRequest.Key(name, type)`, API 29+) whenever the feature's static gate key (`instant_aec_available_modes`) is present, and logs `Vendor key <name> resolved by name (not advertised)` once per camera. Advertised keys still win, and a camera without the gate key is never touched.

## Motorola auto frame rate (IQdynamicFPS)

Measured on the Razr 2026-10-10 with `dumpsys media.camera` while the stock Motorola camera recorded 4K60 (aeTargetFpsRange [60,60]); its Settings > Videos switch is "Auto frame rate".

- ON: request key `com.lenovo.moto.control.IQdynamicFPS_enable` (int32[1]) = 1; session parameters `org.codeaurora.qcamera3.sessionParameters.EnableVIULL` (int32[1]) = 1 and `org.codeaurora.qcamera3.sessionParameters.dynamicFPSConfig` (float[3]) = [2.0, 30.0, 60.0]. In a dim room the sensor ran 30 fps (frameDuration 33.3 ms) and the file was a true 30 fps.
- OFF: `IQdynamicFPS_enable` = 0, no EnableVIULL, dynamicFPSConfig still [2, 30, 60]. True 60 fps.
- The stock app also sends `com.lenovo.moto.control.dynamicFPS_enable` = 0 in both cases.
- Camera 0 lists all four keys in availableRequestKeys, and `IQdynamicFPS_enable`, `dynamicFPSConfig`, `EnableVIULL` in availableSessionKeys. Static: `com.lenovo.moto.control.IQdynamicFPS_support` (int32[1]) and `org.codeaurora.qcamera3.platformCapabilities.EnableVIULL`.

App behaviour (`DynamicFps.kt`, setting "Low-light auto frame rate", key `video-low-light-fps`, device-local, off by default):

- Gate: static `IQdynamicFPS_support` == 1 and the enable, EnableVIULL and dynamicFPSConfig request keys all resolve through `VendorKeys` (by name on API 29+). Only then does the camera descriptor set `lowLightAutoFps` and the switch appear.
- OFF (default): every request of a supporting camera, and the session parameters, carry `IQdynamicFPS_enable` = 0 and `dynamicFPS_enable` = 0 (photo, video, LOG, HLG). Constrained high-speed sessions are left untouched.
- ON: allowed only in regular VIDEO (not LOG, HLG, RAW, HFR, timelapse, or off-speed project timing). Requests carry `IQdynamicFPS_enable` = 1; the session parameters add `EnableVIULL` = 1 and `dynamicFPSConfig` = [2, max(15, target/2), target] (60 gives [2, 30, 60]). Changing the switch reopens the preview, since these are session keys.
- Cadence: the GPU/`MediaCodec` path (normal VIDEO and LOG; `Camera2PreviewEngine.startGpuVideo`) and the direct `MediaRecorder` path (constrained high-speed only, `captureRate != null`) stamp frames with the sensor timestamp, so a 30 fps stretch inside a 60 fps take is stored with real timestamps (variable frame rate). Diagnostics may report PTS gaps. Off-speed project timing (`ProjectFrameTimeline`) assigns a constant cadence per frame, which is why the switch is ignored there.

Verify: record in a dim room with the switch on, then `adb shell dumpsys media.camera` and read "Last request sent" for the keys above; `android.sensor.frameDuration` should read ~33.3 ms in dim light and ~16.7 ms in bright light or with the switch off.

Razr result 2026-10-10 with the switch off: `IQdynamicFPS_enable` = 0 reaches the HAL, but in a very dark room (ISO ~12000) the app still records ~33 fps at 4K60 in VIDEO, HLG and HDR10. The capture result reads exposureTime 30.0 ms and frameDuration 30.18 ms although aeTargetFpsRange is [60,60]: the HAL's auto exposure stretches the exposure past the frame time instead of raising the gain further. This is not the dynamic FPS feature. The following did not change it: sending `dynamicFPSConfig` = [2, 60, 60] per request, `com.lenovo.moto.clientapp.is_motcamera2` = 1 with `current_mode` = 1, and an exposure-time hint. Shutter priority (the app's manual-AE emulation, 1/183) held 16.67 ms frames, so true 60 fps. In comparable light the stock camera ran 15.6 ms at ISO 13653.

### FPS LOCK (cadence lock)

Setting "FPS LOCK" (`video-fps-lock`, device-local, on by default) plus a quick key beside AE/AF (`capture-fps-lock`, label FPS) in VIDEO, LOG and HLG. It answers the stretch above without fixing the shutter permanently, which would overexpose in bright light.

- Supported (`Camera2CameraDescriptor.fpsLockSupported`) when the camera advertises `SHUTTER_PRIORITY` in `CONTROL_AE_AVAILABLE_PRIORITY_MODES` on API 36+ (native hold), or has MANUAL_SENSOR with valid ISO and exposure time ranges (emulated hold). Otherwise the key and the setting are hidden. The native path is used wherever it is advertised.
- `CadenceGuard` (pure, in `:camera`) reads every repeating-request result: SENSOR_FRAME_DURATION and SENSOR_SENSITIVITY. It engages when the frame duration has exceeded 1.08 x (1e9 / target fps) continuously for 250 ms. While engaged it tracks the highest ISO seen and releases when the ISO has stayed at or below 50 % of that for 1 s (light to spare). A gap over 1 s between results drops the pending timers and keeps the verdict. The guard is reset on a camera change, a target fps change, a session restart and when the lock is turned off.
- It runs only for a video graph (regular VIDEO, or the OCLog LOG/HLG graph, preview and recording) with automatic exposure (no manual ISO/shutter, `professionalExposure` AUTO or none), AE not locked, the low-light auto frame rate off (that setting wins), not constrained high-speed, not a timelapse take.
- Engaged, the engine re-issues the repeating request on the camera executor without restarting the session (a take keeps recording): `CONTROL_AE_MODE_ON`, `CONTROL_AE_PRIORITY_MODE` = `SENSOR_EXPOSURE_TIME_PRIORITY`, `SENSOR_EXPOSURE_TIME` = frame time - 0.1 ms, `SENSOR_FRAME_DURATION` = 1e9 / target fps. The HAL still picks the ISO, so the image darkens instead of the rate dropping. AE compensation and antibanding are unchanged. Released, the requests are exactly the normal automatic ones. The key turns amber while it is holding.

Emulated hold (no native priority). The Razr Fold (SDK 36) advertises `android.control.aeAvailablePriorityModes` = [0] (OFF only) but has MANUAL_SENSOR, and its manual exposure (AE OFF, 1/183, fixed ISO) holds 16.67 ms frames at 4K60 in the dark; AE in the dark stretches to 30 ms at ISO ~12000. So the engine sends `CONTROL_AE_MODE_OFF`, `SENSOR_EXPOSURE_TIME` = frame time - 0.1 ms, `SENSOR_FRAME_DURATION` = 1e9 / fps and a `SENSOR_SENSITIVITY` it steers itself:
- At the engage transition the ISO starts at `iso_auto x exposure_auto / held exposure` from the result that triggered engagement, clamped to the sensitivity range. The target is learned from the first settled held frames (see Settling); until then the ISO stays fixed.
- `CadenceIsoLoop` (pure) runs on every analysis frame (GPU and YUV producers, camera executor, before the UI is notified; always the frame's own histogram, never the ISP one). Mean luma = bin-index-weighted mean of the luma histogram, normalised 0..1. ratio = target / measured; deadband +-6 %; otherwise the ISO ramps as described below. With the analysis suspended or absent the ISO stays fixed.
- Hold path order: Android 16 native priority, then Qualcomm exposure priority (vendor tags `org.codeaurora.qcamera3.iso_exp_priority.*`, gated by the `exposure_time_range` characteristic containing 1/fps - 100 us): AE stays on with `select_priority=1` and `use_iso_exp_priority=<held exposure ns>`, so the HAL steers the ISO and its hidden ISP gain; no ISO loop, ramp, burst or hidden gain. The log shows `hold path: native|qcom|emulated` and `qcom hold at N ms: exposure iso`. If the result exposure is still over 1.1x the held one after 600 ms the camera is marked unusable for the process and the emulated hold below is used. The Razr 50 Ultra (2026-10-10) advertises the tags but ignores them: the exposure stays at 30 ms, so it falls back to the emulated hold within 0.6 s. Last resort, the emulated hold:
- Handoff without a brightness drop (emulated hold): the hold starts at AE's own last exposure and total gain (SENSOR_SENSITIVITY x CONTROL_POST_RAW_SENSITIVITY_BOOST / 100), so the first held frames match AE by construction; FrameDuration = max(exposure + 100 us, 1/fps). AE ON applies an ISP gain (about 2.2 to 2.4x on the Razr) that no metadata reports and AE OFF loses, so the start ISO is AE's effective ISO times a hidden gain G (per camera id, in memory, prior 2.2, `hiddenGain=` in the handoff line). The guard engages only once AE has settled (CONTROL_AE_STATE converged, or the last four ISOs within 8 %), at the latest 1 s after the stretch began. The luma target is the median of the last three GPU frames analysed while the results still said AE was on; the first manual frame plus one of margin is compared with it to update G (G x (target/luma)^2, each update within 0.5x..2x, G within 1..4; `hidden gain:` log lines); the loop falls back to learning from held frames if no AE frame was seen (analysis bursts to about 30 Hz for the handoff; burst frames feed the loop only, the UI keeps its rate; off when thermally suspended). Then `CadenceExposureRamp` walks the exposure down to 1/fps in about 13 geometric steps over ~400 ms (at least 33 ms apart), the ISO compensating (exposure x ISO constant, clamped), and the frame duration following. The luma loop only learns during that ramp, the guard does not judge frames, and the UI reports the hold only when the ramp lands; then a 200 ms settle and the normal 4 % / 66 ms corrections. Only one analysis producer drives the loop (the GPU readback when there is a GPU pipeline, else the YUV reader), because their luma domains differ. Logcat (`CadenceGuard`): `handoff: ... startExposure startIso exposureRamp`, `first settled held frame`, `exposure ramp complete`.
- Smooth ramp (no visible staircase): a settled analysis frame only plans. desired ISO = current x (target / luma)^2 (luma is gamma-encoded), factor clamped to 0.5..2, +-6 % deadband. `CadenceIsoLoop.tick`, called from every repeating capture result, then walks there: at most 4 % per request, at least 66 ms apart, landing exactly on the plan once within 1 %. Analysis frames are ignored while ramping and for 250 ms after, so a halving takes about 17 steps (~1.1 s) instead of a few big jumps.
- ISO beyond the sensor maximum (Razr: sensitivity range [71, 12800], AE reports up to 25600 while stretching) is sent as `SENSOR_SENSITIVITY` = max plus `CONTROL_POST_RAW_SENSITIVITY_BOOST` = round(100 x iso / max) where the camera lists that request key (Razr range [100, 3199]); the loop's top is max x boostMax / 100, capped at 8 x max. The guard judges the effective ISO (sensitivity x boost / 100).
- Settling: after engaging, `CadenceGuard` ignores results for 400 ms (in-flight AE results) and then takes the highest ISO seen as its release reference, never the engaging sample's AE ISO. The loop also ignores analysis frames for 400 ms after engaging and after each re-issue, and its target is learned from the held frames, not from pre-engage ones (AE may still be converging then): after the settle window the first three valid frames are collected, their median is the target, and the ISO does not move until then. The initial ISO already keeps AE's ISO x time, so those frames have AE's brightness.
- AE compensation: the target is scaled by 2^(EV step x index change since engage), approximately (the luma is in the encoded domain, not linear light).
- Release works unchanged: the result ISO is the held ISO, which follows the scene, so "ISO at or below 50 % of the highest seen while engaged for 1 s" still fires when the room is lit. Reported exposure mode stays AUTO while held.
- Verify: `dumpsys media.camera` shows `android.control.aeMode` OFF with `android.sensor.exposureTime` ~16.57 ms and a changing `android.sensor.sensitivity`, frameDuration ~16.67 ms; the `CadenceGuard` line is the same.

Verify (native path): record 4K60 in a very dark room with FPS LOCK on, then:

- `adb logcat -s CadenceGuard` shows `engaged: frameDuration=30.xx ms iso=1xxxx target=60fps` once the stretch is seen, and `released: ...` after the room is lit.
- `adb shell dumpsys media.camera`, "Last request sent": `android.control.aePriorityMode` is exposure-time priority and `android.sensor.exposureTime` ~16.57 ms; the latest result's `android.sensor.frameDuration` is ~16.67 ms.
- The file is a true 60 fps (no ~33 fps stretch). With the setting off the same scene should show the 30 ms frame duration again.
- In bright light the key stays white (on, not holding) and no `engaged` line appears.

## Lens calibration and lens lock (PLAN-070 G0)

Razr Fold probe, 2026-10-23, `motorola/blanc_g/blanc:16/W3WB36.36-123-2/a0723-edb3f:user/release-keys`. The probe was a throwaway debug app; it ran in a dim room (ISO ~4500 at 40 ms).

**Logical camera.** Camera `0` lists the physicals `[2, 3, 5]` and has a zoom range of 0.5–10×.

**Direct physical opens.** `openCamera` works for each of `2` (wide), `3` (ultra-wide) and `5` (tele). Each one configures a three-stream session (preview, `MediaRecorder` at 1080p, full-size JPEG) and records about 3 s of video. Each shoots JPEG at 1×, 2×, 4× and 8×, at 4000x3000, 3840x2880 and 4096x3072 respectively. On a direct open `LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID` is absent.

What each physical keeps, compared with the logical camera:

| | Logical `0` | Wide `2` | Ultra-wide `3` | Tele `5` |
|---|---|---|---|---|
| Zoom range | 0.5–10× | 1–8× | 1–8× | 1–8× |
| HLG10 and HDR10 | yes | yes | yes | yes |
| EIS (off, on, preview) | yes | yes | yes | yes |
| Autofocus | yes | yes | yes | yes |
| Minimum focus distance | 8.3 D | 8.3 D | 40 D | 6.7 D |
| Constrained high speed | yes | yes | no | no |
| `[15, 60]` fps range | yes | yes | no | no |
| `[60, 60]` fps range | yes | yes | yes | yes |
| Request keys | 132 | 122 | 122 | 122 |
| Session keys | 72 | 63 | 63 | 63 |

Neither the ultra-wide nor the tele has constrained high speed. A lens-locked high-speed take therefore needs the wide, or the lock has to be refused.

**Where the lenses sit.** The field-of-view ratios (`RawZoomCrop.fovWidth`) put the lenses at 0.55× (ultra-wide), 1× and 3.0× (tele). The focal ratio would suggest about 2× for the tele, but its sensor is smaller.

**Automatic switch points.** In this light the switches happen at:

- going up: 3 to 2 at 1.00× and 2 to 5 at 3.00×;
- going down: 5 to 2 at 2.95× and 2 to 3 at 0.95×.

This is about 5 % hysteresis. The tele switch matches its field-of-view anchor.

**Vendor keys.** `com.qti.chi.multicamerainfo.MasterCamera` was tried with the values 0, 1, 2, 3 and 5 as a repeating request key, and with 0 and 1 as a session parameter. It is accepted but changes nothing: the switch points and the reported lenses are unchanged. It is also missing from both the available request keys and the available session keys. The other candidates (`ForceFullFOV`, `SATFallback.W2UW`, `TransitionMode`, `manualWB`) were not reached before the probe was stopped to spare the device the heat.

**Conclusion.** The lens lock opens the physical directly (ADR-0036). No vendor key is used.

### G1: lens lock on the Razr Fold (2026-10-23)

Same build and fingerprint as G0, debug package, folded (cover screen). The lock was set with the LENS key and moved between lenses with the locked anchor bar. Each lock opened the physical camera directly; no lock was withdrawn.

| Lens | Zoom range on the UI scale | JPEG at the top of the range | Video |
|---|---|---|---|
| 3 (ultra-wide) | 0.55x to 4.4x | 3840x2880, 1.83 mm, digital zoom 8.0 | not recorded |
| 2 (wide) | 1x to 8x | 4000x3000, 6.57 mm, digital zoom 8.0 | 1080p30 HEVC |
| 5 (tele) | 3x to 24x | 4096x3072, 13.3 mm, digital zoom 8.0 | 4K30 HEVC |

- **The lens never switched.** Locked to the wide, the zoom passed the 3.00x point where the logical camera hands over to the tele, and the JPEG at 8x still came from the wide. A take zoomed from about 3x to 8x on the wide shows no colour or framing jump: the largest frame-to-frame scene score was 0.10.
- **RAW.** A DNG on the wide at 3.3x kept the full 4000x3000 frame with a 1217x912 DefaultCrop.
- **Video sizes.** 1080p30 on the wide and 4K30 on the tele mapped to the same profile on the physical camera, so the size mismatch the engine allows for did not happen on this device.
- **Anchor labels.** Unlocked, the anchor bar shows 0.5 / 1.0 / 2.0, from the focal ratios. Locked, it shows 0.6 / 1.0 / 3.0, from the fields of view. The logical camera switches to the tele at 3.00x, so the unlocked 2.0 anchor does not match the lens; this predates the lens lock.

### G2 procedure: the GPU-crop fallback

The Razr opens every physical directly, so G2 forces the fallback. On the debug package, run `adb shell setprop debug.opencinecam.lenslock.forcecrop 1` and force-stop the app. The engine then skips the direct open and serves every lock by the GPU crop. Release builds ignore the property. Lock each of 2, 3 and 5 and check these points:
- logcat shows `served by the GPU crop` with the pinned ratio;
- the "Lens lock: digital crop" notice is shown;
- the UI range is the same as in G1;
- a photo mode reopens on the GPU viewfinder once and then stays on it.
Zoom through each range. Then compare preview framing with a 1080p take, a JPEG with and without the aspect crop, and a DNG's DefaultCrop. A HEIC without the aspect crop stays uncropped by design: the log says so. Cover the lens in low light to see the switched-lens warning come and go. Clear the property with `adb shell setprop debug.opencinecam.lenslock.forcecrop 0`.

### G2: GPU-crop fallback on the Razr Fold (2026-10-24)

Debug package on `motorola/blanc_g/blanc:16/W3WB36.36-123-2/a0723-edb3f:user/release-keys`, folded (cover screen), with `debug.opencinecam.lenslock.forcecrop` set to 1. Checked on the wide (2) and the tele (5). The ultra-wide (3) was not checked.

- **Route.** The wide lock pinned the logical camera at 1.0x over a 1x to 8x range. The tele lock pinned it at 3.015x over a 3.015x to 24.1x range, the same ranges as in G1. The "Lens lock: digital crop" notice showed both times.
- **Restarts.** Each lock started the session three times and then stayed on the GPU viewfinder; there was no restart loop. The remembered zoom survived the change from Photo to Photo RAW to Video.
- **JPEG.** Locked to the tele at 6.0x, the JPEG (no aspect crop) is 2006x1506, a centred 1.99x crop of the 4000x3000 frame that matches the preview. At 3.0x it stays 4000x3000.
- **Video.** A 1080p30 HEVC take at 6.0x (1080x1920, portrait) matches the cropped preview.
- **DNG.** On the logical camera pinned at 3x, the HAL delivers the RAW from the wide sensor, not the tele. The DefaultCrop marks the right region: 1321x990 at 3.0x, and 663x497 at 6.0x. So the framing is right, but on this route a DNG is a crop of the wide, with far fewer pixels than the direct route's tele RAW.
- **Lens-switch warning.** It never showed: SAT stayed on the tele while pinned at 3.015x. Low light was not tried.
- **Not checked.** HEIC, a JPEG with the aspect crop, and the ultra-wide lock.
- **Anchor labels.** After 5e03f44 the unlocked bar shows 0.6 / 1.0 / 3.0, matching the locked bar. The first launch after installing showed 0.5 / 1.0 / 2.0 once, and a cold relaunch did not show it again.

### G4: lens calibration on the Razr Fold (2026-10-24)

Debug package on the same build, folded (cover screen), in Photo, SDR source path, with the wide (2) as the reference. The runner held each pair at the switch points (UW at 1.00x and 0.98x, tele at 2.99x and 3.01x), 3 cycles each. Per-cycle frames and fits come from the `LensCalibration` log tag; debuggable builds also keep the frames under the app's `files/calibration-debug/`.

- **Poster at about 1 m with objects at 30 cm (failed).** Both lenses failed with LOW_TEXTURE, LOW_CHROMA, PARALLAX_SUSPECT and DIAGONAL_FALLBACK. The poster aligned between lenses but the foreground did not, so only 3 or 4 of 7 to 10 textured patches agreed. That is a correct rejection.
- **Colour condition defect (fixed).** In every scene the colour fit fell back to gains only. The solver judged the full RGB normal matrix, which luminance dominates in any real scene (cond 2500 to 8100), instead of the chroma system the fit inverts (cond 24 to 71 on the same frames). The solver now uses that chroma condition, plus a minimum chroma energy of 0.0025 of the total. The Razr frames measured 0.029 to 0.040 in the room and 0.006 to 0.010 on the poster, against about 0.0004 for grey with 1 % noise.
- **Room at 1 to 3 m (passed, after the fix).**

  | Lens | Score | dE before -> after | Alignment px before -> after | Flags |
  |---|---|---|---|---|
  | UW (3) | 0.55 | 3.0 -> 2.3 | 12.2 -> 0.16 | LOW_TEXTURE, PARALLAX_SUSPECT |
  | Tele (5) | 0.76 | 3.8 -> 3.5 | 3.1 -> 0.51 | PARALLAX_SUSPECT |

  Saved set: UW ev +0.13 and tele ev -0.13; UW align rotation -1.44 degrees and scale 1.011; tele scale 1.009. Applied offline to the dumped tele frames, the correction moves the mean from 131/116/98 to 124/107/87, against 123/106/87 for the reference.
- **Repeatability.** The pre-fit alignment varied between runs (UW 2.6 to 12.2 px, tele 3.1 to 14.3 px) because the room still has depth, so treat alignment from a parallax-flagged run as scene-specific. The colour numbers were stable within 0.3 dE across cycles.
- **Not checked yet.** Applying the saved set in preview and video (G3).
- **UI.** On the cover screen the capture rail draws over the result card's right edge and hides the quality percentages.

