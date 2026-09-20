---
plan_id: OCC-PLAN-067
title: "Opt-in WebDAV transfers and finalized capture bundles"
status: InProgress
revision: 7
milestone: H5
intended_executor: Codex
execution_mode: implementation
depends_on:
  - OCC-PLAN-066
blocks: []
requirements:
  - OCC-PRO-001
  - OCC-PRO-008
adrs:
  - ADR-0034
risks:
  - RISK-020
estimated_sessions: 8
expected_repo_state: buildable
created_by: Codex
---
# OCC-PLAN-067: Opt-in WebDAV transfers and finalized capture bundles

## 1. Objective

Implement the full approved opt-in H5 transfer/remote integration scope, beginning with a real finalized-capture WebDAV transport and continuing through actual settings/service/worker/network wiring.

## 2. Why This Plan Exists

The approved decisions require local remote control, RTMP/SRT and automatic finalized-clip WebDAV upload over Wi-Fi, paused during REC; cellular needs explicit activation.

## 3. Prerequisites

OCC-PLAN-066 paired capture finalization and full artifact ownership; preserve H1–H5 scope and existing privacy intent.

## 4. Required Reading

ADR-0034, OCC-PRO-001/008, approved proposal NET-01, CaptureService, VideoOutput, audio sidecar result and current transfers module.

## 5. Inputs

App-published finalized video/audio/JSON bundles, explicit endpoint/consent, selected network and recording generation; local-server protocol fixtures.

## 6. Deliverables

Verified transport, complete capture bundle/outbox, settings and credential boundaries, durable scheduling, REC/network revocation, remote reconciliation, and full H5 integrations.

## 7. In Scope

WebDAV and the approved H5 local remote/RTMP/SRT scope; actual protocol/media/network acceptance and configured runtime behavior.

## 8. Out of Scope

This plan alone does not complete H1–H4 physical/editor or photo/monitor requirements. No final APK before the entire objective is verified.

## 9. Architecture

ADR-0034. Keep transfer workers independent from camera ownership; admit upload only for a completely finalized immutable capture bundle.

Durable outbox design (implemented; worker wiring remains pending): use SQLiteOpenHelper transactions for bundle/artifact rows with pure DTO/reducer interfaces. Store validated enum names, bounded URI/name/hash TEXT and64-bit INTEGER fields; do not rely on recent SQLite RETURNING/UPSERT syntax, enum ordinals, Java serialization or WorkManager payloads containing credentials. Bundle completeness derives from a publication seal plus all expected artifacts VERIFIED. Artifact states include QUEUED, UPLOADING, UNCERTAIN, VERIFIED, CONFLICT and SOURCE_UNAVAILABLE. Consent, REC, route and authentication are hold reasons rather than destructive rewrites of those states.

A revision/attempt/process-token lease is committed before socket I/O; compare-and-set completion rejects stale callbacks. Recovery maps dead-process UPLOADING rows to UNCERTAIN. Stage the complete expected VIDEO/VIDEO_METADATA/AUDIO/AUDIO_METADATA row set before the first publication, and seal after successful paired finalization. Unsealed bundles never become uploadable merely because one published row exists. Exposing committed metadata URIs and separating prepare/validate/allocation from publication are prerequisites; existing compensating cleanup must survive. Optional outbox/network failure must not delete a valid captured original.

Prepare local SHA-256 with bounded chunks and before/after source snapshots. Acknowledged PUT still requires bounded remote content verification; HEAD size or ETag alone is not proof. Reconciliation obeys the same REC/network/consent gates. A proven404 may permit a new conditional PUT; mismatch is a conflict without overwrite/delete. Freeze endpoint revision per bundle; explicitly reconcile uncertain old destinations before rebinding. Store credentials separately under a private Keystore-backed alias, never presets/outbox/worker data; key loss is visible AUTH_REQUIRED, not cleartext fallback. Preserve corrupt/newer schema data and surface recovery instead of silently resetting the queue.

## 10. Implementation Steps

1. Preserve the existing source and baseline tests.
2. Implement bounded conditional PUT/policy and individual MediaStore adapter tests.
3. Expose every finalized artifact URI and persist an outbox.
4. Wire explicit settings/credentials, selected network and pre-REC admission pause.
5. Add durable worker/reconciliation and real TLS/service/process-death acceptance.
6. Continue local remote control, RTMP/SRT and remaining H5 requirements.

