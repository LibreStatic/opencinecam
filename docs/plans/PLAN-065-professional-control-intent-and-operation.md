---
plan_id: OCC-PLAN-065
title: "Professional control intent and operation"
status: InProgress
revision: 9
milestone: H3
intended_executor: Codex
execution_mode: implementation
depends_on:
  - OCC-PLAN-015
blocks: []
requirements:
  - OCC-PRO-005
  - OCC-PRO-006
adrs:
  - ADR-0030
  - ADR-0032
risks:
  - RISK-020
estimated_sessions: 5
expected_repo_state: buildable
created_by: Codex
---
# OCC-PLAN-065: Professional control intent and operation

## 1. Objective

Implement approved H3 professional operation, beginning with connected exposure/white-balance controls, followed by ISP/stabilization, presets/programmable controls, scopes/LUT and audio listening/gain.

## 2. Why This Plan Exists

The user approved H1–H5, central settings and one final APK after the whole program. H2 software verification allows H3 implementation while physical acceptance remains open.

## 3. Prerequisites

OCC-PLAN-015; tested H2 working source preserved independently in build/implementation-h3-controls/BASELINE.tar.

## 4. Required Reading

ADR-0030/0032, OCC-PRO-001/005/006, proposal CAM-02/03/04, MON-01/02, AUD-01, runtime request composition and settings repository.

## 5. Inputs

Existing exposure/WB/ISP models, Camera2 runtime, API 36.1 local reference source, API 30 isolated emulator; physical device pending.

## 6. Deliverables

Connected professional settings and runtime, deterministic models, requested/reported UI, persistence and integration tests, source hashes and reversible evidence.

## 7. In Scope

Full approved H3. Initial execution implements exposure/time-angle/native priority/antibanding/CCT tint; later continuations retain the remaining H3 features.

## 8. Out of Scope

This plan alone does not close H4/H5 or final release. No final APK before the complete goal passes.

## 9. Architecture

ADR-0032. Preferences express requested intent; descriptors gate available branches; one request path resolves intent; metadata reports actual application.

## 10. Implementation Steps

1. Preserve and test the H2 baseline.
2. Implement rational exposure resolution and native capability gates.
3. Connect searchable settings, quick controls and reported metadata.
4. Add ISP, presets, controls, scopes/LUT and audio operation.
5. Run host/UI/runtime tests and independent rollback.
6. Execute physical/media acceptance before marking Done.

## 11. State and Data

Persist bounded professional intent. Never persist measured state, pending authorization, live sessions or claimed hardware support.

## 12. Failure Handling

Unsupported native modes resolve to an explicitly disclosed AUTO branch. Failed live requests retain prior controls and must not discard a recording. New-camera capability loss is visible. HFR remains unavailable until qualified.

## 13. Tests

Exposure and WB model/capability tests; repository/persistence/UI tests; Camera2 request/result integration; existing H1/H2 regression and physical color/exposure/cadence tests.

## 14. Documentation Updates

Requirements, ADR-0032, manifest/index/traceability, goal ledger and exact verification reports.

## 15. Commands to Run

```bash
rtk proxy env ANDROID_HOME=$HOME/Android/Sdk JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew --no-daemon --dependency-verification=strict :app:testDebugUnitTest :camera:testDebugUnitTest :app:lintDebug
rtk proxy ./tools/check_format.sh
rtk proxy python3 tools/validate_plan_system.py
```
Instrumentation uses isolated ADB server 5038 and emulator-5680; record exact classes and command in evidence.

## 16. Acceptance Criteria

- [x] Initial controls have passing host, persistence, UI and runtime tests.
- [ ] Independent rollback restores baseline bytes and tests.
- [ ] ISP/stabilization, presets/programmable controls, scopes/LUT and audio operation are complete and tested.
- [ ] Physical 50/60 Hz, priority, WB-start, LOG/HFR and audio/LUT acceptance passes.
- [ ] Full H3 scope is proven, not inferred from a narrow test.

## 17. Evidence to Record

Commands, exact inputs/outputs/exits, hashes, unit/instrumentation XML, request/result captures, physical evidence and incomplete gates.

## 18. Rollback and Recovery

Restore changed paths from the preserved baseline only after preflight hash checks on a separate workspace; leave the active source modified.

## 19. Risks and Mitigations

HAL-advertised controls may be ignored or rejected. Report measured metadata separately, retain prior live requests on rejection, and do not promote unsupported/unknown tuples.

## 20. Completion Update

InProgress. The whole goal and remaining H3–H5 stay active.

## 21. Execution Record

Started 2026-09-05 from the self-recording checkpoint. Baseline: 262 host tests passed. Evidence directory: build/implementation-h3-controls.

