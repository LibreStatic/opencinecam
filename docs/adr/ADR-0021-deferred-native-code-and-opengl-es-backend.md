# ADR-0021: Deferred native code and OpenGL ES backend

- Status: Accepted
- Date: 2026-08-20
- Decision owners: OpenCineCam architecture maintainers
- Related requirements:
- OCC-NFR-001
- OCC-COLOR-001
- Related risks: RISK-001, RISK-004, RISK-009
- Supersedes: None
- Superseded by: None

## Context

The empty repository needs a portable public-API architecture that distinguishes advertised, requested, reported, file-proven, and empirical behavior. `init.md`, the requirements baseline, and primary references define the constraints.

## Decision

Introduce NDK only at M11, cross explicit handles through JNI, use AHardwareBuffer acquire/release, select OpenGL ES 3.1/EGL first, and preserve direct encoder fallback.

## Alternatives considered

Rejected alternatives: Early C++; Vulkan first; opaque JNI object ownership. They weaken ownership, portability, evidence quality, lifecycle safety, or bounded delivery.

## Consequences

### Positive

The choice is deterministic, testable, traceable, and preserves technical honesty across OEM behavior.

### Negative

Implementation carries explicit compatibility branches, evidence storage, and device-test cost instead of optimistic feature exposure.

### Operational consequences

Failures produce a stable error/evidence record and execute Strict or the documented Adaptive branch. No executor can replace the choice inside an implementation plan.

## Validation evidence

Primary references in `docs/references.md`, plan acceptance tests, schemas, and versioned device/file evidence validate this decision. Hardware-dependent outcomes remain ConditionalReady rather than assumed.

## Implementation constraints

Use public Android APIs, bounded resources, immutable identifiers, explicit Unknown values, local-only data, and no silent degradation. Leave the repository buildable after each plan.

## Affected plans

OCC-PLAN-037-039

## Revisit conditions

Revisit only when official platform contracts, measured device evidence, release policy, or an accepted superseding ADR invalidate an assumption. Evidence contradiction blocks the affected plan until review.

## Amendment (2026-10-28)

- Status: Accepted
- Date: 2026-10-28

### Context

The shipped GPU path never crossed JNI. Every device frame runs through Kotlin `EGL14`/`GLES30` code in `:camera`: Camera2 writes into a `SurfaceTexture` (`GL_TEXTURE_EXTERNAL_OES`), and `OpenCineLogGpuPipeline` renders it on its own GL thread to the viewfinder, the encoder input surface and the analysis readback, sharing one reference-counted EGL display through `GpuEglDisplayLease`. `native-pipeline/` is not a Gradle module, no class calls `System.loadLibrary` or declares an `external fun`, and its CMake build and tests run on the host only, as its README says. See [the GPU frame pipeline](../flows/gpu-frame-pipeline.md).

### Decision

Record the shipped backend as the Kotlin OpenGL ES 3.0 path above. The handle, `AHardwareBuffer` ownership and fallback contracts in `native-pipeline/` stay as a host-tested reference and are not part of the APK. NDK code enters the app only when a feature needs something the Kotlin GL path cannot do (for example `AHardwareBuffer` import for zero-copy RAW or APV processing), under the original handle and ownership rules.

### Consequences

- The app ships no native libraries of its own; no ABI split or NDK toolchain is needed for release builds.
- Device evidence for the GPU path comes from the Kotlin pipeline (GLES numeric checks, Main10 takes), not from `native-pipeline` host runs, which certify nothing about a device.
- Synchronisation relies on `SurfaceTexture.updateTexImage` and `eglSwapBuffers` fences plus the sensor timestamp; explicit `glFenceSync` is used only by the cover preview output.

## Amendment (2026-11-02): RAW video codecs

- Status: Accepted
- Date: 2026-11-02

### Context

RAW video (PLAN-071) writes 234–936 MB/s of RAW10. On ART the Java CRC32C is not intrinsified and tops out around 245 MB/s, and lossless Bayer compression in Kotlin costs about 340 ms per frame. Both need NEON, which the Kotlin GL path cannot provide.

### Decision

`:media` builds `libocc_rawcodec.so` (`media/src/main/cpp`) from the `native-pipeline/` CRC32C and RAW10 codec sources, for `arm64-v8a` and `x86_64`, behind a small JNI surface (`NativeCrc32c`, `NativeRawCodec`). It is the first JNI consumer. Handles stay explicit: Kotlin owns every direct buffer, and native code never retains a pointer past a call. A missing or unloadable library leaves the Java CRC32C and disables LJ92 configurations; it never changes the file format.

### Consequences

- The amendment of 2026-10-28 no longer holds that the app ships no native libraries: release builds need the NDK and CMake, and the APK carries `libocc_rawcodec.so` per ABI.
- The GPU path is unchanged and still crosses no JNI.
- Codec correctness is covered by the host ctest in `native-pipeline/` and by the device runs of `RawThroughputDeviceTest`, which record encode time, ratio and CPU per LJ92 configuration.
