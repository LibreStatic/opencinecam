# Security and Privacy

The portable OSS app has no Internet permission, analytics, telemetry, account, remote crash reporting, cloud sync, or automatic upload. Required permissions are CAMERA; optional RECORD_AUDIO; FOREGROUND_SERVICE plus camera/microphone service permissions; and POST_NOTIFICATIONS where required. No broad storage permission is used.

The service and providers are non-exported unless a documented share surface requires otherwise. PendingIntents are immutable. Shares use content URIs, explicit MIME types, temporary read grants, and chooser UI. MediaStore and SAF descriptors are closed by their owner. Signing keys and Play credentials stay outside the repository.

Capability and diagnostic exports warn about fingerprint, model, camera/codec identifiers, file names, timestamps, and failure context. Redacted export is default; full export is explicit. Logs rotate locally, omit per-frame metadata in release, and are deleted by user action or retention policy.

Dependency verification, SBOM, SPDX notices, secret scanning, exported-component inspection, backup policy, native hardening, and malformed schema/container fuzzing are release gates. F-Droid, direct, and Play artifacts use identical portable runtime behavior and no proprietary service dependency.