Initial connected-control checkpoint: 274 host tests and 62 isolated-emulator instrumentation tests passed, lint has zero errors. Real Camera2 observed manual ISO 400 and 8,333,333 ns for 90°/30 FPS, followed by AUTO restoration. Native priorities and CCT/tint are unadvertised on that emulator, so no physical/API-36 promotion is claimed. Persistent settings, bounded sliders and quick controls share exposure/WB intent; changing ISO retains angle representation, and changing Kelvin retains tint. Physical WB-at-record locking, ISP/stabilization and all remaining H3 features stay open. Detailed reversible source and test evidence: build/implementation-h3-controls/VERIFICATION.txt.

ISP continuation: implement independent image processing and mutually exclusive OIS/EIS, persist intent, freeze from video preparation through finalization, restore template defaults and expose advertised session-key/reported metadata. Preserve physical and remaining H3 acceptance as open. Evidence: build/implementation-h3-isp/VERIFICATION.txt.

ISP checkpoint validation: 286 host tests and 68 isolated-emulator instrumentation tests passed, with no lint errors. Camera2 submission/result observations show EIS ON, HQ image noise reduction and edge OFF; restoring DEFAULT recovers the exact original request tuple. OIS ON is unadvertised on this emulator. Pure tests cover optical/electronic exclusivity, unsupported/HFR branches and template recovery. UI tests cover independent choices, pending preparation state, search, persistence and 200% font accessibility. Actual physical stabilization/crop/quality, WB-at-record locking and remaining H3 work are still open.

WB preparation continuation: persisted CONTINUOUS/LOCK_ON_RECORD policy, pre-output asynchronous convergence/lock confirmation, fixed-WB confirmation, timestamp boundary for all GPU recording paths, cancellation/timeout and AUTO restoration are connected. WB and policy edits remain pending for held takes. Real request/result, unit/persistence/UI and rollback evidence is recorded in build/implementation-h3-wb/VERIFICATION.txt after validation. Physical first-frame color, retained-graph and cross-session media acceptance remain open; this continuation does not complete H3 or the full goal.

WB device acceptance matrix (still pending): compare CONTINUOUS and held AUTO under a controlled daylight/tungsten transition; record submitted/result AWB mode/lock and decode the first frame in retained SDR, retained LOG, new SDR and timelapse sessions. Inject pre-lock queued frames to verify the sensor-timestamp boundary rather than only a metadata callback. Verify fixed preset and API-36 CCT/tint confirmation, cancellation from operator/self controls, screen loss, unsupported HFR, three-second timeout, no MediaStore/audio/encoder creation on failed preparation, pending-WB replay and AUTO release after finalization. Repeat camera/session changes and stops to reject stale results. Synthetic gate and emulator request/result tests are evidence for those mechanisms, not physical color or encoder acceptance.

WB checkpoint final validation: 297 host tests, 74 isolated-emulator instrumentation tests, zero failures/errors/skips and lint with no errors. AUTO preparation produced LOCKED with a positive sensor timestamp; release reported unlocked AUTO; immediate cancellation did not become ready; advertised DAYLIGHT produced FIXED with a matching request/result. Pure tests additionally reject older/equal lock timestamps after convergence. The independent rollback and source hash evidence remain separate from the physical/media acceptance matrix above.

Preset continuation: complete portable-settings snapshots and capture intent, versioned import/export, closed 80-key V2 registry, settings-only V1 migration, 32-entry persistent library, independent rename/update, C1/C2 review access and pending structural application are connected. Known incompatible fields are disclosed before confirmation; actual hardware still controls adaptation. Three programmable buttons, volume mapping, startup/restoration policies, record-control lock and focus-transition lifecycle review remain open CAM-04 requirements. Evidence is collected in build/implementation-h3-presets/VERIFICATION.txt; no full H3 or release completion is inferred.

Preset checkpoint final validation: 308 host tests and 83 isolated-emulator instrumentation tests passed with no failures/errors/skips. Lint has no errors. Repository-to-service-to-Camera2 application reported MANUAL ISO 400, VIDEO and target 1280×720 at 15 fps; document/library round-trip and C2 persistence were observed. The software-selected target is not media/encoder qualification. Intermediate compiler/lint/test corrections and a wrapper-exit discrepancy are retained in evidence; the independent final full run exited 0. Physical geometry/control and REC/finalization preset acceptance remain pending.

Focus lifetime continuation: camera-executor-owned transition tokens fence stale timer work; camera closure and autofocus selection cancel a pull; manual focus clears when changing camera while camera-specific marks remain local. Pure transition and real Camera2 recreation/cancellation tests are recorded in build/implementation-h3-focus/VERIFICATION.txt. Physical focus accuracy/smoothness and remaining CAM-04 programmable controls remain open.

