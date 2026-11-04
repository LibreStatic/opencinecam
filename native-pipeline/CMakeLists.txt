# SPDX-License-Identifier: Apache-2.0
# Copyright 2026 OpenCineCam contributors

cmake_minimum_required(VERSION 3.22.1)
project(opencinecam_native_pipeline LANGUAGES CXX)

include(${CMAKE_CURRENT_LIST_DIR}/toolchain-versions.cmake)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

add_library(opencinecam_native STATIC src/native_pipeline.cpp src/gpu_processing.cpp src/native_hardening.cpp)
target_include_directories(opencinecam_native PUBLIC include)
target_compile_options(opencinecam_native PRIVATE -Wall -Wextra -Werror -fno-exceptions -fno-rtti)

# Lossless RAW10 codecs and CRC32C (OCC-PLAN-071 slice G). Kept apart from
# opencinecam_native so the media JNI library can compile these two sources alone.
# Optimised even in Debug builds so the full-frame tests stay quick.
find_package(Threads REQUIRED)
add_library(opencinecam_raw_codecs STATIC src/raw_codecs.cpp src/crc32c.cpp)
target_include_directories(opencinecam_raw_codecs PUBLIC include)
target_compile_options(opencinecam_raw_codecs PRIVATE -Wall -Wextra -Werror -fno-exceptions -fno-rtti -O2)
target_link_libraries(opencinecam_raw_codecs PUBLIC Threads::Threads)

include(CTest)
if(BUILD_TESTING)
  add_executable(native_pipeline_test tests/native_pipeline_test.cpp)
  target_link_libraries(native_pipeline_test PRIVATE opencinecam_native)
  add_test(NAME native_pipeline_test COMMAND native_pipeline_test)
  add_executable(native_hardening_test tests/native_hardening_test.cpp)
  target_link_libraries(native_hardening_test PRIVATE opencinecam_native)
  add_test(NAME native_hardening_test COMMAND native_hardening_test)
  add_executable(raw_codecs_test tests/raw_codecs_test.cpp)
  target_compile_options(raw_codecs_test PRIVATE -Wall -Wextra -Werror -O2)
  target_link_libraries(raw_codecs_test PRIVATE opencinecam_raw_codecs)
  add_test(NAME raw_codecs_test COMMAND raw_codecs_test)
  add_executable(crc32c_test tests/crc32c_test.cpp)
  target_compile_options(crc32c_test PRIVATE -Wall -Wextra -Werror -O2)
  target_link_libraries(crc32c_test PRIVATE opencinecam_raw_codecs)
  add_test(NAME crc32c_test COMMAND crc32c_test)
  # Not a test: ratio and speed on a synthetic noisy frame.
  add_executable(raw_codecs_bench tests/raw_codecs_bench.cpp)
  target_compile_options(raw_codecs_bench PRIVATE -O2)
  target_link_libraries(raw_codecs_bench PRIVATE opencinecam_raw_codecs)
endif()
