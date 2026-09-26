# OpenCineCam Architecture

## Platform and repository

The portable application is `com.librestatic.opencinecam`, minSdk 29, compile/target 37, AGP 9.3.1, Gradle 9.5, JDK 17, built-in Kotlin 2.2.10, Compose BOM 2026.08.00, Kotlin DSL, and a version catalog. Release dependencies are pinned and verified. No Internet permission or product flavor exists initially. Debug, release, and benchmark build types are planned. NDK 28.2.13676358 and CMake 3.22.1 enter only at M11; release native ABI is arm64-v8a and test/emulator ABI is x86_64.

Modules are introduced when used: `:app` owns Compose, service composition, permissions, and navigation; pure JVM `:core:model` now owns immutable evidence, knowledge, observation, graph, and cache-validity types; `:core:testing` owns fakes/fixtures; `:camera` translates Camera2; `:media` owns codecs/audio/mux/storage; `:benchmark`, `:native-pipeline`, and `:desktop-tools` arrive in their milestones. Framework types cannot enter `:core:model`. Constructor injection and interfaces replace a DI framework.

## Capture ownership and concurrency

One same-process bound `CaptureService` owns CameraDevice, CameraCaptureSession, MediaCodec instances, AudioRecord, muxer, and descriptors. A local Binder exposes typed commands and `StateFlow<CaptureState>`. A dedicated serialized actor accepts commands/callback events; camera callbacks, codec drainers, audio reads, muxer, and storage each use bounded non-main execution. No Activity owns hardware.

States are `Stopped`, `Opening`, `Previewing`, `PreparingRecording`, `Recording`, `Stopping`, `Recovering`, and `Failed`, with structured state data rather than independent Booleans. Invalid transitions return stable failures. Recording promotion to camera/microphone foreground service occurs while the Activity is visible. Preview-only work closes when UI is absent. During UI surface loss, recording targets continue; preview reattachment uses output update when verified and otherwise controlled session recreation. Any cadence discontinuity is detected and sidecar-recorded; Strict stops on a critical discontinuity.

## Preview and foldables

Compose embeds a `SurfaceView` through `AndroidView`. Default preview is aspect-fit with visible letterbox; crop/fill is explicit and never alters recording. Preview display rotation and physical recording orientation are separate inputs: `Display.getRotation()` never determines file geometry, because secondary/foldable displays may use a different natural axis. A sensor-backed physical quadrant is latched at record start. Recording is unmirrored unless explicitly configured. Jetpack WindowManager 1.5.1 supplies window/folding information; no vendor cover-display API is assumed. Controls reflow without changing the stream. Loss of the selected camera stops Strict recording; Adaptive never switches sensor mid-clip.

## Domain and evidence

`CaptureIntent` is immutable. `ResolvedCaptureGraph` names camera/physical route, outputs, size, FPS, dynamic range, color space, codec/profile, bitrate, audio, and policy. `CapabilityEvidence<T>` records stage, outcome, source, time, protocol, and details. Candidate → SessionCreated → FirstFrameReceived → Recorded → FileVerified → Sustained → EmpiricallyVerified → Certified are monotonic only for the exact graph and fingerprint. Failure/staleness is retained rather than erased.

Cached evidence is scoped by schema major, validation protocol, app probe version, build fingerprint/security patch, camera-characteristics digest, camera/physical ID, codec name, and graph ID. Changes mark empirical evidence stale and trigger re-probe. Unknown is never false.

## Camera and stream graphs

Enumeration uses CameraManager, CameraCharacteristics, physical IDs, focal lengths, active array, manual/RAW capabilities, StreamConfigurationMap, dynamic-range profiles, color-space profiles, concurrent camera IDs, and result keys. Lens roles are labeled as inferred and displayed with equivalent focal length; hidden hardware is only described generically as unavailable to public APIs.

A graph includes preview, encoder, RAW/YUV/P010/analysis surfaces, physical routing, dynamic range, color space, FPS, and use-case constraints. Independent advertisements never imply coexistence. Graph evidence records configuration failure, first result/image/output, buffer limits, cadence, queue depths, thermal state, and storage rate.

Physical routing uses `OutputConfiguration.setPhysicalCameraId`; `LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID` is audited when available. A missing report remains Requested/Unknown. Strict refuses a required unverified route; Adaptive can select the logical camera before recording with disclosure but never during a clip.

## Request composition and hardware truth

The composer applies deterministic precedence: manual exposure sets AE off and validates sensitivity, exposure, and frame duration; shutter time is `angle / 360 × frame period`; manual focus sets AF off; manual gains/matrix require manual post-processing capability; stabilization and ISP controls are explicit. Strict rejects out-of-range/conflicting intent. Adaptive clamps only within advertised ranges and reports the resolution before start.

Every CaptureResult is correlated by frame number and sensor timestamp. Critical observations record changes; stable values sample at 1 Hz; debug/lab mode can opt into full-frame metadata. Route, dynamic range, frame cadence, exposure, focus, ISO, AWB, stabilization, and processing differences appear in the Hardware Truth UI and sidecar. Reported metadata is not empirical proof.

## Video, muxing, and files

Encoder selection requires MIME, Surface color format, profile/level, aligned size, rate range, bitrate range, and covering performance point. Qualifying hardware codecs precede software codecs; codec name breaks ties deterministically. Initial AVC/HEVC uses VBR, one-second keyframe interval, and no requested B-frames. Adaptive preflight may try another encoder with the same MIME/profile/bit depth, lower bitrate, then lower resolution/FPS; codec-family changes require visible confirmation. HLG/RAW/10-bit never downgrades to SDR8.