Focus checkpoint final validation: 314 host tests and 83 instrumented cases passed with no failures/errors/skips; lint reports no errors. Final UTP metadata selects emulator-5680. Per-test log confirms three starts, two cancellations, one completion, same-camera mark retention and submitted/reported AF=4 with submitted manual distance null. The optional cross-camera branch did not run because no second eligible descriptor was available; physical multi-camera isolation, smoothness and REC/HAL rejection acceptance remain open.


Operator continuation: three configurable buttons and independent volume mappings share the service/settings action path. Volume repeats and key-up do not re-trigger actions; activity lifecycle/window focus and capture-section membership gate input. Capture retains the same role/generation/microphone authorization as the on-screen button; preset commands require the existing review. Startup PHOTO/VIDEO/LAST, exposure/WB restoration, torch restoration and capture-control lock are persisted. LAST uses current availability, not an imported camera ID; startup runs once per service creation and never starts capture. Locking preserves stop/cancel/monitoring and explicit unlocking; camera settings remain pending, direct camera commands are rejected and focus pulls cancel when entering a locked take. The V3 preset registry adds exactly nine reviewed portable operation preferences and migrates the exact V2 registry with default operation preferences. Validation and remaining runtime/physical gates are recorded in build/implementation-h3-operation/VERIFICATION.txt.

Operator targeted acceptance: 324 host tests, 15 targeted UI/service/input tests and then eight operation tests passed after corrections. A real timelapse clip decoded (158 ms in the targeted run); pending exposure replayed after stop while monitoring stayed live. The AVD announces zero hardware AVC encoders, so the normal VIDEO path rejects preparation and creates no clip. VIDEO/LOG/GPU REC lock acceptance remains explicitly open; the hardware encoder selection was not changed to satisfy a test. Final full regression follows in the checkpoint evidence.

Operator checkpoint final regression: 324 host tests (128 app + 196 camera), 91 instrumented cases and lint with no errors passed; zero failures/errors/skips. UTP identifies emulator-5680. Independent rollback/source evidence and the final real timelapse/input/VIDEO-rejection probe are retained in build/implementation-h3-operation/VERIFICATION.txt. H1–H5 remain active; no final APK.

Independent media follow-up: a separate retained timelapse test clip decoded with FFmpeg, but ffprobe reports 62 frames in 0.160556 s (average 1116000/2891 fps), not the configured project cadence. This is a newly observed H4 timing defect, not validated timelapse cadence. The REC lock/state/stop assertions remain valid; timing promotion remains open. The operator button row also gained an opaque dark surface after visual inspection found white labels hard to read over a bright scene.


Revision7 / E10 MON-01 in progress: typed options (20 keys, presetV10 with V1–V9 migration), 64×64 waveform, 64×64 BT.709 vectorscope and 64×36 false color over bounded RGB8. Camera2 uses a YUV ISP estimate; GPU uses SDR/OC-Log2 code before assist. Configuring does not certify HDR/IRE or a technical LUT. Guides/safe area and magnification belong to the operator, not to file cropping. Cadence and suspension state prevents stale graphics from being presented as current. Local acceptance in `build/implementation-h5-worker-operations/VERIFICATION.txt`: e10-reviewed and e10-integration finished with code 0, with contract XML, 200% settings, overlay, real GPU and YUV service. The sample retains its options/domain and is hidden if it expires or does not match the requested configuration. The geometry includes scale/centering of the same GPU quad; stale generations/owners are discarded on the camera executor. The 12-frame SDR recording decodes without graphics; admission, start callback and file removal keep their focused tests. Lint neither adds nor removes signatures relative to E9. The first test import failure and the review corrections are retained, with no weakened tests. Package/restore/reopen closes in FINAL_E10_REOPEN_FIELDS. Physical colorimetry, alignment/latency, positive LOG and prolonged thermal load remain pending; MON-02 and AUD-01 remain open.


Revision8 / E11 MON-02 — library/operator with local acceptance: atomic local library of original `.cube` files (8 entries/16 MiB; each 2 MiB, 3D 2–33; SDR output 0–1), declared metadata and exact export/hash. CPU parser/trilinear and optional 3D program/texture only in the operator viewer, with an explicit pre-assist domain. Scopes, subject and file keep their original program. First GPU route VIDEO/TIME_LAPSE/LOG; still pending are the photo routes, independent LUT per output, explicit pre-REC baking, labeling/sidecar/editor and HDR/ACES subject to PLAN061. MON-02 is not reduced to a library; the order of deliverables and exact results are recorded in EXECUTION-ORDER and the existing ledger.

