/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

// Synthetic RAW10 frames shared by the codec test and the host benchmark.

#ifndef OPENCINECAM_RAW_TEST_FRAMES_H
#define OPENCINECAM_RAW_TEST_FRAMES_H

#include <cmath>
#include <cstdint>
#include <vector>

namespace rawtest {

struct Rng {
  uint64_t state;
  explicit Rng(uint64_t seed) : state(seed * 0x9E3779B97F4A7C15ull + 1) {}
  uint32_t next() {
    state ^= state << 13;
    state ^= state >> 7;
    state ^= state << 17;
    return static_cast<uint32_t>(state >> 16);
  }
  // Approximately normal, mean 0, deviation 1 (sum of four uniforms).
  double normal() {
    double sum = 0;
    for (int i = 0; i < 4; ++i) sum += (next() & 0xFFFF) / 65535.0;
    return (sum - 2.0) * 1.7320508;
  }
};

enum class Pattern { kRandom, kFlat, kZero, kMax, kGradient, kNoisyBayer, kCleanBayer };

inline int clamp10(double v) { return v < 0 ? 0 : v > 1023 ? 1023 : static_cast<int>(v); }

inline int sample(Pattern pattern, int x, int y, int width, int height, Rng& rng, double noise) {
  switch (pattern) {
    case Pattern::kRandom: return static_cast<int>(rng.next() & 1023);
    case Pattern::kFlat: return 512;
    case Pattern::kZero: return 0;
    case Pattern::kMax: return 1023;
    case Pattern::kGradient: return (x * 1023 / (width > 1 ? width - 1 : 1) + y) & 1023;
    case Pattern::kNoisyBayer:
    case Pattern::kCleanBayer: {
      // A smooth scene with Bayer channel gains, black level 64, and shot plus
      // read noise roughly like a phone sensor at high ISO.
      const double u = static_cast<double>(x) / width, v = static_cast<double>(y) / height;
      const double scene = 0.5 + 0.35 * std::sin(6.0 * u + 2.0 * v) * std::cos(4.0 * v);
      const int colour = (y & 1) * 2 + (x & 1);  // RGGB
      static const double kGain[4] = {0.55, 1.0, 1.0, 0.7};
      const double signal = 64 + scene * kGain[colour] * 900;
      if (pattern == Pattern::kCleanBayer) return clamp10(signal);
      const double sigma = std::sqrt((signal - 64) * 0.9 + 4.0) * noise;
      return clamp10(signal + rng.normal() * sigma);
    }
  }
  return 0;
}

// Packs samples as Android RAW10 with the given row stride; padding bytes get 0xA5.
inline std::vector<uint8_t> make_frame(Pattern pattern, int width, int height, int row_bytes,
                                       uint64_t seed = 1, double noise = 1.0) {
  std::vector<uint8_t> frame(static_cast<size_t>(row_bytes) * height, 0xA5);
  Rng rng(seed);
  std::vector<int> row(width);
  for (int y = 0; y < height; ++y) {
    for (int x = 0; x < width; ++x) row[x] = sample(pattern, x, y, width, height, rng, noise);
    uint8_t* p = frame.data() + static_cast<size_t>(y) * row_bytes;
    for (int x = 0; x < width; x += 4, p += 5) {
      p[0] = static_cast<uint8_t>(row[x] >> 2);
      p[1] = static_cast<uint8_t>(row[x + 1] >> 2);
      p[2] = static_cast<uint8_t>(row[x + 2] >> 2);
      p[3] = static_cast<uint8_t>(row[x + 3] >> 2);
      p[4] = static_cast<uint8_t>((row[x] & 3) | (row[x + 1] & 3) << 2 | (row[x + 2] & 3) << 4 |
                                  (row[x + 3] & 3) << 6);
    }
  }
  return frame;
}

// The encoding 3 tables for the noise model variance(x) = a + b * max(0, x - black)
// (DN squared), as RawQuantisation.build in :media: neighbouring DNs closer than
// about k sigmas share a code, and a code decodes to the midpoint of its DNs.
struct QuantTables {
  std::vector<uint16_t> forward;  // 1024 entries, DN -> code
  std::vector<uint16_t> inverse;  // count entries, code -> DN
};

inline QuantTables quant_tables(double a, double b, int black, double k) {
  QuantTables t;
  t.forward.resize(1024);
  std::vector<int> lo(1024, 1 << 30), hi(1024, -1);
  double acc = 0;
  for (int i = 0; i < 1024; ++i) {
    const int c = static_cast<int>(std::lround(acc));
    t.forward[i] = static_cast<uint16_t>(c);
    lo[c] = lo[c] < i ? lo[c] : i;
    hi[c] = hi[c] > i ? hi[c] : i;
    const double var = a + b * (i > black ? i - black : 0);
    const double sigma = std::sqrt(var > 0 ? var : 0.0);
    const double step = 1.0 / (k * sigma);  // sigma 0 -> infinity -> step 1
    acc += step < 1.0 ? step : 1.0;
  }
  const int count = t.forward[1023] + 1;
  t.inverse.resize(count);
  for (int c = 0; c < count; ++c) t.inverse[c] = static_cast<uint16_t>(hi[c] < 0 ? c : (lo[c] + hi[c] + 1) / 2);
  return t;
}

// The samples of a RAW10 frame, row by row (stride padding skipped).
inline std::vector<int> unpack_frame(const std::vector<uint8_t>& frame, int width, int height, int row_bytes) {
  std::vector<int> out(static_cast<size_t>(width) * height);
  for (int y = 0; y < height; ++y) {
    const uint8_t* p = frame.data() + static_cast<size_t>(y) * row_bytes;
    for (int x = 0; x < width; x += 4, p += 5) {
      for (int k = 0; k < 4; ++k) out[static_cast<size_t>(y) * width + x + k] = p[k] << 2 | ((p[4] >> (2 * k)) & 3);
    }
  }
  return out;
}

// Applies table[sample] to every sample of a RAW10 frame in place.
inline void map_frame(std::vector<uint8_t>& frame, int width, int height, int row_bytes, const uint16_t* table) {
  for (int y = 0; y < height; ++y) {
    uint8_t* p = frame.data() + static_cast<size_t>(y) * row_bytes;
    for (int x = 0; x < width; x += 4, p += 5) {
      int v[4];
      for (int k = 0; k < 4; ++k) v[k] = table[p[k] << 2 | ((p[4] >> (2 * k)) & 3)];
      for (int k = 0; k < 4; ++k) p[k] = static_cast<uint8_t>(v[k] >> 2);
      p[4] = static_cast<uint8_t>((v[0] & 3) | (v[1] & 3) << 2 | (v[2] & 3) << 4 | (v[3] & 3) << 6);
    }
  }
}

}  // namespace rawtest

#endif  // OPENCINECAM_RAW_TEST_FRAMES_H
