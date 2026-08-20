# Versioned Schemas

JSON Schema 2020-12 files here are canonical external contracts. Runtime models require golden parity tests.

- `schemaVersion` is semantic: additive optional fields increment minor; incompatible changes increment major.
- Unknown is `{ "knowledge": "unknown" }`, never false, zero, or empty text.
- Readers ignore additive unknown fields and reject unsupported major versions.
- Media time is normalized microseconds; hardware observations retain declared nanosecond time bases.
- Fingerprints, models, file names, URIs, and diagnostics are privacy-sensitive and redacted by default.
