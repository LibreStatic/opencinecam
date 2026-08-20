/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

#pragma once

#include <cstddef>
#include <cstdint>
#include <unordered_map>
#include <vector>

#include "opencinecam/native_pipeline.h"

namespace opencinecam {

enum class NativeFaultCode : std::uint8_t {
  kUnknown = 0,
  kContextLost,
  kShaderCompile,
  kEncoderSurface,
  kResourceExhausted,
  kThermalCritical,
  kCancelled,
};

enum class NativeFaultSeverity : std::uint8_t {
  kInfo = 0,
  kWarning,
  kError,
  kCritical,
};

enum class ThermalLevel : std::uint8_t {
  kUnknown = 0,
  kNominal,
  kModerate,
  kSevere,
  kCritical,
};

enum class ResourceState : std::uint8_t {
  kUnknown = 0,
  kReady,
  kContextLost,
  kRecovering,
  kReleased,
};

struct NativeFaultEvent {
  std::uint64_t correlation_id = 0;
  std::uint32_t component_code = 0;
  std::uint32_t safe_message_code = 0;
  NativeFaultCode code = NativeFaultCode::kUnknown;
  NativeFaultSeverity severity = NativeFaultSeverity::kInfo;
  bool recoverable = false;
};

struct CrashEvidence {
  std::uint64_t sequence = 0;
  std::uint64_t frame_id = 0;
  std::uint64_t monotonic_ns = 0;
  ThermalLevel thermal = ThermalLevel::kUnknown;
  NativeFaultEvent fault{};
};

struct PerformanceSample {
  std::uint64_t frame_id = 0;
  std::uint64_t duration_ns = 0;
  std::uint32_t queue_depth = 0;
  ThermalLevel thermal = ThermalLevel::kUnknown;
  bool dropped = false;
};

struct BenchmarkSummary {
  std::size_t sample_count = 0;
  std::uint64_t min_duration_ns = 0;
  std::uint64_t max_duration_ns = 0;
  std::uint64_t average_duration_ns = 0;
  std::uint32_t max_queue_depth = 0;
  ThermalLevel peak_thermal = ThermalLevel::kUnknown;
  std::size_t dropped_count = 0;
};

struct NativeHardeningConfig {
  std::size_t max_resources = 16;
  std::size_t max_faults = 32;
  std::size_t max_crash_evidence = 32;
  std::size_t max_samples = 256;
  std::size_t max_commands = 64;
};

class NativeHardening {
 public:
  NativeHardening() = default;

  static NativeError create(const NativeHardeningConfig& config,
                            NativeHardening* out_hardening);

  NativeError register_resource(std::uint64_t resource_id,
                                std::size_t byte_size);
  NativeError release_resource(std::uint64_t resource_id);
  NativeError mark_context_lost(std::uint64_t correlation_id);
  NativeError recover_resource(std::uint64_t resource_id,
                               std::uint64_t expected_generation,
                               std::uint64_t correlation_id);
  NativeError record_fault(const NativeFaultEvent& fault);
  NativeError record_crash(const CrashEvidence& evidence);
  NativeError add_sample(const PerformanceSample& sample);
  NativeError benchmark_summary(BenchmarkSummary* out_summary) const;
  NativeError begin_command(std::uint64_t command_id);
  NativeError cancel_command(std::uint64_t command_id);
  NativeError complete_command(std::uint64_t command_id);
  NativeError close();

  ResourceState resource_state(std::uint64_t resource_id) const;
  std::uint64_t resource_generation(std::uint64_t resource_id) const;
  std::size_t fault_count() const { return faults_.size(); }
  std::size_t crash_count() const { return crash_evidence_.size(); }
  bool closed() const { return closed_; }

 private:
  struct Resource {
    std::size_t byte_size = 0;
    std::uint64_t generation = 1;
    ResourceState state = ResourceState::kReady;
  };

  struct Command {
    bool cancelled = false;
    bool completed = false;
  };

  NativeHardeningConfig config_{};
  std::unordered_map<std::uint64_t, Resource> resources_;
  std::unordered_map<std::uint64_t, Command> commands_;
  std::vector<NativeFaultEvent> faults_;
  std::vector<CrashEvidence> crash_evidence_;
  std::vector<PerformanceSample> samples_;
  std::uint64_t next_crash_sequence_ = 1;
  bool initialized_ = false;
  bool closed_ = false;
};

}  // namespace opencinecam
