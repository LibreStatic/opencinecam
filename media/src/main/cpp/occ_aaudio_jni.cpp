/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

// JNI surface of libocc_aaudio.so, bound by
// com.librestatic.opencinecam.media.audio.AAudioNative (PLAN-074 decision 4):
// one AAudio input stream in low-latency mode, read by blocking calls from the
// app's feeder thread. The handle is the AAudioStream pointer; every entry
// point accepts 0 and does nothing with it. AAudioPcmInput serialises close
// against the other calls, so no entry point here races AAudioStream_close.

#include <aaudio/AAudio.h>
#include <jni.h>
#include <time.h>

#include <cstdint>

namespace {

// Indices of the int[] nativeDescribe fills; AAudioNative mirrors them.
constexpr int kInfoSampleRate = 0;
constexpr int kInfoChannels = 1;
constexpr int kInfoFormat = 2;
constexpr int kInfoPerformanceMode = 3;
constexpr int kInfoDeviceId = 4;
constexpr int kInfoFramesPerBurst = 5;
constexpr int kInfoSessionId = 6;
constexpr int kInfoBufferCapacity = 7;
constexpr int kInfoCount = 8;

AAudioStream* stream_of(jlong handle) { return reinterpret_cast<AAudioStream*>(handle); }

int bytes_per_sample(aaudio_format_t format) {
  switch (format) {
    case AAUDIO_FORMAT_PCM_I16:
      return 2;
    case AAUDIO_FORMAT_PCM_I24_PACKED:
      return 3;
    case AAUDIO_FORMAT_PCM_FLOAT:
    case AAUDIO_FORMAT_PCM_I32:
      return 4;
    default:
      return 0;
  }
}

void put_result(JNIEnv* env, jintArray out, aaudio_result_t result) {
  if (out == nullptr || env->GetArrayLength(out) < 1) return;
  const jint value = result;
  env->SetIntArrayRegion(out, 0, 1, &value);
}

}  // namespace

extern "C" {

// Opens an input stream: PCM16, shared, low latency, the given preset. deviceId
// 0 lets the platform route; capacityFrames 0 keeps the platform's buffer.
// allocateSession asks for a session id (AAudio warns it may cost the low
// latency path, so callers leave it off unless effects are attached). Returns
// the handle, or 0 with the aaudio_result_t in result[0].
JNIEXPORT jlong JNICALL
Java_com_librestatic_opencinecam_media_audio_AAudioNative_nativeOpen(
    JNIEnv* env, jclass, jint sample_rate, jint channels, jint input_preset, jint device_id,
    jint capacity_frames, jboolean allocate_session, jintArray result) {
  if (sample_rate <= 0 || channels <= 0) {
    put_result(env, result, AAUDIO_ERROR_ILLEGAL_ARGUMENT);
    return 0;
  }
  AAudioStreamBuilder* builder = nullptr;
  aaudio_result_t status = AAudio_createStreamBuilder(&builder);
  if (status != AAUDIO_OK || builder == nullptr) {
    put_result(env, result, status != AAUDIO_OK ? status : AAUDIO_ERROR_INTERNAL);
    return 0;
  }
  AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_INPUT);
  AAudioStreamBuilder_setSampleRate(builder, sample_rate);
  AAudioStreamBuilder_setChannelCount(builder, channels);
  AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_I16);
  AAudioStreamBuilder_setInputPreset(builder, input_preset);
  AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_SHARED);
  AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
  if (device_id != 0) AAudioStreamBuilder_setDeviceId(builder, device_id);
  // The caller passes 0: a capacity over the fast capture pipe costs the low-latency mode.
  if (capacity_frames > 0) AAudioStreamBuilder_setBufferCapacityInFrames(builder, capacity_frames);
  if (allocate_session == JNI_TRUE) {
    AAudioStreamBuilder_setSessionId(builder, AAUDIO_SESSION_ID_ALLOCATE);
  }
  AAudioStream* stream = nullptr;
  status = AAudioStreamBuilder_openStream(builder, &stream);
  AAudioStreamBuilder_delete(builder);
  if (status != AAUDIO_OK || stream == nullptr) {
    put_result(env, result, status != AAUDIO_OK ? status : AAUDIO_ERROR_INTERNAL);
    return 0;
  }
  put_result(env, result, AAUDIO_OK);
  return reinterpret_cast<jlong>(stream);
}

