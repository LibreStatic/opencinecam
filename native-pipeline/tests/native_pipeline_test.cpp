/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

#include "opencinecam/native_pipeline.h"

#include <cassert>

int main() {
  using namespace opencinecam;
  BufferView view{reinterpret_cast<AHardwareBuffer*>(0x1), 1024};
  assert(retain_buffer(1, view) == NativeError::kOk);
  BufferView looked_up{};
  assert(lookup_buffer(1, &looked_up) == NativeError::kOk);
  assert(looked_up.byte_capacity == 1024);
  assert(retain_buffer(1, view) == NativeError::kAlreadyReleased);
  assert(release_buffer(1) == NativeError::kOk);
  assert(release_buffer(1) == NativeError::kAlreadyReleased);
  assert(retain_buffer(2, BufferView{}) == NativeError::kNullBuffer);

  GpuProbeInput supported_probe{};
  supported_probe.egl_major = 1;
  supported_probe.egl_minor = 5;
  supported_probe.gles_major = 3;
  supported_probe.gles_minor = 1;
  supported_probe.context = CapabilityState::kSupported;
  supported_probe.ahardware_buffer_import = CapabilityState::kSupported;
  supported_probe.encoder_surface = CapabilityState::kSupported;
  supported_probe.direct_encoder = CapabilityState::kSupported;

  GpuProbeResult probe_result{};
  assert(probe_gpu(supported_probe, GpuPolicy::kStrict, &probe_result) ==
         NativeError::kOk);
  assert(probe_result.decision.route == OutputRoute::kOpenGlEncoderSurface);
  assert(probe_result.decision.user_visible_fallback == false);

  GpuPipelineConfig config{};
  config.probe = supported_probe;
  config.max_commands = 4;
  GpuProcessingPipeline pipeline{};
  assert(GpuProcessingPipeline::create(config, &pipeline) == NativeError::kOk);

  assert(retain_buffer(3, BufferView{reinterpret_cast<AHardwareBuffer*>(0x2),
                                    1024}) == NativeError::kOk);
  ImportedBuffer imported{};
  assert(pipeline.import_buffer(3, 512, &imported) == NativeError::kOk);
  assert(imported.byte_capacity == 1024);

  TransformMatrix translate{};
  translate.values[2] = 2.0F;
  translate.values[5] = 3.0F;
  ProcessedFrame frame{};
  assert(pipeline.submit(1, 9, imported.import_id, translate, 99, &frame) ==
         NativeError::kOk);
  assert(frame.route == OutputRoute::kOpenGlEncoderSurface);
  assert(frame.transformed_origin.x == 2.0F);
  assert(frame.transformed_origin.y == 3.0F);
  assert(pipeline.submit(1, 10, imported.import_id, translate, 99, &frame) ==
         NativeError::kDuplicateCommand);
  assert(pipeline.cancel(2) == NativeError::kOk);
  assert(pipeline.submit(2, 11, imported.import_id, translate, 99, &frame) ==
         NativeError::kCancelled);
  assert(pipeline.release_import(imported.import_id) == NativeError::kOk);
  assert(pipeline.close() == NativeError::kOk);
  assert(pipeline.submit(3, 12, 0, translate, 99, &frame) ==
         NativeError::kNotReady);
  assert(release_buffer(3) == NativeError::kOk);

  GpuProbeInput unsupported_probe = supported_probe;
  unsupported_probe.context = CapabilityState::kUnsupported;
  unsupported_probe.ahardware_buffer_import = CapabilityState::kUnsupported;
  unsupported_probe.encoder_surface = CapabilityState::kUnsupported;
  GpuProbeResult fallback_result{};
  assert(probe_gpu(unsupported_probe, GpuPolicy::kAdaptive, &fallback_result) ==
         NativeError::kOk);
  assert(fallback_result.decision.route == OutputRoute::kDirectCameraEncoder);
  assert(fallback_result.decision.fallback_reason ==
         FallbackReason::kOpenGlUnsupported);
  assert(fallback_result.decision.user_visible_fallback);

  GpuPipelineConfig fallback_config{};
  fallback_config.probe = unsupported_probe;
  fallback_config.policy = GpuPolicy::kAdaptive;
  GpuProcessingPipeline fallback_pipeline{};
  assert(GpuProcessingPipeline::create(fallback_config, &fallback_pipeline) ==
         NativeError::kOk);
  assert(fallback_pipeline.submit(4, 13, 0, TransformMatrix{}, 100, &frame) ==
         NativeError::kOk);
  assert(frame.route == OutputRoute::kDirectCameraEncoder);

  GpuProbeInput unknown_probe = supported_probe;
  unknown_probe.context = CapabilityState::kUnknown;
  unknown_probe.direct_encoder = CapabilityState::kUnsupported;
  assert(probe_gpu(unknown_probe, GpuPolicy::kStrict, &probe_result) ==
         NativeError::kUnknownCapability);
  assert(probe_result.report.gles31 == CapabilityState::kUnknown);
  assert(GpuProcessingPipeline::create(
             GpuPipelineConfig{unknown_probe, GpuPolicy::kStrict, 8, 64},
             &pipeline) == NativeError::kUnknownCapability);
  return 0;
}