E11 verified on API30: originals/hash and library limits, restorable SAF, 200% UI, declared domain, CPU/GPU trilinear charts with 33 table and replacement 2, service selection/disabling, prior subject/scopes intact and 12-frame SDR MP4 without LUT. The texture uses RGBA16F with native HALF_FLOAT payload; half quantization, not bit-for-bit Float32 identity. FLOAT load failures, compilation and lint remain in the ledger. `e11-acceptance-final` reuses valid contracts/UI and confirms lint signatures identical to E10 after focused tests. Package/diff/restore/reopen close-out: `FINAL_E11_REOPEN_FIELDS`. Full MON-02, LOG/physical acceptance and AUD-01 remain pending; next single feature: photo LUT viewer, without file baking.

Revision9 / E12 MON-02 — photo viewer with local API30 acceptance: library selection now enables GPU in PHOTO/RAW_PHOTO/BURST/BRACKET/LIGHT_TRAIL without replacing the JPEG/DNG/HEIC readers or altering payloads. The Camera2 target is the GPU input, not the operator window; repeating/precapture/cancel/bracket keep photographic AF and metering. Redundant YUV is omitted because scopes receive the pre-LUT GPU signal.

Evidence `e12-native`, `e12-detach-fixed` and `e12-acceptance`: PixelCopy green against byte-for-byte original files, related RAW+JPEG, decoded DNG/JPEG, sequences and flash ON; announced PHOTO/RAW/VIDEO/FPS changes and return to PHOTO, selection/disabling and preferences deferred during publication, cancellation with auxiliary and detach/reopen. The timeout for requested FPS in PHOTO and the RED of the CAPTURING lock remain retained. The fix excludes photography from graph retention by an auxiliary lease without a producer; the writer that already won is not canceled. Lint does not change signatures relative to E11. Package/restore: `FINAL_E12_REOPEN_FIELDS` in the existing ledger. MON-02 still pending: independent subject selection/output, explicit pre-REC baking with sidecars/editor, HDR/ACES and physical/LOG/HEIC gates; H3 is not declared complete.


Revision10 / E13 MON-02 — independent subject LUT: atomic library v2 preserves the v1 operator and leaves the subject disabled until explicit selection. EN/ES settings distinguish selection, disable and presented state for each output; selection remains local and is not part of the preset. Separate textures/cache/failure share a compatible shader; the bounded swap frame freezes identity/state until a successful swap. Recording and scopes keep the original signal. The service invalidates the epoch when replacing the target and revalidates lease/generation/identity in the CAS.

API30 SDR evidence: `e13-integration`, `e13-epoch-fixed`, `e13-cleanup-reviewed` and `e13-acceptance-reviewed`; nonlinear charts/distinct selections, disable/mismatch/recovery per output, migration/durability and 200% UI, original 12-frame MP4 and equal scopes. The retained real callback showed RED WAITING versus ACTIVE before the fix; the initial fixture getter failure is also retained. Optional window removal, direct Camera2/GPU and the photo viewer/publication remain verified; lint does not change signatures relative to E12. Package/restore in `FINAL_E13_REOPEN_FIELDS`. Subject only VIDEO/LOG for now, LOG/physical acceptance and photo subject pending; explicit pre-REC baking, metadata/editor, HDR/ACES/PLAN061 and AUD-01 continue. MON-02 and H3 are not declared complete.


Revision11 / E14 MON-02 — explicit file baking with local SDR API30 acceptance: library v3 preserves the operator/subject of v1/v2 and leaves recording disabled until a confirmed selection. Settings block structural changes during reservation, take and publication; the service reads an atomic snapshot of selection and library health, not a lagging main collector. The intent is frozen before waiting for transfers/WB; deleting or changing the original afterward does not alter the reserved take. An incompatible domain or missing application evidence does not publish a file without the requested transformation.

The file texture is independent of the viewers; the receipt identifies the first successful swap to the encoder. Related metadata declares original hash, identity, domain, interpolation, half precision, baking and non-reapplication in the editor. The BT709/SDR/limited labels are verified in the real outputFormat, not invented afterward. Baked LOG keeps Main10 when the device supports it, but identifies LUT→SDR output also when starting/saving. Non-baked routes keep their defaults.

Evidence: `e14-initial`, `e14-service-integration`, `e14-native-retained`, `e14-saved-label` and `e14-acceptance`, in the existing family. Library/migration, UI and confirmation; retained reservation, selection committed before the collector, change/deletion during wait, cancellation without late start, domain rejected without rows and publication with verified metadata. Positive service observed: three-frame TIME_LAPSE per take; this is not equivalent to regular VIDEO. Synthetic GPU generates twelve real frames with a nonlinear LUT; subject changes during REC and scopes remain pre-LUT. Focused regression confirms a file without baking and the original JPEG with retained publication. Lint keeps the E13 signatures.

