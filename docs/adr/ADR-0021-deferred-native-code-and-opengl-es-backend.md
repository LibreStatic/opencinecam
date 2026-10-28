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