## 11. State and Data

Local consent/credentials and runtime outbox do not become portable presets. Track endpoint identity, bundle/artifact state and uncertainty independently from clip publication.

## 12. Failure Handling

No overwrite, redirect credential forwarding or blind retry/delete. Revoked consent/network/REC stops the attempt. Ambiguous remote state requires content verification before retry.

## 13. Tests

Actual JVM loopback protocol tests, native MediaStore publication tests, then complete service→outbox→TLS/hash integration, consent/network transitions and process interruption.

Outbox acceptance must exercise every crash transition, stale lease callbacks, Long values above2^53, source mutation, endpoint rebinding and bundle completeness; native SQLite reopen/transaction rollback, Keystore invalidation, complete MediaStore bundle including JSON, interrupted TLS PUT and reconciliation match/mismatch/404. REC must be exercised at every I/O admission boundary, including hashing and verification. These are pending tests, not completed module acceptance.

## 14. Documentation Updates

ADR-0034, OCC-PRO-008, manifest/traceability, approved proposal and GOAL-PROGRESS; distinguish implemented module from missing app wiring.

## 15. Commands to Run

```bash
rtk proxy env ANDROID_HOME=$HOME/Android/Sdk JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew --no-daemon --dependency-verification=strict :app:testDebugUnitTest :camera:testDebugUnitTest :app:lintDebug
rtk proxy bash tools/check_format.sh
rtk proxy python3 tools/validate_plan_system.py
```
Native tests use isolated emulator-5680 and ADB server5038; exact class selections/results belong in evidence.

## 16. Acceptance Criteria

- [x] Transport/policy and native row tests pass with retained commands/results.
- [ ] Full finalized bundles are queued durably after paired publication only.
- [ ] Settings, credentials, Wi-Fi/cellular and pre-REC pause/revocation are wired end-to-end.
- [ ] Remote uncertainty, process death, real TLS/vendor acceptance and resource budgets pass.
- [ ] Local remote control, RTMP/SRT and remaining H5 scope are complete.

## 17. Evidence to Record

Commands, inputs, literal results/exits, source/media hashes, unit/native reports, selected network and consent traces, unimplemented gates and rollback.

## 18. Rollback and Recovery

Preserve source hashes, modify working copies, and test the portable rollback against a separate modified workspace; keep the main source changed.

## 19. Risks and Mitigations

HTTP acknowledgement is not content equality; MediaStore publication is not paired-take completion; camera service lifetime is not upload lifetime. Test each boundary independently and then end-to-end.

## 20. Completion Update

InProgress. Transport/policy, durable outbox, prepared publication and journal foundations have passed prior shared regressions. Revision4 integrates queue settings and admission-time service registration. Credentials, scheduler, selected-network/REC retirement, remote reconciliation, retention and remaining H5 are not complete. The execution record distinguishes each verified checkpoint from the remaining full acceptance gates.

## 21. Execution Record

User explicitly requested subagents. One agent owned only transfers sources/tests; coordinator owns shared settings/service/docs, build freeze, tests and reversible artifact packaging. Current evidence: build/implementation-h4-timecode-continuity/VERIFICATION.txt.



Pre-REC integration must close the control-plane race between publishing a disallowed policy and latching its stop reason on an active attempt. Latch exclusion under the admission lock; perform blocking disconnect outside the lock. A strict no-transfer-during-REC requirement also needs acknowledged worker retirement before starting the encoder, rather than treating a cancellation request as completed retirement. Current isolated transport is not yet that application-wide gate.


Transport integration targeted acceptance: all17 JVM cases and3 native MediaStore cases passed. The native deleted-row fixture now performs owned-row cleanup once; both assertions rejecting snapshot/open on the deleted URI remain intact. Conditional PUT, policy revocation and native publication admission are module evidence, not complete app upload wiring or TLS qualification. Full shared regression remains in progress.


Final shared acceptance:566 unit and179 native cases pass; app/camera lint has zero errors or new warnings. Exact commands, retained failures, native media inspection, original/modified/rollback results and source hashes: build/implementation-h4-timecode-continuity/VERIFICATION.txt. Full plan remains InProgress; this checkpoint does not close physical/editor or outstanding application-integration gates.


