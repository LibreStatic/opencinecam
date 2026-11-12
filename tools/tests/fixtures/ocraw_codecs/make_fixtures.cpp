/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

// Regenerates the encoded RAW frame fixtures used by tools/tests/test_ocraw_to_dng.py
// with the reference codec in native-pipeline/src/raw_codecs.cpp. Each fixture is
// the encoded frame container exactly as an OCRAW RAW_FRAME carries it. The source
// samples come from fixture_samples(), which the Python test reimplements.
//
//   g++ -std=c++17 -O2 -I native-pipeline/include
//     tools/tests/fixtures/ocraw_codecs/make_fixtures.cpp native-pipeline/src/raw_codecs.cpp
//     -o <scratch>/make_fixtures -lpthread
//   <scratch>/make_fixtures tools/tests/fixtures/ocraw_codecs

#include <cmath>
#include <cstdint>
#include <cstdio>
#include <string>
#include <vector>

#include "opencinecam/raw_codecs.h"

namespace rc = opencinecam::rawcodec;

namespace {

// A smooth ramp with xorshift32 noise and a few extreme samples, so residuals
// cover small widths and the full 11-bit range.
std::vector<int> fixture_samples(int width, int height, uint32_t seed) {
  std::vector<int> out(static_cast<size_t>(width) * height);
  uint32_t state = seed;
  for (int y = 0; y < height; ++y) {
    for (int x = 0; x < width; ++x) {
      state ^= state << 13;
      state ^= state >> 17;
      state ^= state << 5;
      int v = 64 + x * 600 / width + y * 200 / height + static_cast<int>(state % 33) - 16;
      out[static_cast<size_t>(y) * width + x] = v < 0 ? 0 : v > 1023 ? 1023 : v;
    }
  }
  const int extremes[4] = {0, 1023, 512, 3};
  for (int k = 0; k < 4; ++k) out[k] = extremes[k];
  out[static_cast<size_t>(height - 1) * width + width - 1] = 1023;
  out[static_cast<size_t>(height - 1) * width + width - 2] = 0;
  out[static_cast<size_t>(height / 2) * width + width / 2] = 1023;
  return out;
}

std::vector<uint8_t> pack_raw10(const std::vector<int>& s) {
  std::vector<uint8_t> out;
  for (size_t i = 0; i < s.size(); i += 4) {
    for (int k = 0; k < 4; ++k) out.push_back(static_cast<uint8_t>(s[i + k] >> 2));
    out.push_back(static_cast<uint8_t>((s[i] & 3) | (s[i + 1] & 3) << 2 | (s[i + 2] & 3) << 4 |
                                       (s[i + 3] & 3) << 6));
  }
  return out;
}

// Encoding 3 tables for variance(x) = a + b * max(0, x - black), step k sigmas,
// as RawQuantisation.build in :media (and quant_tables() in the Python test).
struct QuantTables {
  std::vector<uint16_t> forward, inverse;
};

QuantTables quant_tables(double a, double b, int black, double k) {
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
    const double step = 1.0 / (k * std::sqrt(var > 0 ? var : 0.0));
    acc += step < 1.0 ? step : 1.0;
  }
  t.inverse.resize(t.forward[1023] + 1);
  for (size_t c = 0; c < t.inverse.size(); ++c) {
    t.inverse[c] = static_cast<uint16_t>(hi[c] < 0 ? static_cast<int>(c) : (lo[c] + hi[c] + 1) / 2);
  }
  return t;
}

struct Fixture {
  const char* name;
  rc::Codec codec;
  int width, height;
  uint32_t seed;
  int strips;
  bool optimal_huffman;
};

// Encoding 3 fixtures: the Razr's ISO 25600 noise model, black 64, k = 0.5.
constexpr double kQuantA = 0.6, kQuantB = 6.8974, kQuantK = 0.5;
constexpr int kQuantBlack = 64;
constexpr uint32_t kQuantIso = 25600;

}  // namespace

int main(int argc, char** argv) {
  const std::string dir = argc > 1 ? argv[1] : ".";
  const Fixture fixtures[] = {
      {"lj92_64x34_s8_seed1.bin", rc::Codec::kLj92, 64, 34, 1, 8, true},
      {"lj92_128x50_s4_seed2.bin", rc::Codec::kLj92, 128, 50, 2, 4, false},
      {"blockpack_64x34_s8_seed1.bin", rc::Codec::kBlockPack, 64, 34, 1, 8, true},
      {"blockpack_128x50_s4_seed2.bin", rc::Codec::kBlockPack, 128, 50, 2, 4, true},
      // One strip of the whole frame; 72 columns give a short, odd last block.
      {"lj92_72x26_s1_seed3.bin", rc::Codec::kLj92, 72, 26, 3, 1, true},
      {"blockpack_72x26_s1_seed3.bin", rc::Codec::kBlockPack, 72, 26, 3, 1, true},
      {"blockpackq_64x34_s8_seed1.bin", rc::Codec::kBlockPackQuantised, 64, 34, 1, 8, true},
      {"blockpackq_72x26_s1_seed3.bin", rc::Codec::kBlockPackQuantised, 72, 26, 3, 1, true},
  };
  const QuantTables quant = quant_tables(kQuantA, kQuantB, kQuantBlack, kQuantK);
  for (const Fixture& f : fixtures) {
    const std::vector<uint8_t> raw = pack_raw10(fixture_samples(f.width, f.height, f.seed));
    rc::EncodeOptions options;
    options.strips = f.strips;
    options.lj92_optimal_huffman = f.optimal_huffman;
    options.kernel = rc::Kernel::kScalar;
    std::vector<uint8_t> expected = raw;
    if (f.codec == rc::Codec::kBlockPackQuantised) {
      options.lut = quant.forward.data();
      options.inverse_lut = quant.inverse.data();
      options.lut_count = static_cast<int>(quant.inverse.size());
      options.quant_iso = kQuantIso;
      options.quant_k_milli = static_cast<uint16_t>(kQuantK * 1000);
      options.quant_black_level = kQuantBlack;
      std::vector<int> samples = fixture_samples(f.width, f.height, f.seed);
      for (int& v : samples) v = quant.inverse[quant.forward[v]];
      expected = pack_raw10(samples);
    }
    std::vector<uint8_t> dst(rc::max_encoded_size(f.codec, f.width, f.height, options));
    const size_t size = rc::encode(f.codec, raw.data(), f.width, f.height, f.width * 5 / 4,
                                   dst.data(), dst.size(), options);
    std::vector<uint8_t> back(raw.size());
    if (size == 0 || !rc::decode(f.codec, dst.data(), size, f.width, f.height, back.data(),
                                 f.width * 5 / 4) ||
        back != expected) {
      std::fprintf(stderr, "%s: encode/decode failed\n", f.name);
      return 1;
    }
    const std::string path = dir + "/" + f.name;
    FILE* out = std::fopen(path.c_str(), "wb");
    if (out == nullptr || std::fwrite(dst.data(), 1, size, out) != size || std::fclose(out) != 0) {
      std::fprintf(stderr, "%s: write failed\n", path.c_str());
      return 1;
    }
    std::printf("%s %zu bytes\n", f.name, size);
  }
  return 0;
}
