# Security, privacy, and accessibility review

Review baseline for the portable OpenCineCam release:

- **Permissions:** Camera, microphone, notification, and camera/microphone
  foreground-service permissions only. No Internet, broad storage, analytics,
  account, or remote-crash permission.
- **Components:** `MainActivity` is the deliberate launcher export;
  `CaptureService` is non-exported. Foreground starts are visible and use
  immutable Stop intents.
- **Data flow:** MediaStore/SAF owns destinations; diagnostics remain local and
  bounded. Sharing, report export, and full diagnostic export require explicit
  user actions. Redacted export is the default.
- **Native boundary:** opaque handles, bounded capacities, stable errors, and
  explicit release are required before native input is accepted.
- **Accessibility:** controls expose semantic labels independent of color and
  use at least 48 dp touch targets across portrait/landscape layouts.
- **Backups:** application backup is disabled so local evidence cannot leave the
  app boundary through automatic backup.

The executable audit contract is
`core/model/src/main/kotlin/com/librestatic/opencinecam/core/model/SecurityAudit.kt`.
Run it with the deterministic core-model tests before release review. This is a
source/configuration audit; it does not claim a device certification run.
