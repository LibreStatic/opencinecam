/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

#include "opencinecam/crc32c.h"

#include <cstring>

#if defined(__aarch64__) && defined(__ARM_FEATURE_CRC32)
#include <arm_acle.h>
#if defined(__linux__)
#include <sys/auxv.h>
#ifndef HWCAP_CRC32
#define HWCAP_CRC32 (1 << 7)
#endif
#endif
#define OPENCINECAM_CRC32C_ARM 1
#endif

namespace opencinecam {
namespace {

constexpr uint32_t kPoly = 0x82F63B78u;

struct Tables {
  uint32_t t[8][256];
};

Tables make_tables() {
  Tables tables{};
  for (uint32_t i = 0; i < 256; ++i) {
    uint32_t crc = i;
    for (int k = 0; k < 8; ++k) crc = (crc & 1u) ? (crc >> 1) ^ kPoly : crc >> 1;
    tables.t[0][i] = crc;
  }
  for (int k = 1; k < 8; ++k) {
    for (int i = 0; i < 256; ++i) {
      const uint32_t previous = tables.t[k - 1][i];
      tables.t[k][i] = (previous >> 8) ^ tables.t[0][previous & 0xFFu];
    }
  }
  return tables;
}

const Tables& tables() {
  static const Tables instance = make_tables();
  return instance;
}

#if defined(OPENCINECAM_CRC32C_ARM)
bool detect_hardware() {
#if defined(__linux__)
  return (getauxval(AT_HWCAP) & HWCAP_CRC32) != 0;
#else
  return true;
#endif
}

uint32_t update_arm(uint32_t crc, const uint8_t* p, size_t n) {
  uint32_t c = ~crc;
  while (n > 0 && (reinterpret_cast<uintptr_t>(p) & 7u) != 0) {
    c = __crc32cb(c, *p++);
    --n;
  }
  while (n >= 32) {
    uint64_t a, b, d, e;
    std::memcpy(&a, p, 8);
    std::memcpy(&b, p + 8, 8);
    std::memcpy(&d, p + 16, 8);
    std::memcpy(&e, p + 24, 8);
    c = __crc32cd(c, a);
    c = __crc32cd(c, b);
    c = __crc32cd(c, d);
    c = __crc32cd(c, e);
    p += 32;
    n -= 32;
  }
  while (n >= 8) {
    uint64_t a;
    std::memcpy(&a, p, 8);
    c = __crc32cd(c, a);
    p += 8;
    n -= 8;
  }
  while (n > 0) {
    c = __crc32cb(c, *p++);
    --n;
  }
  return ~c;
}
#endif

}  // namespace

uint32_t crc32c_update_portable(uint32_t crc, const void* data, size_t length) {
  const auto& t = tables().t;
  const auto* p = static_cast<const uint8_t*>(data);
  uint32_t c = ~crc;
  while (length >= 8) {
    // Little-endian assembly of the two words keeps this byte-order independent.
    const uint32_t low = c ^ (uint32_t{p[0]} | uint32_t{p[1]} << 8 | uint32_t{p[2]} << 16 |
                              uint32_t{p[3]} << 24);
    const uint32_t high =
        uint32_t{p[4]} | uint32_t{p[5]} << 8 | uint32_t{p[6]} << 16 | uint32_t{p[7]} << 24;
    c = t[7][low & 0xFFu] ^ t[6][(low >> 8) & 0xFFu] ^ t[5][(low >> 16) & 0xFFu] ^
        t[4][low >> 24] ^ t[3][high & 0xFFu] ^ t[2][(high >> 8) & 0xFFu] ^
        t[1][(high >> 16) & 0xFFu] ^ t[0][high >> 24];
    p += 8;
    length -= 8;
  }
  while (length > 0) {
    c = (c >> 8) ^ t[0][(c ^ *p++) & 0xFFu];
    --length;
  }
  return ~c;
}

bool crc32c_hardware_available() {
#if defined(OPENCINECAM_CRC32C_ARM)
  static const bool available = detect_hardware();
  return available;
#else
  return false;
#endif
}

uint32_t crc32c_update(uint32_t crc, const void* data, size_t length) {
#if defined(OPENCINECAM_CRC32C_ARM)
  if (crc32c_hardware_available()) {
    return update_arm(crc, static_cast<const uint8_t*>(data), length);
  }
#endif
  return crc32c_update_portable(crc, data, length);
}

}  // namespace opencinecam