Independent editor: Kdenlive loads a project with the baked MP4 without reapplying the LUT and another with the lossless source plus the original/trilinear `.cube`. Both resulting files and the native MP4 pass twelve decoded frames, BT709 labels and CPU reference; hashes and samples are in `editor-e14/PIXEL_VERIFICATION.json`. The installed renderer crashes when closing Qt (SIGSEGV in dispatcher; also with QT_NO_GLIB); the final diagnostic with composition disabled keeps the scenelist/log and also does not close normally. Despite wrapper exit 0: clean editor finalization remains pending. The initial extraction failure keeps its diagnostic: connected tests had uninstalled the package; only the necessary chart was regenerated through direct instrumentation and its bytes were extracted/hash-verified once.

Retained gates: regular VIDEO requires a hardware AVC encoder that this emulator does not announce; MediaRecorder does not substitute for the bake. LOG needs HEVC Main10/EGL10 and an announced profile (regular HLG LOG additionally requires API34+ and BT2020_HLG). Physical colorimetry/cadence/thermal, HDR/ACES/PLAN061, full MON-02 and AUD-01 remain open. Package/diff/restore/reopen of this advance are recorded in `FINAL_E14_REOPEN_FIELDS`; H1–H5 remains active and no final APK is generated.


Revision12 / E15 AUD-01 — first connection of digital gain with local acceptance, without closing listening: manual setting −24…+24 dB, disabled/0 dB by default, preset v11 with two new keys and exact migration of previous registries. The file intent is frozen during reservation/take/finalization; changing preferences does not modify that take. Manual, even at 0 dB, excludes AGC, keeping its preference for when manual is disabled. It is not presented as analog gain or headphone volume.

The owned PCM of preview/AAC/WAV/FLAC applies gain before the encoder/writer; the meter receipt identifies the configuration actually processed, not merely the requested one. PCM16/24 saturates on overflow; float keeps finite headroom. The meter keeps input clipping even if a later attenuation hides it, including samples between publications; RMS/peak remain post-gain. The interleaved stereo count of the AGC was corrected. MediaRecorder without a PCM tap rejects manual before taking the descriptor, even manual 0 dB. Old callbacks do not reactivate receipts or clipping after replacing/removing the producer or destroying the service. WAV/FLAC removal attempts all resources and does not declare success if a release fails.

Existing evidence: `e15-gain-integration` keeps configuration/migration contracts and gain/AGC vectors; `e15-gain-reviewed` keeps camera 40 approved and a failed app compilation due to the Material3 opt-in; `e15-gain-final` finishes with code 0 with the app removal contracts, assembly and lint with no new signatures relative to E14. `e15-native-initial` keeps two real failures, even though adb returned 0: unannounced PCM24 input and a slider smaller than 48 dp. The real 52 dp thumb and a targeted opt-in fix the UI without changing the assertions. `e15-native-final` finishes with code 0 and runner `OK (14 tests)`: stereo WAV16, mono float WAV and stereo FLAC, manual 0 dB preview, PCM24 rejection without rows, MediaRecorder admission without taking the descriptor, callback fencing, three UI cases including 200%/48 dp, real removal of both writers and default WAV/FLAC timing.

The three initial native files were extracted once, with matching device/host hashes; FFmpeg decoded all their frames/channels to finite values and metadata gain 6 dB/AGC false (`audio-e15/VERIFIED.json`). The later review changes clipping/cleanup, not the byte transformation nor encoding/timing; that decoding is reused, not repeated. The CPU vectors prove mathematical amplitude; the emulator microphone does not certify calibrated analog gain or audible listening. The positive PCM24 test remains intact for an input that announces it; the API30 rejection is not counted as positive acceptance. Manual AAC is integrated/compiled, but its positive recording requires the available hardware video route.

`e15-acceptance` consolidates sources/hash and valid evidence without deleting failures. Package/diff/restore/reopen of this connection is recorded in `FINAL_E15_GAIN_REOPEN_FIELDS`, in the same family. E15 is still the only active deliverable: still missing are listening, effective cable/USB/Bluetooth output, independent volume, handoff with a real receipt of asynchronous preview removal, effects/VU/PPM and audible/latency/A-V acceptance. The observed happy-path closing of preview does not certify its removal if a worker/stop gets stuck. All external H1–H5 acceptances and the final APK remain.


Revision13 / E15 AUD-01 — real removal and preview handoff with local API30 acceptance: `PreviewAudioMonitor.start()` starts the worker without running startRecording on main; `close()` requests removal and `closeAsync()` delivers a defensive observation of the closing. The coordinator waits for native start, stop and the real exit of the reader before releasing effects/AudioRecord. Neither timeout, interrupt nor a waiter canceled/completed by its caller advances the closing. A release failure keeps owner/gate; a stop failure stays visible even if a later release does remove the resources. A failed factory keeps the cleanup receipt of the handles already acquired.

