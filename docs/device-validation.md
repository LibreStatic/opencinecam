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
- Cadence: the MediaRecorder and OCLog GPU paths stamp frames with the sensor timestamp, so a 30 fps stretch inside a 60 fps take is stored with real timestamps (variable frame rate). Diagnostics may report PTS gaps. Off-speed project timing (`ProjectFrameTimeline`) assigns a constant cadence per frame, which is why the switch is ignored there.

Verify: record in a dim room with the switch on, then `adb shell dumpsys media.camera` and read "Last request sent" for the keys above; `android.sensor.frameDuration` should read ~33.3 ms in dim light and ~16.7 ms in bright light or with the switch off.

Razr result 2026-10-10 with the switch off: `IQdynamicFPS_enable` = 0 reaches the HAL, but in a very dark room (ISO ~12000) the app still records ~33 fps at 4K60 in VIDEO, HLG and HDR10. The capture result reads exposureTime 30.0 ms and frameDuration 30.18 ms although aeTargetFpsRange is [60,60]: the HAL's auto exposure stretches the exposure past the frame time instead of raising the gain further. This is not the dynamic FPS feature. The following did not change it: sending `dynamicFPSConfig` = [2, 60, 60] per request, `com.lenovo.moto.clientapp.is_motcamera2` = 1 with `current_mode` = 1, and an exposure-time hint. Shutter priority (the app's manual-AE emulation, 1/183) held 16.67 ms frames, so true 60 fps. In comparable light the stock camera ran 15.6 ms at ISO 13653.
