/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

#include "opencinecam/native_hardening.h"

#include <cassert>

int main() {
  using namespace opencinecam;

  NativeHardening hardening{};
  NativeHardeningConfig config{};
  config.max_faults = 8;
  config.max_crash_evidence = 4;
  config.max_samples = 4;
  config.max_commands = 4;
  assert(NativeHardening::create(config, &hardening) == NativeError::kOk);

  assert(hardening.register_resource(7, 4096) == NativeError::kOk);
  assert(hardening.resource_state(7) == ResourceState::kReady);
  assert(hardening.resource_generation(7) == 1);
  assert(hardening.register_resource(7, 4096) == NativeError::kDuplicateCommand);

  assert(hardening.mark_context_lost(10) == NativeError::kOk);
  assert(hardening.resource_state(7) == ResourceState::kContextLost);
  assert(hardening.recover_resource(7, 1, 11) == NativeError::kOk);
  assert(hardening.resource_state(7) == ResourceState::kReady);
  assert(hardening.resource_generation(7) == 2);
  assert(hardening.recover_resource(7, 1, 12) == NativeError::kInvalidHandle);

  NativeFaultEvent fault{13, 3, 3001, NativeFaultCode::kShaderCompile,
                         NativeFaultSeverity::kError, true};
  assert(hardening.record_fault(fault) == NativeError::kOk);
  CrashEvidence crash{0, 42, 1000, ThermalLevel::kSevere, fault};
  assert(hardening.record_crash(crash) == NativeError::kOk);
  assert(hardening.crash_count() == 1);

  assert(hardening.add_sample(PerformanceSample{1, 100, 2,
                                                ThermalLevel::kNominal, false}) ==
         NativeError::kOk);
  assert(hardening.add_sample(PerformanceSample{2, 300, 5,
                                                ThermalLevel::kCritical, true}) ==
         NativeError::kOk);
  BenchmarkSummary summary{};
  assert(hardening.benchmark_summary(&summary) == NativeError::kOk);
  assert(summary.sample_count == 2);
  assert(summary.min_duration_ns == 100);
  assert(summary.max_duration_ns == 300);
  assert(summary.average_duration_ns == 200);
  assert(summary.max_queue_depth == 5);
  assert(summary.peak_thermal == ThermalLevel::kCritical);
  assert(summary.dropped_count == 1);

  assert(hardening.begin_command(20) == NativeError::kOk);
  assert(hardening.begin_command(20) == NativeError::kDuplicateCommand);
  assert(hardening.complete_command(20) == NativeError::kOk);
  assert(hardening.complete_command(20) == NativeError::kAlreadyReleased);
  assert(hardening.cancel_command(21) == NativeError::kOk);
  assert(hardening.complete_command(21) == NativeError::kCancelled);

  assert(hardening.release_resource(7) == NativeError::kOk);
  assert(hardening.close() == NativeError::kOk);
  assert(hardening.close() == NativeError::kAlreadyReleased);
  assert(hardening.add_sample(PerformanceSample{3, 100, 0,
                                                ThermalLevel::kUnknown, false}) ==
         NativeError::kNotReady);
  return 0;
}
