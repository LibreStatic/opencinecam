# OpenCineCam

OpenCineCam is a planned native, open-source Android cinematic camera that distinguishes what hardware advertises, what the app requests, what Camera2 reports, and what a produced file or controlled test proves.

## Current status

The repository is executing its dependency-ordered implementation plans. The Android build, CI quality/instrumentation, licensing/provenance, pure-JVM capability/evidence model, schema serialization, Strict/Adaptive policy, Camera2 identity, stream/HDR capability, codec/audio cache, adaptive probe UI baselines, private SAF report export and sharing, storage benchmarking, result persistence, serialized CaptureService ownership, SurfaceView preview transform/lifecycle, logical/physical graph negotiation, lifecycle recovery, deterministic AVC/HEVC encoder selection, bounded muxer/drain, MediaStore/SAF finalization, AVC/HEVC SDR recorder integration, audio source/effect negotiation, native AHardwareBuffer ownership, host-validated OpenGL ES processing/fallback, bounded native fault/thermal hardening, physical HLG10/BT2020_HLG/Main10 candidate qualification and short recorded-file graph/file evidence on a physical reference foldable, independent Main10/HLG file signaling, controlled-gradient precision qualification, public RAW_SENSOR still/DNG capture and validation, RAW10 parsing/ring contracts with a physical packed-frame pass, the 60-second RAW10 throughput fail branch with RAW-video disabled, append-only RAW journaling/recovery, desktop reader/index recovery/PCM16 extraction/DNG fixtures, truncation/bit-flip/interruption/unknown-chunk/bounded-large-chunk and writer/reader interoperability matrices, host OpenCine Log transform/provenance contracts, host APV qualification/UI fallback, dependency-free desktop tooling, host soak-matrix model/CLI, and release certification checklist contracts are implemented. The next handoff is [`OCC-PLAN-047`](docs/plans/PLAN-047-opencine-log-specification-and-test-vectors.md), which remains ConditionalReady pending accepted Log/P010/ISP provenance; RAW-video promotion remains disabled after the failed throughput gate.

## Invariants

- Public Android APIs and Camera2 in the portable OSS capture path.
- No accounts, analytics, telemetry, cloud dependency, automatic upload, or Internet permission.
- No silent downgrade of camera, codec, frame rate, RAW, HLG, bit depth, or audio state.
- Unknown hardware behavior remains Unknown rather than false or verified.
- Active recording survives UI recreation through a correctly declared foreground service.

## Identity

- Name: **OpenCineCam**
- Namespace/application ID: `com.librestatic.opencinecam`
- License: [Apache-2.0](LICENSE)
- Release channels: F-Droid, signed direct APK, and Google Play

## Source hierarchy

Accepted requirements → Accepted ADRs → architecture and schemas → implementation plans → code and evidence.

Read [technical closure](docs/technical-closure.md), [architecture](docs/architecture.md), and the [plan index](docs/plans/README.md).

## Local quality checks

Use JDK 17 and the checked-in wrapper:

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew --no-daemon --dependency-verification=strict lint test assembleDebug
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew --no-daemon --dependency-verification=strict connectedDebugAndroidTest
./tools/check_format.sh
# Record non-promotional device identity evidence for conditional gates
python3 tools/device_gate.py --output evidence/plan-034/device-gate.json
```