Revision2 begins the actual SQLite outbox implementation: a transactional staged artifact set, paired-publication seal, immutable local fingerprint/hash, revision/attempt/process ownership and explicit uncertain/reconciled states. A successful PUT acknowledgement is not a VERIFIED artifact; completion derives from the sealed full bundle and independently verified artifacts. Dead-process recovery revokes old attempts and preserves uncertainty rather than retrying blindly. Source changes, unknown schema/data and conflicting remote contents remain visible errors. The outbox remains separate from app permissions/settings, Keystore credentials, worker/network selection and finalizer enqueue/seal wiring, which are still required. Unit and real SQLite reopen/transaction tests are in progress under build/implementation-h2-owner-admission/VERIFICATION.txt.


The durable outbox now records missing/access-denied/read-failed/changed local source states without inventing a fresh snapshot. Revision/attempt CAS excludes stale hash/worker observations; remoteMayExist survives source loss and recovery, requiring reconciliation before another PUT. This is persistent state infrastructure only. Before application wiring, split prepared artifacts from publication, keep optional registration errors outside capture compensation, and provide retention/backpressure for the bounded queue. Full finalized-bundle scheduling and actual remote body verification remain pending.


Prepared-bundle integration now splits VIDEO/WAV/FLAC/JSON preparation from publication. CaptureService prepares audio first, derives final AV timing, prepares video and its JSON, records the entire identity set, then publishes existing audio/video rows. A private no-backup AtomicFile journal records PREPARED/COMMITTED/ABORTED by UUID independently of SQLite or endpoint credentials. An early abort has an empty tombstone rather than invented identities. Canonical bounded JSON rejects excessive nesting before parsing, duplicate fields and malformed identities. Optional callback errors are returned separately from capture success and produce a localized saved-locally notice; they never trigger compensation of valid originals.

This journal is an input to the future outbox bridge, not an upload worker or permission to upload old captures. Complete recovery still needs byte/fingerprint revalidation, a qualified publication recovery owner, and stage/seal mapping under explicit endpoint/consent policy. The1024-receipt cap is visible backpressure with no silent purge; retention/archive policy remains required before complete scheduling. Process death between individual MediaStore updates and durable commit remains unqualified.


Revision4 connects a new take's immutable enrollment to the service before encoder admission, then bridges the complete prepared artifact set to SQLite stage and paired publication to seal. Endpoint profiles have immutable URL/UUID bindings; changing a URL selects a new UUID and retains earlier profiles. Enrollment stores the consent revision and endpoint binding independently under noBackupFilesDir. Default-off queue settings live outside portable camera presets, expose an HTTPS-only destination, separate cellular intent and accessible labeled switches in a searchable Transfers category. These controls register new takes; they do not claim that network scheduling or credentials are already implemented.

The real MediaStore probe opens and closes its own read-only descriptor and validates identity, collection/role, canonical metadata types, byte length and observed before/after state. Pending provider SIZE may be zero or null; the descriptor supplies prepared byte length. Published SIZE must match. This is not a content hash or a guarantee against subsequent mutation. Optional journal/outbox errors remain independent of capture compensation. Recovery requires prior enrollment plus an identical COMMITTED receipt; it does not infer consent from current settings, PREPARED receipts or isolated published rows. An existing stage may seal from the actual successful finalizer callback despite independent journal failure; reconstructing a missing stage additionally requires COMMITTED.

Parallel agents own the probe, enrollment/codec and bridge, then isolated preferences/storage/UI/integration tests. Root owns shared contracts, admission helper, service, UI, centralized source freeze/build/device runs and reversible packaging. Current evidence is build/implementation-h5-enrollment-bridge/VERIFICATION.txt. Baseline559 files and649 unit plus55 tool tests passed. Integration is in progress; the initial compiler rejection of lost character literals in the probe is retained and corrected rather than hidden. Credentials, actual selected-network worker, acknowledged pre-REC I/O retirement, TLS reconciliation, retention and all remaining remote/streaming gates remain required.


