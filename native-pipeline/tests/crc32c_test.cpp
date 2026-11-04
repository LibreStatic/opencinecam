/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

#include "opencinecam/crc32c.h"

#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <vector>

namespace {

int failures = 0;

#define CHECK(condition)                                                        \
  do {                                                                          \
    if (!(condition)) {                                                         \
      std::fprintf(stderr, "%s:%d: CHECK failed: %s\n", __FILE__, __LINE__, #condition); \
      ++failures;                                                               \
    }                                                                           \
  } while (0)

using opencinecam::crc32c_update;
using opencinecam::crc32c_update_portable;

void test_vectors() {
  const char* digits = "123456789";
  CHECK(crc32c_update(0, digits, 9) == 0xE3069283u);
  CHECK(crc32c_update_portable(0, digits, 9) == 0xE3069283u);
  CHECK(crc32c_update(0, nullptr, 0) == 0u);
  // RFC 3720 B.4.
  uint8_t buf[32];
  for (auto& b : buf) b = 0;
  CHECK(crc32c_update(0, buf, 32) == 0x8A9136AAu);
  for (auto& b : buf) b = 0xFF;
  CHECK(crc32c_update(0, buf, 32) == 0x62A8AB43u);
  for (int i = 0; i < 32; ++i) buf[i] = static_cast<uint8_t>(i);
  CHECK(crc32c_update(0, buf, 32) == 0x46DD794Eu);
  for (int i = 0; i < 32; ++i) buf[i] = static_cast<uint8_t>(31 - i);
  CHECK(crc32c_update(0, buf, 32) == 0x113FDB5Cu);
  for (int i = 0; i < 32; ++i) buf[i] = static_cast<uint8_t>(31 - i);
  CHECK(crc32c_update_portable(0, buf, 32) == 0x113FDB5Cu);
}

void test_random_and_incremental() {
  std::vector<uint8_t> data(1 << 16);
  uint64_t state = 0x1234567;
  for (auto& b : data) {
    state = state * 6364136223846793005ull + 1442695040888963407ull;
    b = static_cast<uint8_t>(state >> 56);
  }
  // Every alignment and length mix agrees with the portable path and with
  // incremental updates.
  for (size_t offset = 0; offset < 16; ++offset) {
    for (size_t length : {size_t{0}, size_t{1}, size_t{7}, size_t{8}, size_t{31}, size_t{33},
                          size_t{1000}, size_t{65000}}) {
      if (offset + length > data.size()) continue;
      const uint32_t whole = crc32c_update(0, data.data() + offset, length);
      CHECK(whole == crc32c_update_portable(0, data.data() + offset, length));
      const size_t split = length / 3;
      uint32_t parts = crc32c_update(0, data.data() + offset, split);
      parts = crc32c_update(parts, data.data() + offset + split, length - split);
      CHECK(parts == whole);
    }
  }
}

void report_speed() {
  std::vector<uint8_t> data(64 << 20, 0x5A);
  for (int pass = 0; pass < 2; ++pass) {
    const bool portable = pass == 1;
    const auto start = std::chrono::steady_clock::now();
    uint32_t crc = 0;
    crc = portable ? crc32c_update_portable(crc, data.data(), data.size())
                   : crc32c_update(crc, data.data(), data.size());
    const double s = std::chrono::duration<double>(std::chrono::steady_clock::now() - start).count();
    std::printf("crc32c %s: %.0f MB/s (crc %08x)\n", portable ? "portable" : "default",
                data.size() / s / 1e6, crc);
  }
  std::printf("crc32c hardware: %s\n", opencinecam::crc32c_hardware_available() ? "yes" : "no");
}

}  // namespace

int main() {
  test_vectors();
  test_random_and_incremental();
  report_speed();
  if (failures != 0) {
    std::fprintf(stderr, "%d check(s) failed\n", failures);
    return EXIT_FAILURE;
  }
  std::printf("crc32c_test: all checks passed\n");
  return EXIT_SUCCESS;
}