The service serializes preview replacements on main and chains all receipts, including that of a failed factory. Generation/eligibility and PCM epoch prevent an old replacement from opening or publishing. The existing capture reservation now waits for audio transfer and removal before starting WB/capture; cancellation keeps the reservation while the microphone is still alive and discards stale continuations. `audioRetirementPending` distinguishes that wait from WebDAV and offers accessible EN/ES cancellation without new settings or preset schema.

Evidence in the same family: `e15-handoff-build` finishes with code 0; app 33 contracts (lifecycle 11, UI state 3 and existing removal 19), shared camera 31, assembly and lint. `e15-handoff-native` finishes with code 0 with runner `OK (12 tests)`, no skips: a retained real AudioRecord reader keeps STATE_INITIALIZED until it exits; canceled/forged receipts do not release; an absent input factory delivers verified cleanup; the service cancels capture without late dispatch, keeps only the latest gain when replacing and does not reopen after detach; 200%/48 dp button; preview gain/epoch, LUT reservations and the focused TIME_LAPSE silent/VIDEO matrix with hardware gate. The retained native start/stop tests are lifecycle contracts; the native blocking observed on the emulator retains a real PCM callback, and does not claim to reproduce a hung driver.

The independent review was read-only and found no concrete blockers. App lint keeps the gain signatures; camera/media did not change. The gain, WAV/FLAC bytes, encoder and timing remain byte-identical to the previous source: its three already decoded/hash-verified files are reused, with no new copies. `e15-handoff-acceptance` verifies sources/evidence; package, patch, rollback and reopen are recorded in `FINAL_E15_HANDOFF_REOPEN_FIELDS`. No broad regression is running and there are no subagents with adjacent fronts.

E15 remains the only active one. Next connection: listening from the already-owned PCM, effective output and file-independent volume, without a second microphone or writer blocking; then effects/VU/PPM and audible/latency/A-V acceptance. Cable/USB/Bluetooth, positive PCM24/AAC and other physical/external gates keep their scope. This local removal does not close AUD-01 nor enable a final H1–H5 APK.


Revision14 / E15 AUD-01 — PCM listening and independent volume with local API30 acceptance. Persistent settings and preset v12 separate activation, volume 0…100 and category cable/USB, Bluetooth or speaker; the concrete ID remains local. Importing/restoring enabled does not connect: only the explicit button arms the output. The EN/ES controls declare the runtime's effective route/state and remain accessible at 200% with real 48 dp targets. Volume and output are live preferences during REC, independent of the frozen gain/input; changing only the volume keeps the same microphone and AudioTrack.

Preview/AAC/WAV/FLAC offer a read-only PCM view after gain and before writer/encoder. The optional consumer reserves at most four 64 KiB copies, including the worker's; it discards the excess without waiting for AudioTrack or blocking the writer. A single worker converts only the listening copy to PCM16 and applies volume to the AudioTrack. The preferred route is actually verified before/after writing; priming uses silence and volume 0. The output identity is fixed by the explicit connection and kept across producers; change/disconnection disarms, without looking for another output or automatically enabling the speaker. A process claim prevents overlapping two AudioTracks even during removal; closeAsync does not grant release by canceling/completing a view of the receipt.

The review fixed three races of this integration: old PCM admitted under a new generation, old ACTIVE after clear/reconnect and implicit selection of another device between producers. Admission captures the generation before verifying the producer epoch; publications validate generation/identity, including the service state CAS; boundDeviceId keeps the connection's destination. Their deterministic fixtures pass. The device-removal notification uses a real AudioDeviceInfo and synthetic callback: it does not certify physical unplugging or acoustic mute latency.

Evidence of the existing family: `e15-listen-camera` passes 34 contracts and lint; `e15-listen-build` passes 106 app contracts, assembly and lint. The review only recompiles app/APKs and lint (`e15-listen-reviewed-build`), reusing the valid host XML. `e15-listen-native` finishes with code 0, `OK (26 tests)`, no skips: controller 7, UI 4, files 3, preview/handoff 6, gain/epoch 2 and reservations/capture 4. Real AudioRecord and AudioTrack, effective routedDevice, frames written, applied volume, reconnection and no microphone duplication are observed. The listening changes do not claim PCM parity for MediaRecorder.

