# ADR-0034: Opt-in transfer ownership

- Status: Accepted
- Date: 2026-09-06
- Related requirements: OCC-PRO-001, OCC-PRO-008
- Related plan: OCC-PLAN-067

## Decision

Network transfers are explicitly enabled by the user, initially off, Wi-Fi only unless cellular consent is given. Recording preparation/capture/finalization owns priority over uploads and new proxies. Transfer consent and revocation are local operational state, never activated merely by importing a portable camera preset. Credentials stay outside CameraSettings and portable snapshots.

The initial dependency-free transport performs an actual HTTPS conditional PUT with platform trust and hostname verification by default, bounded64-KiB streaming, a fixed declared size and If-None-Match:*. It does not follow redirects, overwrite existing resources, blindly retry/delete uncertain remote state or claim remote checksum equality from an HTTP acknowledgement. Source admission requires a published immutable clip and checks metadata/length before and after streaming. The MediaStore adapter accepts only individual supported media rows. Row publication alone does not establish that its complete video/audio/JSON capture bundle finalized.

HttpURLConnection does not support MOVE in its documented request-method set. This transport therefore does not pretend to provide temporary-resource atomic MOVE/resume. Interrupted, changed-source or ambiguous requests retain remoteMayExist for explicit reconciliation. A later worker must bind each attempt to the approved network, stop on route/consent/REC changes and use content hashes or a qualified checksum protocol before treating uncertain remote bytes as ours. Length/ETag/412 alone is insufficient.

## Ownership and next integration

The original transport checkpoint had no upload scheduler/settings/network permissions wired to this module. E1 now connects explicit per-bundle UI actions, opt-in INTERNET/ACCESS_NETWORK_STATE declarations and a selected-network runtime; automatic scheduling remains pending. Add a complete finalized-capture bundle result after finalizePreparedRecordingTake, including private video provenance/timing and audio metadata URIs. Persist an outbox before scheduling. A transfer coordinator/worker must outlive CaptureService, whose foreground/started lifetime ends after save and whose storage executor is destroyed with it. Pause admission before encoder start, not only after onRecordingStarted; release only after finalization or a generation-owned rejected start. Unknown/mixed VPN routes remain conservative; revocation must bypass deferred camera preferences.

The durable outbox must distinguish queued/uploading/uncertain/uploaded artifacts and endpoint identity without storing credentials in diagnostics. Process interruption of uploading becomes uncertain. Atomic remote commit/resume, real TLS/vendor interoperability, full app service/network integration and RTMP/SRT/local remote control remain open. This decision and a passing transport test do not complete H5.

## References

- [Android HttpURLConnection](https://developer.android.com/reference/java/net/HttpURLConnection)
- [WebDAV RFC4918](https://www.rfc-editor.org/rfc/rfc4918.html)
- [Android selected-network connections](https://developer.android.com/reference/android/net/Network.html)
- [Long-running background work](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running)


### Complete identities before publication

Each recorder prepares immutable artifact identities while rows remain pending. The paired finalizer prepares audio before video metadata, performs optional durable registration, and only then publishes those exact rows. Actual capture errors compensate owned outputs; optional registration errors remain independent of local save success. The private bounded publication journal is separate from endpoint/outbox policy and retains PREPARED/COMMITTED/ABORTED explicitly. No recovery task may infer whole-take commitment merely because one row is no longer pending, nor treat a local receipt as authorization to transfer previously captured media.

User-authorized LAN exception (2026-09-06): a default-off `ignoreTlsErrors` option is persisted per immutable endpoint URL/UUID. It accepts only explicit private/loopback/link-local IP literals, never DNS names or public addresses. Both PUT and verification GET apply the exception to their own same-origin HTTPS connection; process-wide TLS defaults and redirect rejection remain unchanged. The settings UI explains that this removes server authentication. Preference schema v2 preserves canonical v1 identities and consent, treating absent exceptions as false. Wi-Fi LAN admission does not require public-Internet validation; REC, consent, cellular and VPN gates still apply. This exception does not certify other devices, remote vendors or physical networks.


### Amendment (2026-10-19): opt-in automatic upload of new takes

The "automatic scheduling remains pending" statement above is superseded. Automatic upload is now implemented with WorkManager (`androidx.work`), under these rules:

- **Opt-in and scope.** A device-local `autoUpload` switch in the queue settings, off by default, valid only while the queue is enabled, never part of a portable preset. It is frozen into the REC enrollment at admission, so only takes recorded while it was on are eligible; existing media and previously enrolled takes are never enqueued. Takes whose sidecar says `takeStatus: incomplete` are never enrolled, as before.
- **Scheduling.** After the publication bridge seals and registers the outbox bundle (the outbox is persisted before scheduling), an enrollment with `autoUpload` enqueues one unique work `webdav-auto-<bundleId>` with `ExistingWorkPolicy.KEEP` (idempotent), tag `webdav-auto`, the bundle id as the only payload (no endpoint, credential or path), a network constraint of `UNMETERED`, or `CONNECTED` when the cellular consent is on, and exponential backoff from one minute. A scheduling failure is logged and never reaches capture publication.
- **Execution.** The worker re-reads the current settings and enrollment first and fails without network when the queue or the switch is off, storage needs recovery, or the enrollment's endpoint is no longer the active one. It then runs the existing runtime batch for that single bundle, so the REC fence, selected-network, consent, media-mutation and ownership gates are unchanged: a running capture or media mutation makes it retry later, and stopping the worker cancels only its own batch. It declares a `dataSync` foreground service (`FOREGROUND_SERVICE_DATA_SYNC`, low-importance "Transfers" notification) and continues without it if Android refuses the start.
- **Retry classification.** Backoff applies only to states the runtime can resume safely: remaining QUEUED artifacts, or UNCERTAIN artifacts without an attempt, which go through remote verification and never a blind PUT, with a transient message (waiting for recording or media, no network, cellular consent required, uncertain). Retries are capped at 20 runs. Success is a COMPLETE bundle. Conflict, missing source, authentication, error, disabled and cancelled results stop, as does any artifact with an in-flight attempt, which needs the user's reconciliation. A stopped bundle stays visible in the existing transfer UI for manual action.
- **Revocation.** Saving settings with the queue off, the switch off or a different active endpoint cancels every `webdav-auto` work. A change of the cellular consent alone re-enqueues the still-pending works with `REPLACE` and the new constraint. Turning the switch on again does not resurrect cancelled bundles; they stay manual.
- **Permissions.** `FOREGROUND_SERVICE_DATA_SYNC` is justified by the WebDAV opt-in in `SecurityAudit`; the WorkManager `SystemForegroundService` entry is non-exported with the `dataSync` type. WorkManager's own library manifest adds `RECEIVE_BOOT_COMPLETED` and `WAKE_LOCK` to the merged manifest.

Real-device behaviour (vendor background restrictions, Doze, foreground-start limits) is not claimed by the host tests.
