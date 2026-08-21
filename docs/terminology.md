# Operational Terminology

These definitions are normative.

## Logical camera
A Camera2 ID that can route among physical devices; it is not proof that one sensor stays selected.

## Physical camera
A member ID disclosed by a logical camera through public characteristics.

## Active physical camera
The physical ID reported for a capture result; missing metadata leaves it Unknown.

## Public physical camera
A physical ID addressable through public Camera2 output configuration.

## Sensor RAW
CFA sensor samples plus sensor metadata; never YUV.

## Camera2 RAW
A public RAW_SENSOR, RAW10, RAW12, or later public RAW format from ImageReader.

## RAW video
A timed sequence of complete RAW frames and metadata sustained without silent loss.

## RAW still
One or a bounded burst of RAW captures; it proves no video rate.

## YUV
ISP-produced luma/chroma data, not sensor RAW.

## P010
Nominal 10-bit 4:2:0 semiplanar samples in 16-bit words; it does not prove effective precision.

## P210
Nominal 10-bit 4:2:2 semiplanar samples, capability-gated.

## SDR8
An 8-bit SDR recording with declared transfer, primaries, matrix, and range.

## Clean SDR
SDR8 with supported cosmetic processing requested off; requests do not prove OEM compliance.

## Flat8
An 8-bit ISP-derived low-contrast look; never Log.

## HLG10
Camera2 HLG10 encoded as Main10 with HLG/BT.2020 signaling and file evidence.

## HDR10
A PQ-based HDR path with appropriate metadata, distinct from HLG10.

## HEVC Main
The nominal 8-bit HEVC profile.

## HEVC Main10
A profile permitting 10-bit samples; signaling does not prove effective bits.

## APV
Advanced Professional Video exposed as `video/apv` on API 36+, subject to complete gates.

## OpenCine Log
A versioned scene-referred encoding with published math and source-specific transforms. A clip must identify the exact specification, transform build, and provenance tier.

## RAW-derived Log
OpenCine Log from public sensor RAW and documented calibration.

## ISP-derived Log
OpenCine Log from ISP output with explicit provenance and no RAW implication. The HFR tier uses a standard-range source with an explicit BT.709 working assumption and makes no HDR or 10-bit source claim.

## Requested
Present in the immutable capture intent and translated into a request.

## Accepted
The platform accepted configuration without synchronous or callback failure.

## Reported
Returned by CaptureResult or codec output metadata.

## File-verified
An independent parser confirmed required properties in a finalized file.

## Empirically verified
The exact fingerprint/configuration passed three cold-start sustained protocol runs.

## Advertised capability
Enumerated by public APIs with no session or sustained proof.

## Candidate configuration
A complete advertised graph eligible for an attempt.

## Verified configuration
A graph with session, first-frame, sustained, file, and required protocol evidence.

## Strict fallback policy
Reject or stop when a required invariant fails; never change the recorded path automatically.

## Adaptive fallback policy
Use only pre-authorized ordered transitions and disclose them; protected invariants stay fixed.

## Dropped frame
A missing expected capture or presentation interval proven from timestamps.

## Repeated frame
A duplicate proven by timestamps/samples or a controlled unique-frame fixture; static content is insufficient.

## Effective frame rate
Recorded frame count divided by monotonic media duration without excluding failures.

## Signal path
The sensor, HAL/ISP, surface, transform, encoder, muxer, and file sequence.

## Processing path
Declared requested and reported controls/transforms on the signal path.

## Capability report
A versioned snapshot of advertised, attempted, and evidenced device behavior.

## Clip sidecar
Versioned JSON linking intent, observations, events, warnings, and validation to a recording UUID.

## Device profile
Evidence scoped to protocol/schema, fingerprint, camera, codec, and graph.

## Device quirk
A narrow evidence-backed rule that can disable or correct but cannot manufacture verification.

## Unknown
No authoritative observation exists; distinct from false and Unsupported.

## Sustained
Mode-specific duration completed with no prohibited drop, overrun, pressure, thermal, or integrity event.

## Certified
The exact fingerprint and release build passed the complete release protocol.
