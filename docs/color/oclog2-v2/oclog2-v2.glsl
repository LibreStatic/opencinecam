// SPDX-License-Identifier: Apache-2.0
// Normative OCLog2 component reference; source tier decoding occurs before this transform.
const float OCLOG2_BASE = 50.0;
const float OCLOG2_BLACK = 0.10;
const float OCLOG2_WHITE = 0.90;

float oclog2_encode(float sceneLinear) {
    float x = clamp(sceneLinear, 0.0, 1.0);
    return OCLOG2_BLACK + (OCLOG2_WHITE - OCLOG2_BLACK) * log(1.0 + OCLOG2_BASE * x) / log(1.0 + OCLOG2_BASE);
}

float oclog2_decode(float code) {
    float y = clamp(code, OCLOG2_BLACK, OCLOG2_WHITE);
    float normalized = (y - OCLOG2_BLACK) / (OCLOG2_WHITE - OCLOG2_BLACK);
    return (pow(1.0 + OCLOG2_BASE, normalized) - 1.0) / OCLOG2_BASE;
}
