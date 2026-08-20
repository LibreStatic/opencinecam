/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

#include "opencinecam/native_hardening.h"

#include <limits>

namespace opencinecam {

NativeError NativeHardening::create(const NativeHardeningConfig& config,
                                    NativeHardening* out_hardening) {
  if (out_hardening == nullptr || config.max_resources == 0 ||
      config.max_faults == 0 || config.max_crash_evidence == 0 ||
      config.max_samples == 0 || config.max_commands == 0) {
    return NativeError::kInvalidArgument;
  }
  NativeHardening hardening{};
  hardening.config_ = config;
  hardening.initialized_ = true;
  hardening.closed_ = false;
  *out_hardening = hardening;
  return NativeError::kOk;
}

NativeError NativeHardening::register_resource(std::uint64_t resource_id,
                                               std::size_t byte_size) {
  if (!initialized_ || closed_) return NativeError::kNotReady;
  if (resource_id == 0 || byte_size == 0) return NativeError::kInvalidArgument;
  if (resources_.contains(resource_id)) return NativeError::kDuplicateCommand;
  if (resources_.size() >= config_.max_resources) {
    return NativeError::kCapacityExceeded;
  }
  resources_.emplace(resource_id, Resource{byte_size, 1, ResourceState::kReady});
  return NativeError::kOk;
}

NativeError NativeHardening::release_resource(std::uint64_t resource_id) {
  if (!initialized_ || closed_) return NativeError::kNotReady;
  if (resource_id == 0) return NativeError::kInvalidArgument;
  const auto it = resources_.find(resource_id);
  if (it == resources_.end()) return NativeError::kInvalidHandle;
  resources_.erase(it);
  return NativeError::kOk;
}

NativeError NativeHardening::mark_context_lost(std::uint64_t correlation_id) {
  if (!initialized_ || closed_) return NativeError::kNotReady;
  if (correlation_id == 0) return NativeError::kInvalidArgument;
  for (auto& [resource_id, resource] : resources_) {
    (void)resource_id;
    if (resource.state != ResourceState::kReleased) {
      resource.state = ResourceState::kContextLost;
    }
  }
  return record_fault(NativeFaultEvent{correlation_id,
                                       1,
                                       1001,
                                       NativeFaultCode::kContextLost,
                                       NativeFaultSeverity::kError,
                                       true});
}

NativeError NativeHardening::recover_resource(std::uint64_t resource_id,
                                              std::uint64_t expected_generation,
                                              std::uint64_t correlation_id) {
  if (!initialized_ || closed_) return NativeError::kNotReady;
  if (resource_id == 0 || expected_generation == 0 || correlation_id == 0) {
    return NativeError::kInvalidArgument;
  }
  const auto it = resources_.find(resource_id);
  if (it == resources_.end()) return NativeError::kInvalidHandle;
  Resource& resource = it->second;
  if (resource.generation != expected_generation) {
    return NativeError::kInvalidHandle;
  }
  if (resource.state != ResourceState::kContextLost) {
    return NativeError::kNotReady;
  }
  resource.state = ResourceState::kRecovering;
  ++resource.generation;
  resource.state = ResourceState::kReady;
  return record_fault(NativeFaultEvent{correlation_id,
                                       1,
                                       1002,
                                       NativeFaultCode::kContextLost,
                                       NativeFaultSeverity::kInfo,
                                       true});
}

NativeError NativeHardening::record_fault(const NativeFaultEvent& fault) {
  if (!initialized_ || closed_) return NativeError::kNotReady;
  if (fault.correlation_id == 0 || fault.component_code == 0 ||
      fault.safe_message_code == 0) {
    return NativeError::kInvalidArgument;
  }
  if (faults_.size() >= config_.max_faults) return NativeError::kCapacityExceeded;
  faults_.push_back(fault);
  return NativeError::kOk;
}

NativeError NativeHardening::record_crash(const CrashEvidence& evidence) {
  if (!initialized_ || closed_) return NativeError::kNotReady;
  if (evidence.frame_id == 0 || evidence.monotonic_ns == 0 ||
      evidence.fault.correlation_id == 0) {
    return NativeError::kInvalidArgument;
  }
  if (crash_evidence_.size() >= config_.max_crash_evidence) {
    return NativeError::kCapacityExceeded;
  }
  CrashEvidence stored = evidence;
  if (stored.sequence == 0) stored.sequence = next_crash_sequence_++;
  crash_evidence_.push_back(stored);
  return NativeError::kOk;
}

NativeError NativeHardening::add_sample(const PerformanceSample& sample) {
  if (!initialized_ || closed_) return NativeError::kNotReady;
  if (sample.frame_id == 0) return NativeError::kInvalidArgument;
  if (samples_.size() >= config_.max_samples) return NativeError::kCapacityExceeded;
  samples_.push_back(sample);
  return NativeError::kOk;
}

NativeError NativeHardening::benchmark_summary(BenchmarkSummary* out_summary) const {
  if (!initialized_ || closed_) return NativeError::kNotReady;
  if (out_summary == nullptr) return NativeError::kInvalidArgument;
  if (samples_.empty()) return NativeError::kNotReady;

  BenchmarkSummary summary{};
  summary.sample_count = samples_.size();
  summary.min_duration_ns = std::numeric_limits<std::uint64_t>::max();
  std::uint64_t total_duration = 0;
  for (const PerformanceSample& sample : samples_) {
    if (total_duration > std::numeric_limits<std::uint64_t>::max() -
                             sample.duration_ns) {
      return NativeError::kCapacityExceeded;
    }
    total_duration += sample.duration_ns;
    if (sample.duration_ns < summary.min_duration_ns) {
      summary.min_duration_ns = sample.duration_ns;
    }
    if (sample.duration_ns > summary.max_duration_ns) {
      summary.max_duration_ns = sample.duration_ns;
    }
    if (sample.queue_depth > summary.max_queue_depth) {
      summary.max_queue_depth = sample.queue_depth;
    }
    if (sample.dropped) ++summary.dropped_count;
    if (static_cast<std::uint8_t>(sample.thermal) >
        static_cast<std::uint8_t>(summary.peak_thermal)) {
      summary.peak_thermal = sample.thermal;
    }
  }
  summary.average_duration_ns = total_duration / summary.sample_count;
  *out_summary = summary;
  return NativeError::kOk;
}

NativeError NativeHardening::begin_command(std::uint64_t command_id) {
  if (!initialized_ || closed_) return NativeError::kNotReady;
  if (command_id == 0) return NativeError::kInvalidArgument;
  if (commands_.contains(command_id)) return NativeError::kDuplicateCommand;
  if (commands_.size() >= config_.max_commands) return NativeError::kCapacityExceeded;
  commands_.emplace(command_id, Command{false, false});
  return NativeError::kOk;
}

NativeError NativeHardening::cancel_command(std::uint64_t command_id) {
  if (!initialized_ || closed_) return NativeError::kNotReady;
  if (command_id == 0) return NativeError::kInvalidArgument;
  const auto it = commands_.find(command_id);
  if (it == commands_.end()) {
    if (commands_.size() >= config_.max_commands) return NativeError::kCapacityExceeded;
    commands_.emplace(command_id, Command{true, false});
    return record_fault(NativeFaultEvent{command_id,
                                         2,
                                         2001,
                                         NativeFaultCode::kCancelled,
                                         NativeFaultSeverity::kInfo,
                                         true});
  }
  if (it->second.completed) return NativeError::kAlreadyReleased;
  if (it->second.cancelled) return NativeError::kCancelled;
  it->second.cancelled = true;
  return record_fault(NativeFaultEvent{command_id,
                                       2,
                                       2001,
                                       NativeFaultCode::kCancelled,
                                       NativeFaultSeverity::kInfo,
                                       true});
}

NativeError NativeHardening::complete_command(std::uint64_t command_id) {
  if (!initialized_ || closed_) return NativeError::kNotReady;
  const auto it = commands_.find(command_id);
  if (it == commands_.end()) return NativeError::kInvalidHandle;
  if (it->second.cancelled) return NativeError::kCancelled;
  if (it->second.completed) return NativeError::kAlreadyReleased;
  it->second.completed = true;
  return NativeError::kOk;
}

NativeError NativeHardening::close() {
  if (!initialized_) return NativeError::kNotReady;
  if (closed_) return NativeError::kAlreadyReleased;
  resources_.clear();
  commands_.clear();
  faults_.clear();
  crash_evidence_.clear();
  samples_.clear();
  closed_ = true;
  return NativeError::kOk;
}

ResourceState NativeHardening::resource_state(std::uint64_t resource_id) const {
  const auto it = resources_.find(resource_id);
  return it == resources_.end() ? ResourceState::kUnknown : it->second.state;
}

std::uint64_t NativeHardening::resource_generation(
    std::uint64_t resource_id) const {
  const auto it = resources_.find(resource_id);
  return it == resources_.end() ? 0 : it->second.generation;
}

}  // namespace opencinecam
