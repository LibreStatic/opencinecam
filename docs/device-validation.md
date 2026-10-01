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
