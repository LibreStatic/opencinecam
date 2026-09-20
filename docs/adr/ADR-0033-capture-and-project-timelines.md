# ADR-0033: Capture and project timelines

- Status: Accepted
- Date: 2026-09-05
- Related requirements: OCC-PRO-001, OCC-PRO-007
- Related plan: OCC-PLAN-066

## Decision

Separate sensor/capture time from project presentation time. The retained H3 operation fixture has 62 encoded frames in 0.160556 seconds, with most PTS deltas near 2.22 ms instead of 33.33 ms for the configured 30-fps project. Decoder success was not cadence acceptance. Preserve that file and its packet report as the regression input.

Interval capture selects at most one real sensor frame per configured interval before encoding. Duplicate, zero and backward timestamps are ignored. Late delivery fills only the current slot, records missed intervals and never invents duplicate catch-up images. Each selected frame receives a project timestamp calculated from its absolute index and a rational rate with integer arithmetic; rounding errors do not accumulate. Camera profile FPS does not overwrite the project FPS preference.

Silent SDR timelapse uses the bounded GLES/MediaCodec graph so frame selection precedes compression. It prefers advertised hardware AVC Surface encoders and may use an advertised software Surface encoder when hardware is absent, preserving the previous MediaRecorder timelapse route's automatic codec selection. The actual codec name and hardware flag are exposed. Normal VIDEO and LOG retain their hardware-only encoder gates; software timelapse is not evidence for those routes. No codec claim is inferred from a label alone.

Frame-count limits use selected/submitted frames, not an interval multiplied by elapsed time. Duration limits remain monotonic wall-clock limits. Finalization rejects a mismatch between selected inputs and emitted encoded frames. Recording geometry and pending structural preferences follow the same service-owned route as other video, while preview/analysis are not throttled to the timelapse interval.

## Remaining full timeline scope

Rational capture/project preferences throughout regular VIDEO/off-speed, pause/resume with shared video/audio epochs, long-run drift, timecode/editor acceptance, frame sequences and physical/HFR/LOG/exterior/audio qualification remain open under the approved H4 and whole H1–H5 objective. Initial interval-selection work does not complete H4.

## Encoder configuration evidence