Native integration distinguishes pending provider metadata from finalized byte facts: a null pending DATE_MODIFIED uses checked descriptor mtime, while null published DATE_MODIFIED remains an error. Before/after nullable provider values and descriptor identity/size/timestamps must remain stable; nonnull metadata must remain canonical INTEGER. The first failed native run and its precise boundary are retained. Startup settings reads now use the same process lock as save/reload/admission so AtomicFile recovery cannot race another instance's unfinished write. A deterministic held-writer fixture verifies this ownership rather than relying on stress alone.


The SAVED state and its registration outcome now publish in one atomic StateFlow emission. A cross-thread observer can no longer see successful publication before its optional-bookkeeping notice. The broad integration also strengthens the existing complete-bundle fixture: generated frames wait for actual GL-owner timestamp consumption, then require exactly12 muxed/extracted samples after EOS rather than a wall-sleep approximation. Full device acceptance is rerun after these repairs; prior failed runs remain retained.


API29 compatibility keeps descriptor-mode verification independent of the API30-only public fcntlInt method: a bounded4096-byte kernel fdinfo reader checks the owned descriptor's octal access flags and closes only its own input stream. Denial, malformed data or non-readonly mode is a visible registration error.12 pure bounded-reader/parser tests supplement native actual R/RW descriptor and close checks. Runtime qualification remains explicitly API30 emulator evidence, not an assertion of every vendor's procfs/MediaStore behavior.


Final frozen-source acceptance:723 unit +55 tools and285 native cases pass, with zero new lint signatures/errors (app58 warnings/1 hint; camera5 warnings). Exact failures, repairs, native source/device binding, independent media replay and tested rollback are recorded in build/implementation-h5-enrollment-bridge/VERIFICATION.txt. Service policy-change cases qualify actual two-artifact TIMELAPSE registration; real SQLite/MediaStore metadata fixtures cover1/2/4-role bridge sets. The complete actual service/audio-format→outbox matrix and all worker/credentials/network/retention/crash/remote/streaming gates remain open. Full H1–H5 remains ACTIVE.


Revision5 closes the isolated upload control stop/admission race: the first stop reason and cleanup reservation latch under the admission lock, while socket disconnection occurs outside it. Attempt-specific read-only retirement receipts complete only after transport finally and every reserved cancellation disconnect has actually returned. Timeout, interrupted waiting, cancelled futures and re-enabled policy are not retirement or REC permission. The application-wide worker/hash/remote-verification gate remains to be connected and qualified.

Endpoint credentials now have a separate AndroidKeyStore AES-GCM key per immutable endpoint UUID and a bounded versioned binary record in noBackupFilesDir. Endpoint identity and schema are authenticated as AAD. Status reads do not recover/delete uncertain AtomicFile residues or generate replacement keys. Corruption/key loss remains visible until explicit credential removal; valid replacement uses a fresh provider nonce. Credentials never enter queue settings, enrollment, outbox or camera presets. Searchable transfer settings expose password-masked empty drafts, explicit save/replace and confirmed removal; endpoint changes discard drafts, and a pending old-endpoint atomic save never becomes a new-endpoint credential. Disk/Keystore work stays off the UI thread, and cancelling composition result delivery does not claim cancellation of blocking I/O.

The actual codec/finalizer fixtures now bridge AVC/WAV/FLAC/JSON directly through prior enrollment, prepared stage and paired-publication seal into reopened SQLite, including 1/2/4-artifact takes. Real journal obstruction and SQL trigger rejection preserve the published, decodable originals. This strengthens recorder/finalizer integration; the full actual CaptureService audio-format matrix and network/TLS workflow remain required. Central verification is recorded in build/implementation-h5-transfer-retirement/VERIFICATION.txt; no final APK or full-plan completion is claimed.


Final frozen-source shared regression passed751 unit cases and308 native cases in7m51s, with no failures/errors/skips. Lint retains the prior app58 warnings/1 hint and camera5 warnings, without new issue signatures or errors. The65-case targeted integration also passed after the retained label-overflow repair. These are API30 private-emulator integration results, not physical/editor/network-TLS qualification. Exact-build media replay, independent decoding and tested rollback are recorded under build/implementation-h5-transfer-retirement/VERIFICATION.txt; full H1–H5 remains ACTIVE.