// Fills out (kInfoCount ints, see the indices above) with what the stream
// actually opened as. Returns false for a 0 handle or a short array.
JNIEXPORT jboolean JNICALL
Java_com_librestatic_opencinecam_media_audio_AAudioNative_nativeDescribe(
    JNIEnv* env, jclass, jlong handle, jintArray out) {
  AAudioStream* stream = stream_of(handle);
  if (stream == nullptr || out == nullptr || env->GetArrayLength(out) < kInfoCount) return JNI_FALSE;
  jint info[kInfoCount];
  info[kInfoSampleRate] = AAudioStream_getSampleRate(stream);
  info[kInfoChannels] = AAudioStream_getChannelCount(stream);
  info[kInfoFormat] = AAudioStream_getFormat(stream);
  info[kInfoPerformanceMode] = AAudioStream_getPerformanceMode(stream);
  info[kInfoDeviceId] = AAudioStream_getDeviceId(stream);
  info[kInfoFramesPerBurst] = AAudioStream_getFramesPerBurst(stream);
  info[kInfoSessionId] = AAudioStream_getSessionId(stream);
  info[kInfoBufferCapacity] = AAudioStream_getBufferCapacityInFrames(stream);
  env->SetIntArrayRegion(out, 0, kInfoCount, info);
  return JNI_TRUE;
}

JNIEXPORT jint JNICALL
Java_com_librestatic_opencinecam_media_audio_AAudioNative_nativeStart(JNIEnv*, jclass, jlong handle) {
  AAudioStream* stream = stream_of(handle);
  if (stream == nullptr) return AAUDIO_ERROR_NULL;
  return AAudioStream_requestStart(stream);
}

JNIEXPORT jint JNICALL
Java_com_librestatic_opencinecam_media_audio_AAudioNative_nativeStop(JNIEnv*, jclass, jlong handle) {
  AAudioStream* stream = stream_of(handle);
  if (stream == nullptr) return AAUDIO_ERROR_NULL;
  return AAudioStream_requestStop(stream);
}

// Blocks until frames frames are read into the start of the direct buffer or
// timeoutNanos passes. Returns the frames read (fewer, or 0, on a timeout) or a
// negative aaudio_result_t; a buffer too small for frames is ILLEGAL_ARGUMENT.
JNIEXPORT jint JNICALL
Java_com_librestatic_opencinecam_media_audio_AAudioNative_nativeRead(
    JNIEnv* env, jclass, jlong handle, jobject buffer, jint frames, jlong timeout_nanos) {
  AAudioStream* stream = stream_of(handle);
  if (stream == nullptr) return AAUDIO_ERROR_NULL;
  if (buffer == nullptr || frames <= 0 || timeout_nanos < 0) return AAUDIO_ERROR_ILLEGAL_ARGUMENT;
  const int sample_bytes = bytes_per_sample(AAudioStream_getFormat(stream));
  const int channels = AAudioStream_getChannelCount(stream);
  if (sample_bytes <= 0 || channels <= 0) return AAUDIO_ERROR_INVALID_FORMAT;
  void* base = env->GetDirectBufferAddress(buffer);
  const jlong capacity = env->GetDirectBufferCapacity(buffer);
  const jlong needed = static_cast<jlong>(frames) * channels * sample_bytes;
  if (base == nullptr || capacity < needed) return AAUDIO_ERROR_ILLEGAL_ARGUMENT;
  return AAudioStream_read(stream, base, frames, static_cast<int64_t>(timeout_nanos));
}

// out[0] = frame position, out[1] = CLOCK_BOOTTIME nanoseconds. False until the
// stream has started and published a position (INVALID_STATE is not fatal).
JNIEXPORT jboolean JNICALL
Java_com_librestatic_opencinecam_media_audio_AAudioNative_nativeGetTimestamp(
    JNIEnv* env, jclass, jlong handle, jlongArray out) {
  AAudioStream* stream = stream_of(handle);
  if (stream == nullptr || out == nullptr || env->GetArrayLength(out) < 2) return JNI_FALSE;
  int64_t position = 0;
  int64_t nanos = 0;
  if (AAudioStream_getTimestamp(stream, CLOCK_BOOTTIME, &position, &nanos) != AAUDIO_OK) {
    return JNI_FALSE;
  }
  const jlong values[2] = {static_cast<jlong>(position), static_cast<jlong>(nanos)};
  env->SetLongArrayRegion(out, 0, 2, values);
  return JNI_TRUE;
}

JNIEXPORT jint JNICALL
Java_com_librestatic_opencinecam_media_audio_AAudioNative_nativeXRunCount(JNIEnv*, jclass, jlong handle) {
  AAudioStream* stream = stream_of(handle);
  if (stream == nullptr) return 0;
  return AAudioStream_getXRunCount(stream);
}

// Frees the stream. The handle is dead afterwards; the caller forgets it, so a
// second close of the same stream never reaches here.
JNIEXPORT void JNICALL
Java_com_librestatic_opencinecam_media_audio_AAudioNative_nativeClose(JNIEnv*, jclass, jlong handle) {
  AAudioStream* stream = stream_of(handle);
  if (stream == nullptr) return;
  AAudioStream_close(stream);
}

// Text for an aaudio_result_t, for logs and refusal reasons.
JNIEXPORT jstring JNICALL
Java_com_librestatic_opencinecam_media_audio_AAudioNative_nativeResultText(
    JNIEnv* env, jclass, jint result) {
  const char* text = AAudio_convertResultToText(result);
  return env->NewStringUTF(text != nullptr ? text : "");
}

}  // extern "C"
