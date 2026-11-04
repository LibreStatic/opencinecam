/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

#include "opencinecam/raw_codecs.h"

#include <atomic>
#include <condition_variable>
#include <cstring>
#include <memory>
#include <mutex>
#include <thread>
#include <vector>

#if defined(__ARM_NEON) && defined(__aarch64__)
#include <arm_neon.h>
#define OPENCINECAM_RAW_NEON 1
#endif

#if !defined(__BYTE_ORDER__) || __BYTE_ORDER__ != __ORDER_LITTLE_ENDIAN__
#error "raw_codecs.cpp assumes a little-endian target"
#endif

namespace opencinecam::rawcodec {
namespace {

constexpr int kSampleMax = 1023;
constexpr int kBlock = 16;
constexpr size_t kSlack = 8;         // write-ahead of the 64-bit bit writers
constexpr int kLj92MaxDiff = 2047;   // |difference| bound for P = 10, any predictor
constexpr size_t kLj92HeaderBound = 128;

inline uint32_t read_u32(const uint8_t* p) {
  return uint32_t{p[0]} | uint32_t{p[1]} << 8 | uint32_t{p[2]} << 16 | uint32_t{p[3]} << 24;
}

inline void write_u32(uint8_t* p, uint32_t v) {
  p[0] = static_cast<uint8_t>(v);
  p[1] = static_cast<uint8_t>(v >> 8);
  p[2] = static_cast<uint8_t>(v >> 16);
  p[3] = static_cast<uint8_t>(v >> 24);
}

inline int bit_length(uint32_t v) { return v == 0 ? 0 : 32 - __builtin_clz(v); }

bool use_neon(Kernel kernel) {
#if defined(OPENCINECAM_RAW_NEON)
  return kernel != Kernel::kScalar;
#else
  (void)kernel;
  return false;
#endif
}

// ---------------------------------------------------------------- RAW10 rows

void unpack_row_scalar(const uint8_t* src, int width, int from, uint16_t* out) {
  src += from / 4 * 5;
  for (int i = from; i < width; i += 4, src += 5) {
    const uint8_t low = src[4];
    out[i] = static_cast<uint16_t>(src[0] << 2 | (low & 3));
    out[i + 1] = static_cast<uint16_t>(src[1] << 2 | ((low >> 2) & 3));
    out[i + 2] = static_cast<uint16_t>(src[2] << 2 | ((low >> 4) & 3));
    out[i + 3] = static_cast<uint16_t>(src[3] << 2 | (low >> 6));
  }
}

#if defined(OPENCINECAM_RAW_NEON)
// 16 samples from 20 bytes per step; the two loads read exactly bytes 0..19.
void unpack_row_neon(const uint8_t* src, int width, uint16_t* out) {
  static const uint8_t kHigh[16] = {0, 1, 2, 3, 5, 6, 7, 8, 10, 11, 12, 13, 15, 28, 29, 30};
  static const uint8_t kLow[16] = {4, 4, 4, 4, 9, 9, 9, 9, 14, 14, 14, 14, 31, 31, 31, 31};
  static const int16_t kShift[8] = {0, -2, -4, -6, 0, -2, -4, -6};
  const uint8x16_t high_index = vld1q_u8(kHigh);
  const uint8x16_t low_index = vld1q_u8(kLow);
  const int16x8_t shift = vld1q_s16(kShift);
  const uint16x8_t three = vdupq_n_u16(3);
  int i = 0;
  for (; i + 16 <= width; i += 16) {
    const uint8_t* p = src + i / 4 * 5;
    uint8x16x2_t table;
    table.val[0] = vld1q_u8(p);
    table.val[1] = vld1q_u8(p + 4);
    const uint8x16_t high = vqtbl2q_u8(table, high_index);
    const uint8x16_t low = vqtbl2q_u8(table, low_index);
    const uint16x8_t h0 = vshll_n_u8(vget_low_u8(high), 2);
    const uint16x8_t h1 = vshll_high_n_u8(high, 2);
    const uint16x8_t l0 = vandq_u16(vshlq_u16(vmovl_u8(vget_low_u8(low)), shift), three);
    const uint16x8_t l1 = vandq_u16(vshlq_u16(vmovl_high_u8(low), shift), three);
    vst1q_u16(out + i, vorrq_u16(h0, l0));
    vst1q_u16(out + i + 8, vorrq_u16(h1, l1));
  }
  unpack_row_scalar(src, width, i, out);
}
#endif

void unpack_row(const uint8_t* src, int width, uint16_t* out, bool neon) {
#if defined(OPENCINECAM_RAW_NEON)
  if (neon) {
    unpack_row_neon(src, width, out);
    return;
  }
#endif
  (void)neon;
  unpack_row_scalar(src, width, 0, out);
}

void pack_row(const uint16_t* in, int width, uint8_t* dst) {
  for (int i = 0; i < width; i += 4, dst += 5) {
    dst[0] = static_cast<uint8_t>(in[i] >> 2);
    dst[1] = static_cast<uint8_t>(in[i + 1] >> 2);
    dst[2] = static_cast<uint8_t>(in[i + 2] >> 2);
    dst[3] = static_cast<uint8_t>(in[i + 3] >> 2);
    dst[4] = static_cast<uint8_t>((in[i] & 3) | (in[i + 1] & 3) << 2 | (in[i + 2] & 3) << 4 |
                                  (in[i + 3] & 3) << 6);
  }
}

// ---------------------------------------------------------------- bit writers

// Least significant bit first; flush() stores 8 bytes ahead, so the output needs
// kSlack bytes beyond the data. Callers keep n + bits <= 63.
struct LsbWriter {
  uint8_t* p;
  uint64_t acc = 0;
  int n = 0;

  void put(uint64_t value, int bits) {
    acc |= value << n;
    n += bits;
  }
  void flush() {
    std::memcpy(p, &acc, 8);
    p += n >> 3;
    acc = (n & ~7) == 0 ? acc : acc >> (n & ~7);
    n &= 7;
  }
};

// Most significant bit first with JPEG 0xFF00 stuffing. The output pointer may be
// redirected between rows; the pending bits carry over.
struct MsbWriter {
  uint8_t* p = nullptr;
  uint64_t acc = 0;
  int n = 0;

