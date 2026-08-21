# OpenCine Log v1 host contract

OpenCine Log v1 is a **candidate** scene-linear transform, not a device claim.
It was never promoted for live capture and is superseded there by
[`opencine-log-v2.md`](opencine-log-v2.md). Existing OCLog1 equations and experimental
sidecars remain immutable.
Its immutable domain is scene-linear, gamut BT.2020, full range, and curve
`OCLog1`:

```text
encode(x) = log(1 + 9x) / log(10)
decode(y) = (10^y - 1) / 9
```

Inputs and outputs are normalized to `[0, 1]`; out-of-range values are rejected
by the reference transform. Numeric vectors, CPU/GPU metadata, bounded 1D LUT
lookup, provenance gating, and a narrowing numerical editor are implemented in
`camera/src/main/java/com/librestatic/opencinecam/camera/OpenCineLog.kt`.

RAW-derived, P010-derived, and ISP-derived branches are separate provenance
labels. A branch is integrated only when its fixture is verified. Unknown or
unverified fixtures keep Flat8/HLG naming and expose no OpenCine Log mode. A
physical RAW/P010/ISP fixture and the upstream conditional gates are still
required before this contract can be promoted or its vectors published as a
device/file claim.
