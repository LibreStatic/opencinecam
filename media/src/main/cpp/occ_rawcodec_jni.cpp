/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

// JNI surface of libocc_rawcodec.so, bound by
// com.librestatic.opencinecam.media.raw.RawCodecJni. Buffers cross as direct
// ByteBuffers (no copies); the Kotlin side validates ranges, and every entry
// point re-checks them against the buffer capacity before touching memory.

#include <jni.h>

#include <cstdint>
#include <cstring>

#include "opencinecam/crc32c.h"
#include "opencinecam/raw_codecs.h"

namespace {

using opencinecam::rawcodec::Codec;
using opencinecam::rawcodec::EncodeOptions;
using opencinecam::rawcodec::Kernel;
using opencinecam::rawcodec::ThreadPool;

// Address of buffer[offset, offset + length), or nullptr when the buffer is not
// direct or the range is outside its capacity.
uint8_t* direct_range(JNIEnv* env, jobject buffer, jint offset, jlong length) {
  if (buffer == nullptr || offset < 0 || length < 0) return nullptr;
  auto* base = static_cast<uint8_t*>(env->GetDirectBufferAddress(buffer));
  const jlong capacity = env->GetDirectBufferCapacity(buffer);
  if (base == nullptr || capacity < 0 || offset + length > capacity) return nullptr;
  return base + offset;
}

bool valid_codec(jint codec) {
  return codec == static_cast<jint>(Codec::kLj92) || codec == static_cast<jint>(Codec::kBlockPack) ||
         codec == static_cast<jint>(Codec::kBlockPackQuantised);
}

using opencinecam::rawcodec::kQuantLutSize;
using opencinecam::rawcodec::kQuantPreambleBytes;

// Encoding 3 tables as NativeRawCodec.Encoder.setQuantisation lays them out in
// its direct buffer: the forward table (kQuantLutSize little-endian u16), then
// the payload prefix exactly as written (the 12-byte preamble and N inverse
// u16). Copied to aligned arrays here; the encoder validates their values.
struct QuantTables {
  uint16_t forward[kQuantLutSize];
  uint16_t inverse[kQuantLutSize];
};

inline uint16_t u16_at(const uint8_t* p) { return static_cast<uint16_t>(p[0] | p[1] << 8); }

bool read_quant(JNIEnv* env, jobject buffer, QuantTables* tables, EncodeOptions* options) {
  constexpr jlong kForwardBytes = 2 * kQuantLutSize;
  const uint8_t* head =
      direct_range(env, buffer, 0, kForwardBytes + static_cast<jlong>(kQuantPreambleBytes));
  if (head == nullptr) return false;
  const uint8_t* preamble = head + kForwardBytes;
  const int count = u16_at(preamble + 2);
  if (count < 1 || count > kQuantLutSize) return false;
  const uint8_t* all = direct_range(
      env, buffer, 0, kForwardBytes + static_cast<jlong>(kQuantPreambleBytes) + 2 * count);
  if (all == nullptr) return false;
  for (int i = 0; i < kQuantLutSize; ++i) tables->forward[i] = u16_at(head + 2 * i);
  for (int c = 0; c < count; ++c) tables->inverse[c] = u16_at(preamble + kQuantPreambleBytes + 2 * c);
  uint32_t iso;
  std::memcpy(&iso, preamble + 4, sizeof iso);  // little-endian target, as raw_codecs.cpp requires
  options->lut = tables->forward;
  options->inverse_lut = tables->inverse;
  options->lut_count = count;
  options->quant_iso = iso;
  options->quant_k_milli = u16_at(preamble + 8);
  options->quant_black_level = u16_at(preamble + 10);
  return true;
}

EncodeOptions options_of(jint strips, jint predictor, jboolean optimal, jint kernel) {
  EncodeOptions options;
  options.strips = strips;
  options.lj92_predictor = predictor;
  options.lj92_optimal_huffman = optimal == JNI_TRUE;
  options.kernel = kernel == 1 ? Kernel::kScalar : kernel == 2 ? Kernel::kNeon : Kernel::kAuto;
  return options;
}

}  // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_librestatic_opencinecam_media_raw_RawCodecJni_nativeNeonAvailable(JNIEnv*, jclass) {
  return opencinecam::rawcodec::neon_available() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_librestatic_opencinecam_media_raw_RawCodecJni_nativeCrc32cHardware(JNIEnv*, jclass) {
  return opencinecam::crc32c_hardware_available() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL
Java_com_librestatic_opencinecam_media_raw_RawCodecJni_nativeCreatePool(JNIEnv*, jclass, jint threads) {
  return reinterpret_cast<jlong>(new ThreadPool(threads));
}

JNIEXPORT void JNICALL
Java_com_librestatic_opencinecam_media_raw_RawCodecJni_nativeDestroyPool(JNIEnv*, jclass, jlong pool) {
  delete reinterpret_cast<ThreadPool*>(pool);
}

JNIEXPORT jlong JNICALL
Java_com_librestatic_opencinecam_media_raw_RawCodecJni_nativeMaxEncodedSize(
    JNIEnv*, jclass, jint codec, jint width, jint height, jint strips, jint predictor,
    jboolean optimal) {
  if (!valid_codec(codec)) return 0;
  return static_cast<jlong>(opencinecam::rawcodec::max_encoded_size(
      static_cast<Codec>(codec), width, height, options_of(strips, predictor, optimal, 0)));
}

// Returns the encoded size, 0 when it did not fit, or -1 for invalid arguments.
// quant is the encoding 3 table buffer (see read_quant), required for codec 3
// and ignored otherwise.
JNIEXPORT jint JNICALL
Java_com_librestatic_opencinecam_media_raw_RawCodecJni_nativeEncode(
    JNIEnv* env, jclass, jlong pool, jint codec, jobject src, jint src_offset, jint width,
    jint height, jint row_bytes, jobject dst, jint dst_offset, jint dst_capacity, jint strips,
    jint predictor, jboolean optimal, jint kernel, jobject quant) {
  if (!valid_codec(codec) || width <= 0 || height <= 0 || row_bytes <= 0) return -1;
  const uint8_t* in =
      direct_range(env, src, src_offset, static_cast<jlong>(row_bytes) * height);
  uint8_t* out = direct_range(env, dst, dst_offset, dst_capacity);
  if (in == nullptr || out == nullptr) return -1;
  EncodeOptions options = options_of(strips, predictor, optimal, kernel);
  QuantTables tables;
  if (codec == static_cast<jint>(Codec::kBlockPackQuantised) &&
      !read_quant(env, quant, &tables, &options)) {
    return -1;
  }
  const size_t size = opencinecam::rawcodec::encode(
      static_cast<Codec>(codec), in, width, height, row_bytes, out,
      static_cast<size_t>(dst_capacity), options, reinterpret_cast<ThreadPool*>(pool));
  return static_cast<jint>(size);
}

JNIEXPORT jboolean JNICALL
Java_com_librestatic_opencinecam_media_raw_RawCodecJni_nativeDecode(
    JNIEnv* env, jclass, jlong pool, jint codec, jobject src, jint src_offset, jint src_length,
    jint width, jint height, jobject dst, jint dst_offset, jint dst_row_bytes) {
  if (!valid_codec(codec) || width <= 0 || height <= 0 || dst_row_bytes <= 0) return JNI_FALSE;
  const uint8_t* in = direct_range(env, src, src_offset, src_length);
  uint8_t* out = direct_range(env, dst, dst_offset, static_cast<jlong>(dst_row_bytes) * height);
  if (in == nullptr || out == nullptr) return JNI_FALSE;
  return opencinecam::rawcodec::decode(static_cast<Codec>(codec), in,
                                       static_cast<size_t>(src_length), width, height, out,
                                       dst_row_bytes, reinterpret_cast<ThreadPool*>(pool))
             ? JNI_TRUE
             : JNI_FALSE;
}

// crc is the finished CRC so far (0 to start). Returns the updated CRC; the
// range has been validated by the caller and is re-checked here (an invalid
// range leaves crc unchanged).
JNIEXPORT jint JNICALL
Java_com_librestatic_opencinecam_media_raw_RawCodecJni_nativeCrc32cUpdate(
    JNIEnv* env, jclass, jint crc, jobject buffer, jint offset, jint length) {
  const uint8_t* data = direct_range(env, buffer, offset, length);
  if (data == nullptr) return crc;
  return static_cast<jint>(
      opencinecam::crc32c_update(static_cast<uint32_t>(crc), data, static_cast<size_t>(length)));
}

JNIEXPORT jint JNICALL
Java_com_librestatic_opencinecam_media_raw_RawCodecJni_nativeCrc32cUpdateArray(
    JNIEnv* env, jclass, jint crc, jbyteArray array, jint offset, jint length) {
  if (array == nullptr || offset < 0 || length < 0 ||
      offset > env->GetArrayLength(array) - length) {
    return crc;
  }
  void* base = env->GetPrimitiveArrayCritical(array, nullptr);
  if (base == nullptr) return crc;
  const uint32_t result = opencinecam::crc32c_update(
      static_cast<uint32_t>(crc), static_cast<const uint8_t*>(base) + offset,
      static_cast<size_t>(length));
  env->ReleasePrimitiveArrayCritical(array, base, JNI_ABORT);
  return static_cast<jint>(result);
}

}  // extern "C"