  void emit_byte(uint8_t b) {
    *p++ = b;
    if (b == 0xFF) *p++ = 0;
  }
  void emit32(uint32_t w) {
    if ((((~w) - 0x01010101u) & w & 0x80808080u) == 0) {  // no 0xFF byte
      p[0] = static_cast<uint8_t>(w >> 24);
      p[1] = static_cast<uint8_t>(w >> 16);
      p[2] = static_cast<uint8_t>(w >> 8);
      p[3] = static_cast<uint8_t>(w);
      p += 4;
    } else {
      emit_byte(static_cast<uint8_t>(w >> 24));
      emit_byte(static_cast<uint8_t>(w >> 16));
      emit_byte(static_cast<uint8_t>(w >> 8));
      emit_byte(static_cast<uint8_t>(w));
    }
  }
  // value holds exactly bits (<= 32) bits; n <= 31 on entry.
  void put(uint32_t value, int bits) {
    acc = (acc << bits) | value;
    n += bits;
    if (n >= 32) {
      n -= 32;
      emit32(static_cast<uint32_t>(acc >> n));
    }
  }
  // Pads with 1 bits to a byte boundary; at most 10 bytes.
  void finish() {
    while (n >= 8) {
      n -= 8;
      emit_byte(static_cast<uint8_t>(acc >> n));
    }
    if (n > 0) {
      const int pad = 8 - n;
      emit_byte(static_cast<uint8_t>(((acc << pad) | ((1u << pad) - 1)) & 0xFFu));
      n = 0;
    }
  }
};

// Routes each row either straight into the strip's output or, near its end,
// through a scratch row so the capacity check stays exact.
struct RowSink {
  uint8_t* base;
  size_t capacity;
  size_t used = 0;
  uint8_t* scratch;
  size_t row_worst;  // without kSlack
  bool redirected = false;

