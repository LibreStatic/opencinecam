# Native pipeline foundation

This directory contains the pinned CMake/NDK-facing native ownership boundary. `Handle` values are opaque, `BufferView` is borrowed only during lookup, and each retained `AHardwareBuffer` is released once by its owner. Android builds call the public NDK release function; host tests use the same handle/error contract with an opaque pointer.

The first JNI consumer is `:media` (OCC-PLAN-071 phase 2): `media/src/main/cpp/CMakeLists.txt` compiles only `src/raw_codecs.cpp` and `src/crc32c.cpp` (with a JNI file) into `libocc_rawcodec.so` for arm64-v8a and x86_64, and `NativeRawCodec` / `Crc32c` load it lazily, so host JVM tests run without it. The rest of this directory is still host-only. Host validation is available with:

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

`raw_codecs.cpp` holds the two lossless RAW10 frame codecs compared for RAW
video, LJ92 (DNG Compression 7, CFA layout) and a Bayer block-pack, with a
persistent thread pool, strip-parallel encode and decode, and NEON kernels on
arm64 beside the scalar ones; `include/opencinecam/raw_codecs.h` specifies the
container and both stream formats. `crc32c.cpp` is CRC32C on the ARMv8 CRC32
instructions with a slicing-by-8 fallback. `build/raw_codecs_bench [noise]
[repeats]` prints host ratio and speed on a synthetic noisy 4080x3060 frame; the
device numbers come from `RawCodecComparisonDeviceTest`.
