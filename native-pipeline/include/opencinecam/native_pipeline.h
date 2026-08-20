/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

#pragma once

#include <cstddef>
#include <cstdint>
#include <array>
#include <unordered_map>

struct AHardwareBuffer;

namespace opencinecam {

enum class NativeError : std::uint8_t {
  kOk = 0,
  kInvalidHandle,
  kNullBuffer,
  kAlreadyReleased,
  kUnsupported,
  kCapacityExceeded,
  kUnknownCapability,
  kInvalidArgument,
  kDuplicateCommand,
  kCancelled,
  kNotReady,
};

using Handle = std::uint64_t;

struct BufferView {
  AHardwareBuffer* buffer = nullptr;
  std::size_t byte_capacity = 0;
};

NativeError retain_buffer(Handle handle, BufferView view);
NativeError release_buffer(Handle handle);
NativeError lookup_buffer(Handle handle, BufferView* out_view);

enum class CapabilityState : std::uint8_t {
  kUnknown = 0,
  kUnsupported,
  kSupported,
};

enum class OutputRoute : std::uint8_t {
  kUnknown = 0,
  kOpenGlEncoderSurface,
  kDirectCameraEncoder,
};

enum class GpuPolicy : std::uint8_t {
  kStrict = 0,
  kAdaptive,
};

enum class FallbackReason : std::uint8_t {
  kNone = 0,
  kOpenGlUnsupported,
  kOpenGlUnknown,
  kOpenGlRuntimeFailure,
};

struct GpuProbeInput {
  std::uint8_t egl_major = 0;
  std::uint8_t egl_minor = 0;
  std::uint8_t gles_major = 0;
  std::uint8_t gles_minor = 0;
  CapabilityState context = CapabilityState::kUnknown;
  CapabilityState ahardware_buffer_import = CapabilityState::kUnknown;
  CapabilityState encoder_surface = CapabilityState::kUnknown;
  CapabilityState direct_encoder = CapabilityState::kUnknown;
};

struct GpuFeatureReport {
  CapabilityState egl = CapabilityState::kUnknown;
  CapabilityState gles31 = CapabilityState::kUnknown;
  CapabilityState ahardware_buffer_import = CapabilityState::kUnknown;
  CapabilityState encoder_surface = CapabilityState::kUnknown;
  CapabilityState direct_encoder = CapabilityState::kUnknown;
};

struct GpuRouteDecision {
  OutputRoute route = OutputRoute::kUnknown;
  FallbackReason fallback_reason = FallbackReason::kNone;
  bool user_visible_fallback = false;
};

struct GpuProbeResult {
  NativeError status = NativeError::kNotReady;
  GpuFeatureReport report{};
  GpuRouteDecision decision{};
};

NativeError probe_gpu(const GpuProbeInput& input, GpuPolicy policy,
                      GpuProbeResult* out_result);

struct TransformMatrix {
  std::array<float, 9> values{1.0F, 0.0F, 0.0F,
                              0.0F, 1.0F, 0.0F,
                              0.0F, 0.0F, 1.0F};
};

struct Coordinate {
  float x = 0.0F;
  float y = 0.0F;
};

NativeError apply_transform(const TransformMatrix& matrix, Coordinate input,
                            Coordinate* out_coordinate);

struct ImportedBuffer {
  std::uint64_t import_id = 0;
  Handle source_handle = 0;
  std::size_t byte_capacity = 0;
};

struct ProcessedFrame {
  std::uint64_t command_id = 0;
  std::uint64_t frame_id = 0;
  std::uint64_t import_id = 0;
  std::uint64_t encoder_surface = 0;
  OutputRoute route = OutputRoute::kUnknown;
  Coordinate transformed_origin{};
};

struct GpuPipelineConfig {
  GpuProbeInput probe{};
  GpuPolicy policy = GpuPolicy::kStrict;
  std::size_t max_imported_buffers = 8;
  std::size_t max_commands = 64;
};

class GpuProcessingPipeline {
 public:
  GpuProcessingPipeline() = default;

  static NativeError create(const GpuPipelineConfig& config,
                            GpuProcessingPipeline* out_pipeline);

  NativeError import_buffer(Handle source_handle, std::size_t required_bytes,
                            ImportedBuffer* out_buffer);
  NativeError release_import(std::uint64_t import_id);
  NativeError submit(std::uint64_t command_id, std::uint64_t frame_id,
                     std::uint64_t import_id, TransformMatrix transform,
                     std::uint64_t encoder_surface,
                     ProcessedFrame* out_frame);
  NativeError cancel(std::uint64_t command_id);
  NativeError close();

  const GpuProbeResult& probe() const { return probe_result_; }
  bool closed() const { return closed_; }

 private:
  struct CommandRecord {
    bool cancelled = false;
    bool completed = false;
  };

  GpuPipelineConfig config_{};
  GpuProbeResult probe_result_{};
  std::unordered_map<std::uint64_t, ImportedBuffer> imports_;
  std::unordered_map<std::uint64_t, CommandRecord> commands_;
  std::uint64_t next_import_id_ = 1;
  bool initialized_ = false;
  bool closed_ = false;
};

}  // namespace opencinecam