  uint8_t* begin_row() {
    redirected = capacity - used < row_worst + kSlack;
    return redirected ? scratch : base + used;
  }
  // Returns false when the row does not fit.
  bool end_row(const uint8_t* end) {
    const uint8_t* start = redirected ? scratch : base + used;
    const size_t bytes = static_cast<size_t>(end - start);
    if (redirected) {
      if (bytes > capacity - used) return false;
      std::memcpy(base + used, scratch, bytes);
    }
    used += bytes;
    return true;
  }
};

// ---------------------------------------------------------------- block-pack

size_t block_pack_row_worst(int width) {
  const size_t blocks = static_cast<size_t>((width + kBlock - 1) / kBlock);
  return (blocks + 1) / 2 + blocks * 22;
}

// Residuals of one row: buf[0..1] hold the predictions of the first two
// samples, buf[2..width+1] the samples. Fills z (padded to whole blocks with 0)
// and the block widths.
void block_residuals_scalar(const uint16_t* buf, int width, int from, uint16_t* z,
                            uint8_t* widths) {
  const int blocks = (width + kBlock - 1) / kBlock;
  for (int b = from / kBlock; b < blocks; ++b) {
    uint32_t max = 0;
    for (int k = 0; k < kBlock; ++k) {
      const int i = b * kBlock + k;
      uint32_t v = 0;
      if (i < width) {
        const int r = int{buf[i + 2]} - int{buf[i]};
        v = (static_cast<uint32_t>(r) << 1) ^ static_cast<uint32_t>(r >> 31);
      }
      z[i] = static_cast<uint16_t>(v);
      if (v > max) max = v;
    }
    widths[b] = static_cast<uint8_t>(bit_length(max));
  }
}

#if defined(OPENCINECAM_RAW_NEON)
void block_residuals_neon(const uint16_t* buf, int width, uint16_t* z, uint8_t* widths) {
  int i = 0;
  for (; i + kBlock <= width; i += kBlock) {
    const int16x8_t r0 = vreinterpretq_s16_u16(vsubq_u16(vld1q_u16(buf + i + 2), vld1q_u16(buf + i)));
    const int16x8_t r1 =
        vreinterpretq_s16_u16(vsubq_u16(vld1q_u16(buf + i + 10), vld1q_u16(buf + i + 8)));
    const uint16x8_t z0 = vreinterpretq_u16_s16(veorq_s16(vshlq_n_s16(r0, 1), vshrq_n_s16(r0, 15)));
    const uint16x8_t z1 = vreinterpretq_u16_s16(veorq_s16(vshlq_n_s16(r1, 1), vshrq_n_s16(r1, 15)));
    vst1q_u16(z + i, z0);
    vst1q_u16(z + i + 8, z1);
    widths[i / kBlock] = static_cast<uint8_t>(bit_length(vmaxvq_u16(vmaxq_u16(z0, z1))));
  }
  block_residuals_scalar(buf, width, i, z, widths);
}

// Packs 16 values of w bits into four 4w-bit chunks.
inline void block_chunks_neon(const uint16_t* z, int w, uint64_t chunks[4]) {
  const int32x4_t shift_w = vdupq_n_s32(w);
  const int64x2_t shift_2w = vdupq_n_s64(2 * w);
  const uint32x4_t mask16 = vdupq_n_u32(0xFFFFu);
  const uint64x2_t mask32 = vdupq_n_u64(0xFFFFFFFFu);
  for (int half = 0; half < 2; ++half) {
    const uint32x4_t a = vreinterpretq_u32_u16(vld1q_u16(z + half * 8));
    const uint32x4_t pairs = vorrq_u32(vandq_u32(a, mask16), vshlq_u32(vshrq_n_u32(a, 16), shift_w));
    const uint64x2_t q = vreinterpretq_u64_u32(pairs);
    const uint64x2_t quads = vorrq_u64(vandq_u64(q, mask32), vshlq_u64(vshrq_n_u64(q, 32), shift_2w));
    chunks[half * 2] = vgetq_lane_u64(quads, 0);
    chunks[half * 2 + 1] = vgetq_lane_u64(quads, 1);
  }
}
#endif

inline void block_chunks_scalar(const uint16_t* z, int w, uint64_t chunks[4]) {
  for (int c = 0; c < 4; ++c) {
    const uint16_t* v = z + c * 4;
    chunks[c] = uint64_t{v[0]} | uint64_t{v[1]} << w | uint64_t{v[2]} << (2 * w) |
                uint64_t{v[3]} << (3 * w);
  }
}

uint8_t* block_pack_write_row(const uint16_t* z, const uint8_t* widths, int width, uint8_t* out,
                              bool neon) {
  const int blocks = (width + kBlock - 1) / kBlock;
  for (int b = 0; b < blocks; b += 2) {
    const uint8_t high = b + 1 < blocks ? widths[b + 1] : 0;
    *out++ = static_cast<uint8_t>(widths[b] | high << 4);
  }
  LsbWriter writer{out};
  for (int b = 0; b < blocks; ++b) {
    const int w = widths[b];
    if (w == 0) continue;
    uint64_t chunks[4];
#if defined(OPENCINECAM_RAW_NEON)
    if (neon) {
      block_chunks_neon(z + b * kBlock, w, chunks);
    } else {
      block_chunks_scalar(z + b * kBlock, w, chunks);
    }
#else
    (void)neon;
    block_chunks_scalar(z + b * kBlock, w, chunks);
#endif
    if (w <= 7) {
      writer.put(chunks[0] | chunks[1] << (4 * w), 8 * w);
      writer.flush();
      writer.put(chunks[2] | chunks[3] << (4 * w), 8 * w);
      writer.flush();
    } else {
      for (int c = 0; c < 4; ++c) {
        writer.put(chunks[c], 4 * w);
        writer.flush();
      }
    }
  }
  return writer.p;  // every block payload is whole bytes, so nothing is pending
}

struct StripScratch {
  std::unique_ptr<uint16_t[]> a, b, z;
  std::unique_ptr<uint8_t[]> widths, row_out;
  void ensure(int width, size_t row_bytes) {
    const size_t padded = static_cast<size_t>((width + kBlock - 1) / kBlock * kBlock + 2);
    a.reset(new uint16_t[padded]());
    b.reset(new uint16_t[padded]());
    z.reset(new uint16_t[padded]());
    widths.reset(new uint8_t[padded / kBlock + 1]());
    row_out.reset(new uint8_t[row_bytes + kSlack + 16]());
  }
};

// Returns the strip size or SIZE_MAX when it does not fit. lut (nullptr: none)
// maps each unpacked sample to the value that is coded.
size_t block_pack_encode_strip(const uint8_t* raw, int width, int rows, int row_bytes,
                               uint8_t* dst, size_t capacity, bool neon, const uint16_t* lut) {
  StripScratch scratch;
  const size_t row_worst = block_pack_row_worst(width);
  scratch.ensure(width, row_worst);
  RowSink sink{dst, capacity, 0, scratch.row_out.get(), row_worst};
  uint16_t first[2][2] = {{0, 0}, {0, 0}};  // first two samples of rows y-2 / y-1 by parity
  uint16_t* buf = scratch.a.get();
  for (int y = 0; y < rows; ++y) {
    unpack_row(raw + static_cast<size_t>(y) * row_bytes, width, buf + 2, neon);
    if (lut != nullptr) {
      uint16_t* x = buf + 2;
      for (int i = 0; i < width; ++i) x[i] = lut[x[i]];
    }
    buf[0] = y >= 2 ? first[y & 1][0] : 0;
    buf[1] = y >= 2 ? first[y & 1][1] : 0;
    first[y & 1][0] = buf[2];
    first[y & 1][1] = buf[3];
#if defined(OPENCINECAM_RAW_NEON)
    if (neon) {
      block_residuals_neon(buf, width, scratch.z.get(), scratch.widths.get());
    } else {
      block_residuals_scalar(buf, width, 0, scratch.z.get(), scratch.widths.get());
    }
#else
    block_residuals_scalar(buf, width, 0, scratch.z.get(), scratch.widths.get());
#endif
    uint8_t* out = sink.begin_row();
    uint8_t* end = block_pack_write_row(scratch.z.get(), scratch.widths.get(), width, out, neon);
    if (!sink.end_row(end)) return SIZE_MAX;
  }
  return sink.used;
}

// inverse (nullptr: none) maps each decoded value, which must be below
// sample_limit, to the sample written; prediction stays on the decoded values.
bool block_pack_decode_strip(const uint8_t* src, size_t length, int width, int rows,
                             uint8_t* dst, int dst_row_bytes, const uint16_t* inverse,
                             int sample_limit) {
  const int blocks = (width + kBlock - 1) / kBlock;
  std::vector<uint16_t> row(static_cast<size_t>(blocks) * kBlock + 2);
  std::vector<uint16_t> mapped(inverse != nullptr ? static_cast<size_t>(width) : 0);
  std::vector<uint8_t> widths(static_cast<size_t>(blocks));
  uint16_t first[2][2] = {{0, 0}, {0, 0}};
  const uint8_t* p = src;
  const uint8_t* end = src + length;
  for (int y = 0; y < rows; ++y) {
    const size_t nibble_bytes = static_cast<size_t>((blocks + 1) / 2);
    if (static_cast<size_t>(end - p) < nibble_bytes) return false;
    size_t payload = 0;
    for (int b = 0; b < blocks; ++b) {
      const int w = (p[b / 2] >> ((b & 1) * 4)) & 0xF;
      if (w > 11) return false;
      widths[b] = static_cast<uint8_t>(w);
      payload += static_cast<size_t>(2 * w);
    }
    if ((blocks & 1) && (p[blocks / 2] >> 4) != 0) return false;
    p += nibble_bytes;
    if (static_cast<size_t>(end - p) < payload) return false;
    uint16_t* x = row.data() + 2;
    x[-2] = y >= 2 ? first[y & 1][0] : 0;
    x[-1] = y >= 2 ? first[y & 1][1] : 0;
    for (int b = 0; b < blocks; ++b) {
      const int w = widths[b];
      uint64_t acc = 0;
      int n = 0;
      const uint32_t mask = (1u << w) - 1;
      for (int k = 0; k < kBlock; ++k) {
        uint32_t zv = 0;
        if (w > 0) {
          while (n < w) {
            acc |= uint64_t{*p++} << n;
            n += 8;
          }
          zv = static_cast<uint32_t>(acc) & mask;
          acc >>= w;
          n -= w;
        }
        const int i = b * kBlock + k;
        if (i >= width) continue;
        const int r = static_cast<int>(zv >> 1) ^ -static_cast<int>(zv & 1);
        const int v = int{x[i - 2]} + r;
        if (v < 0 || v >= sample_limit) return false;
        x[i] = static_cast<uint16_t>(v);
      }
    }
    first[y & 1][0] = x[0];
    first[y & 1][1] = x[1];
    if (inverse != nullptr) {
      for (int i = 0; i < width; ++i) mapped[i] = inverse[x[i]];
      pack_row(mapped.data(), width, dst + static_cast<size_t>(y) * dst_row_bytes);
    } else {
      pack_row(x, width, dst + static_cast<size_t>(y) * dst_row_bytes);
    }
  }
  return p == end;
}

// ---------------------------------------------------------------- LJ92

struct HuffSpec {
  uint8_t bits[17] = {};  // bits[1..16]: number of codes of each length
  uint8_t vals[17] = {};
  int count = 0;
};

// T.81 K.3 luminance DC table: categories 0..11.
HuffSpec default_spec() {
  HuffSpec spec;
  static const uint8_t kBits[17] = {0, 0, 1, 5, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0};
  std::memcpy(spec.bits, kBits, sizeof(kBits));
  for (int i = 0; i < 12; ++i) spec.vals[i] = static_cast<uint8_t>(i);
  spec.count = 12;
  return spec;
}

// T.81 K.2 (as libjpeg's jpeg_gen_optimal_table): Huffman code lengths for
// categories 0..16 limited to 16 bits, with a reserved symbol that keeps the
// all-ones code unused.
HuffSpec optimal_spec(const uint64_t histogram[17]) {
  constexpr int kSymbols = 18;
  uint64_t freq[kSymbols];
  int codesize[kSymbols] = {};
  int others[kSymbols];
  for (int i = 0; i < 17; ++i) freq[i] = histogram[i];
  freq[17] = 1;
  for (int i = 0; i < kSymbols; ++i) others[i] = -1;
  for (;;) {
    int c1 = -1, c2 = -1;
    uint64_t v = UINT64_MAX;
    for (int i = 0; i < kSymbols; ++i) {
      if (freq[i] && freq[i] <= v) {
        v = freq[i];
        c1 = i;
      }
    }
    v = UINT64_MAX;
    for (int i = 0; i < kSymbols; ++i) {
      if (freq[i] && freq[i] <= v && i != c1) {
        v = freq[i];
        c2 = i;
      }
    }
    if (c2 < 0) break;
    freq[c1] += freq[c2];
    freq[c2] = 0;
    ++codesize[c1];
    while (others[c1] >= 0) {
      c1 = others[c1];
      ++codesize[c1];
    }
    others[c1] = c2;
    ++codesize[c2];
    while (others[c2] >= 0) {
      c2 = others[c2];
      ++codesize[c2];
    }
  }
  int bits[33] = {};
  for (int i = 0; i < kSymbols; ++i) {
    if (codesize[i]) ++bits[codesize[i]];
  }
  for (int i = 32; i > 16; --i) {
    while (bits[i] > 0) {
      int j = i - 2;
      while (bits[j] == 0) --j;
      bits[i] -= 2;
      ++bits[i - 1];
      bits[j + 1] += 2;
      --bits[j];
    }
  }
  int i = 16;
  while (bits[i] == 0) --i;
  --bits[i];  // drop the reserved symbol's code
  HuffSpec spec;
  for (int l = 1; l <= 16; ++l) spec.bits[l] = static_cast<uint8_t>(bits[l]);
  for (int l = 1; l <= 32; ++l) {
    for (int s = 0; s < 17; ++s) {
      if (codesize[s] == l) spec.vals[spec.count++] = static_cast<uint8_t>(s);
    }
  }
  return spec;
}

struct HuffCodes {
  uint16_t code[17] = {};
  uint8_t length[17] = {};
};

HuffCodes codes_of(const HuffSpec& spec) {
  HuffCodes codes;
  uint32_t code = 0;
  int k = 0;
  for (int l = 1; l <= 16; ++l) {
    for (int i = 0; i < spec.bits[l]; ++i) {
      const int symbol = spec.vals[k++];
      codes.code[symbol] = static_cast<uint16_t>(code);
      codes.length[symbol] = static_cast<uint8_t>(l);
      ++code;
    }
    code <<= 1;
  }
  return codes;
}

inline int lj92_predict(int predictor, int ra, int rb, int rc) {
  switch (predictor) {
    case 1: return ra;
    case 2: return rb;
    case 3: return rc;
    case 4: return ra + rb - rc;
    case 5: return ra + ((rb - rc) >> 1);
    case 6: return rb + ((ra - rc) >> 1);
    default: return (ra + rb) >> 1;
  }
}

// Differences of one row into diff[0..width). cur/prev point at the samples
// (two readable slots before index 0); prev is unused on the strip's first row.
void lj92_row_diffs(const uint16_t* cur, const uint16_t* prev, bool first_row, int width,
                    int predictor, int16_t* diff, bool neon) {
  if (first_row) {
    diff[0] = static_cast<int16_t>(cur[0] - 512);
    diff[1] = static_cast<int16_t>(cur[1] - 512);
    for (int i = 2; i < width; ++i) diff[i] = static_cast<int16_t>(cur[i] - cur[i - 2]);
    return;
  }
  diff[0] = static_cast<int16_t>(cur[0] - prev[0]);
  diff[1] = static_cast<int16_t>(cur[1] - prev[1]);
  int i = 2;
#if defined(OPENCINECAM_RAW_NEON)
  if (neon && predictor == 1) {
    for (; i + 8 <= width; i += 8) {
      vst1q_s16(diff + i, vreinterpretq_s16_u16(vsubq_u16(vld1q_u16(cur + i), vld1q_u16(cur + i - 2))));
    }
  }
#else
  (void)neon;
#endif
  if (predictor == 1) {
    for (; i < width; ++i) diff[i] = static_cast<int16_t>(cur[i] - cur[i - 2]);
    return;
  }
  for (; i < width; ++i) {
    diff[i] = static_cast<int16_t>(cur[i] - lj92_predict(predictor, cur[i - 2], prev[i], prev[i - 2]));
  }
}

size_t lj92_row_worst(int width) {
  // 16-bit code + 11 extra bits per sample, every byte possibly stuffed.
  return (static_cast<size_t>(width) * 27 + 7) / 8 * 2 + 8;
}

bool put_bytes(uint8_t*& p, const uint8_t* end, const uint8_t* bytes, size_t n) {
  if (static_cast<size_t>(end - p) < n) return false;
  std::memcpy(p, bytes, n);
  p += n;
  return true;
}

size_t lj92_encode_strip(const uint8_t* raw, int width, int rows, int row_bytes, uint8_t* dst,
                         size_t capacity, int predictor, bool optimal, bool neon) {
  const size_t padded = static_cast<size_t>(width) + 2;
  std::vector<uint16_t> rows_buf(padded * 2);
  std::vector<int16_t> diff(static_cast<size_t>(width));
  const size_t row_worst = lj92_row_worst(width);
  std::vector<uint8_t> row_out(row_worst + kSlack + 16);

  auto row_ptr = [&](int y) { return rows_buf.data() + (y & 1) * padded + 2; };

  HuffSpec spec;
  if (optimal) {
    std::vector<uint32_t> by_diff(2 * kLj92MaxDiff + 1);
    for (int y = 0; y < rows; ++y) {
      unpack_row(raw + static_cast<size_t>(y) * row_bytes, width, row_ptr(y), neon);
      lj92_row_diffs(row_ptr(y), row_ptr(y + 1), y == 0, width, predictor, diff.data(), neon);
      for (int i = 0; i < width; ++i) ++by_diff[diff[i] + kLj92MaxDiff];
    }
    uint64_t histogram[17] = {};
    for (int d = -kLj92MaxDiff; d <= kLj92MaxDiff; ++d) {
      histogram[bit_length(static_cast<uint32_t>(d < 0 ? -d : d))] += by_diff[d + kLj92MaxDiff];
    }
    spec = optimal_spec(histogram);
  } else {
    spec = default_spec();
  }
  const HuffCodes codes = codes_of(spec);

  // Code word (Huffman code followed by the extra bits) and its length per difference.
  std::vector<uint32_t> word(2 * kLj92MaxDiff + 1);
  std::vector<uint8_t> word_bits(2 * kLj92MaxDiff + 1);
  for (int d = -kLj92MaxDiff; d <= kLj92MaxDiff; ++d) {
    const int ssss = bit_length(static_cast<uint32_t>(d < 0 ? -d : d));
    const int length = codes.length[ssss];
    if (length == 0) {
      word_bits[d + kLj92MaxDiff] = 0;  // unused category: fails the encode below if met
      continue;
    }
    const uint32_t extra = static_cast<uint32_t>(d < 0 ? d - 1 : d) & ((1u << ssss) - 1);
    word[d + kLj92MaxDiff] = (uint32_t{codes.code[ssss]} << ssss) | extra;
    word_bits[d + kLj92MaxDiff] = static_cast<uint8_t>(length + ssss);
  }

  uint8_t* p = dst;
  const uint8_t* end = dst + capacity;
  uint8_t header[kLj92HeaderBound];
  size_t h = 0;
  auto u8 = [&](int v) { header[h++] = static_cast<uint8_t>(v); };
  auto u16 = [&](int v) {
    u8(v >> 8);
    u8(v & 0xFF);
  };
  u16(0xFFD8);  // SOI
  u16(0xFFC4);  // DHT
  u16(2 + 1 + 16 + spec.count);
  u8(0x00);  // Tc = 0, Th = 0
  for (int l = 1; l <= 16; ++l) u8(spec.bits[l]);
  for (int i = 0; i < spec.count; ++i) u8(spec.vals[i]);
  u16(0xFFC3);  // SOF3
  u16(8 + 3 * 2);
  u8(10);  // P
  u16(rows);
  u16(width / 2);
  u8(2);
  for (int c = 0; c < 2; ++c) {
    u8(c);
    u8(0x11);
    u8(0);
  }
  u16(0xFFDA);  // SOS
  u16(6 + 2 * 2);
  u8(2);
  for (int c = 0; c < 2; ++c) {
    u8(c);
    u8(0x00);
  }
  u8(predictor);
  u8(0);
  u8(0);
  if (!put_bytes(p, end, header, h)) return SIZE_MAX;

  RowSink sink{dst, capacity, static_cast<size_t>(p - dst), row_out.data(), row_worst};
  MsbWriter writer;
  for (int y = 0; y < rows; ++y) {
    unpack_row(raw + static_cast<size_t>(y) * row_bytes, width, row_ptr(y), neon);
    lj92_row_diffs(row_ptr(y), row_ptr(y + 1), y == 0, width, predictor, diff.data(), neon);
    writer.p = sink.begin_row();
    for (int i = 0; i < width; ++i) {
      const int index = diff[i] + kLj92MaxDiff;
      const int bits = word_bits[index];
      if (bits == 0) return SIZE_MAX;
      writer.put(word[index], bits);
    }
    if (!sink.end_row(writer.p)) return SIZE_MAX;
  }
  // Trailing bits and EOI through the scratch row, then a checked copy.
  writer.p = row_out.data();
  writer.finish();
  *writer.p++ = 0xFF;
  *writer.p++ = 0xD9;
  const size_t tail = static_cast<size_t>(writer.p - row_out.data());
  if (tail > capacity - sink.used) return SIZE_MAX;
  std::memcpy(dst + sink.used, row_out.data(), tail);
  return sink.used + tail;
}

struct HuffDecoder {
  int32_t maxcode[18];
  int32_t mincode[17];
  int valptr[17];
  uint8_t vals[17];
  uint16_t fast[512];  // (length << 8) | symbol for codes of <= 9 bits, 0 otherwise
};

bool build_decoder(const HuffSpec& spec, HuffDecoder* d) {
  int k = 0;
  int32_t code = 0;
  std::memset(d->fast, 0, sizeof(d->fast));
  for (int l = 1; l <= 16; ++l) {
    d->valptr[l] = k;
    d->mincode[l] = code;
    code += spec.bits[l];
    k += spec.bits[l];
    d->maxcode[l] = spec.bits[l] ? code - 1 : -1;
    if (code > (1 << l)) return false;
    code <<= 1;
  }
  d->maxcode[17] = INT32_MAX;
  if (k != spec.count || k > 17) return false;
  std::memcpy(d->vals, spec.vals, sizeof(d->vals));
  const HuffCodes codes = codes_of(spec);
  for (int s = 0; s < spec.count; ++s) {
    const int symbol = spec.vals[s];
    const int length = codes.length[symbol];
    if (length == 0 || length > 9) continue;
    const int first = codes.code[symbol] << (9 - length);
    for (int j = 0; j < (1 << (9 - length)); ++j) {
      d->fast[first + j] = static_cast<uint16_t>(length << 8 | symbol);
    }
  }
  return true;
}

struct MsbReader {
  const uint8_t* p;
  const uint8_t* end;
  uint64_t acc = 0;
  int n = 0;
  int fake = 0;  // zero bits fed past the end of the entropy data
  bool marker = false;

