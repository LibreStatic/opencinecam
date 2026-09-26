# OpenCineCam

OpenCineCam is a planned native, open-source Android cinematic camera that distinguishes what hardware advertises, what the app requests, what Camera2 reports, and what a produced file or controlled test proves.

## Current status

The repository is executing its dependency-ordered implementation plans. The Android build, CI quality checks (instrumented tests run locally only), licensing/provenance, pure-JVM capability/evidence model, schema serialization, Strict/Adaptive policy, Camera2 identity, stream/HDR capability, codec/audio cache, private SAF report export and sharing, storage benchmarking, result persistence, serialized CaptureService ownership, SurfaceView preview transform/lifecycle, logical/physical graph negotiation, lifecycle recovery, deterministic AVC/HEVC encoder selection, bounded muxer/drain, MediaStore/SAF finalization, AVC/HEVC SDR recorder integration, audio source/effect negotiation, native AHardwareBuffer ownership, host-validated OpenGL ES processing/fallback, bounded native fault/thermal hardening, physical HLG10/BT2020_HLG/Main10 candidate qualification and short recorded-file graph/file evidence on a physical reference foldable, independent Main10/HLG file signaling, controlled-gradient precision qualification, public RAW_SENSOR still/DNG capture and validation, RAW10 parsing/ring contracts with a physical packed-frame pass, the 60-second RAW10 throughput fail branch with RAW-video disabled, append-only RAW journaling/recovery, desktop reader/index recovery/PCM16 extraction/DNG fixtures, truncation/bit-flip/interruption/unknown-chunk/bounded-large-chunk and writer/reader interoperability matrices, the launcher-reachable OCLog2 runtime pipeline, the accepted APV unsupported fallback, dependency-free desktop tooling, host soak-matrix evaluation, release-certification evaluation, and signed ARM64 direct GitHub releases are implemented. OCLog2 runtime availability is not itself a qualification claim: [`OCC-PLAN-061`](docs/plans/PLAN-061-oclog2-specification-interchange-and-device-qualification.md) now has a physical GLES numeric pass and three cadence-gated process cold-start 32-second HLG-derived 1080p30/Main10 passes with 961/961 frames, zero inferred drops, and zero PTS intervals above the 1.5× threshold on the reference device. The exact tuple remains experimental because the ISP-derived tier, artifact-bound OCIO/DCTL/LUT evidence, clipping/range measurements, and an independent editor are still open. F-Droid build-server and Google Play activation remain conditional under [`OCC-PLAN-062`](docs/plans/PLAN-062-f-droid-and-google-play-channel-activation.md); RAW-video promotion remains disabled after the failed throughput gate.

## What the app does today

- Capture modes: photo, burst, bracket, light trail, video, slow motion and time-lapse; RAW photo, OCLog2 LOG (Main10), APV and RAW video are gated experimental modes that stay unavailable unless the device qualifies (APV and RAW video currently take their documented unsupported branch).
- Recording: hardware AVC/HEVC video (software AVC only for time-lapse), audio as an AAC MP4 track or separate lossless WAV/FLAC sidecars, shared VIDEO/LOG pause, SMPTE timecode (JSON sidecar) and production slate metadata.
- Monitoring and operation: focus peaking, zebra, histogram, grid, horizon level, operator/subject/recording LUTs, LOG view assist, assignable operator buttons, persistent settings and portable presets, fold/exterior-display subject preview.
- Media: on-device catalog with playback, rename, share and delete, plus a durable queue for on-device editing proxies.
- Optional, off by default: WebDAV transfers of finalized takes (explicit per-bundle action, HTTPS only, Wi-Fi unless cellular is allowed; no background scheduler yet) and photo/take geotagging.

The standalone hardware probe screen (`ProbeScreen`) is no longer reachable from the launcher.

## Invariants

- Public Android APIs and Camera2 in the portable OSS capture path.
- No accounts, analytics, telemetry, cloud dependency, or automatic upload. Network access exists only for the opt-in WebDAV transfers above.
- No silent downgrade of camera, codec, frame rate, RAW, HLG, bit depth, or audio state.
- Unknown hardware behavior remains Unknown rather than false or verified.
- Active recording survives UI recreation through a correctly declared foreground service.

## Identity

- Name: **OpenCineCam**
- Namespace/application ID: `com.librestatic.opencinecam`
- License: [Apache-2.0](LICENSE)
- Release channels: F-Droid, signed direct APK, and Google Play

## Permissions

| Permission | Used for |
|---|---|
| `CAMERA` | Capture and preview. |
| `RECORD_AUDIO` | Optional audio tracks, sidecars and meters; video still records without it. |
| `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CAMERA`, `FOREGROUND_SERVICE_MICROPHONE` | The visible foreground capture service that keeps a take alive across UI recreation. |
| `INTERNET`, `ACCESS_NETWORK_STATE` | Opt-in WebDAV transfers only (PLAN-067 / ADR-0034). Off by default; presets never enable networking; nothing is uploaded without an explicit user action. |
| `ACCESS_COARSE_LOCATION`, `ACCESS_FINE_LOCATION` | Opt-in photo/take geotagging only, requested at runtime while the camera screen is open. Off by default. |

No broad storage permission is requested: MediaStore and the Storage Access Framework own destinations.

## Source hierarchy

Accepted requirements → Accepted ADRs → architecture and schemas → implementation plans → code and evidence.

Read [technical closure](docs/technical-closure.md), [architecture](docs/architecture.md), and the [plan index](docs/plans/README.md).

## Local quality checks

Use JDK 17 and the checked-in wrapper:

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew --no-daemon --dependency-verification=strict lint test assembleDebug
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew --no-daemon --dependency-verification=strict connectedDebugAndroidTest
./tools/check_format.sh
python3 -m unittest discover -s tools/tests -p 'test_*.py'
# Plan graph; add --require-evidence for release certification (evidence/ is not in a clean checkout)
python3 tools/validate_plan_system.py
# Record non-promotional device identity evidence for conditional gates
python3 tools/device_gate.py --output evidence/plan-034/device-gate.json
```
