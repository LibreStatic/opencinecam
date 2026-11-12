/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

// Lossless codecs for Android RAW10 Bayer frames (OCC-PLAN-071, slice G).
//
// Input and output frames are Android ImageFormat.RAW10: each group of four
// pixels is four bytes holding their 8 most significant bits, then one byte
// with their 2 least significant bits (pixel k in bits 2k..2k+1). A row is
// width * 5 / 4 bytes; rowBytes may be larger (stride padding is skipped on
// encode and left untouched on decode). width must be a multiple of 4 (whole
// RAW10 packing groups; it also makes width / 2, the LJ92 column count, even)
// and height must be even (whole CFA row pairs).
//
// In an OCRAW file (docs/formats/opencine-raw-v1.md) an encoded frame is a
// RAW_FRAME whose frame header has encoding 1 or 2, dataLength = the container
// size below (encoding 3: the payload described further down), and rowBytes = width * 5 / 4: the row length of the decoded RAW10
// frame, not of anything in the container. Encoding may differ from frame to
// frame in one file (a frame that does not fit is stored as encoding 0).
//
// Encoded frame container (all integers little-endian):
//
//   u32 stripCount                         1..kMaxStrips
//   stripCount x { u32 rows, u32 byteLength }
//   the strip streams, concatenated in order, byteLength bytes each
//
// Strips are horizontal bands of whole rows, top to bottom; their rows add up to
// the frame height. Every strip but the last has the same row count,
// ceil(height / strips) rounded up to a multiple of 16 (or the whole frame when
// that is taller), so a requested strip count may yield fewer strips on short
// frames, and every strip has an even row count. The last strip holds the
// remaining rows (at most the others' count). The decoder here accepts any even,
// non-zero strip heights that add up to the frame height; a DNG writer that
// stores LJ92 strips as tiles needs the uniform layout and rejects others. Strips are independent: each
// encodes and decodes on its own thread. A DNG writer stores them as tiles of
// TileWidth = width and TileLength = the first strip's rows (LibRaw, for one,
// reads multi-part lossless JPEG only from tiles; the last tile is short).
//
// Strip stream, encoding 1 (LJ92): one complete ITU-T T.81 lossless JPEG
// (SOF3) stream, SOI to EOI, laid out as DNG stores CFA data: a frame of
// width / 2 columns x rows lines with two components (component 0 holds the
// even image columns, component 1 the odd ones), so predictor 1 (Ra) sees the
// same-colour sample two pixels to the left. Precision P = 10, point transform
// 0, no restart interval, one Huffman table (Tc = 0, Th = 0) shared by both
// components. The table is either optimal for that strip (two passes, length
// limited to 16 bits as in T.81 K.2) or the T.81 K.3 luminance DC table, which
// covers the categories 0..11 that P = 10 can produce. Each stream can be
// stored unchanged as a DNG tile (see above) with Compression = 7.
//
// Strip stream, encoding 2 (Bayer block-pack): rows one after another, each
// starting on a byte boundary. Within a strip, sample x[i] of row y is predicted
// from the same-colour sample two to the left, x[i - 2]; the first two samples
// of a row are predicted from the first two samples of row y - 2 of the same
// strip (same CFA colours), or from 0 in the first two rows of the strip. The
// residual r = x - prediction (-1023..1023) is zigzag mapped, z = 2r for r >= 0
// and -2r - 1 for r < 0. A row of W samples is split into ceil(W / 16) blocks
// of 16 residuals; a short last block is padded with zero residuals (they count
// towards that block's width, which they cannot raise; a decoder ignores the
// padded values). A row is coded as:
//
//   ceil(blocks / 2) bytes of block widths, two 4-bit codes per byte, low
//     nibble first (an unused last high nibble is 0). The width w (0..11) of
//     a block is the bit length of its largest z.
//   for each block, 2 * w bytes: its 16 z values of w bits each, packed
//     least significant bit first into a little-endian bit string.
//
// Encoding 3 (quantised block-pack, near-lossless). Before block-pack, every
// RAW10 sample x is replaced by code = forward[x] from a monotonic forward table
// of kQuantLutSize entries built from the sensor's noise model (see
// media/.../raw/RawQuantisation.kt: neighbouring DNs closer than about k noise
// sigmas share a code). Prediction, residuals and the bitstream above then
// apply to the codes unchanged. The frame payload is self-contained (all
// integers little-endian, no padding):
//
//   u8  quantVersion                        1
//   u8  reserved                            0 (readers ignore it)
//   u16 tableCount N                        1..kQuantLutSize
//   u32 isoUsed                             the ISO the table was built for (informative)
//   u16 kMilli                              k * 1000, the step in noise sigmas (informative)
//   u16 blackLevel                          the black level of the noise model (informative)
//   u16 inverse[N]                          code -> reconstructed DN, each 0..1023
//   the block-pack container (above) of the codes, to the end of the payload
//
// The preamble is kQuantPreambleBytes long, so the container starts at
// 12 + 2 * N. A reader decodes the container to codes and maps each through
// inverse[]; it needs no noise model. In addition to the block-pack checks
// below, a frame is malformed when quantVersion is not 1, N is outside
// 1..kQuantLutSize, the payload is shorter than 12 + 2 * N, an inverse entry is
// above 1023, or a decoded code is N or more. Decoding encoding 3 here yields
// RAW10 rows of the reconstructed DN, so callers treat it like encodings 1 and 2.
//
// Decoder checks. A frame is malformed, and decode returns false, when: the
// strip count is outside 1..kMaxStrips; a strip has 0 or an odd number of rows;
// the strip rows do not add up to the height; the strip lengths do not consume
// the container exactly (no trailing bytes). Block-pack: a block width above
// 11; an unused last high nibble that is not 0; a decoded sample outside
// 0..1023; a strip stream not consumed exactly to its last byte. LJ92: a stream
// that is not SOF3 P = 10 with width / 2 columns, the strip's rows and two
// components; a predictor outside 1..7, a point transform other than 0 or a
// restart interval; an invalid Huffman table or code; a sample outside 0..1023;
// entropy data that runs past its end or leaves more than 7 padding bits.
//
// Thread safety: a ThreadPool runs one job at a time; give each concurrent
// encoder its own pool. The free functions themselves keep no state.

