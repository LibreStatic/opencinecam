# Native pipeline foundation

This directory contains the pinned CMake/NDK-facing native ownership boundary. `Handle` values are opaque, `BufferView` is borrowed only during lookup, and each retained `AHardwareBuffer` is released once by its owner. Android builds call the public NDK release function; host tests use the same handle/error contract with an opaque pointer.

The Android NDK/CMake toolchain is intentionally not auto-enabled in the application until a consumer owns a JNI entry point. Host validation is available with:

```sh
cmake -S native-pipeline -B native-pipeline/build -DBUILD_TESTING=ON
cmake --build native-pipeline/build
ctest --test-dir native-pipeline/build --output-on-failure
```

`gpu_processing.cpp` adds the M11 OpenGL ES boundary without pretending that a
host run is device certification. `probe_gpu` reports EGL 1.5, OpenGL ES 3.1,
`AHardwareBuffer` import, encoder-surface, and direct-encoder capabilities as
Supported/Unsupported/Unknown. `GpuProcessingPipeline` then imports retained
buffers, applies a bounded homogeneous transform, emits an encoder-surface
frame, or selects the explicit direct camera-to-encoder fallback in Adaptive
mode. Fallback decisions are returned with a reason and a user-visible flag;
Strict mode never silently changes the route.

`native_hardening.cpp` keeps native faults, crash evidence, shader/resource
generation recovery, cancellation state, and bounded thermal/performance
samples in explicit host-testable contracts. Context loss marks resources
stale; recovery requires the expected generation and emits a fault event.
Benchmark summaries expose min/max/average duration, queue pressure, dropped
frames, and peak thermal level without claiming device certification.
