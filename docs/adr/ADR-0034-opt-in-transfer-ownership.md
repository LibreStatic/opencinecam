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

The original transport checkpoint had no upload scheduler/settings/network permissions wired to this module. E1 now connects explicit per-bundle UI actions, opt-in INTERNET/ACCESS_NETWORK_STATE declarations and a selected-network runtime; automatic scheduling remains pending. Add a complete finalized-capture bundle result after finalizeRecordingTake, including private video provenance/timing and audio metadata URIs. Persist an outbox before scheduling. A transfer coordinator/worker must outlive CaptureService, whose foreground/started lifetime ends after save and whose storage executor is destroyed with it. Pause admission before encoder start, not only after onRecordingStarted; release only after finalization or a generation-owned rejected start. Unknown/mixed VPN routes remain conservative; revocation must bypass deferred camera preferences.

The durable outbox must distinguish queued/uploading/uncertain/uploaded artifacts and endpoint identity without storing credentials in diagnostics. Process interruption of uploading becomes uncertain. Atomic remote commit/resume, real TLS/vendor interoperability, full app service/network integration and RTMP/SRT/local remote control remain open. This decision and a passing transport test do not complete H5.

## References

- [Android HttpURLConnection](https://developer.android.com/reference/java/net/HttpURLConnection)
- [WebDAV RFC4918](https://www.rfc-editor.org/rfc/rfc4918.html)
- [Android selected-network connections](https://developer.android.com/reference/android/net/Network.html)
- [Long-running background work](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running)


### Complete identities before publication

Each recorder prepares immutable artifact identities while rows remain pending. The paired finalizer prepares audio before video metadata, performs optional durable registration, and only then publishes those exact rows. Actual capture errors compensate owned outputs; optional registration errors remain independent of local save success. The private bounded publication journal is separate from endpoint/outbox policy and retains PREPARED/COMMITTED/ABORTED explicitly. No recovery task may infer whole-take commitment merely because one row is no longer pending, nor treat a local receipt as authorization to transfer previously captured media.

User-authorized LAN exception (2026-09-06): a default-off `ignoreTlsErrors` option is persisted per immutable endpoint URL/UUID. It accepts only explicit private/loopback/link-local IP literals, never DNS names or public addresses. Both PUT and verification GET apply the exception to their own same-origin HTTPS connection; process-wide TLS defaults and redirect rejection remain unchanged. The settings UI explains that this removes server authentication. Preference schema v2 preserves canonical v1 identities and consent, treating absent exceptions as false. Wi-Fi LAN admission does not require public-Internet validation; REC, consent, cellular and VPN gates still apply. This exception does not certify other devices, remote vendors or physical networks.