Revision6 adds one real artifact-processing step under a single admitted operation lifetime: strict descriptor-backed SHA-256, conditional PUT of the verified bytes, bounded remote GET and fresh local rehash, with exact durable lease transitions. The public upload wrapper preserves its prior contract; the internal owned overload avoids double enter/BUSY and leaves retirement to the outer worker after all source/socket/SQL work. PUT verifies its actual streamed digest and uses the explicit remoteName separately from validated local metadata. Fresh PUT snapshots must still equal the completed prehash snapshot.

Reconciliation releases an interrupted/inconclusive exact lease without fabricating local evidence or impersonating a dead process. Acknowledgment stays UNCERTAIN; only a completely read remote body plus unchanged fresh local bytes can mark VERIFIED. A qualified404 permits QUEUED but never an immediate retry within the same step. A failed terminal SQL write is not automatically attempted again in finally. Credentials resolve by original immutable endpoint identity using device-local settings and the vault; missing authentication holds work, and key loss/corruption never becomes anonymous fallback. Legacy non-collection URLs remain preserved and require explicit correction, not silent rebinding.

The descriptor reader uses bounded64KiB chunks and Long counters, validates before/after metadata and descriptor identity/stat (including nanosecond mtime/ctime), and closes only its own read-only descriptor. Deadline checks bound acceptance of remote evidence and cap per-read timeouts to remaining time; they do not claim immediate preemption or retirement when platform I/O blocks. The coordinator must retain REC exclusion until the shared receipt proves actual operation cleanup ended.

Six actual codec/finalizer fixtures extend through enrollment/journal/SQLite, strict source hashing, original-endpoint Keystore credentials and complete-bundle verification against an explicit in-memory HTTP peer. Independent JVM fixtures exercise real HTTP loopback. Neither qualifies platform TLS, selected Network routing, background scheduling or application-wide REC fencing. Those remain required before enabling the app worker. Central source ownership, failures and final acceptance are recorded in build/implementation-h5-worker-operations/VERIFICATION.txt; the full H1–H5 goal remains ACTIVE.


Integration review also moves PUT connection ownership before all request setters. A configuration exception must still disconnect the already-created connection, and the outer operation remains admitted until that cleanup actually returns. Three JVM regressions cover setter failure, held disconnect with pending retirement/BUSY, and cleanup exceptions without stranded admission.


E1 application wiring now connects explicit per-sealed-bundle send/verify/refresh/cancel actions to the existing worker and durable state. A runtime reservation excludes transfer work before VIDEO/LOG/TIMELAPSE preparation; the service waits off the UI thread, exposes cancellation without a second microphone prompt, rejects late continuations, and retains exclusion through publication/native and deferred-audio cleanup. Selected-network callbacks and device-local consent stop the current batch without migration or automatic retry. This is implementation in focused acceptance, not a completed HTTPS/server or H5 qualification. Commands and retained failures remain in the existing worker-operations ledger; EXECUTION-ORDER.md keeps E1 as the only active deliverable.


The focused E1 integration passed on the private API30 emulator: explicit queue UI, injected-peer runtime, consent/network revocation, delivered late cancellation and a real service movie admitted only after transfer-local I/O retired. Existing WB-cancel and queue-settings cases also passed. No full media replay or broad native regression was repeated. A real app-to-HTTPS-server demonstration remains the closure gate for E1.

Revision7 (user-authorized LAN, 2026-09-06): device-local endpoint schema v2 adds default-off `ignoreTlsErrors`, preserving canonical v1 identities and consent. The accessible settings switch applies only to the saved local IP profile; it never trusts a pending address draft or another destination. PUT and GET apply the exception only to their same-origin HTTPS connection. Wi-Fi no longer needs public-Internet validation for LAN access; other consent/REC/network gates remain. Targeted JVM and API30 native runs passed. A newly produced service timelapse and metadata traversed publication → explicit UI send → actual Network/TLS and independent PUT/GET hash verification with production opt-in, without fixture CA injection. Default trust still rejected the peer and global TLS defaults stayed unchanged. Evidence, retained initial failures and packaging/rollback: build/implementation-h5-worker-operations/VERIFICATION.txt. This closes neither physical/vendor acceptance nor the full H1–H5 objective.
