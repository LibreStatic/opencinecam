/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

#include "opencinecam/native_pipeline.h"

#include <cmath>

namespace opencinecam {
namespace {

CapabilityState version_capability(CapabilityState context, std::uint8_t major,
                                    std::uint8_t minor, std::uint8_t required_major,
                                    std::uint8_t required_minor) {
  if (context == CapabilityState::kUnknown) return CapabilityState::kUnknown;
  if (context == CapabilityState::kUnsupported) return CapabilityState::kUnsupported;
  if (major > required_major ||
      (major == required_major && minor >= required_minor)) {
    return CapabilityState::kSupported;
  }
  return CapabilityState::kUnsupported;
}

bool all_supported(const GpuFeatureReport& report) {
  return report.egl == CapabilityState::kSupported &&
         report.gles31 == CapabilityState::kSupported &&
         report.ahardware_buffer_import == CapabilityState::kSupported &&
         report.encoder_surface == CapabilityState::kSupported;
}

bool any_unknown(const GpuFeatureReport& report) {
  return report.egl == CapabilityState::kUnknown ||
         report.gles31 == CapabilityState::kUnknown ||
         report.ahardware_buffer_import == CapabilityState::kUnknown ||
         report.encoder_surface == CapabilityState::kUnknown;
}

}  // namespace

NativeError probe_gpu(const GpuProbeInput& input, GpuPolicy policy,
                      GpuProbeResult* out_result) {
  if (out_result == nullptr) return NativeError::kInvalidArgument;

  GpuProbeResult result{};
  result.report.egl = version_capability(input.context, input.egl_major,
                                         input.egl_minor, 1, 5);
  result.report.gles31 = version_capability(input.context, input.gles_major,
                                            input.gles_minor, 3, 1);
  result.report.ahardware_buffer_import = input.ahardware_buffer_import;
  result.report.encoder_surface = input.encoder_surface;
  result.report.direct_encoder = input.direct_encoder;

  if (all_supported(result.report)) {
    result.status = NativeError::kOk;
    result.decision.route = OutputRoute::kOpenGlEncoderSurface;
  } else if (any_unknown(result.report)) {
    if (policy == GpuPolicy::kAdaptive &&
        input.direct_encoder == CapabilityState::kSupported) {
      result.status = NativeError::kOk;
      result.decision.route = OutputRoute::kDirectCameraEncoder;
      result.decision.fallback_reason = FallbackReason::kOpenGlUnknown;
      result.decision.user_visible_fallback = true;
    } else {
      result.status = NativeError::kUnknownCapability;
      result.decision.route = OutputRoute::kUnknown;
    }
  } else if (policy == GpuPolicy::kAdaptive &&
             input.direct_encoder == CapabilityState::kSupported) {
    result.status = NativeError::kOk;
    result.decision.route = OutputRoute::kDirectCameraEncoder;
    result.decision.fallback_reason = FallbackReason::kOpenGlUnsupported;
    result.decision.user_visible_fallback = true;
  } else {
    result.status = NativeError::kUnsupported;
    result.decision.route = OutputRoute::kUnknown;
  }

  *out_result = result;
  return result.status;
}

NativeError apply_transform(const TransformMatrix& matrix, Coordinate input,
                            Coordinate* out_coordinate) {
  if (out_coordinate == nullptr) return NativeError::kInvalidArgument;
  for (const float value : matrix.values) {
    if (!std::isfinite(value)) return NativeError::kInvalidArgument;
  }
  if (!std::isfinite(input.x) || !std::isfinite(input.y)) {
    return NativeError::kInvalidArgument;
  }

  const float w = matrix.values[6] * input.x + matrix.values[7] * input.y +
                  matrix.values[8];
  if (!std::isfinite(w) || std::fabs(w) < 0.000001F) {
    return NativeError::kInvalidArgument;
  }
  const float x = matrix.values[0] * input.x + matrix.values[1] * input.y +
                  matrix.values[2];
  const float y = matrix.values[3] * input.x + matrix.values[4] * input.y +
                  matrix.values[5];
  if (!std::isfinite(x) || !std::isfinite(y)) {
    return NativeError::kInvalidArgument;
  }
  out_coordinate->x = x / w;
  out_coordinate->y = y / w;
  if (!std::isfinite(out_coordinate->x) || !std::isfinite(out_coordinate->y)) {
    return NativeError::kInvalidArgument;
  }
  return NativeError::kOk;
}

NativeError GpuProcessingPipeline::create(const GpuPipelineConfig& config,
                                          GpuProcessingPipeline* out_pipeline) {
  if (out_pipeline == nullptr || config.max_imported_buffers == 0 ||
      config.max_commands == 0) {
    return NativeError::kInvalidArgument;
  }

  GpuProbeResult probe_result{};
  const NativeError probe_status =
      probe_gpu(config.probe, config.policy, &probe_result);
  if (probe_status != NativeError::kOk) return probe_status;

  GpuProcessingPipeline pipeline{};
  pipeline.config_ = config;
  pipeline.probe_result_ = probe_result;
  pipeline.initialized_ = true;
  pipeline.closed_ = false;
  *out_pipeline = pipeline;
  return NativeError::kOk;
}

NativeError GpuProcessingPipeline::import_buffer(Handle source_handle,
                                                 std::size_t required_bytes,
                                                 ImportedBuffer* out_buffer) {
  if (!initialized_ || closed_) return NativeError::kNotReady;
  if (out_buffer == nullptr || source_handle == 0 || required_bytes == 0) {
    return NativeError::kInvalidArgument;
  }
  if (probe_result_.decision.route != OutputRoute::kOpenGlEncoderSurface) {
    return NativeError::kUnsupported;
  }
  if (imports_.size() >= config_.max_imported_buffers) {
    return NativeError::kCapacityExceeded;
  }

  BufferView view{};
  const NativeError lookup_status = lookup_buffer(source_handle, &view);
  if (lookup_status != NativeError::kOk) return lookup_status;
  if (view.byte_capacity < required_bytes) return NativeError::kCapacityExceeded;

  const std::uint64_t import_id = next_import_id_++;
  if (import_id == 0) return NativeError::kCapacityExceeded;
  ImportedBuffer imported{import_id, source_handle, view.byte_capacity};
  imports_.emplace(import_id, imported);
  *out_buffer = imported;
  return NativeError::kOk;
}

NativeError GpuProcessingPipeline::release_import(std::uint64_t import_id) {
  if (!initialized_ || closed_) return NativeError::kNotReady;
  if (import_id == 0) return NativeError::kInvalidArgument;
  const auto it = imports_.find(import_id);
  if (it == imports_.end()) return NativeError::kInvalidHandle;
  imports_.erase(it);
  return NativeError::kOk;
}

NativeError GpuProcessingPipeline::submit(std::uint64_t command_id,
                                          std::uint64_t frame_id,
                                          std::uint64_t import_id,
                                          TransformMatrix transform,
                                          std::uint64_t encoder_surface,
                                          ProcessedFrame* out_frame) {
  if (!initialized_ || closed_) return NativeError::kNotReady;
  if (out_frame == nullptr || command_id == 0 || frame_id == 0 ||
      encoder_surface == 0) {
    return NativeError::kInvalidArgument;
  }
  const auto existing_command = commands_.find(command_id);
  if (existing_command != commands_.end()) {
    return existing_command->second.cancelled ? NativeError::kCancelled
                                               : NativeError::kDuplicateCommand;
  }
  if (commands_.size() >= config_.max_commands) {
    return NativeError::kCapacityExceeded;
  }

  if (probe_result_.decision.route == OutputRoute::kOpenGlEncoderSurface) {
    if (import_id == 0 || !imports_.contains(import_id)) {
      return NativeError::kInvalidHandle;
    }
  } else if (probe_result_.decision.route == OutputRoute::kDirectCameraEncoder) {
    if (import_id != 0) return NativeError::kInvalidArgument;
  } else {
    return NativeError::kNotReady;
  }

  Coordinate transformed_origin{};
  const NativeError transform_status =
      apply_transform(transform, Coordinate{}, &transformed_origin);
  if (transform_status != NativeError::kOk) return transform_status;

  commands_.emplace(command_id, CommandRecord{false, true});
  *out_frame = ProcessedFrame{command_id,
                              frame_id,
                              import_id,
                              encoder_surface,
                              probe_result_.decision.route,
                              transformed_origin};
  return NativeError::kOk;
}

NativeError GpuProcessingPipeline::cancel(std::uint64_t command_id) {
  if (!initialized_ || closed_) return NativeError::kNotReady;
  if (command_id == 0) return NativeError::kInvalidArgument;
  const auto it = commands_.find(command_id);
  if (it == commands_.end()) {
    if (commands_.size() >= config_.max_commands) {
      return NativeError::kCapacityExceeded;
    }
    commands_.emplace(command_id, CommandRecord{true, false});
    return NativeError::kOk;
  }
  if (it->second.completed) return NativeError::kAlreadyReleased;
  if (it->second.cancelled) return NativeError::kCancelled;
  it->second.cancelled = true;
  return NativeError::kOk;
}

NativeError GpuProcessingPipeline::close() {
  if (!initialized_) return NativeError::kNotReady;
  if (closed_) return NativeError::kAlreadyReleased;
  imports_.clear();
  commands_.clear();
  closed_ = true;
  return NativeError::kOk;
}

}  // namespace opencinecam
