# Security, privacy, and accessibility review

Review baseline for the portable OpenCineCam release:

- **Permissions:** Camera, microphone, notification, and camera/microphone
  foreground-service permissions for capture. `INTERNET` and
  `ACCESS_NETWORK_STATE` are declared only for opt-in WebDAV transfers
  (OCC-PLAN-067, ADR-0034): off by default, never enabled by presets, HTTPS
  only, and started by an explicit per-bundle user action unless the separate
  opt-in *Upload new takes automatically* switch (off by default, device-local,
  never in a preset) is on. That switch lets WorkManager upload takes recorded
  after it was turned on, on Wi-Fi unless cellular is allowed and never during a
  recording; `FOREGROUND_SERVICE_DATA_SYNC` covers its visible `dataSync`
  foreground service (ADR-0034 amendment 2026-10-19), and WorkManager's library
  manifest adds `RECEIVE_BOOT_COMPLETED` and `WAKE_LOCK`.
  `ACCESS_COARSE_LOCATION` and `ACCESS_FINE_LOCATION` are declared only for
  opt-in photo/take geotagging: off by default and requested at runtime while
  the camera activity is resumed. No broad storage, analytics,
  account, or remote-crash permission.
- **Opt-in network/location (OCC-PRIV-001, amended 2026-09-26 and 2026-10-19;
  ADR-0027 and ADR-0034 amendments):** network and location permissions are
  allowed only for the named opt-in features above, each off by default, with no
  background or automatic network use other than the opt-in WebDAV automatic
  upload of new takes, no analytics/telemetry/accounts, HTTPS-only transfers and
  no third-party SDKs. The `SEC-NETWORK`/`SEC-PERMISSIONS` checks in
  `SecurityAudit.kt` accept those permissions only when their `OptInFeature` is
  declared, and `SecurityAuditManifestTest` audits the real manifest. A separate
  no-network flavor was considered and rejected for now.
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