  void fill() {
    while (n <= 56) {
      uint8_t b = 0;
      if (marker || p >= end) {
        fake += 8;
      } else {
        b = *p;
        if (b == 0xFF) {
          if (p + 1 < end && p[1] == 0x00) {
            p += 2;
          } else {
            marker = true;  // a marker ends the entropy data; feed zeros
            b = 0;
            fake += 8;
          }
        } else {
          ++p;
        }
      }
      acc |= uint64_t{b} << (56 - n);
      n += 8;
    }
  }
  uint32_t peek(int bits) const { return static_cast<uint32_t>(acc >> (64 - bits)); }
  void skip(int bits) {
    acc <<= bits;
    n -= bits;
  }
  uint32_t get(int bits) {
    if (bits == 0) return 0;
    if (n < bits) fill();
    const uint32_t v = peek(bits);
    skip(bits);
    return v;
  }
};

int decode_symbol(MsbReader& r, const HuffDecoder& d) {
  if (r.n < 16) r.fill();
  const uint16_t fast = d.fast[r.peek(9)];
  if (fast) {
    r.skip(fast >> 8);
    return fast & 0xFF;
  }
  int32_t code = 0;
  for (int l = 1; l <= 16; ++l) {
    code = (code << 1) | static_cast<int32_t>(r.get(1));
    if (d.maxcode[l] >= 0 && code <= d.maxcode[l]) {
      return d.vals[d.valptr[l] + code - d.mincode[l]];
    }
  }
  return -1;
}

bool lj92_decode_strip(const uint8_t* src, size_t length, int width, int rows, uint8_t* dst,
                       int dst_row_bytes) {
  const uint8_t* p = src;
  const uint8_t* end = src + length;
  if (length < 4 || p[0] != 0xFF || p[1] != 0xD8) return false;
  p += 2;
  HuffSpec specs[4];
  bool have_spec[4] = {};
  bool have_frame = false;
  int predictor = 0;
  int table_of[2] = {0, 0};
  for (;;) {
    if (end - p < 4 || p[0] != 0xFF) return false;
    const int marker = p[1];
    const int seg = p[2] << 8 | p[3];
    if (seg < 2 || end - p < 2 + seg) return false;
    const uint8_t* s = p + 4;
    const uint8_t* s_end = p + 2 + seg;
    if (marker == 0xC4) {
      while (s < s_end) {
        if (s_end - s < 17) return false;
        const int th = *s & 0x0F;
        if ((*s >> 4) != 0 || th > 3) return false;
        HuffSpec& spec = specs[th];
        spec = HuffSpec{};
        int count = 0;
        for (int l = 1; l <= 16; ++l) {
          spec.bits[l] = s[l];
          count += s[l];
        }
        s += 17;
        if (count > 17 || s_end - s < count) return false;
        bool seen[17] = {};
        for (int i = 0; i < count; ++i) {
          if (s[i] > 16 || seen[s[i]]) return false;  // categories are 0..16, each once
          seen[s[i]] = true;
          spec.vals[i] = s[i];
        }
        spec.count = count;
        s += count;
        have_spec[th] = true;
      }
    } else if (marker == 0xC3) {
      if (seg != 8 + 6 || s[0] != 10) return false;
      const int y = s[1] << 8 | s[2];
      const int x = s[3] << 8 | s[4];
      if (y != rows || x != width / 2 || s[5] != 2) return false;
      have_frame = true;
    } else if (marker == 0xDA) {
      if (!have_frame || seg != 6 + 4 || s[0] != 2) return false;
      for (int c = 0; c < 2; ++c) {
        table_of[c] = s[2 + 2 * c] >> 4;
        if (table_of[c] > 3 || !have_spec[table_of[c]]) return false;
      }
      predictor = s[5];
      if (predictor < 1 || predictor > 7 || s[7] != 0) return false;
      p = s_end;
      break;
    } else if (marker == 0xDD) {
      if (seg != 4 || (s[0] | s[1]) != 0) return false;  // restart intervals unsupported
    } else if (marker == 0xD8 || marker == 0xD9 || (marker >= 0xC0 && marker <= 0xCF)) {
      return false;
    }
    p = s_end;
  }
  HuffDecoder decoders[2];
  for (int c = 0; c < 2; ++c) {
    if (!build_decoder(specs[table_of[c]], &decoders[c])) return false;
  }
  const size_t padded = static_cast<size_t>(width) + 2;
  std::vector<uint16_t> rows_buf(padded * 2);
  MsbReader reader{p, end};
  for (int y = 0; y < rows; ++y) {
    uint16_t* cur = rows_buf.data() + (y & 1) * padded + 2;
    const uint16_t* prev = rows_buf.data() + ((y + 1) & 1) * padded + 2;
    for (int i = 0; i < width; ++i) {
      const int ssss = decode_symbol(reader, decoders[i & 1]);
      if (ssss < 0 || ssss > 16) return false;
      int d;
      if (ssss == 16) {
        d = 32768;
      } else {
        const int v = static_cast<int>(reader.get(ssss));
        d = (ssss > 0 && v < (1 << (ssss - 1))) ? v - (1 << ssss) + 1 : v;
      }
      int pred;
      if (y == 0) {
        pred = i < 2 ? 512 : cur[i - 2];
      } else if (i < 2) {
        pred = prev[i];
      } else {
        pred = lj92_predict(predictor, cur[i - 2], prev[i], prev[i - 2]);
      }
      const int v = (pred + d) & 0xFFFF;
      if (v > kSampleMax) return false;
      cur[i] = static_cast<uint16_t>(v);
    }
    pack_row(cur, width, dst + static_cast<size_t>(y) * dst_row_bytes);
  }
  // No fake bits may have been consumed, and at most 7 real (padding) bits may be left.
  if (reader.n < reader.fake || reader.n - reader.fake > 7) return false;
  const uint8_t* q = reader.p;
  return end - q == 2 && q[0] == 0xFF && q[1] == 0xD9;
}

// ---------------------------------------------------------------- framing

bool valid_frame(int width, int height, int row_bytes) {
  return width > 0 && height > 0 && width % 4 == 0 && height % 2 == 0 && width <= 65534 &&
         height <= 65535 && row_bytes >= width / 4 * 5;
}

size_t strip_bound(Codec codec, int width, int rows) {
  if (codec != Codec::kLj92) return block_pack_row_worst(width) * static_cast<size_t>(rows);
  return kLj92HeaderBound + (lj92_row_worst(width) - 8) * static_cast<size_t>(rows) + 16;
}

inline uint16_t read_u16(const uint8_t* p) { return static_cast<uint16_t>(p[0] | p[1] << 8); }

inline void write_u16(uint8_t* p, uint16_t v) {
  p[0] = static_cast<uint8_t>(v);
  p[1] = static_cast<uint8_t>(v >> 8);
}

size_t quant_prefix_bytes(int count) { return kQuantPreambleBytes + 2 * static_cast<size_t>(count); }

// The forward table is required for kBlockPackQuantised and optional for
// kBlockPack; its entries (and the inverse's) must be samples, and every code
// must have an inverse entry.
bool valid_tables(Codec codec, const EncodeOptions& options) {
  if (codec == Codec::kLj92) return true;
  if (options.lut == nullptr) return codec == Codec::kBlockPack;
  int limit = kSampleMax + 1;
  if (codec == Codec::kBlockPackQuantised) {
    if (options.inverse_lut == nullptr || options.lut_count < 1 || options.lut_count > kQuantLutSize) {
      return false;
    }
    for (int c = 0; c < options.lut_count; ++c) {
      if (options.inverse_lut[c] > kSampleMax) return false;
    }
    limit = options.lut_count;
  }
  for (int i = 0; i < kQuantLutSize; ++i) {
    if (options.lut[i] >= limit) return false;
  }
  return true;
}

struct EncodeJob {
  Codec codec;
  const uint8_t* raw;
  int width;
  int row_bytes;
  const EncodeOptions* options;
  bool neon;
  int rows[kMaxStrips];
  int first_row[kMaxStrips];
  uint8_t* out[kMaxStrips];
  size_t capacity[kMaxStrips];
  size_t size[kMaxStrips];
};

size_t encode_one(const EncodeJob& job, int s, uint8_t* out, size_t capacity) {
  const uint8_t* raw = job.raw + static_cast<size_t>(job.first_row[s]) * job.row_bytes;
  if (job.codec == Codec::kBlockPack) {
    return block_pack_encode_strip(raw, job.width, job.rows[s], job.row_bytes, out, capacity, job.neon,
                                   job.options->lut);
  }
  return lj92_encode_strip(raw, job.width, job.rows[s], job.row_bytes, out, capacity,
                           job.options->lj92_predictor, job.options->lj92_optimal_huffman, job.neon);
}

void encode_task(void* context, int s) {
  auto* job = static_cast<EncodeJob*>(context);
  job->size[s] = encode_one(*job, s, job->out[s], job->capacity[s]);
}

struct DecodeJob {
  Codec codec;
  const uint8_t* src;
  int width;
  uint8_t* dst;
  int dst_row_bytes;
  int rows[kMaxStrips];
  int first_row[kMaxStrips];
  size_t offset[kMaxStrips];
  size_t length[kMaxStrips];
  const uint16_t* inverse = nullptr;  // encoding 3: points into inverse_table
  int sample_limit = kSampleMax + 1;
  uint16_t inverse_table[kQuantLutSize];
  std::atomic<bool> ok{true};
};

void decode_task(void* context, int s) {
  auto* job = static_cast<DecodeJob*>(context);
  uint8_t* dst = job->dst + static_cast<size_t>(job->first_row[s]) * job->dst_row_bytes;
  const uint8_t* src = job->src + job->offset[s];
  const bool ok = job->codec == Codec::kBlockPack
                      ? block_pack_decode_strip(src, job->length[s], job->width, job->rows[s], dst,
                                                job->dst_row_bytes, job->inverse, job->sample_limit)
                      : lj92_decode_strip(src, job->length[s], job->width, job->rows[s], dst,
                                          job->dst_row_bytes);
  if (!ok) job->ok.store(false);
}

void run_tasks(ThreadPool* pool, int tasks, void (*fn)(void*, int), void* context) {
  if (pool != nullptr) {
    pool->run(tasks, fn, context);
  } else {
    for (int t = 0; t < tasks; ++t) fn(context, t);
  }
}

}  // namespace

// ---------------------------------------------------------------- thread pool

struct ThreadPool::State {
  std::mutex mutex;
  std::condition_variable wake;
  std::condition_variable done;
  std::vector<std::thread> workers;
  uint64_t generation = 0;
  bool stop = false;
  void (*fn)(void*, int) = nullptr;
  void* context = nullptr;
  int tasks = 0;
  std::atomic<int> next{0};
  int active = 0;