The first API30 attempt rejected an explicitly configured AVC profile without its corresponding level (`configureCodec returning error -38`). The Android 11 [ACodec implementation](https://android.googlesource.com/platform/frameworks/av/+/refs/tags/android-11.0.0_r48/media/libstagefright/ACodec.cpp) requires both when a profile is supplied. Candidate selection now retains an advertised level for that profile and supplies both; the second attempt configured the encoder successfully. Further media acceptance remains separate from configuration acceptance.

The compatible timestamp conversion checks the nonnegative BigInteger bit length before `toLong()`; it does not call `longValueExact`, which failed at runtime on the API30 fixture despite passing JVM tests. The retained SDR GPU preview now also serves TIME_LAPSE, keeping analysis/operator frames independent of selected encoder intervals and avoiding direct-Surface producer replacement at REC.

High-frequency metadata, analysis and effective zoom use atomic StateFlow reducers. A retained-preview finalization run exposed pending-intent loss, while the identical repeat passed; these reducers prevent stale telemetry snapshots from replacing newer phase/settings state. Regression must still cover service finalization, not just the pure clock.


## Rational project preferences and off-speed

Version 4 portable presets explicitly add timelapse project denominator and VIDEO off-speed/project numerator/denominator (93 portable keys). V1–V3 retain their exact accepted registries and default to integer timelapse/realtime VIDEO. Rational pairs are validated together; corrupt local pairs restore one coherent default, while noncanonical imports are rejected rather than normalized silently. Initial fractional choices are 24000/1001, 30000/1001 and 60000/1001 **project** clocks, never an assertion of fractional Camera2 sensor cadence.

SDR VIDEO off-speed assigns one project timestamp to each real incoming frame, ignoring invalid/backward/duplicate source timestamps. It is explicitly silent and does not rewrite the remembered audio setting; normal VIDEO and LOG continue to honor audio intent. The backend rejects a project override combined with embedded audio, LOG or a second interval clock. Hardware-only VIDEO selection is preserved. Each take snapshots its project rate so pending settings cannot relabel or retime its media. Full pause/resume, off-speed sidecars/processed audio, physical codec/capture cadence, timecode and editor promotion remain separate acceptance gates.


## Container acceptance, not input-PTS acceptance

The first fractional-media run showed 12 samples at 60000/1001 with final extracted PTS 183455 µs instead of the intended 183516 µs; 24000/1001 also differed, while 30000/1001 aligned. Per-delta tolerance alone hid this accumulated short-clip deviation. Android 11 [MPEG4Writer](https://android.googlesource.com/platform/frameworks/av/+/refs/tags/android-11.0.0_r48/media/libstagefright/MPEG4Writer.cpp) smooths near-equal tick durations. Tests therefore check every absolute file timestamp, not just encoder input or adjacent differences.

After successful muxer stop/release, the owned silent project clip receives an in-place timing-table finalization. It keeps all selected/compressed samples, box sizes, sample/chunk offsets and payload bytes unchanged. It preflights a single nonfragmented video track with no composition offsets and matching expected sample counts; `mdhd` uses the rational numerator as timescale and `stts` durations use the denominator. Track/movie/edit durations follow that exact media duration. Both 32/64-bit duration headers are handled; unsupported structures/overflow/readback failure prevent successful publication. Public positional FD reads/writes preserve the caller descriptor and do not require private platform timescale keys. This is not a generic imported-video retimer or a substitute for frame selection before encoding.


## Terminal output ownership

Muxer stop and release are ordered mandatory stages. Release still runs after a stop failure; timing mutation runs only after both succeed and prerequisites hold. Later failures are suppressed onto the first cause rather than replacing diagnostics. Partial timing writes or mismatched readback never qualify the output for publication.

The app output transaction caches the first completion outcome under synchronization. It closes the descriptor before validation, writes a pending JSON provenance sidecar and requires exactly one updated row per publication. Any failed stage attempts deletion of both owned rows; a later success call cannot resurrect an aborted URI. Video and Downloads providers do not offer a joint atomic transaction: this is synchronous compensating cleanup, not crash-atomic publication. Cleanup failures remain diagnostic failures. SAVED requires a committed URI. Mode changes clear the old take's project-rate label and preserve the desired GPU policy on reopening.

Separate WAV/FLAC audio sidecars are not owned by VideoOutput; their service-level publication ordering still needs the same compensation review. This revision does not qualify their cleanup when the subsequent video publication fails.


## Separate audio compensation (revision 4)

The service now invokes a shared take finalizer while retaining both output owners. Missing requested audio is a failed take, not a silently successful video. A video/provenance publication failure invokes explicit audio discard even after WAV/FLAC finish succeeded. The recorder registers its audio/metadata rows before publication; cleanup continues after a deletion failure and retains failed rows for retry. A successful finish is cached, ordinary close is idempotent, and explicit discard clears the cache and revokes owned outputs. File finalization exceptions also attempt owned-row cleanup, including header/STREAMINFO mutation and descriptor closure.

This supersedes revision 3's missing service-level compensation; it does not claim a crash-atomic multi-provider transaction. Worker timeout/resource-generation handling, physical recording and shared audio/video clocks remain distinct work.


During revision 4 regression, direct/GPU graph switching exposed concurrent analysis-reader closure invalidating acquired chroma buffers. Each analysis reader now owns a lease that holds through acquisition, analysis and image release; reader closure uses the same lease. Listener publication happens after the read lock. This protects buffer lifetime without copying full frames or swallowing the observed null-buffer exception; queued retired-reader callbacks skip acquisition. The fix is specific to analysis readers, not a qualification of RAW/JPEG or all asynchronous session callbacks.


## Audio worker retirement (revision 5)

Timeout does not prove a worker stopped. A dedicated retained coordinator requests native stop, waits for actual worker exit and only then releases AudioRecord/MediaCodec. The caller has one bounded wait (WAV 3 s; FLAC 5 s). If it expires or the caller is interrupted, the take fails and pending rows are discarded; file cleanup moves to the coordinator after native work ends. New sidecar creation checks a process-wide retirement gate, avoiding repeated allocation over an unretired owner. A scheduling failure retains that owner rather than closing beneath it. A permanently hung native call can retain the gate until process teardown; the implementation does not fabricate recovery.

FLAC EOS input and drain retries share a 4.5-second monotonic stop budget that repeated retries do not reset. Intentional stop returns any acquired empty input buffer before leaving the feeder. Native release runs only after feeder/drain exit, including early start failures. Real-rate metadata is captured while AudioRecord is initialized. These changes bound the caller's native-worker wait, not arbitrary filesystem/provider calls or the whole application shutdown path.


Revision 5's service replay exposed PREVIEWING with a false GPU flag after returning to TIME_LAPSE. The ready callback now reports the selected engine session path explicitly; it no longer relies only on a desired flag stored during opening. Opening, readiness and effective-settings reducers preserve concurrent state fields atomically. This is a reporting correction supported by the failed/repeated same-owner service test, not a claim that every camera callback now has generation-scoped ownership.


## GPU/AAC and descriptor retirement (revision 6)

The encoder/muxer owns a duplicated ParcelFileDescriptor, independent of the app's pending-output handle. Final container timing uses this owned descriptor and closes it before the terminal recording callback. Closing the app handle therefore does not revoke a still-active native writer's descriptor.

A GL-detach observation timeout marks the take unsuccessful but continues waiting for actual owner completion; it never substitutes a timed wait for release. A rejected GL task waits for thread exit before downstream native release. Embedded AAC stop executes on a dedicated thread; its feeder/stop threads must exit before codec release, and EOS retries share a non-renewable stop epoch. This does not prove that all native calls return on every device.

Pipeline close is asynchronous and snapshots the active recording on the GL thread, avoiding a close/start race. Its cleanup follows drain exit and executes EGL destruction on the owning thread. Camera2 tracks retiring pipelines and waits before a replacement graph reuses the producer surface; failed retirement retains that gate and reports failure. Subject-output display leases retain their existing independent retirement policy. Terminal GPU callbacks run on the camera executor and reject obsolete camera-generation/pipeline identities. Start-failure/timeout cancellation and native resource-failure qualification remain separate gates.


## Silent timelapse pause (revision 7)

The first qualified pause route is silent GPU timelapse. Pause changes the GL-owned selector, never the camera graph or MediaMuxer identity. Resume restarts interval phase on its first new image; absolute project frame indexing remains continuous and intentional idle time is not counted as missed capture. A monotonic command receipt clock measures active capture duration and records each boundary with the next project frame index. Stop seals that clock before asynchronous resource retirement. The `.timing.json` sidecar participates in the existing compensating output publication transaction.

The live owner advertises support after preparation, rather than the mode enum enabling a speculative control. The service awaits command acknowledgment and exposes paused state to operator/self/subject UI. Duration limits use active capture time, frame limits still use submitted images, and pending structural settings remain frozen for the current take. Settings explain these policies; pause is an action rather than a persisted preference. This does not map sensor epochs to PCM clocks or qualify VIDEO/LOG/audio pause, project timecode, editor interchange or physical folds.


Pause UI captures the originating take ID and existing CaptureActionTicket. The binder rejects other takes, display roles or capture generations before dispatch; the GL command also retains and checks its Recording identity. The active clock begins after actual codec startup/onStarted, not native preparation. Compact controls keep a full spoken description, with a glyph when a label would split across a narrow column. These guards do not replace physical role-transfer qualification.


## Capture epoch correspondence (revision 8)

A REALTIME camera timestamp can be compared with elapsedRealtimeNanos; an UNKNOWN camera timestamp has no guaranteed relation to other subsystems. AudioRecord's BOOTTIME timestamp pairs a client frame position with its estimated capture time, rather than the time an application read returns. The renderer therefore receives the actual Camera2 timestamp-source flag, and the AAC feeder calibrates client PCM frame zero from these pairs. Sources: [CameraCharacteristics timestamp source](https://developer.android.com/reference/android/hardware/camera2/CameraCharacteristics#SENSOR_INFO_TIMESTAMP_SOURCE) and [AudioTimestamp](https://developer.android.com/reference/android/media/AudioTimestamp).

The chosen PCM frame-zero anchor stays fixed for the take; later timestamp observations report maximum residual against nominal client-rate progression. Regressions fail capture rather than shifting the file timeline. When a successful PCM read observes 500 ms without a timestamp, the recorder explicitly labels its start-receipt estimate. This observation budget does not bound a native read that has not returned. Unknown camera epochs are not compared to audio clocks. Neither estimate is promoted to waveform verification.

Comparable input origins replace independent zeroing at the mux boundary. The codec's first emitted PTS establishes only its local output epoch; subsequent deltas are restored to the captured track-start offset. This preserves start correspondence but does not compensate unadvertised codec priming. The pending queue waits for formats and capture anchors before publication, retains per-track order and normalizes before cross-track sorting. Silent timelapse/off-speed keep their existing project timelines. Requested AAC requires real submitted PCM and encoded payload.

`CaptureEpochReport` is persisted by the same JSON serializer for VIDEO `.timing.json` and LOG provenance, recording both capture and codec observations. `waveformAlignmentVerified` stays false until marker/physical measurements prove it. The test-only encoder selection seam exercises real software AVC + AAC without changing the app's hardware-selection gate. General pause needs a shared capture-domain pause journal, separate consumed/submitted PCM counters and sample trimming at boundaries; those are not implemented by merely preserving initial offsets.


The encoder-delay format key is read only on API30+, where it is public. Final emulator replay preserves a 399833 us capture offset as 399800 us in the MP4, but decodes 52224 of 52661 submitted PCM frames. The difference must be investigated with identifiable first/tail markers; neither successful decoding nor a shared timestamp origin proves complete audio content. These measurements remain explicit in the evidence and the waveform-verification field remains false.


## AAC waveform/tail diagnosis (revision 9; production correction still open)

The 437-frame count deficit in the shared-epoch checkpoint was not harmless padding. A new real MediaCodec/MediaMuxer probe encodes known swept PCM, retains exact source bytes and emitted packet records, and independently decodes every file with FFmpeg. Its matrix covers 48 kHz mono (52661 and exact-block 52224 source frames), 44.1 kHz stereo (52661 frames), EOS on the last data buffer versus a separate empty buffer, and zero versus 4096 explicitly experimental trailing zero frames. The zeros exist only in the test, not in production audio or captured-source counts.

On emulator-5680 / API30 / c2.android.aac.encoder, both legal EOS forms produce the same result. Without experimental zeros, the decoded signal lags the source by 2048 frames and loses 2485 source-tail frames for 52661-frame inputs, or 2048 for the exact-block input. The decoder's 52224-frame result includes the leading delay; it is not 52224 preserved source frames. AAC output packet counts equal independently extracted MP4 packet counts, so the diagnostic locates the missing content before a supposed mux-packet discard. This is measured decoded-waveform alignment, not a microphone-latency calibration or a claim that every vendor encoder behaves alike.

With 4096 experimental zeros, all six matrix files preserve the full known source at the measured 2048-frame lag, with channel correlations above 0.98. They still contain 1611 or 2048 trailing decoded frames and retain the leading delay: they are not gapless or correct A/V outputs. Production must not adopt a hard-coded universal priming value or silently add zeros merely to pass a packet-count check.

Reusable evidence: app/src/androidTest/java/com/librestatic/opencinecam/AacTailProbeTest.kt; tools/analyze_aac_tail.py and its tests; build/implementation-h4-aac-tail/VERIFICATION.txt. The analyzer distinguishes matched source coverage from gapless trimming, detects missing starts/tails even when total lengths agree, checks channels separately and offers an explicit --require-source-coverage gate. That gate fails the unpadded matrix (exit 2); successful diagnostic collection is not successful audio qualification.

Next implementation must couple explicit codec drain/source-frame accounting to verified priming and container start/end trimming, preserve capture-domain A/V offsets, and test short/partial/exact-block takes, rates/channels, actual service finalization and independent playback. A matching observed codec/configuration needs a measured qualification policy; a vendor name alone or one emulator probe is not a portable delay contract. General A/V pause still waits on correct source sample boundaries and complete terminal audio. H1–H5 remains InProgress and no final APK is produced.

The Android MediaCodec API permits EOS on the final nonempty input or a separate empty input and requires draining to output EOS ([MediaCodec](https://developer.android.com/reference/android/media/MediaCodec)). The retained Android 11 reference C2 AAC source has an EOS flush path conditional on remaining input bytes; this is a plausible mechanism, not proof of the emulator binary's exact provenance ([Android 11 reference implementation](https://android.googlesource.com/platform/frameworks/av/+/refs/tags/android-11.0.0_r48/media/codec2/components/aac/C2SoftAacEnc.cpp)). The native waveform/packet results, rather than that source inference, determine the next corrective work.


## Explicit AAC source-window finalization (revision 10; automatic production integration open)

`AacSourceWindow` separates captured source frames, verified priming frames, expected AAC packet count and capture-domain presentation offset. `finalizeAacSourceWindow` validates the AAC-LC description, rate/channels, sample counts/durations and chunk ranges before any write. A truncated input is rejected rather than given metadata claiming the missing source exists. The caller must supply a configuration-qualified priming measurement; the function does not guess one.

The finalizer builds a replacement moov, keeps all mdat bytes and chunk offsets intact, and writes explicit roll sample groups plus a source-window edit list. A common movie timescale represents source samples, existing video movie ticks and the requested microsecond offset exactly. Version-1 movie/track/edit durations prevent 32-bit duration overflow. Video media time and composition/sample tables stay unchanged; existing video edit durations are rescaled without changing their meaning. The new moov is appended and read back before the old moov becomes free. Failed writes invalidate the pending take; this is not crash-atomic publication.

Native AAC fixtures cover six complete, explicitly padded test inputs and six known truncated inputs. The fixture's 2048-frame priming parameter comes from the previous waveform matrix, not a new production default. Complete files receive exact source windows at zero or 250123 us presentation offset. All truncated files remain byte-identical after rejection. Independent decoding of the edited audio removes the measured initial lag and retains the known source; partial-block files still expose 587 extra frames in raw FFmpeg PCM output. Decoding clipped explicitly to the verified edit duration produces exactly 52661 source frames; exact-block fixtures produce 52224. These are separate observations, not a claim that every reader clips raw decoder buffers automatically.

API30 MediaExtractor still reports encoded media duration and pre-roll sample timestamps. FFprobe's stream duration also differs for delayed edits even though the inspected edit-list duration is exact. Therefore automatic priming/drain integration, player/editor interpretation, raw-decoder clipping and physical A/V qualification remain required. The helper is exercised on actual files but is not automatically invoked by the recording service yet; production has no silent padding or guessed delay.

The native A/V fixture uses actual GLES/Surface AVC frames and AAC, remuxes the tracks with a delayed audio origin, then finalizes the source window. Its acceptance requires independently unchanged video packet payloads, PTS and durations, unchanged mdat, zero audio signal lag and preservation of the audio presentation offset. The initial byte-buffer AVC experiment found no matching regular codec and then failed configuration; those failures are retained. The fixture now reuses the existing working GLES recording path rather than assuming a PCM-like video input layout.

Evidence and independent rollback: `build/implementation-h4-aac-window/VERIFICATION.txt`. Full H1–H5 stays InProgress; the next production work couples measured codec qualification, explicit drain/source accounting, this source-window finalizer and metadata/player handling before general synchronized pause. Separate WAV/FLAC epochs, timecode, remaining H3 monitoring, H4 capture/media and H5 integrations and physical/editor gates retain their full scope. No final APK.

The explicit edit and roll-group representation follows the primary [QuickTime/MP4 AAC track-structure description](https://developer.apple.com/documentation/quicktime-file-format/using_track_structures_to_represent_encode_delay_explictly). Movie-timescale precision and actual reader behavior are tested separately; a correct declared source interval is not proof that a raw decoder applies its end boundary. The current [FFmpeg MOV reader source](https://www.ffmpeg.org/doxygen/8.0/mov_8c_source.html) is useful context, while the retained file/PCM reports establish behavior of the installed FFmpeg version.

The real delayed A/V fixture exposed an additional muxer-header discrepancy: the audio stts represents 56320 frames, but mdhd declares 68326 (including a 12006-frame initial placement). The finalizer now derives encoded media duration from validated AAC sample tables and writes that duration separately from the new placement edit. A focused host regression covers the retained failing file's values. The original failed native report and original MP4 are retained; this is a correction to file accounting rather than a relaxation of source-coverage requirements.


## Revision 11: measured AAC configuration, explicit drain and recording integration

Production uses the same format-specific AAC encoder selection for calibration and recording. A process-local five-minute cache stores exact-configuration measurements from two known-signal encode/decode boundaries, not a universal codec-name delay. Both measurements must preserve the complete signal on every channel and agree on priming and codec-specific bytes. Actual recording CSD is hash-checked before its track is accepted. Synthetic calibration PCM never enters AudioRecord or the saved take.

Captured frames, codec-only drain and encoded packets remain separate quantities. AAC retirement queues the qualified drain under its existing deadline, then emits EOS at source-plus-drain time. Muxer completion automatically invokes the validated source-window finalizer with measured priming, actual source count and shared capture placement. Timing/provenance disclose calibration and source-window policy, retain waveformAlignmentVerified=false and automaticReaderClippingVerified=false, and do not claim physical lip sync.

The read-only source-window inspector checks actual file edits/roll groups instead of inferring presentation origin from seek-dependent MediaExtractor samples. Independent raw packet counts use ffprobe with edit-list processing disabled; decoded start correction, raw final-block remainder and explicit presentation clipping are distinct results. Tests cover controlled audio-first/video-first/unknown-epoch recordings and independent native/FFmpeg known-signal comparison.

Cancellation checks run between preparation stages and within calibration loops; native-call hangs and the generic committed-start timeout race remain unqualified. Full playback/editor, physical A/V, general pause and remaining H1–H5 work stay open. Evidence: build/implementation-h4-aac-calibration/VERIFICATION.txt; implementation/acceptance detail: PLAN-066 revision 11.


## Revision 12: start commit is distinct from waiting for the GL task

A caller observation timeout is not a native-owner termination event. RecordingPreparation supplies one terminal commit/cancel decision; the caller returns the winning result rather than revoking an already committed take. A drain worker is started before commit and waits for that decision, so start failure/cancellation cannot race direct cleanup with native draining. External start callbacks happen after commit and do not change an accepted start into a contradictory preparation failure merely by running longer than the observation budget.

A per-pipeline preparation lease lasts through actual GL cleanup; descriptors are duplicated before queuing and retained until cancellation cleanup or committed recording retirement. Close cancels a pending decision, while committed takes retain normal stop/retirement. There is no forced release under a still-running native call. Native hangs and process-global arbitration across independent engines remain distinct requirements. The pure concurrent decision tests and two real GLES blocked-preparation/blocked-callback probes are recorded in PLAN-066 revision12 and build/implementation-h4-start-commit/VERIFICATION.txt.

The separation follows the documented distinction between waiting with a timeout and cancellation in [FutureTask](https://docs.oracle.com/en/java/javase/26/docs/api/java.base/java/util/concurrent/FutureTask.html). The application-specific commit/ownership contract is established by the actual implementation and tests, not inferred from timeout alone.


An additional real test reproduced an admission gap after native preparation cleanup but before delivery of the old caller's failure: an overlapping new start was accepted. The failed assertion/report is retained in caller-retirement-before-results. The preparation lease now requires both ownerFinished and callerFinished before admission reopens; timeout alone does not retire native work, and native cleanup alone does not retire pending result/failure delivery. Three additional pure tests exercise both completion orders and 100 concurrent retirement races. The native regression blocks the actual caller failure callback, requires immediate replacement rejection, releases it, then verifies a new start is accepted. This closes a reproduced stale-failure/new-start overlap, not every engine/service generation race.


## Revision 13: shared capture sample windows

Embedded AAC/video pause is a source-domain operation before encoding. CaptureEpochClock admits it only for actual comparable camera/PCM anchors and serializes decisions against both producer horizons. Boundaries use the PCM sample grid and never move already classified audio or video. Half-open source-frame intervals drive both whole-frame PCM selection and exact-duration video PTS subtraction; stop also cuts a source boundary. Initial track placement remains the original shared capture offset.

A bounded direct PCM buffer decouples continuous microphone reads/metering from codec input availability during pause. Only retained frames enter AAC; measured drain remains separate. Publication checks retained-versus-submitted accounting and uses the existing measured AAC source-window finalizer. Metadata discloses read/retained counts, source intervals, stop frame, last command/effective boundary and policy. Unknown/estimated clocks and separate/off-speed audio remain unqualified rather than assuming receipt time is sensor time.

Existing pause UI actions reuse take/role identity with an explicit shared-versus-timelapse status policy. Operational elapsed command time is not relabeled as exact sensor duration. Physical source timestamps, microphone latency/drift, reader/editor clipping and separate audio mapping remain additional acceptance gates. Tests and reversible evidence: PLAN-066 revision13; build/implementation-h4-shared-pause/VERIFICATION.txt.

The microphone read and timestamp contracts are documented by [AudioRecord](https://developer.android.com/reference/android/media/AudioRecord). The sample-grid interval policy is application behavior verified independently of that API contract; an API timestamp alone does not establish physical waveform alignment.


Final shared-pause regression passed 469 Android unit tests (160 app +309 camera), 55 tools tests and 138 instrumented cases on emulator-5680; app/camera lint reports no errors or new warnings. Independent replay passed both actual AudioRecord/AAC/GLES cases. Repeated pause retained63831 of117027 captured PCM frames; stop while paused retained60074 of130924. Every encoded video PTS matches an ordered submitted source timestamp outside the exact shared cut windows. Both resume joins in each recording are34.72–35.15ms; maximum initial video gaps176.856ms and229.5ms remain disclosed separately. Terminal audio/video differences are2.78ms and44.22ms in these controlled fixtures, not physical lip-sync qualification.

Independent FFmpeg decoding is clean. Explicit source-window clipping yields exactly63831/60074 retained frames; raw decoder final blocks expose681/342 extra frames, so automatic player clipping remains unqualified. Preview analysis and microphone meters remain live during pause, and stopping during a third pause seals the source window without publishing excluded PCM. Full evidence, original hashes and independently tested rollback are recorded in build/implementation-h4-shared-pause/VERIFICATION.txt. Full H1–H5 remains ACTIVE; separate WAV/FLAC timing/pause, service/UI and physical/editor qualification, timecode and remaining capture/integration requirements remain open. No final APK.


## Separate WAV/FLAC source epochs (revision 14)

Lossless sidecars now distinguish start/stop command receipts from an actual AudioRecord BOOTTIME source-frame epoch. The PCM writer/feeder samples AudioTimestamp after reads, establishes frame zero from the first successful source observation, and measures later residuals without moving that origin. Missing timestamps remain explicitly unavailable; no scheduling receipt or System.nanoTime anchor substitutes for source capture. The metadata records first/last timestamp observations, unavailable-observation count, actual sample rate, interleaved frame size, captured/written frames and exact rational source duration. Existing sidecar schema fields remain; captureTiming is additive.

Both writers reject actual PCM format mismatches and partial interleaved reads, and require captured/written/container frame accounting to agree before successful publication. WAV buffers and FLAC codec reads request complete interleaved frames, including stereo and float WAV. Standalone FLAC codec PTS now start at source frame zero and advance by exact source-frame duration rather than a feeder scheduling-time anchor. The existing retained native ownership, failed-output cleanup and paired-take compensation still apply. No new preference can replace a missing source timestamp: format/rate/channels remain configurable through the existing audio settings.

Ten pure tests exercise unknown/delayed source anchors, fixed-origin residuals, rational-rate stereo float and packed24 frame accounting, partial writes and regression rejection. Five native cases record actual WAV mono48k, WAV stereo44.1k, WAV float stereo44.1k and FLAC mono48k/stereo44.1k. They reopen published metadata and cache actual files for independent header/STREAMINFO and FFmpeg frame-count verification. Results and independently tested rollback are recorded after execution in build/implementation-h4-sidecar-epoch/VERIFICATION.txt.

This makes the separate audio source epoch observable; it does not yet apply video placement, shared separate-audio pause, hardware-overrun detection or per-take waveform alignment. captureTiming explicitly reports videoAlignmentApplied=false and waveformAlignmentVerified=false. Camera/video linkage and coordinated lossless pause remain next integration work, with off-speed/timecode, player/editor/physical qualification and all remaining H1–H5 requirements retained. Full goal remains ACTIVE; no final APK.

API reference: https://developer.android.com/reference/android/media/AudioRecord


Independent five-case replay passed on emulator-5680. WAV48k mono retained and decoded59244 frames; WAV44.1k stereo PCM16 and float each retained and decoded55104; FLAC48k mono retained and decoded59244; FLAC44.1k stereo retained and decoded54432. All files match their actual WAV/STREAMINFO frame counts and metadata sample rates. Source clocks obtained14–21 successful timestamp observations per take; measured maximum residuals were10.073–25.467microseconds in these short emulator captures. These observations are not a physical drift or microphone waveform qualification. Original published metadata, file hashes and FFmpeg commands/results are retained in build/implementation-h4-sidecar-epoch.


Final selected regression passed479 Android unit tests (160 app +319 camera),143 instrumented cases and app/camera lint with zero errors. UTP identifies emulator-5680. The full run completed in16m27s; no timeout was treated as terminal and the source stayed frozen throughout. Separate original/modified/rollback host results and restored original hashes are retained in build/implementation-h4-sidecar-epoch/VERIFICATION.txt. Full H1–H5 remains ACTIVE, with separate audio/video linkage and coordinated pause still open.


## Shared VIDEO/LOG and separate lossless pause (revision 15)

VIDEO and LOG now pass a fresh per-take CaptureEpochClock to either embedded AAC or the separate WAV/FLAC recorder, never both. The separate route requires matching camera timestamp provenance and regular capture (not timelapse/off-speed). All existing/new GPU graph entry points forward the same clock. A standalone-video muxer does not wait for an external microphone timestamp or shift its zero-based file PTS: the shared source clock governs cuts, while each file keeps its own local timeline. Unknown or missing audio/camera anchors therefore do not stall muxing or falsely expose shared pause.

WAV and FLAC select the same half-open PCM source windows that map video input. PCM compaction preserves complete interleaved PCM16, packed24 and float frames byte-for-byte, validates all spans before writing, and preserves the no-cut path. Audio meters/effects still receive live source data. FLAC reads into a bounded reusable source buffer independently of codec input availability, then submits only retained complete frames, split to actual codec capacity. A fully paused read queues no empty audio packet. Stop seals the shared source boundary on the GL owner; later lossless reads exclude samples beyond that boundary while the existing native retirement/publication protocol finishes.

AudioSidecarRecordingResult/captureTiming retain captured versus written counts. Successful external-clock publication requires retained source selection to equal actual written/submitted frames, not captured frames including pauses. The audio JSON stores the final sharedTiming after actual audio retirement. CaptureService refreshes VIDEO timing and LOG provenance from that same final clock after audio completion, keeping paired-output cleanup if either file fails. audioStorage distinguishes separate WAV/FLAC from embedded AAC; codecInputFrames is null for separate files, and sourceAudioMinusVideoNs is populated only for actual comparable source anchors. This is source-offset metadata, not automatic editor file placement or measured microphone waveform alignment.

Settings → recording/project timing explains shared pause for embedded AAC and separate WAV/FLAC, live meters and the need for explicit editor alignment. The existing configurable audio format/rate/depth/channel intent selects this route; runtime clock availability is not force-enabled by a new switch. Operator/self/exterior pause controls retain their existing take/role acknowledgement guards.

Six new pure cases exercise bit-preserving stereo float/packed24 compaction, complete validation before mutation, empty pause output and rational source-grid/terminal cuts. Five native cases pair actual GLES video with real WAV/FLAC: repeated pause, stop while paused with stereo float WAV or stereo FLAC, and unknown camera clock without shared pause. Published audio metadata and both files are reopened for independent decoded PCM counts, header/STREAMINFO checks and every-video-PTS source-window mapping. The first compile rejected Kotlin cross-module nullable smart casts in the offset serializer; explicit checked reads correct this and preserve the failed compile evidence.

Evidence: build/implementation-h4-lossless-pause/VERIFICATION.txt. Physical sensor/microphone timing, per-device cadence/drift, service/UI/editor acceptance, silent regular VIDEO/off-speed policy, timecode and the rest of H1–H5 remain open. Full goal stays ACTIVE; no final APK.


The second fixture compile required the existing explicit pause-completion callback in the unknown-clock rejection case. The corrected fixture supplies it; both failed compile logs remain retained. Final host/assembly completed successfully before actual media replay, with485 unit tests (160 app +325 camera).


The first independent inspector rejected a null-output FFmpeg muxer warning on the float-WAV paired video: its default output timebase quantized two distinct input DTS to the same tick. The original MP4 has38 strictly increasing PTS/DTS at1/90000 timebase with minimum separation3097ticks. The same bytes decode cleanly with -err_detect explode -fps_mode passthrough -enc_time_base demux. The retained first-inspect-evidence includes the failed command/file/log and the clean same-file diagnostic. The inspector now preserves the input timebase, separately requires strictly increasing packet DTS/PTS and matches every video PTS to the actual input source/window mapping; no production timestamps are rewritten to hide the diagnostic. FFmpeg option contract: https://www.ffmpeg.org/ffmpeg.html


Independent same-file inspection passed all five paired takes after preserving the decoder timebase. Repeated WAV/FLAC pause retained73449/66441 PCM frames and excluded60401/60094; stereo float WAV/stereo FLAC stopped while paused retained66560/68837 and excluded72544/70939. Independent decoding returns exactly each retained count. Every video PTS maps to actual submitted source input outside the same pause/stop windows. The two resume joins per known-clock take measure34.81–42.75ms; terminal A/V differences after applying the recorded source offset are below9ms in these controlled fixtures. Initial video gaps169–310ms remain separately disclosed, not constant-camera-cadence acceptance. Unknown-clock WAV retains58513 frames, exposes no shared pause and keeps sourceAudioMinusVideoNs=null. Preview analysis/meters remain live. Physical waveform alignment and automatic editor placement remain unverified.


Final selected regression passed485 Android unit tests (160 app +325 camera),148 instrumented cases and app/camera lint with no errors or new warnings. UTP identifies emulator-5680; the frozen-source full run completed in10m14s. The complete148-case selection includes existing audio retirement/paired-output failure tests, embedded-AAC pause and the five new lossless-pause cases. This confirms the tested native/media paths, not unexecuted physical or editor gates. Independent original/modified/rollback host results, file hashes and all514 restored original bytes are recorded in build/implementation-h4-lossless-pause/VERIFICATION.txt. Full H1–H5 remains ACTIVE; no final APK.


## Timecode arithmetic and valid local labels (revision 16)

A retained failing regression proves that the previous inverse conversion mapped01:00:00;00 at29.97DF to108000 instead of107892 timecode-frame ordinals. SmpteTimecode.toTotalFrames now subtracts omitted DF labels and rejects skipped labels, mismatched DF flags and frame numbers outside the nominal rate. Inverse conversion wraps at the correct DF/NDF24-hour frame count before doing arithmetic, including negative and Long-limit offsets without overflow. Canonical labels use ASCII digits independently of the device locale.

TimecodeRate now exposes exact numerator/denominator for the existing integer NDF and29.97/59.94DF choices, validates supported combinations and counts elapsed nanoseconds with exact rational arithmetic. frameDurationUs remains a truncated legacy single-frame value, no longer an accumulator. TimecodeTracker FREE_RUN uses an injectable BOOTTIME nanosecond clock and exact elapsed-frame conversion; an observed clip frame index is no longer added a second time to an already elapsed FREE_RUN count. Invalid start labels fail before changing tracker configuration; unchanged configuration preserves its anchor.

Local persisted timecode values are normalized together: unsupported DF/rate combinations are repaired, frame bounds follow the chosen nominal rate and skipped DF labels advance to the first valid frame of that minute. Settings rate/DF controls use the same normalization, including60→24 frame-bound changes. Presets remain strict: their existing canonical snapshot comparison rejects malformed/skipped labels rather than silently importing repaired values. No preset schema or new rate preference was added.

An independent host oracle calls public FFmpeg libavutil timecode functions to generate11535 forward/inverse vectors across all seven existing rates, minute/hour/day boundaries and large positive offsets. The production Kotlin tests consume those stored vectors; the generator can independently reproduce their exact bytes. Two actual FFmpeg reference MOV files contain tmcd sample ordinals107892/215784 for01:00:00;00 at30000/1001 and60000/1001. These are independent reference files, not timecode tracks emitted by OpenCineCam. Sixteen new camera tests (including the initially failing inverse regression) and five app settings/preset tests bring host acceptance to506 unit tests (165 app +341 camera).

This corrects arithmetic/configuration and the existing FREE_RUN display clock. CaptureService currently configures the tracker and queries FREE_RUN display only: RECORD_RUN/REGEN frame/lifecycle calls remain unwired, and actual per-take timecode freezing, pause/source/project mapping, fractional NDF choices and interoperable app-written timecode tracks remain required. The reference tmcd files do not complete those requirements or external timecode/genlock qualification. Full H1–H5 remains ACTIVE; no final APK.

Evidence: build/implementation-h4-timecode-arithmetic/VERIFICATION.txt. Public oracle contracts: https://www.ffmpeg.org/doxygen/8.0/timecode_8h_source.html and https://www.ffmpeg.org/doxygen/8.0/timecode_8c_source.html


The first full regression passed148 existing native cases but lint correctly rejected BigInteger.longValueExact as API31-only with min29. This exposed a platform gap that JVM-only timecode tests did not prove. The implementation now checks bitLength before the longstanding toLong conversion. Two new instrumented cases execute exact DF counts, Long-limit arithmetic and FREE_RUN hour behavior on actual API30 platform classes. Original failed lint/build evidence remains under acceptance-before-api-fix; the final selection includes150 native cases.


Final frozen-source regression completed successfully in9m5s:506 Android unit tests (165 app +341 camera),150 instrumented cases including both new API30 timecode executions, and app/camera lint without errors or new warnings. The independent11535-vector oracle remains byte-identical, and the reference tmcd samples retain107892/215784 at the one-hour DF labels. The failed original inverse regression and failed API31-only lint run remain preserved. Byte-for-byte rollback of all516 original files and separate original/modified/restored test results are recorded in build/implementation-h4-timecode-arithmetic/VERIFICATION.txt. Full H1–H5 stays ACTIVE. Next timecode work must connect actual encoded-frame progress and per-take lifecycle/configuration to RECORD_RUN/REGEN and final file metadata/tracks; the corrected arithmetic alone does not complete that integration. No final APK.
