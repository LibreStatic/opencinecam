/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

#include "opencinecam/raw_codecs.h"

#include <algorithm>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <vector>

#include "raw_test_frames.h"

namespace {

using namespace opencinecam::rawcodec;
using rawtest::Pattern;

int failures = 0;

#define CHECK(condition)                                                        \
  do {                                                                          \
    if (!(condition)) {                                                         \
      std::fprintf(stderr, "%s:%d: CHECK failed: %s\n", __FILE__, __LINE__, #condition); \
      ++failures;                                                               \
    }                                                                           \
  } while (0)

const char* codec_name(Codec codec) { return codec == Codec::kLj92 ? "lj92" : "blockpack"; }

std::vector<uint8_t> encode_frame(Codec codec, const std::vector<uint8_t>& frame, int width,
                                  int height, int row_bytes, const EncodeOptions& options,
                                  ThreadPool* pool) {
  std::vector<uint8_t> out(max_encoded_size(codec, width, height, options));
  const size_t size =
      encode(codec, frame.data(), width, height, row_bytes, out.data(), out.size(), options, pool);
  CHECK(size > 0);
  out.resize(size);
  return out;
}

// Encodes, decodes into a padded destination and compares every sample byte;
// stride padding of the destination must stay untouched.
std::vector<uint8_t> round_trip(Codec codec, Pattern pattern, int width, int height,
                                const EncodeOptions& options, ThreadPool* pool, int src_pad = 0) {
  const int tight = width / 4 * 5;
  const int row_bytes = tight + src_pad;
  const auto frame = rawtest::make_frame(pattern, width, height, row_bytes, width * 31 + height);
  const auto encoded = encode_frame(codec, frame, width, height, row_bytes, options, pool);
  const int dst_row_bytes = tight + 3;
  std::vector<uint8_t> decoded(static_cast<size_t>(dst_row_bytes) * height, 0x5A);
  const bool ok = decode(codec, encoded.data(), encoded.size(), width, height, decoded.data(),
                         dst_row_bytes, pool);
  CHECK(ok);
  bool same = true;
  for (int y = 0; y < height && same; ++y) {
    same = std::memcmp(frame.data() + static_cast<size_t>(y) * row_bytes,
                       decoded.data() + static_cast<size_t>(y) * dst_row_bytes, tight) == 0;
    for (int k = tight; k < dst_row_bytes; ++k) {
      same = same && decoded[static_cast<size_t>(y) * dst_row_bytes + k] == 0x5A;
    }
  }
  if (!same) {
    std::fprintf(stderr, "mismatch: %s %dx%d pattern %d strips %d pred %d optimal %d\n",
                 codec_name(codec), width, height, static_cast<int>(pattern), options.strips,
                 options.lj92_predictor, options.lj92_optimal_huffman);
  }
  CHECK(same);
  return encoded;
}

void test_strip_layout() {
  int rows[kMaxStrips];
  CHECK(strip_layout(3060, 8, rows) == 8);
  CHECK(rows[0] == 384 && rows[7] == 3060 - 7 * 384);
  CHECK(strip_layout(48, 7, rows) == 3);  // ceil(48 / 7) = 7, rounded up to 16 rows
  CHECK(strip_layout(3000, 8, rows) == 8 && rows[0] == 384 && rows[7] == 312);
  CHECK(strip_layout(100, 3, rows) == 3 && rows[0] == 48 && rows[2] == 4);
  CHECK(strip_layout(10, 4, rows) == 1 && rows[0] == 10);
  int sum = 0;
  const int count = strip_layout(48, 7, rows);
  for (int s = 0; s < count; ++s) {
    CHECK(rows[s] % 2 == 0 && rows[s] > 0);
    sum += rows[s];
  }
  CHECK(sum == 48);
  CHECK(strip_layout(2, 64, rows) == 1 && rows[0] == 2);
  CHECK(strip_layout(48, 1000, rows) == 3);
}

void test_round_trips() {
  ThreadPool pool1(1), pool2(2), pool4(4);
  ThreadPool* pools[] = {nullptr, &pool1, &pool2, &pool4};
  const Pattern patterns[] = {Pattern::kRandom, Pattern::kFlat,     Pattern::kZero,
                              Pattern::kMax,    Pattern::kGradient, Pattern::kNoisyBayer,
                              Pattern::kCleanBayer};
  const int sizes[][2] = {{4, 2}, {8, 4}, {20, 6}, {64, 48}, {100, 10}, {256, 34}, {64, 130}};
  for (Codec codec : {Codec::kLj92, Codec::kBlockPack}) {
    for (Pattern pattern : patterns) {
      for (const auto& size : sizes) {
        for (int strips : {1, 3, 7, 8, 64}) {
          EncodeOptions options;
          options.strips = strips;
          std::vector<uint8_t> reference;
          for (ThreadPool* pool : pools) {
            const auto encoded = round_trip(codec, pattern, size[0], size[1], options, pool);
            if (reference.empty()) reference = encoded;
            CHECK(encoded == reference);  // the thread count never changes the bytes
          }
        }
      }
    }
  }
  // Every LJ92 predictor, both tables, with source stride padding.
  for (int predictor = 1; predictor <= 7; ++predictor) {
    for (bool optimal : {true, false}) {
      for (Pattern pattern : {Pattern::kRandom, Pattern::kNoisyBayer, Pattern::kMax, Pattern::kZero}) {
        EncodeOptions options;
        options.lj92_predictor = predictor;
        options.lj92_optimal_huffman = optimal;
        options.strips = 3;
        round_trip(Codec::kLj92, pattern, 64, 48, options, &pool2, 12);
      }
    }
  }
  EncodeOptions padded;
  round_trip(Codec::kBlockPack, Pattern::kNoisyBayer, 64, 48, padded, &pool2, 7);
}

void test_kernels_match() {
  // NEON and scalar kernels must emit identical bytes (only meaningful on arm64).
  EncodeOptions scalar, neon;
  scalar.kernel = Kernel::kScalar;
  neon.kernel = Kernel::kNeon;
  std::printf("neon kernels: %s\n", neon_available() ? "compiled in" : "not available");
  for (Codec codec : {Codec::kLj92, Codec::kBlockPack}) {
    for (Pattern pattern : {Pattern::kRandom, Pattern::kNoisyBayer, Pattern::kGradient}) {
      for (int width : {4, 16, 20, 36, 100, 4080}) {
        const int height = width == 4080 ? 64 : 12;
        const auto frame = rawtest::make_frame(pattern, width, height, width / 4 * 5, 7);
        const auto a = encode_frame(codec, frame, width, height, width / 4 * 5, scalar, nullptr);
        const auto b = encode_frame(codec, frame, width, height, width / 4 * 5, neon, nullptr);
        CHECK(a == b);
      }
    }
  }
}

void test_full_frame() {
  ThreadPool pool(4);
  EncodeOptions options;
  for (Codec codec : {Codec::kLj92, Codec::kBlockPack}) {
    const auto encoded = round_trip(codec, Pattern::kNoisyBayer, 4080, 3060, options, &pool);
    std::printf("%s 4080x3060 noisy: ratio %.3f\n", codec_name(codec),
                4080.0 * 3060 * 5 / 4 / static_cast<double>(encoded.size()));
  }
}

void test_overflow() {
  ThreadPool pool(4);
  const int width = 256, height = 64, row_bytes = width / 4 * 5;
  for (Codec codec : {Codec::kLj92, Codec::kBlockPack}) {
    for (Pattern pattern : {Pattern::kRandom, Pattern::kNoisyBayer}) {
      EncodeOptions options;
      options.strips = 5;
      const auto frame = rawtest::make_frame(pattern, width, height, row_bytes, 3);
      const auto reference = encode_frame(codec, frame, width, height, row_bytes, options, &pool);
      // Exactly enough room (forces the serial fallback when one slice is short).
      std::vector<uint8_t> exact(reference.size() + 64, 0xEE);
      CHECK(encode(codec, frame.data(), width, height, row_bytes, exact.data(), reference.size(),
                   options, &pool) == reference.size());
      CHECK(std::memcmp(exact.data(), reference.data(), reference.size()) == 0);
      for (size_t k = reference.size(); k < exact.size(); ++k) CHECK(exact[k] == 0xEE);
      // One byte short, much too short, and smaller than the container header.
      for (size_t capacity : {reference.size() - 1, reference.size() / 2, size_t{20}, size_t{0}}) {
        std::vector<uint8_t> small(capacity + 64, 0xEE);
        CHECK(encode(codec, frame.data(), width, height, row_bytes, small.data(), capacity, options,
                     &pool) == 0);
        for (size_t k = capacity; k < small.size(); ++k) CHECK(small[k] == 0xEE);
      }
      // Capacity equal to the raw size: accepted only when smaller.
      std::vector<uint8_t> raw_sized(frame.size());
      const size_t size = encode(codec, frame.data(), width, height, row_bytes, raw_sized.data(),
                                 raw_sized.size(), options, &pool);
      CHECK(size == (reference.size() <= frame.size() ? reference.size() : 0));
    }
  }
  // Invalid arguments.
  std::vector<uint8_t> frame(1000), out(4000);
  EncodeOptions options;
  CHECK(encode(Codec::kBlockPack, frame.data(), 6, 4, 10, out.data(), out.size(), options) == 0);
  CHECK(encode(Codec::kBlockPack, frame.data(), 8, 3, 10, out.data(), out.size(), options) == 0);
  CHECK(encode(Codec::kBlockPack, frame.data(), 8, 4, 9, out.data(), out.size(), options) == 0);
  options.lj92_predictor = 0;
  CHECK(encode(Codec::kLj92, frame.data(), 8, 4, 10, out.data(), out.size(), options) == 0);
  CHECK(max_encoded_size(Codec::kLj92, 6, 4, options) == 0);
}

void test_corrupt_input() {
  const int width = 64, height = 16, row_bytes = width / 4 * 5;
  std::vector<uint8_t> dst(static_cast<size_t>(row_bytes) * height);
  for (Codec codec : {Codec::kLj92, Codec::kBlockPack}) {
    EncodeOptions options;
    options.strips = 2;
    const auto frame = rawtest::make_frame(Pattern::kNoisyBayer, width, height, row_bytes, 9);
    const auto encoded = encode_frame(codec, frame, width, height, row_bytes, options, nullptr);
    for (size_t cut : {size_t{0}, size_t{3}, size_t{12}, encoded.size() / 2, encoded.size() - 1}) {
      CHECK(!decode(codec, encoded.data(), cut, width, height, dst.data(), row_bytes));
    }
    CHECK(!decode(codec, encoded.data(), encoded.size(), width, height + 2, dst.data(), row_bytes));
    if (codec == Codec::kLj92) {
      CHECK(!decode(codec, encoded.data(), encoded.size(), width + 4, height, dst.data(), row_bytes));
    }
    auto bad = encoded;
    bad[0] = 0;  // zero strips
    CHECK(!decode(codec, bad.data(), bad.size(), width, height, dst.data(), row_bytes));
    // Random byte flips must never crash (the result may or may not decode).
    rawtest::Rng rng(5);
    for (int trial = 0; trial < 500; ++trial) {
      auto flipped = encoded;
      const size_t at = 20 + rng.next() % (flipped.size() - 20);
      flipped[at] = static_cast<uint8_t>(flipped[at] ^ (1u << (rng.next() % 8)));
      (void)decode(codec, flipped.data(), flipped.size(), width, height, dst.data(), row_bytes);
    }
    // Decoding with the other codec fails cleanly.
    const Codec other = codec == Codec::kLj92 ? Codec::kBlockPack : Codec::kLj92;
    CHECK(!decode(other, encoded.data(), encoded.size(), width, height, dst.data(), row_bytes));
  }
}

// The Razr's noise models at ISO 143 and ISO 25600 (black 64), and a tiny one.
struct QuantCase {
  double a, b;
};
const QuantCase kQuantCases[] = {{0.3747, 0.04022}, {0.6, 6.8974}, {1e-6, 0.0}};

EncodeOptions quant_options(const rawtest::QuantTables& t, int strips) {
  EncodeOptions options;
  options.strips = strips;
  options.lut = t.forward.data();
  options.inverse_lut = t.inverse.data();
  options.lut_count = static_cast<int>(t.inverse.size());
  options.quant_iso = 25600;
  options.quant_k_milli = 500;
  options.quant_black_level = 64;
  return options;
}

void test_quant_tables() {
  for (const QuantCase& q : kQuantCases) {
    const auto t = rawtest::quant_tables(q.a, q.b, 64, 0.5);
    CHECK(t.forward[0] == 0);
    for (int i = 1; i < 1024; ++i) CHECK(t.forward[i] == t.forward[i - 1] || t.forward[i] == t.forward[i - 1] + 1);
    for (int i = 0; i < 1024; ++i) CHECK(t.inverse[t.forward[i]] <= 1023);
  }
  // Tiny noise: every DN keeps its own code.
  const auto identity = rawtest::quant_tables(1e-6, 0.0, 64, 0.5);
  CHECK(identity.inverse.size() == 1024);
  for (int i = 0; i < 1024; ++i) CHECK(identity.forward[i] == i && identity.inverse[i] == i);
}

void test_lut_block_pack() {
  ThreadPool pool(3);
  // An identity table codes exactly what no table codes.
  std::vector<uint16_t> identity(kQuantLutSize);
  for (int i = 0; i < kQuantLutSize; ++i) identity[i] = static_cast<uint16_t>(i);
  for (const QuantCase& q : kQuantCases) {
    const auto t = rawtest::quant_tables(q.a, q.b, 64, 0.5);
    for (Pattern pattern : {Pattern::kRandom, Pattern::kNoisyBayer, Pattern::kGradient, Pattern::kMax}) {
      for (int width : {16, 20, 100, 4080}) {
        const int height = width == 4080 ? 48 : 14;
        const int tight = width / 4 * 5;
        const int row_bytes = tight + 3;
        const auto frame = rawtest::make_frame(pattern, width, height, row_bytes, 11);
        auto mapped = frame;
        rawtest::map_frame(mapped, width, height, row_bytes, t.forward.data());
        for (Kernel kernel : {Kernel::kScalar, Kernel::kNeon}) {
          EncodeOptions plain;
          plain.strips = 3;
          plain.kernel = kernel;
          EncodeOptions with_identity = plain;
          with_identity.lut = identity.data();
          CHECK(encode_frame(Codec::kBlockPack, frame, width, height, row_bytes, with_identity, &pool) ==
                encode_frame(Codec::kBlockPack, frame, width, height, row_bytes, plain, &pool));
          // The table fused into the encoder equals block-pack of the pre-mapped frame.
          EncodeOptions with_lut = plain;
          with_lut.lut = t.forward.data();
          const auto codes = encode_frame(Codec::kBlockPack, mapped, width, height, row_bytes, plain, nullptr);
          CHECK(encode_frame(Codec::kBlockPack, frame, width, height, row_bytes, with_lut, &pool) == codes);
          // Encoding 3: preamble, inverse table, then that same container.
          EncodeOptions quant = quant_options(t, 3);
          quant.kernel = kernel;
          const auto payload = encode_frame(Codec::kBlockPackQuantised, frame, width, height, row_bytes, quant, &pool);
          const size_t prefix = kQuantPreambleBytes + 2 * t.inverse.size();
          CHECK(payload.size() == prefix + codes.size());
          CHECK(payload[0] == 1 && payload[1] == 0);
          CHECK((payload[2] | payload[3] << 8) == static_cast<int>(t.inverse.size()));
          CHECK((payload[4] | payload[5] << 8 | payload[6] << 16 | payload[7] << 24) == 25600);
          CHECK((payload[8] | payload[9] << 8) == 500 && (payload[10] | payload[11] << 8) == 64);
          bool table_ok = true;
          for (size_t c = 0; c < t.inverse.size(); ++c) {
            table_ok = table_ok && (payload[12 + 2 * c] | payload[13 + 2 * c] << 8) == t.inverse[c];
          }
          CHECK(table_ok);
          CHECK(std::equal(codes.begin(), codes.end(), payload.begin() + static_cast<long>(prefix)));
          // Decoding yields inverse[forward[x]] for every sample, padding untouched.
          const int dst_row_bytes = tight + 5;
          std::vector<uint8_t> decoded(static_cast<size_t>(dst_row_bytes) * height, 0x5A);
          CHECK(decode(Codec::kBlockPackQuantised, payload.data(), payload.size(), width, height,
                       decoded.data(), dst_row_bytes, &pool));
          const auto source = rawtest::unpack_frame(frame, width, height, row_bytes);
          const auto back = rawtest::unpack_frame(decoded, width, height, dst_row_bytes);
          bool same = true;
          for (size_t i = 0; i < source.size(); ++i) same = same && back[i] == t.inverse[t.forward[source[i]]];
          for (int y = 0; y < height; ++y) {
            for (int k = tight; k < dst_row_bytes; ++k) same = same && decoded[static_cast<size_t>(y) * dst_row_bytes + k] == 0x5A;
          }
          CHECK(same);
          // An old reader decoding it as encoding 2 fails rather than returning codes.
          CHECK(!decode(Codec::kBlockPack, payload.data(), payload.size(), width, height, decoded.data(), dst_row_bytes));
        }
      }
    }
  }
}

void test_quant_invalid() {
  const int width = 64, height = 16, row_bytes = width / 4 * 5;
  const auto t = rawtest::quant_tables(0.6, 6.8974, 64, 0.5);
  const auto frame = rawtest::make_frame(Pattern::kNoisyBayer, width, height, row_bytes, 4);
  std::vector<uint8_t> out(max_encoded_size(Codec::kBlockPackQuantised, width, height, quant_options(t, 2)));
  std::vector<uint8_t> dst(static_cast<size_t>(row_bytes) * height);
  auto encode_with = [&](EncodeOptions options) {
    return encode(Codec::kBlockPackQuantised, frame.data(), width, height, row_bytes, out.data(), out.size(), options);
  };
  // Bad tables are refused.
  EncodeOptions none;
  CHECK(encode_with(none) == 0);
  EncodeOptions no_inverse = quant_options(t, 2);
  no_inverse.inverse_lut = nullptr;
  CHECK(encode_with(no_inverse) == 0);
  EncodeOptions short_count = quant_options(t, 2);
  short_count.lut_count = static_cast<int>(t.inverse.size()) - 1;  // the top code has no entry
  CHECK(encode_with(short_count) == 0);
  EncodeOptions zero_count = quant_options(t, 2);
  zero_count.lut_count = 0;
  CHECK(encode_with(zero_count) == 0);
  auto inverse = t.inverse;
  inverse[1] = 1024;
  EncodeOptions big_inverse = quant_options(t, 2);
  big_inverse.inverse_lut = inverse.data();
  CHECK(encode_with(big_inverse) == 0);
  auto forward = t.forward;
  forward[5] = 1024;
  EncodeOptions big_forward;
  big_forward.lut = forward.data();
  CHECK(encode(Codec::kBlockPack, frame.data(), width, height, row_bytes, out.data(), out.size(), big_forward) == 0);
  // A payload too small for its prefix does not fit.
  CHECK(encode(Codec::kBlockPackQuantised, frame.data(), width, height, row_bytes, out.data(),
               kQuantPreambleBytes + 2 * t.inverse.size(), quant_options(t, 2)) == 0);

  const size_t size = encode_with(quant_options(t, 2));
  CHECK(size > 0);
  const std::vector<uint8_t> good(out.begin(), out.begin() + static_cast<long>(size));
  CHECK(decode(Codec::kBlockPackQuantised, good.data(), good.size(), width, height, dst.data(), row_bytes));
  const size_t prefix = kQuantPreambleBytes + 2 * t.inverse.size();
  for (size_t cut : {size_t{0}, size_t{5}, size_t{11}, prefix - 1, prefix, prefix + 3, good.size() - 1}) {
    CHECK(!decode(Codec::kBlockPackQuantised, good.data(), cut, width, height, dst.data(), row_bytes));
  }
  auto bad = good;
  bad[0] = 2;  // unknown version
  CHECK(!decode(Codec::kBlockPackQuantised, bad.data(), bad.size(), width, height, dst.data(), row_bytes));
  bad = good;
  bad[2] = 0;
  bad[3] = 0;  // no table
  CHECK(!decode(Codec::kBlockPackQuantised, bad.data(), bad.size(), width, height, dst.data(), row_bytes));
  bad = good;
  bad[2] = 0x01;
  bad[3] = 0x04;  // 1025 entries
  CHECK(!decode(Codec::kBlockPackQuantised, bad.data(), bad.size(), width, height, dst.data(), row_bytes));
  bad = good;
  bad[12] = 0x00;
  bad[13] = 0x04;  // inverse[0] = 1024
  CHECK(!decode(Codec::kBlockPackQuantised, bad.data(), bad.size(), width, height, dst.data(), row_bytes));
  // The reserved byte and the informative fields are ignored.
  bad = good;
  bad[1] = 7;
  bad[4] = bad[8] = bad[10] = 0x33;
  CHECK(decode(Codec::kBlockPackQuantised, bad.data(), bad.size(), width, height, dst.data(), row_bytes));
  // A code at or above the table count is corrupt: a lossless container of the
  // raw samples (up to 1023) behind a four-entry table.
  EncodeOptions plain;
  plain.strips = 2;
  const auto lossless = encode_frame(Codec::kBlockPack, frame, width, height, row_bytes, plain, nullptr);
  std::vector<uint8_t> small = {1, 0, 4, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 2, 0, 3, 0};
  small.insert(small.end(), lossless.begin(), lossless.end());
  CHECK(!decode(Codec::kBlockPackQuantised, small.data(), small.size(), width, height, dst.data(), row_bytes));
  // With a full table the same container decodes to the samples themselves.
  std::vector<uint8_t> full = {1, 0, 0, 4, 0, 0, 0, 0, 0, 0, 0, 0};
  for (int c = 0; c < 1024; ++c) {
    full.push_back(static_cast<uint8_t>(c));
    full.push_back(static_cast<uint8_t>(c >> 8));
  }
  full.insert(full.end(), lossless.begin(), lossless.end());
  CHECK(decode(Codec::kBlockPackQuantised, full.data(), full.size(), width, height, dst.data(), row_bytes));
  CHECK(dst == frame);
  // Random byte flips never crash.
  rawtest::Rng rng(8);
  for (int trial = 0; trial < 500; ++trial) {
    auto flipped = good;
    const size_t at = rng.next() % flipped.size();
    flipped[at] = static_cast<uint8_t>(flipped[at] ^ (1u << (rng.next() % 8)));
    (void)decode(Codec::kBlockPackQuantised, flipped.data(), flipped.size(), width, height, dst.data(), row_bytes);
  }
}

void test_lj92_layout() {
  // The stream must look like a DNG CFA LJ92 strip: SOI, DHT, SOF3 (P=10,
  // Y=rows, X=width/2, 2 components), SOS (predictor), entropy data, EOI.
  const int width = 64, height = 8, row_bytes = width / 4 * 5;
  EncodeOptions options;
  options.strips = 1;
  options.lj92_predictor = 6;
  const auto frame = rawtest::make_frame(Pattern::kNoisyBayer, width, height, row_bytes, 2);
  const auto encoded = encode_frame(Codec::kLj92, frame, width, height, row_bytes, options, nullptr);
  const uint8_t* s = encoded.data() + 12;
  CHECK(s[0] == 0xFF && s[1] == 0xD8 && s[2] == 0xFF && s[3] == 0xC4);
  const int dht = s[4] << 8 | s[5];
  const uint8_t* sof = s + 4 + dht;
  CHECK(sof[0] == 0xFF && sof[1] == 0xC3 && sof[4] == 10);
  CHECK((sof[5] << 8 | sof[6]) == height && (sof[7] << 8 | sof[8]) == width / 2 && sof[9] == 2);
  const uint8_t* sos = sof + 2 + (sof[2] << 8 | sof[3]);
  CHECK(sos[0] == 0xFF && sos[1] == 0xDA && sos[4] == 2 && sos[9] == 6 && sos[10] == 0 && sos[11] == 0);
  CHECK(encoded[encoded.size() - 2] == 0xFF && encoded.back() == 0xD9);
  // Flat data codes every difference as category 0 with a 1-bit code.
  const auto flat = rawtest::make_frame(Pattern::kFlat, width, height, row_bytes);
  options.lj92_predictor = 1;
  const auto flat_encoded = encode_frame(Codec::kLj92, flat, width, height, row_bytes, options, nullptr);
  CHECK(flat_encoded.size() < 12 + 2 + 2 + 2 + 17 + 1 + 16 + 14 + 2 + width * height / 8 + 4);
}

}  // namespace

int main() {
  test_strip_layout();
  test_round_trips();
  test_kernels_match();
  test_overflow();
  test_corrupt_input();
  test_lj92_layout();
  test_quant_tables();
  test_lut_block_pack();
  test_quant_invalid();
  test_full_frame();
  if (failures != 0) {
    std::fprintf(stderr, "%d check(s) failed\n", failures);
    return EXIT_FAILURE;
  }
  std::printf("raw_codecs_test: all checks passed\n");
  return EXIT_SUCCESS;
}
