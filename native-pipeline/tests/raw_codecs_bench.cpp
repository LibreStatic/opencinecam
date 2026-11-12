/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

// Host benchmark (not a test): ratio and encode time of every codec option on a
// synthetic 4080x3060 noisy Bayer frame. Usage: raw_codecs_bench [noise] [repeats]
// The quantised rows (encoding 3) use the Razr's ISO 25600 noise model, k = 0.5.

#include <algorithm>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <vector>

#include "opencinecam/raw_codecs.h"
#include "raw_test_frames.h"

using namespace opencinecam::rawcodec;

int main(int argc, char** argv) {
  const double noise = argc > 1 ? std::atof(argv[1]) : 1.0;
  const int repeats = argc > 2 ? std::atoi(argv[2]) : 5;
  const int width = 4080, height = 3060, row_bytes = width / 4 * 5;
  const auto frame = rawtest::make_frame(rawtest::Pattern::kNoisyBayer, width, height, row_bytes, 1, noise);
  const auto quant = rawtest::quant_tables(0.6, 6.8974, 64, 0.5);
  struct Config {
    const char* name;
    Codec codec;
    int predictor;
    bool optimal;
  } configs[] = {
      {"lj92-p1-optimal", Codec::kLj92, 1, true},
      {"lj92-p1-default", Codec::kLj92, 1, false},
      {"lj92-p6-optimal", Codec::kLj92, 6, true},
      {"blockpack", Codec::kBlockPack, 1, true},
      {"blockpack-quant", Codec::kBlockPackQuantised, 1, true},
  };
  std::printf("noise %.2f, neon %s\n", noise, neon_available() ? "yes" : "no");
  for (int threads : {1, 2, 4}) {
    ThreadPool pool(threads);
    for (const auto& config : configs) {
      EncodeOptions options;
      options.lj92_predictor = config.predictor;
      options.lj92_optimal_huffman = config.optimal;
      if (config.codec == Codec::kBlockPackQuantised) {
        options.lut = quant.forward.data();
        options.inverse_lut = quant.inverse.data();
        options.lut_count = static_cast<int>(quant.inverse.size());
      }
      std::vector<uint8_t> out(max_encoded_size(config.codec, width, height, options));
      std::vector<double> ms;
      size_t size = 0;
      for (int r = 0; r < repeats; ++r) {
        const auto start = std::chrono::steady_clock::now();
        size = encode(config.codec, frame.data(), width, height, row_bytes, out.data(), out.size(),
                      options, &pool);
        ms.push_back(std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - start).count());
      }
      std::sort(ms.begin(), ms.end());
      std::printf("%-16s threads %d: ratio %.3f, median %.1f ms/frame\n", config.name, threads,
                  size ? static_cast<double>(frame.size()) / size : 0.0, ms[ms.size() / 2]);
    }
  }
  return 0;
}