`e15-listen-files` extracts three files and their exact taps once with matching device/host hashes: stereo WAV16, mono float WAV and stereo FLAC, 48000 Hz, 28672 frames each. FFmpeg returns PCM byte-for-byte identical to the post-gain 6 dB tap while the requested volume alternates 0/100; the controller test also verifies applied volume without replacing the track. The file float keeps its original representation, separate from the listening conversion. `audio-e15-listen/VERIFIED.json` keeps hashes and channels/frames. The new decoding is justified by verifying the newly added tap/isolation; it does not replace or repeat the previous evidence without that connection.

`e15-listen-acceptance` consolidates sources/evidence and lint signatures identical to the previous checkpoints; package/patch/restore are recorded in `FINAL_E15_LISTEN_REOPEN_FIELDS`. Both subagents finished and their changes were integrated, with no other fronts or broad regression. E15 remains the only active one: effects/VU/PPM and audible/latency/A-V acceptance, physical cable/USB/Bluetooth output, positive PCM24/AAC and other H1–H5 gates remain pending. The speaker route observed on the emulator does not certify audibility, calibrated volume or acceptance on the physical device. The final APK awaits full closure.


Revision15 / E15 AUD-01 — real NS/AGC/AEC state with local acceptance. `AudioLevelSnapshot.effects` distinguishes the original request, UNKNOWN/UNAVAILABLE/ENABLED/DISABLED/FAILED, platform/software implementation, nullable observed control and separate configuration failure. Reading valid getters is not the same as obtaining control; a failed set also does not erase an observed enabled. Preview/AAC/WAV/FLAC observe their own still-live handles before transforming PCM. Manual and software AGC require confirming any retained public AGC as disabled; losing that confirmation fails through the existing removal paths and avoids publishing a take as exclusively manual. Public absence does not certify hidden HAL processing.

The connection also fixes fictitious software AGC on float PCM: that loop does not process SoftAgc, so it is no longer created or announced. The fallback only occurs without a public handle or after observing it disabled, without overlapping a known AGC. AAC now receives and applies its own NS/AEC requests with handles retained before set/get and release by the existing owner. Its toggles also appear in AAC, keeping eligibility by capability and separate applied state. The UI test found that `normalizedFor` erased both requests; the two AAC assignments now keep requested && capability, the same as WAV/FLAC. Preset schema/keys are not changed.

WAV/FLAC keep the last receipt before removal and its timestamp in the result/`audioEffects` metadata, with state/implementation/control/error and a point-in-time observation disclosure. The legacy booleans are true only when ENABLED is observed; the structured state distinguishes false, absence and uncertainty. The result does not query already-released effects nor promise uniform processing of the whole take. The EN/ES UI shows the current preference and the frozen session's receipt, failed configuration even with software active and absence of observation for routes without PCM. The existing publication by epoch also discards old enriched receipts.

Evidence of the existing family: `e15-effects-build` keeps camera 76 approved (includes 13 read/policy contracts and the 31 base) and app 30 lifecycle/removal; it finished with code 1 due to an ambiguous import of the new fixture. The explicit import, without assertion changes, allows `e15-effects-reviewed-build` code 0; it reuses the host XML. `e15-effects-native` keeps 23 approved walkthroughs and the real AAC normalization failure. `e15-effects-aac-settings-build` passes five contracts, including a 64-case matrix of request/capability/manual/AGC and idempotence; `e15-effects-native-aac-settings` passes exactly the previously failing UI case, intact. `e15-effects-acceptance` gathers 24 unique accepted walkthroughs without skips and lint with signatures identical to listening, without repeating the 23 valid ones.

The native walkthroughs include real AudioRecord in preview/WAV/FLAC, getter/metadata and float state, abort/removal without publication when injecting confirmation loss, 200%/48 dp UI, epoch, listening/handoff and reservations/capture. Direct AAC produces real PCM and MediaCodec packets and ends in EOS, with the drainer removed before the codec; no camera, video or muxer is used and it does not certify the positive CaptureService×video cell. Confirmation loss in sidecars is injected into the observation reader over a real producer; physical loss of HAL control is not declared. Positive/vendor NS/AGC/AEC, PCM24, listening, latency and A/V remain as external acceptance.

The gain, SoftAgc, listening queue/tap and preset algorithms remain byte-identical; the previous PCM/files evidence is reused and no new copies are extracted/decoded for metadata/state. Package/patch/restore are in `FINAL_E15_EFFECTS_REOPEN_FIELDS`. Both subagents finished their changes and are frozen, with no adjacent fronts; E15 continues with configurable VU/PPM and physical acceptance. The H1–H5 scope and the final APK remain pending.


Revision16 / E15 AUD-01 — configurable VU/PPM with local acceptance. `AudioMeterSettings` separates visibility, Peak/RMS–VU–PPM, VU reference −24…−6 dBFS (−18 initial), hold 0…3000 ms (1500 initial) and numeric values. EN/ES settings and preset v13 keep 137 portable keys and previous migrations; the preference applies during REC without changing gain, listening or input device. The service keeps the same producer/epoch when only the presentation changes.

