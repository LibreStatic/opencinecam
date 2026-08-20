/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

#include "opencinecam/native_pipeline.h"

#include <mutex>
#include <unordered_map>

#if defined(__ANDROID__)
#include <android/hardware_buffer.h>
#endif

namespace opencinecam {
namespace {
std::mutex g_mutex;
std::unordered_map<Handle, BufferView> g_buffers;
}  // namespace

NativeError retain_buffer(Handle handle, BufferView view) {
  if (handle == 0) return NativeError::kInvalidHandle;
  if (view.buffer == nullptr) return NativeError::kNullBuffer;
  std::lock_guard<std::mutex> lock(g_mutex);
  if (g_buffers.contains(handle)) return NativeError::kAlreadyReleased;
  g_buffers.emplace(handle, view);
  return NativeError::kOk;
}

NativeError release_buffer(Handle handle) {
  if (handle == 0) return NativeError::kInvalidHandle;
  std::lock_guard<std::mutex> lock(g_mutex);
  const auto it = g_buffers.find(handle);
  if (it == g_buffers.end()) return NativeError::kAlreadyReleased;
#if defined(__ANDROID__)
  // Ownership is explicit: the owner that retained this handle releases exactly once.
  ::AHardwareBuffer_release(it->second.buffer);
#endif
  g_buffers.erase(it);
  return NativeError::kOk;
}

NativeError lookup_buffer(Handle handle, BufferView* out_view) {
  if (handle == 0 || out_view == nullptr) return NativeError::kInvalidHandle;
  std::lock_guard<std::mutex> lock(g_mutex);
  const auto it = g_buffers.find(handle);
  if (it == g_buffers.end()) return NativeError::kInvalidHandle;
  *out_view = it->second;
  return NativeError::kOk;
}

}  // namespace opencinecam