Normal Video and OCLog share one GPU-to-Surface-encoder geometry path: SDR is an identity shader into AVC, while OCLog applies its pinned transform into HEVC Main10. `Compatible` output rotates the quad and swaps encoder dimensions for portrait, leaving the container matrix at zero. `Native raster` keeps sensor dimensions and writes the latched rotation matrix. Both request square-pixel SAR 1:1. Orientation is immutable for a clip; an unsupported portrait encoder size rejects start rather than silently changing mode.

A single muxer actor buffers bounded output until tracks have formats, normalizes to a common microsecond origin, orders samples, signals EOS, drains, stops, releases, closes, then clears MediaStore `IS_PENDING`. Graceful partial failures publish an explicitly incomplete clip if the muxer can finalize; unfinalizable and zero-length entries are deleted and diagnostics retained. Stop order is camera repeating stop, video EOS, AudioRecord stop/audio EOS, drains, muxer finalization, descriptor close, publication.

## Audio and clocks

Source choices are capability-backed: UNPROCESSED is exposed only when its AudioManager property is true, alongside VOICE_RECOGNITION and MIC. The UI retains correlated AudioRecord tuples for 44.1/48/88.2/96/192 kHz, PCM16/packed-PCM24/float32, and mono/stereo only when initialization succeeds. Standard video embeds configurable AAC-LC using app-owned AudioRecord and MediaCodec into the shared MP4 muxer. Lossless audio is a basename-paired PCM WAV sidecar, including OCLog2, with monotonic alignment and an `.audio.json` requested/effective report. WAV bitrate is derived rather than selected.

NS/AGC/AEC are attached and reported only when their app-owned AudioRecord session exposes verifiable effect state; the current embedded AAC path does not claim those effects. A preferred AudioDeviceInfo may be selected, but public APIs do not guarantee an individual built-in capsule and OEM DSP remains Unknown. FLAC is gated on a validated streaming container writer and external decoder fixtures. See ADR-0029.

AudioTimestamp TIMEBASE_MONOTONIC maps frame position to time. Video encoder PTS is the file clock; capture sensor timestamps remain correlated metadata with declared source. No time stretching occurs initially. Absolute drift above 40 ms for five seconds is a critical Strict stop; Adaptive continues with a persistent warning and sidecar event. Overruns create explicit gaps; video-only capture requests no audio resources.

## Storage and performance

MediaStore with `IS_PENDING` is the default; SAF enables selected local/USB destinations. The owner retains each descriptor. Reserve is the greater of 1 GiB or two minutes of projected data. Sequential benchmark writes use 8 MiB buffers for 30 seconds or 1 GiB, after a five-second warmup, record one-second windows, flush at completion, and delete the fixture. RAW qualification requires p01 throughput ≥ 1.25 × projected stream rate and no write error/window below stream rate.

Runtime degradation order is UI animation → vectorscope/waveform → false color/peaking → histogram/zebra → analysis stream → preview resolution/frame rate. Recording format does not change in-place. Strict stops on cadence, audio, storage, route, thermal-critical, or integrity failure. Adaptive can continue video after audio failure and can reduce monitoring, but logs the exact transition; it also stops rather than changing camera/RAW/bit depth/dynamic range.

## Color, native, RAW, and APV

SDR8, Clean SDR, Flat8, HLG10, and Log names remain distinct. HLG candidate requires API 33+, ten-bit dynamic-range capability, HLG10 constraints, Main10 Surface encoder, compatible graph, and storage. File verification requires Main10 plus BT.2020/HLG signaling; effective precision requires controlled gradient/test evidence and is a separate label.

M11 adds C++/JNI only for measurable buffer/GPU work. OpenGL ES 3.1/EGL is the first backend, with AHardwareBuffer ownership expressed as explicit acquire/release handles. Direct camera-to-encoder remains fallback. Vulkan is revisited only if an accepted performance ADR supersedes this decision.

RAW still uses public formats and DngCreator. RAW video uses a byte-bounded ring and stops before ImageReader saturation. OpenCine RAW v1 (`.ocraw`) is append-only: fixed header/UUID, typed length-delimited chunks, canonical CBOR metadata, payload CRC32C, periodic indexes, PCM16 audio, final index/footer, and recovery scan to the last valid chunk. V1 sensor payload is uncompressed.

OCLog2 has a launcher-reachable experimental runtime with distinct HLG10-derived and SDR ISP-derived source tiers, but it cannot be described as qualified until its versioned specification, scene-linear domain/gamut, transfer function, middle gray, range, independent vectors, CPU/GPU references, LUT/OCIO/DCTL, metadata, tolerances, editor workflow, and exact physical fixtures pass OCC-PLAN-061. Provenance is mandatory, and Main10 output never proves source precision. APV requires API 36+, `video/apv`, qualifying profile/chroma/rate/Surface input, MP4 support, storage, independent extraction/decoding, and device qualification. APV RAW is a separate research verdict.

## Privacy, diagnostics, and trust

Portable manifests use CAMERA, optional RECORD_AUDIO, notification, foreground-service, and camera/microphone FGS permissions for capture, plus INTERNET/ACCESS_NETWORK_STATE for opt-in WebDAV transfers (ADR-0034) and COARSE/FINE location for opt-in geotagging; both features are off by default. Components default non-exported; immutable PendingIntents and content URI grants are required. No broad storage permission exists.

Structured local NDJSON logs use session/recording/correlation IDs, roll at five 2 MiB files, omit per-frame data in release, and are exported manually as a redacted ZIP by default. Bundled profiles are release-trusted; imported profiles are untrusted and require confirmation. User overrides can narrow/disable behavior but never promote evidence. Quirks match exact fingerprint/camera/codec scope and expire on validity changes.