The meter processes each PCM16/24/float block before limiting publications: peak/RMS gather the whole window and pre-/post-gain clipping survives intermediate blocks. VU and quasi-PPM use the sample clock and real rate of the four producers, not the UI clock. VU approximates second-order motion (99% at 300 ms, overshoot 1.11%); PPM approximates the individual tolerances of historical EBU bursts and return of 24 dB/2.8 s. The tests include five rates 44100…192000, sinusoidal signal, buffer partitioning, independent channels, float headroom, non-finite data and intact bytes/cursor. Model references: [ITU-R BS.645](https://www.itu.int/rec/R-REC-BS.645-2-199203-I/en) and [EBU Tech3205](https://tech.ebu.ch/docs/tech/tech3205.pdf); it is a digital approximation with sine-peak alignment, not electrical certification, true-peak or LUFS.

The HUD declares MIC only with an active producer and a PCM receipt of up to 500 ms; its own clock expires the receipt even if no other callback arrives. Missing ballistic values show —, never renamed RMS/peak; independent per-channel hold and latched clip/reset ≥48 dp are kept separate from the mode. The layout tests receive updated synthetic PCM, keeping the MIC/L/R asserts and non-overlap; the specific cases check expiration, future, absence and removal.

`e15-meters-build` passed 80 camera and 85 app contracts, test APKs and lint. `e15-meters-native` passed 22 API30 walkthroughs: six new UI, lifecycle/handoff with AudioRecord, presentation changes without restart, direct AAC, epoch and layouts. Three new real WAV16/float/FLAC recordings are extracted/decoded exactly once in `audio-e15-meters`: PCM identical to the tap, including WAV16 at 44100 Hz and listening volume 0/100. This extraction is justified because the DSP now reads all blocks between tap and writer; it does not replace physical evidence nor is it repeated for the later UI adjustment.

The initial consolidator failed when reading a TAR open for append; the failure is kept, along with all original records/bytes and the exclusive repair of TAR terminators. The next comparator detected a new AutoboxingStateCreation hint from the clock. `mutableLongStateOf` removes that hint without suppression; `e15-meters-clock-build` passes and `e15-meters-clock-native` repeats only the same expiration case intact. `e15-meters-acceptance-final` reuses the 165 contracts, 22 walkthroughs and valid files, checks the single source difference and lint signatures identical to the effects checkpoint. No count expresses percentage progress.

Package/patch/restore of this connection are recorded in `FINAL_E15_METERS_REOPEN_FIELDS`, same family. Both subagents finished, their changes are integrated and frozen, with no adjacent fronts. AUD-01 keeps physical acceptance of listening/latency/A-V, cable/USB/Bluetooth, vendor effects, PCM24 and service×video/AAC by hardware. The H1–H5 goal remains active; no final APK is generated. After the verifiable closure of local E15, the next local connection in the queue is to specify clapperboard/gallery/H4 relations; external gates are not turned into missing implementation nor do they block everything else.

Revision17 / H3 operator — production changes after the Rev67 seal (F1–F3), recorded here because they were applied without formal review (HANDOFF-2026-09-21, end-to-end review 2026-09-25). `OperatorControls.kt`: each assignable button draws one glyph per action (`OperatorActionIcon`; letters for A/B/C1/C2) instead of plain text; actions with latched state (TORCH, PEAKING, ZEBRA, HISTOGRAM, VIEW_ASSIST, CONTROL_LOCK, EXTERIOR) show a container color and an ON/OFF pill with `stateDescription` for TalkBack, derived from `operatorActionToggleState` in `OperatorPreferences.kt`; a long press shows the action's help text (21 new `operator_*` strings in EN and ES, parity kept). `operatorActionAvailable` gates VIEW_ASSIST so it is not left enabled where it is a no-op. Verification status: host 1731/0 and lint with no errors in the 2026-09-25 review; `OperatorUiTest` 6/6 on the API30 emulator per the handoff; nothing verified on a physical device.

The 2026-09-25 review opened observations on this work that the fixes flow (package WP4) corrects without closing acceptance: the `state.gpuViewfinder || selectedMode == LOG` gate enables VIEW_ASSIST and shows ON also in TIME_LAPSE, VIDEO with LUT/subject preview and PHOTO with LUT, where the GPU viewfinder is passthrough and does not apply the transformation (R2; the correct gate is LOG only); the TORCH pill reads the requested setting and not the effective one; the long-press help must not be lost on a disabled button; the toggles lack a `Role`. Still open: LOG without hang, VIEW_ASSIST in LOG and peaking during recording on a real device, and all the physical H3 acceptance listed above. No final APK.