  void drain() {
    for (;;) {
      const int task = next.fetch_add(1, std::memory_order_relaxed);
      if (task >= tasks) return;
      fn(context, task);
    }
  }

  void work() {
    uint64_t seen = 0;
    for (;;) {
      {
        std::unique_lock<std::mutex> lock(mutex);
        wake.wait(lock, [&] { return stop || generation != seen; });
        if (stop) return;
        seen = generation;
      }
      drain();
      std::lock_guard<std::mutex> lock(mutex);
      if (--active == 0) done.notify_all();
    }
  }
};

ThreadPool::ThreadPool(int threads) : threads_(threads < 1 ? 1 : threads), state_(new State) {
  for (int i = 1; i < threads_; ++i) state_->workers.emplace_back([this] { state_->work(); });
}

ThreadPool::~ThreadPool() {
  {
    std::lock_guard<std::mutex> lock(state_->mutex);
    state_->stop = true;
  }
  state_->wake.notify_all();
  for (auto& worker : state_->workers) worker.join();
  delete state_;
}

void ThreadPool::run(int tasks, void (*fn)(void*, int), void* context) {
  if (tasks <= 0) return;
  if (state_->workers.empty() || tasks == 1) {
    for (int t = 0; t < tasks; ++t) fn(context, t);
    return;
  }
  {
    std::lock_guard<std::mutex> lock(state_->mutex);
    state_->fn = fn;
    state_->context = context;
    state_->tasks = tasks;
    state_->next.store(0, std::memory_order_relaxed);
    state_->active = static_cast<int>(state_->workers.size());
    ++state_->generation;
  }
  state_->wake.notify_all();
  state_->drain();
  std::unique_lock<std::mutex> lock(state_->mutex);
  state_->done.wait(lock, [&] { return state_->active == 0; });
}

// ---------------------------------------------------------------- public API

bool neon_available() {
#if defined(OPENCINECAM_RAW_NEON)
  return true;
#else
  return false;
#endif
}

int strip_layout(int height, int requested_strips, int rows[kMaxStrips]) {
  if (height <= 0) return 0;
  int requested = requested_strips < 1 ? 1 : requested_strips > kMaxStrips ? kMaxStrips : requested_strips;
  // A multiple of 16 rows (the TIFF TileLength rule), or the whole frame.
  int per = (height + requested - 1) / requested;
  per = (per + 15) & ~15;
  if (per > height) per = height;
  int count = 0;
  for (int y = 0; y < height; y += per) rows[count++] = height - y < per ? height - y : per;
  return count;
}

size_t max_encoded_size(Codec codec, int width, int height, const EncodeOptions& options) {
  if (!valid_frame(width, height, width / 4 * 5)) return 0;
  int rows[kMaxStrips];
  const int strips = strip_layout(height, options.strips, rows);
  size_t total = 4 + 8 * static_cast<size_t>(strips);
  for (int s = 0; s < strips; ++s) total += strip_bound(codec, width, rows[s]);
  if (codec == Codec::kBlockPackQuantised) total += quant_prefix_bytes(kQuantLutSize);
  return total;
}

size_t encode(Codec codec, const uint8_t* raw10, int width, int height, int row_bytes,
              uint8_t* dst, size_t dst_capacity, const EncodeOptions& options, ThreadPool* pool) {
  if (raw10 == nullptr || dst == nullptr || !valid_frame(width, height, row_bytes)) return 0;
  if (codec != Codec::kLj92 && codec != Codec::kBlockPack && codec != Codec::kBlockPackQuantised) return 0;
  if (codec == Codec::kLj92 && (options.lj92_predictor < 1 || options.lj92_predictor > 7)) return 0;
  if (!valid_tables(codec, options)) return 0;
  if (codec == Codec::kBlockPackQuantised) {
    // The preamble and inverse table, then the block-pack container of the codes.
    const size_t prefix = quant_prefix_bytes(options.lut_count);
    if (dst_capacity <= prefix) return 0;
    const size_t size = encode(Codec::kBlockPack, raw10, width, height, row_bytes, dst + prefix,
                               dst_capacity - prefix, options, pool);
    if (size == 0) return 0;
    dst[0] = static_cast<uint8_t>(kQuantVersion);
    dst[1] = 0;
    write_u16(dst + 2, static_cast<uint16_t>(options.lut_count));
    write_u32(dst + 4, options.quant_iso);
    write_u16(dst + 8, options.quant_k_milli);
    write_u16(dst + 10, options.quant_black_level);
    for (int c = 0; c < options.lut_count; ++c) write_u16(dst + kQuantPreambleBytes + 2 * c, options.inverse_lut[c]);
    return prefix + size;
  }
  auto job = std::make_unique<EncodeJob>();
  job->codec = codec;
  job->raw = raw10;
  job->width = width;
  job->row_bytes = row_bytes;
  job->options = &options;
  job->neon = use_neon(options.kernel);
  const int strips = strip_layout(height, options.strips, job->rows);
  const size_t header = 4 + 8 * static_cast<size_t>(strips);
  if (dst_capacity <= header) return 0;
  const size_t room = dst_capacity - header;

  // Each strip first gets a slice of dst proportional to its worst case.
  size_t bounds[kMaxStrips];
  size_t bound_total = 0;
  for (int s = 0, y = 0; s < strips; y += job->rows[s], ++s) {
    job->first_row[s] = y;
    bounds[s] = strip_bound(codec, width, job->rows[s]);
    bound_total += bounds[s];
  }
  size_t offset = header;
  for (int s = 0; s < strips; ++s) {
    const size_t slice =
        room >= bound_total ? bounds[s]
                            : static_cast<size_t>(static_cast<double>(room) * bounds[s] / bound_total);
    job->out[s] = dst + offset;
    job->capacity[s] = slice;
    offset += slice;
  }
  run_tasks(pool, strips, encode_task, job.get());

  // Compact the slices in order; offsets only move left, so memmove is safe.
  size_t at = header;
  int s = 0;
  for (; s < strips; ++s) {
    if (job->size[s] == SIZE_MAX) break;
    if (job->out[s] != dst + at) std::memmove(dst + at, job->out[s], job->size[s]);
    at += job->size[s];
  }
  // A strip overflowed its slice (only when dst is tighter than the worst case):
  // encode the rest serially into whatever room is left.
  for (; s < strips; ++s) {
    job->size[s] = encode_one(*job, s, dst + at, dst_capacity - at);
    if (job->size[s] == SIZE_MAX) return 0;
    at += job->size[s];
  }
  write_u32(dst, static_cast<uint32_t>(strips));
  for (int k = 0; k < strips; ++k) {
    write_u32(dst + 4 + 8 * k, static_cast<uint32_t>(job->rows[k]));
    write_u32(dst + 8 + 8 * k, static_cast<uint32_t>(job->size[k]));
  }
  return at;
}

bool decode(Codec codec, const uint8_t* src, size_t src_length, int width, int height,
            uint8_t* dst, int dst_row_bytes, ThreadPool* pool) {
  if (src == nullptr || dst == nullptr || !valid_frame(width, height, dst_row_bytes)) return false;
  if (codec != Codec::kLj92 && codec != Codec::kBlockPack && codec != Codec::kBlockPackQuantised) return false;
  auto job = std::make_unique<DecodeJob>();
  if (codec == Codec::kBlockPackQuantised) {
    if (src_length < kQuantPreambleBytes || src[0] != kQuantVersion) return false;
    const int count = read_u16(src + 2);
    if (count < 1 || count > kQuantLutSize || src_length < quant_prefix_bytes(count)) return false;
    for (int c = 0; c < count; ++c) {
      const uint16_t value = read_u16(src + kQuantPreambleBytes + 2 * c);
      if (value > kSampleMax) return false;
      job->inverse_table[c] = value;
    }
    job->inverse = job->inverse_table;
    job->sample_limit = count;
    src += quant_prefix_bytes(count);
    src_length -= quant_prefix_bytes(count);
    codec = Codec::kBlockPack;
  }
  if (src_length < 4) return false;
  const uint32_t strips = read_u32(src);
  if (strips < 1 || strips > static_cast<uint32_t>(kMaxStrips)) return false;
  const size_t header = 4 + 8 * static_cast<size_t>(strips);
  if (src_length < header) return false;
  job->codec = codec;
  job->src = src;
  job->width = width;
  job->dst = dst;
  job->dst_row_bytes = dst_row_bytes;
  size_t offset = header;
  uint64_t total_rows = 0;
  for (uint32_t s = 0; s < strips; ++s) {
    const uint32_t rows = read_u32(src + 4 + 8 * s);
    const uint32_t length = read_u32(src + 8 + 8 * s);
    if (rows == 0 || rows % 2 != 0 || length > src_length - offset) return false;
    job->rows[s] = static_cast<int>(rows);
    job->first_row[s] = static_cast<int>(total_rows);
    job->offset[s] = offset;
    job->length[s] = length;
    total_rows += rows;
    offset += length;
    if (total_rows > static_cast<uint64_t>(height)) return false;
  }
  if (total_rows != static_cast<uint64_t>(height) || offset != src_length) return false;
  run_tasks(pool, static_cast<int>(strips), decode_task, job.get());
  return job->ok.load();
}

}  // namespace opencinecam::rawcodec