#ifndef OPENCINECAM_RAW_CODECS_H
#define OPENCINECAM_RAW_CODECS_H

#include <cstddef>
#include <cstdint>

namespace opencinecam::rawcodec {

// Values match OcrawFrameHeader.ENCODING_* in the OpenCine RAW v1 format.
enum class Codec : int { kLj92 = 1, kBlockPack = 2, kBlockPackQuantised = 3 };

// The block-pack and unpack kernels. kAuto picks NEON when compiled in.
enum class Kernel : int { kAuto = 0, kScalar = 1, kNeon = 2 };

inline constexpr int kMaxStrips = 64;

// Encoding 3: entries of the forward table, version and preamble size.
inline constexpr int kQuantLutSize = 1024;
inline constexpr int kQuantVersion = 1;
inline constexpr size_t kQuantPreambleBytes = 12;

struct EncodeOptions {
  int strips = 8;                    // requested strip count, 1..kMaxStrips
  int lj92_predictor = 1;            // T.81 predictor selection value, 1..7
  bool lj92_optimal_huffman = true;  // false: the T.81 K.3 luminance DC table
  Kernel kernel = Kernel::kAuto;     // kNeon falls back to scalar without NEON

  // Block-pack forward table, kQuantLutSize entries of 0..1023: each sample x is
  // coded as lut[x], applied right after unpacking and before prediction.
  // nullptr (the default) codes the samples themselves, byte-identical to a
  // build without this field. kBlockPack: optional, the container then holds
  // the codes with no preamble (the caller owns the inverse). kBlockPackQuantised:
  // required, and every lut[x] must be below lut_count. Ignored by kLj92.
  const uint16_t* lut = nullptr;

  // kBlockPackQuantised only: the preamble and inverse table written ahead of
  // the container. inverse_lut holds lut_count (1..kQuantLutSize) entries of
  // 0..1023.
  const uint16_t* inverse_lut = nullptr;
  int lut_count = 0;
  uint32_t quant_iso = 0;
  uint16_t quant_k_milli = 0;
  uint16_t quant_black_level = 0;
};

// True when this build contains the NEON kernels (arm64).
bool neon_available();

// A persistent pool of worker threads. threads is the total parallelism: the
// calling thread works too, so threads - 1 workers are started (none for 1).
// Workers are joined by the destructor.
class ThreadPool {
 public:
  explicit ThreadPool(int threads);
  ~ThreadPool();
  ThreadPool(const ThreadPool&) = delete;
  ThreadPool& operator=(const ThreadPool&) = delete;

  int threads() const { return threads_; }

  // Calls fn(context, task) for every task in [0, tasks) across the pool and
  // returns when all have finished.
  void run(int tasks, void (*fn)(void* context, int task), void* context);

  struct State;

 private:
  int threads_;
  State* state_;
};

// Rows in each strip for this frame height and requested strip count; returns
// the actual strip count and fills rows[0..count).
int strip_layout(int height, int requested_strips, int rows[kMaxStrips]);

// Worst-case encoded size, container included (for kBlockPackQuantised also
// the preamble and a full kQuantLutSize inverse table). Encoding into a buffer
// of this size never overflows.
size_t max_encoded_size(Codec codec, int width, int height, const EncodeOptions& options);

// Encodes one frame into dst. Returns the encoded size, or 0 when the frame does
// not fit in dst_capacity (the caller then stores it uncompressed) or the
// arguments are invalid (including a kBlockPackQuantised call without valid
// tables). Source and destination must not overlap. With pool ==
// nullptr all strips run on the calling thread.
size_t encode(Codec codec, const uint8_t* raw10, int width, int height, int row_bytes,
              uint8_t* dst, size_t dst_capacity, const EncodeOptions& options,
              ThreadPool* pool = nullptr);

// Decodes one encoded frame back to RAW10 rows of dst_row_bytes (>= width * 5 / 4).
// kBlockPackQuantised yields the reconstructed DN (inverse[code]) of its own
// table. Returns false on malformed or truncated input.
bool decode(Codec codec, const uint8_t* src, size_t src_length, int width, int height,
            uint8_t* dst, int dst_row_bytes, ThreadPool* pool = nullptr);

}  // namespace opencinecam::rawcodec

#endif  // OPENCINECAM_RAW_CODECS_H
