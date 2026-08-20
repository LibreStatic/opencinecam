cmake_minimum_required(VERSION 3.22.1)
project(opencinecam_native_pipeline LANGUAGES CXX)

include(${CMAKE_CURRENT_LIST_DIR}/toolchain-versions.cmake)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

add_library(opencinecam_native STATIC src/native_pipeline.cpp src/gpu_processing.cpp src/native_hardening.cpp)
target_include_directories(opencinecam_native PUBLIC include)
target_compile_options(opencinecam_native PRIVATE -Wall -Wextra -Werror -fno-exceptions -fno-rtti)

include(CTest)
if(BUILD_TESTING)
  add_executable(native_pipeline_test tests/native_pipeline_test.cpp)
  target_link_libraries(native_pipeline_test PRIVATE opencinecam_native)
  add_test(NAME native_pipeline_test COMMAND native_pipeline_test)
  add_executable(native_hardening_test tests/native_hardening_test.cpp)
  target_link_libraries(native_hardening_test PRIVATE opencinecam_native)
  add_test(NAME native_hardening_test COMMAND native_hardening_test)
endif()
