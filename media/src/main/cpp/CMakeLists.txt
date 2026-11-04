# SPDX-License-Identifier: Apache-2.0
# Copyright 2026 OpenCineCam contributors

# libocc_rawcodec.so: the RAW10 lossless codecs and CRC32C from native-pipeline/
# behind a small JNI surface (NativeRawCodec, NativeCrc32c). The first JNI
# consumer under ADR-0021; only these two sources are compiled.

cmake_minimum_required(VERSION 3.22.1)
project(occ_rawcodec LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

set(OCC_NATIVE_PIPELINE ${CMAKE_CURRENT_LIST_DIR}/../../../../native-pipeline)

add_library(occ_rawcodec SHARED
  occ_rawcodec_jni.cpp
  ${OCC_NATIVE_PIPELINE}/src/raw_codecs.cpp
  ${OCC_NATIVE_PIPELINE}/src/crc32c.cpp)
target_include_directories(occ_rawcodec PRIVATE ${OCC_NATIVE_PIPELINE}/include)

# -O3 in every variant: the device codec benchmarks run from debug APKs. The
# scalar kernels stay compiled beside NEON so a device test can compare them.
target_compile_options(occ_rawcodec PRIVATE
  -O3 -Wall -Wextra -Werror -fno-exceptions -fno-rtti -fvisibility=hidden)
if(ANDROID_ABI STREQUAL "arm64-v8a")
  # ARMv8.2-A (Razr 50, Galaxy S24) brings the CRC32 instructions.
  target_compile_options(occ_rawcodec PRIVATE -march=armv8.2-a)
endif()
# 16 KB page compatibility (Android 15+ devices, Play requirement).
target_link_options(occ_rawcodec PRIVATE -Wl,--gc-sections -Wl,-z,max-page-size=16384)
