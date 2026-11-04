/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

// CRC32C (Castagnoli, reflected polynomial 0x82F63B78), as OpenCine RAW chunks
// use it. The value is the finished CRC: start with 0 and feed the previous
// result back in, so crc32c_update(crc32c_update(0, a), b) is the CRC of a
// followed by b, and crc32c_update(0, "123456789", 9) == 0xE3069283.

#ifndef OPENCINECAM_CRC32C_H
#define OPENCINECAM_CRC32C_H

#include <cstddef>
#include <cstdint>

namespace opencinecam {

// Uses the ARMv8 CRC32 instructions when the build and the CPU have them,
// otherwise crc32c_update_portable.
uint32_t crc32c_update(uint32_t crc, const void* data, size_t length);

// Slicing-by-8 table implementation; always available.
uint32_t crc32c_update_portable(uint32_t crc, const void* data, size_t length);

// True when crc32c_update runs on the CRC32 instructions.
bool crc32c_hardware_available();

}  // namespace opencinecam

#endif  // OPENCINECAM_CRC32C_H
