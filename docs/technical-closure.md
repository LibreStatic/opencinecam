# Technical Closure

## Repository baseline

Inspection found only `init.md`; there was no Git repository, Android project, README, license, tests, CI, modules, or connected ADB device. Host tools: Git 2.53, JDK 17 and default JDK 26, Python 3.14.6, FFmpeg/ffprobe 8.1, Android SDK APIs 35/36/36.1/37, build-tools through 37, NDK 27.1, CMake 3.22.1/4.3.1, and no standalone Gradle. Python XML parsing is broken by a host pyexpat symbol mismatch. No product build can truthfully run before OCC-PLAN-001.

Official 2026-08-20 baselines close API 37, AGP 9.3.1, Gradle 9.5, JDK 17, built-in Kotlin 2.2.10, and Compose BOM 2026.08.00. Build commands must select `/usr/lib/jvm/java-17-openjdk`; the future wrapper supplies Gradle. NDK 28.2 is installed only at M11.

## Closed decisions

| Area | Decision | ADRs | Consequence | Validation/revisit |
|---|---|---|---|---|
| Identity/build | OpenCineCam, `com.librestatic.opencinecam`, min 29/target 37, pinned modern toolchain | ADR-0001 | Runtime guards preserve API 29 | Revisit on supported-tool EOL |
| Modules | Staged small modules, pure domain, no DI framework | ADR-0002 | No empty-module bootstrap | Revisit on measured build/ownership pressure |
| Ownership/lifecycle | One service capture actor; recording FGS; immutable clip graph | ADR-0003/0004 | UI cannot own hardware | State/fault/device tests |
| Evidence/camera | Layered evidence, exact invalidation, public enumeration/routing, graph gates | ADR-0005–0009 | API advertising never equals verification | Protocol-version change |
| Media | Deterministic codec, muxer actor, explicit audio clocks/effects, MediaStore/SAF | ADR-0010–0013 | Valid finalization and A/V evidence | File/device tests |
| Schemas/policy/performance | JSON 2020-12, Strict default, bounded queues and degradation | ADR-0014–0016 | Inspectable state and no silent downgrade | Schema major/performance evidence |
| UX/color/future | Accessible original UI, HLG gates, RAW, `.ocraw`, GL, Log process, APV | ADR-0017–0023 | Future modes remain unavailable until proof | Mode-specific gates |
| Trust/release | Narrow profiles, local logs, evidence pyramid, no Internet, Apache-2.0, three channels | ADR-0024–0028 | Portable private FOSS build | Policy/license changes |

## Conditional runtime decisions

| Capability | Candidate and verification | Pass | Fail |
|---|---|---|---|
| HLG10 | API 33+, Camera2 ten-bit+HLG10 constraints, Main10 Surface encoder, session/first frame, storage; then Main10/BT.2020/HLG file and controlled precision tests | Expose exact verified level | Show unsupported/reason; never SDR fallback |
| RAW video | Public RAW format, compatible graph, first frame, bounded ring, 60-second no-loss test, storage p01 ≥ 1.25× rate | Expose exact size/FPS/destination | Preserve RAW still where valid; hide RAW video |
| OpenCine Log | Accepted v1 math/gamut/vectors/transforms plus verified RAW/P010/ISP provenance | Expose provenance-qualified mode | Keep HLG10/Flat8 naming; no Log label |
| APV | API 36+, `video/apv`, supported profile/chroma/rate/Surface, MP4, storage, extract/decode, sustained test | Expose qualified configuration | Probe report only; use explicitly selected non-APV mode |
| Certification | Exact fingerprint/release passes full device, fault, privacy, accessibility, and release protocol | Mark Certified | Retain lower evidence stage and failures |

## External hardware conditions

No device is connected, so sustainable RAW throughput, effective camera-to-file precision, OEM ISP/audio behavior, physical routing adherence, thermal duration, fold posture behavior, and APV availability are Unknown. Plans 034–036, 040–042, 047–053, and 060 contain tests, thresholds, pass/fail behavior, and documentation effects. No result is invented.

## Closure gate

**Status: Conditionally closed.** Requirements, terminology, ownership, state machine, capability/evidence model, request/result semantics, codec/mux/audio/storage policies, schemas, fallback, foldables, errors, testing levels, native/GPU boundary, HLG, RAW, Log process, APV, licensing, risks, and deterministic hardware gates are closed. Remaining conditions are exclusively measured hardware or release evidence. Implementation plans can be Ready or ConditionalReady; Luna receives no unresolved architecture choice.
