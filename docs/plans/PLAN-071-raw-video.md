---
plan_id: OCC-PLAN-071
title: "RAW video: qualified RAW10 takes in OCRAW, with a RAM ring and optional lossless compression"
status: InProgress
revision: 1
milestone: H2
intended_executor: Claude Code
execution_mode: implementation
depends_on:
  - OCC-PLAN-040
  - OCC-PLAN-041
  - OCC-PLAN-042
blocks: []
requirements:
  - OCC-RAW-001
  - OCC-CAM-001
adrs:
  - ADR-0013
  - ADR-0019
  - ADR-0020
  - ADR-0021
risks:
  - RISK-001
  - RISK-004
  - RISK-009
estimated_sessions: 10
expected_repo_state: buildable
created_by: Claude Code
---

# OCC-PLAN-071: RAW video

## 1. Objective

Make `CaptureMode.RAW_VIDEO` real. A take records the public Camera2 RAW10 stream into an OpenCine RAW v1 (`.ocraw`) file. A RAM ring absorbs short storage stalls. The mode offers only configurations this device has qualified: camera, crop, frame rate and encoding. Phase 2 adds optional lossless compression, decided by measuring two codecs on the device.

## 2. Evidence (Galaxy S24 Ultra, 2026-11-02, `RawVideoProbeDeviceTest`)

- **RAW10 is advertised only at full sensor size:** 4080x3060 on camera 0, 4000x3000 on cameras 1 and 2, 3392x2544 on camera 3.
- **The HAL delivers more than it advertises.** `minFrameDuration` claims 30 fps, yet the HAL delivers 60 fps at full size with zero drops. Asking for 120 fps clamps to about 59. Constrained high-speed sessions refuse RAW.
- **RAW10 also runs with the production graph.** RAW10 plus a 1080p preview plus a 640x480 YUV stream configures and runs at 30 and 60 fps.
- **Storage, with zero-copy `FileChannel` writes and a force every 256 MB:**

  | Target | Median | p01 |
  |---|---|---|
  | App dir | 1363 MB/s | 598 MB/s |
  | MediaStore Movies | 1807 MB/s | 595 MB/s |
  | MediaStore Documents | 1499 MB/s | 546 MB/s |

  The dips last single seconds. The certification's 345 MB/s came from `RawThroughputDeviceTest`'s own design: a per-frame heap copy on the camera thread, `RandomAccessFile`, a 64 MB ring and no force until the end.
- **Data rates at 1.25 B/px:**

  | Config | MB/s |
  |---|---|
  | 4080x3060 @15 | 234 |
  | 4080x3060 @24 | 375 |
  | 4080x3060 @30 | 468 |
  | 4080x3060 @60 | 936 |
  | 3840x2160 centre crop @30 | 311 |
  | 1920x1080 centre crop @30 | 78 |
- **Lossless ratio on real frames:**

  | Method | Ratio | ms per frame, one core |
  |---|---|---|
  | Deflate-1 | 1.38× | 275 |
  | Bayer same-colour delta block-pack, naive Kotlin | 1.72× | 340 |
  | Shannon bound of that predictor | 1.88× | — |

## 3. Decisions

1. **Uncompressed RAW10 is the qualified baseline.** The long runs and the camera checks judge the uncompressed configurations. Since protocol 3 an LJ92 configuration is judged on its own, so it can be ready when its uncompressed twin is only risky (the Razr's full@60). It needs all of these:
   - its twin's camera delivered (a pass, or a failure only the storage explains);
   - the encode benchmark that judges it keeps up with the pixel rate × 1.1225, and the camera held that benchmark's rate while it encoded (see decision 9, LJ92 benchmark);
   - a passing long run covers the compressed rate at a *conservative* ratio: the benchmark's worst single frame, rounded down to a tenth.

   Since 2026-11-03 the heaviest such twins (at most three) instead run a long run of their own with the encoder active and are judged from it (`compressed-long-run`, decision 9); the conditions above remain the fallback for the rest. The ratio falls with ISO and scene detail, so the aggregate of one scene is never counted on. The time-left estimate still assumes 1.8×.
2. **Frame dropping is forbidden (ADR-0019).** When the ring passes its high-water mark (90 %), the take stops cleanly: the file is finalised and a stable reason is shown. This holds in both Strict and Adaptive.
3. **The ring copies.** The camera callback copies each image's rows (stride padding removed, crop applied) into a preallocated direct `ByteBuffer` slot and closes the image at once. One writer thread drains the slots to a `FileChannel` with gather writes (chunk header plus payload, no further copies) and forces every 256 MB.
4. **The ring size is chosen at arm time** from `ActivityManager.MemoryInfo`: the default is 4 GiB on a device reporting at least 14 GiB of total RAM (16 GB phones), 2 GiB from 11 GiB (12 GB phones), otherwise min(1 GiB, 1/8 of total RAM), and it stays below `availMem` minus the low-memory threshold. The size used is part of each qualification record.
5. **A crop reduces only what is written.** The sensor still reads out full size, so a crop is a narrower field of view. It is labelled that way.
6. **Module boundaries** (`:media` depends on `:camera`):
   - `:camera` gets the RAW10 stream and a `RawVideoFrameConsumer` interface.
   - `:media` gets the ring, the streaming writer, the reader and recovery.
   - `:app` wires them together and owns output rows, the qualification store and the UI.
7. **Destination:** `Documents/OpenCineCam/RAW/<take>.ocraw` through `MediaStore.Files` with `application/octet-stream`, using the existing pending-row and recovery-group lifecycle. The probe measured it at a 1499 MB/s median.
8. **Format (ADR-0020):** the v1 header and chunk header stay unchanged. This plan pins down the RAW frame payload and adds one optional chunk, both in `docs/formats/opencine-raw-v1.md`:
   - `RAW_FRAME` (0x0010) payload: a 64-byte little-endian frame header, then the frame data. The header holds: frame header version, encoding (0 = RAW10 packed, as Android packs it, rows tight, no padding; 1 = LJ92 and 2 = Bayer block-pack, defined in the format document; the app writes 0, 1 or 2 (recordings use 2, block-pack, since 2026-11-03; takes recorded earlier hold 1), and a frame that does not compress smaller is stored as 0, so the encoding can change from frame to frame), width, height, row bytes, crop origin, sensor timestamp ns, frame number and reserved bytes.
   - `FRAME_METADATA` (0x0040), new and optional: a canonical CBOR text map for one frame, keyed by its sequence. It holds exposure ns, ISO, frame duration, neutral point, colour correction gains and the focus distance. It is written when the frame's `TotalCaptureResult` arrives. v1 readers skip it as an unknown chunk.
   - The first `METADATA` chunk holds the static camera data a DNG needs: CFA pattern, black and white levels, colour matrices 1/2, forward matrices, calibration illuminants, sensor size, active array, crop, fps, camera id, device model and app version.
9. **Qualification rule v2** (`RawThroughputBenchmark`): a configuration passes only when all of these hold:
   - **(a) storage sweep:** an unthrottled write of at least 60 s to the real destination (direct buffers, 8 MB writes, force every 256 MB), with a median of at least 1.1225× the stream rate (it was 1.25×; lowering it keeps every stored pass valid, so the protocol version did not change);
   - **(b) buffer simulation:** feed the stream rate against the sweep's per-second rates; the simulated backlog never exceeds 50 % of the ring;
   - **(c) live run:** the real camera, ring and writer at that configuration for at least 20 s, with zero lost frames (completed captures equal frames written), no overflow, and a peak ring occupancy of at most 50 %. The app's runs measure 40 s (80 s for a stream of 500 MB/s or more, where sustained writes slow down).

   A configuration that fails only for storage reasons (`sweep-median-margin-fail`, `buffer-backlog-exceeded`, `raw-frame-loss`, `ring-overflow`, `ring-peak-exceeded`) is *risky*: the app reads that from the stored reason codes, offers it by hand with a warning (it may stutter, lag or corrupt the take), and never picks it automatically. Any other reason (camera limit, heat, capture or writer faults, invalid sweep or live run) keeps it failed.

   A frame-rate shortfall is a *camera limit* (`camera-fps-limit`, shown as "Camera limit") only when the camera's declared minimum frame duration explains it. A run that sent the vendor frame-rate override (`qtiFrameRateOverride`, the Razr's `QTI_RAW_FRAME_RATE_OVERRIDE` quirk) asked past that declaration on purpose, so its shortfall stays an ordinary failure with `fps-not-reached`: on the Razr, 1920×1080@60 delivered 50–57 fps while UHD@60 and 4000×3000@60 delivered 59–60 on the same stream, which is not a limit of the camera.

   *Reduced-rate pass (2026-11-03).* A run, check or benchmark whose only camera problem is `fps-not-reached`, with a thermal peak of at most MODERATE (status 2), some frames delivered, no capture failure and no lost buffer, is not failed for it: `fps-not-reached` becomes the informational `fps-reduced`, and the configuration passes (or stays risky for its storage reasons alone). `camera-fps-limit`, `camera-no-frames`, capture failures, lost buffers and heat at SEVERE are never relaxed, and at status 3 a shortfall stays `fps-not-reached`. Every record stores the rate the camera achieved, `achievedFps`, to one decimal (from the RAM camera check when one ran, since the storage load can slow a long run; a compressed twin extrapolated from its benchmark takes its twin's). The field is optional, so older records load without it. A run reaches its target at 87.5 % of it or more (`FPS_TOLERANCE`, 95 % until 2026-11-04: up to a 12.5 % shortfall is accepted). Below 87.5 % of the target the record is a *reduced-rate* configuration: the sustained rule, the burst estimate and the encode benchmark's camera-held check use the stream at the achieved rate; rule v2's sweep margin, the encoder margin and the chooser's MB/s and minutes stay at the target rate, the conservative side. Automatic never picks a reduced-rate configuration; it is offered by hand. The qualification results show each ready row's rate ("59.9 fps") or, below target, "Reaches 51.9 fps"; the RES/FPS chooser and the Settings pills show the rate only below target ("full@60 · reaches 51.9 fps").

   *Runtime risk:* the RAM ring armed for a take can be smaller than the one the configuration was qualified with (the budget follows the free memory at arm time). Each time the ring is (re)armed, the service compares it with the configuration's newest current record (`rawVideoRingTooSmall`): it is risky when the larger of the live run's `peakRingBytes` and the simulated `maxBacklogBytes` passes 50 % of the armed ring, the same rule as (b) and (c); a record without either figure falls back to "armed ring smaller than the qualified one". A runtime-risky configuration shows exactly like a risky one (the capture notice while it is active, the tile and pill warning) for as long as that ring stays armed, but Automatic keeps choosing among the passed configurations.

   Each live run's report also carries `frameDiagnostics`, evidence that never changes a verdict: the inter-frame gap histogram against 1/fps (`upTo1_25x`, `upTo2_25x`, `upTo3_25x`, `larger`), `lateGaps` (past 1.5×) with the first 20 (`index`, `atMs`, `gapMs`) and their count per quarter of the measured window, the capture results' exposure, sensitivity and reported frame duration (min/median/max) with `exposureOverFrame`, the crop copy's p50/p99 and the reader thread's CPU cores.

   The old rule (p01 at least 1.25× the stream, no ring) stays available as `qualifyWithoutBuffer` for comparison and evidence.

   *Staged qualification (protocol 3, `RawQualificationStaging`).* Rule v2 runs where it says the most. Since 2026-11-04 the stages run in this order: the ring, the camera checks (3), the encode benchmarks (5), a 2 min storage rest, the storage sweep (1), the long runs (2) and the compressed twins' long runs; see *The camera measured cool* below.
   1. **Storage sweep**, as in (a).
   2. **Long runs** (rule v2 in full), on the uncompressed configurations only, heaviest stream first. The first pass stops the descent and covers every lighter rate. A failure only the storage explains makes that configuration risky; any other failure fails it, and the descent goes on. A configuration whose camera check proved the camera cannot deliver it gets no long run.
   3. **Camera check**, for every uncompressed configuration (until 2026-11-04, only those no long run judged): the camera and the RAM ring with no disk writes. The window is 8 s after a 2 s warm-up by default. It checks fps, gaps, capture failures, frames that never arrive and frame loss, with the same `cameraReasons`, QTI exemption and `frameDiagnostics` as the long runs. Such a configuration is ready only when its check passed *and* a passing long run of the same or a higher rate covers it (`method: extrapolated`, `coveredBy`). Its stored record runs rule v2 on the sweep at its own rate with no live run, minus `live-run-missing`, so the backlog simulation behind `rawVideoRingTooSmall` stays meaningful. A sweep too slow for the rate still fails it.
   4. **Heat:** SEVERE (`PowerManager` thermal status ≥ 3), sampled every second during a run, check or benchmark, fails it with `thermal-limit`, shown as "Too hot", except a compressed twin's own long run that fails for heat alone, which since 2026-11-05 takes the extrapolated verdict (see *A compressed run cut short by heat*).
   5. **LJ92 benchmark.** One session per configured thread count encodes every frame from the RAM ring, first on the heaviest configuration whose camera delivered. The defaults are the production 4 threads and 8 strips; the device test takes `rawLj92Threads` and `rawLj92Strips` to compare others. The sustained rate is the slowest quarter's mean encode time. Each session reports:
      - encode ms (mean, p95, max, per quarter);
      - sustained fps and pixels/s;
      - the aggregate and worst-frame ratios;
      - process CPU cores;
      - the thermal status and battery temperature;
      - dropped frames.

      Verdicts use the 4-thread entry when it was measured. LJ92 configurations follow decision 1 (`method: lj92-extrapolated`).

      *The camera must hold under the encode load.* On the Razr (camera 0, 4000×3000) the camera slows down while the encoder runs: the full@60 benchmark delivered 55.9 fps with 4 threads (encode 20.6 ms per frame, 47 fps sustained) and 43 fps with 8 threads (16.2 ms), yet the encoder figures alone would have passed UHD@60 (498 Mpx/s × 1.1225 = 559 < 567 measured) without anyone checking that the camera holds 60 there. So each benchmark session also records `cameraHeld` (the camera check's frame-rate and frame-loss criteria on its measured window, less the ring overflow, which there is the encoder falling behind) and adds `lj92-camera-starved` when it did not hold. When the camera was judged (the long or camera stage ran), the benchmark descends over the LJ92 configurations whose twin's camera delivered, heaviest first: a benchmark that holds the camera and keeps up with its own configuration stops the descent and judges it and every lighter one; one that does not fails its configuration (`lj92-camera-starved` and/or `lj92-encode-too-slow`) and every candidate its encoder is too slow for, and the descent benchmarks the next heaviest undecided one. A benchmark spoiled for another reason, or that measured nothing, stops it and judges the rest. At most three configurations are benchmarked; anything still undecided stays UNKNOWN with `lj92-bench-missing`. The estimate still plans one benchmark configuration and grows when the descent adds one. Each LJ92 record's `bench` evidence is the benchmark that judged it; the report lists every benchmark session.

      *Heat during the descent (2026-11-03).* On the Razr the full@60 benchmark heated the phone to thermal status 3; its `thermal-limit` stopped the descent and failed every lighter candidate as too hot, so no compressed twin got a long run. Two changes. A benchmark whose only reason is `thermal-limit` now fails only its own configuration and the descent goes on to the next heaviest undecided candidate, within the same three-configuration cap; when the cap ends the descent right after such a benchmark, the candidates still undecided take its figures and stay "Too hot" rather than `bench-missing`. And every encode benchmark session and every long run (uncompressed or a compressed twin's; since 2026-11-04) first cools down: it waits, showing the thermal-wait progress, until the status is below MODERATE (none or light), for at most 180 s (`COOL_WAIT_MS`), then runs anyway under the usual SEVERE rule. The first long run, right after the sweep, keeps the old rule so nothing lets the write cache recover; camera checks still wait up to 60 s for headroom below SEVERE. Each session's JSON records `thermalWaitMs` beside `thermalBefore`. The compressed long runs still go heaviest first.

      *Heat in the long runs (2026-11-04).* The next Razr run sent the descent's raw10 runs to SEVERE one after another: 3840×2160@60, full@30, full@24 and 3840×2160@30 failed with `thermal-limit`, so their twins' camera evidence was not `cameraOk` and none of their compressed twins was benchmarked or got a long run; and the sweep (96 GB, no cliff) took its long-run fallback rate, 354 MB/s, from the SEVERE-degraded 3840×2160@60, so full@30 and full@24 also got `sustained-rate-exceeded` although they kept up. Two changes. A run whose reasons are `thermal-limit` plus only storage or informational ones kept its rate (`RawStageResult.cameraDelivered`): that makes its compressed twin a benchmark candidate and eligible for a long run of its own, whose own thermal peak then judges its heat (a twin's `thermal-limit` never reaches the compressed run's verdict, and a storage-load shortfall is weighed against the twin's camera, heat aside); the uncompressed configuration's own verdict stays the thermal failure. And the long-run fallback ignores a run that fell behind at SEVERE (the heat may have slowed its writer), and never goes below a floor: the highest storage rate (`storageBytesPerSecond`, the stream at the achieved rate or a compressed run's measured rate) of a run after the first counted one that kept up. The sweep's `sustained` object lists each run's `thermalPeak` and `storageBytesPerSecond` and, with `long-runs`, adds `derivedFloorBytesPerSecond` and `floorFrom`. With that run's numbers the rate is 615 MB/s from full@60 (floor 450 MB/s from full@30), so full@30 and full@24 lose `sustained-rate-exceeded`.

      *An unrested write cache (2026-11-04).* A second Razr qualification 28 minutes after a full one (96 GB of sweep plus every long run) measured a sweep median of 198 MB/s against 1.16 GB/s rested: its first 30 windows swung between 15 and 675 MB/s, then it settled at the post-cache 270–300 MB/s. The SLC cache had not flushed, so every storage verdict after it would have been wrong and pessimistic, and the long runs only add heat and wear. Right after the burst windows the sweep now checks them (`RawStorageRest.judge`): they are unrested when their spread (median absolute deviation over the median) is above 0.15 (`erratic`; rested Razr sweeps 0.015 and 0.017, unrested 0.40; a first rule on p01 below 0.2× the median stopped a rested sweep for one slow window, p01 / median 0.18; with a cliff inside the burst windows each side of it is judged on its own, so a clean step is not noise), or when a rested reference exists and the median is below 0.6× it (`below-reference`). The reference is the best burst median a sweep judged rested measured on this build and destination, kept beside the records (`raw-storage-rest.tsv`) and only ever raised. An unrested sweep stops there (`stopReason: not-rested`, no sustained phase), and the qualification ends at once (before the ring until 2026-11-04; since then after the camera checks and benchmarks, whose rows stay in the report) with status UNKNOWN and `storage-not-rested`; it stores nothing, so the records in force stay. The sweep's `rest` object (`rested`, `rule`, `medianBytesPerSecond`, `p01BytesPerSecond`, `referenceBytesPerSecond`, `cliffWindow`, `worstSpread`, `ignored`) is written for every sweep. The step shows "Storage needs to rest" with the advice to leave the phone idle for about an hour, and says the previous qualification is kept when there is one. The device test takes `rawIgnoreRest true` to run on regardless and `rawRestReference` to set the reference (default: the app's stored one).

      *The camera measured cool (2026-11-04).* The long runs heated the Razr before the camera checks ran, so the camera was judged on a hot phone, and the sweep ran first on whatever rest the phone had had. The order is now inverted: the ring; a camera check of every uncompressed configuration, heaviest first, while the phone is cool (cool-downs and thermal waits between sessions as before, none before the first); the encode benchmarks, whose candidates come from those checks; a storage rest of 120 s (`STORAGE_REST_MS`, cancellable, shown as "Resting before the storage test") when a camera session ran and a sweep follows; the sweep, with no thermal wait after the rest; the long-run descent, heaviest first, stopping at the first pass and skipping a configuration whose check failed for the camera (`RawQualificationStaging.skipLongRun`: a FAIL that is not `cameraDelivered`; a passing, reduced-rate, skipped or heat-only check keeps the run); then the compressed twins' long runs, unchanged. The first long run still follows the sweep with no cool-down and no cool wait. The camera evidence of a configuration (`cameraEvidence`, which the benchmarks, the compressed long runs and the compressed verdicts read) is now its camera check when that judged it, else its long run; its achieved rate is the check's, which a hotter long run never overwrites. A configuration with a long run keeps that run's verdict (`method: long-run`), the storage-load reclassification weighing it against its earlier check without a new session; the others take their check's verdict, ready when a passing long run covers them. A long run that fails with `thermal-limit` or `fps-not-reached` while its cool check passed keeps the existing rules (risky only through the storage-load reclassification; no new pass rule). With several cameras, every camera's checks and benchmarks run first, then one rest and one sweep, then each camera's long runs. The estimate counts every check, the benchmarks, the rest, the sweep and every long run of the descent; the ring keeps its step count through the rest and the sweep. A sweep judged unrested now ends the run after the camera work: those rows stay in the report and nothing is stored.

      *Compressed twins get a long run of their own (2026-11-03).* After the benchmarks, a compressed configuration whose uncompressed twin's camera delivered (a pass, a reduced rate or a storage-only risk) and whose benchmark keeps up (no heat, the camera held, the encoder within its margin) runs a real long run with the encoder active: the qualifier's long-run session compresses each frame on the writer thread before writing it, as the recorder does, with the codec's default threads and strips, and writes an ordinary OCRAW stream. It is judged from its own evidence like any long run (`method: compressed-long-run`): rule v2 at the run's conservative ratio (its worst frame's, floored), its own camera reasons (a shortfall can be a reduced rate), the writer, the scan and the sustained rule at the bytes per second it stored. A shortfall under a ring overflow is weighed against the twin's camera evidence instead of a new RAM check. At most three twins run, heaviest stream first (`MAX_COMPRESSED_LONG_RUNS`); the step count and the time estimate grow when they do. The others, and any twin whose run was skipped (no space, no codec), keep the extrapolation (`compressed-extrapolated`). Since 2026-11-05 so does a twin whose own long run failed for heat alone (`thermal-limit`, with at most informational reasons; `RawQualificationStaging.compressedHeatOnly`): the verdict is `compressedVerdict` from its twin's camera evidence and its benchmark, with no extra pass rule, so it can still fail (encoder too slow, no covering run). The failed run's row stays in the report with `supersededByVerdict: true` and no longer counts toward the overall status; its record is dropped, and the extrapolated verdict's record (its evidence's `heatFallback`) and row (`infoCodes: ["compressed-heat-fallback"]`, `heatFallback`) replace them. `heatFallback` holds the run's status, reasons, `achievedFps`, `thermalBefore` and `thermalPeak`. The info code is never a failing reason, so a pass reads Ready. Heat with frame loss or any other reason stays the run's failure; uncompressed long runs are unchanged.

      *A compressed run cut short by heat (2026-11-05).* On the Razr, full@30+bp's own long run, the last session and so the hottest, failed with only `thermal-limit`, although its twin's cool camera check delivered 30.0 fps and its benchmark kept up. A compressed run the heat alone cuts short falls back to its twin and bench, since it runs last and hottest.

   *The SLC write cache (sweep sustained phase, `RawStorageCliff`).* Flash storage writes fast until its SLC cache is full, then falls to its sustained rate. On the Razr Fold (1 TB UFS, MediaStore Documents) the sweep wrote about 1.2 GB/s for about 56 s (about 66 GB), then its 1 s windows fell to 270–880 MB/s. The long runs follow the sweep immediately, so they wrote past the cache. Even full@30 (450 MB/s) then lost frames to ring overflow (fsync 250–300 ms), while full@24 (360 MB/s) passed. The day before, on a rested phone, full@60 wrote 900 MB/s. The outcome depended on how long the phone had idled and on whether the sweep happened to exhaust the cache. The sweep now always reaches the post-cache regime. After its 60 burst windows, which are the only ones rule v2 reads (median, p01, backlog), it keeps writing 1 s windows until two things are measured: the cliff, and 15 windows after it (`SUSTAINED_TAIL_WINDOWS`). It stops anyway after 90 extra seconds (`SUSTAINED_MAX_EXTRA_SECONDS`). On the Razr, where the cliff falls inside the first 60 windows, that is about 15 s more. The whole sweep, burst windows included, writes at most 96 GB (`SUSTAINED_MAX_TOTAL_BYTES`, `stopReason: max-bytes`; the burst windows always complete), so fast flash with a very large cache does not wear for nothing; with no cliff by then the sustained rate stays unknown from the sweep and `cacheBytesAtLeast` is what was written. On the Razr the capped sweep stopped twice that way (about 95 GB at 1.05 GB/s), yet the long runs right after it fell behind (3840×2160@60 lost 1671 frames writing about 578 MB/s, full@60 lost 687 at about 733 MB/s). So when the sweep found no cliff, a final pass after every long run (the compressed twins' included) takes the sustained rate from the long runs that fell behind the storage (`ring-overflow` or `ring-peak-exceeded`): the slowest rate a writer achieved among them (bytes written over the writer's write and force time, `writerBytesPerSecond` in each run's row), with the sweep's `cacheBytesAtLeast` as the cache, a lower bound (`RawStorageCliff.withLongRunFallback`). Every record is then judged again against it, the sustained rule and `burstSeconds` included (`rejudgeWithLongRunSustained`), and its row emitted again with `sustainedSource: long-runs`; a verdict judged with a real cliff never changes. The sweep's `sustained` object says `source: sweep` or `long-runs`, lists each long run's writer rate and whether it fell behind (`longRuns`), and with `long-runs` adds `derivedFrom`, `derivedSustainedBytesPerSecond` and `derivedCacheBytes`. If no run fell behind, nothing changes. An unbounded sweep ends the phase early (`stopReason: free-space`) rather than leave less than the 1 GiB reserve free. A rewinding sweep stays within its 4 GiB, so the disk estimate does not grow; the duration estimate adds the full 90 s. The cliff is found on the burst median: it is the first window from which the rolling 5-window median stays under 0.75× that median until the end. A single slow window is never a cliff. `cacheBytes` is what was written before the cliff, and the sustained rate is the median of the windows after it (at least 10 are needed, otherwise unknown). Without a cliff, `cacheBytesAtLeast` is everything written. The sweep JSON reports all of it in `sustained`, with every extra window in its `samples`. Nothing waits between the sweep and the first long run (no cool-down; until 2026-11-04 the ring allocation sat between them), so every long run is judged past the cache, as a long take would be. A risky record also stores `burstSeconds`: how long a take lasts on a rested phone before it drops frames. For a stream S above the sustained rate it is `cacheBytes / S + ring / (S − sustained)`, floored. For LJ92, S is the compressed rate at the conservative ratio, and the ring, which holds RAW10 frames, counts ring / ratio. It is null with no cliff or an unknown sustained rate. For the Razr's full@60 (936 MB/s, about 66 GB of cache) that is about 70 s plus the ring term. The risky card and the capture notice add "frames may drop after about 1 min (with a rested phone)". The field is optional, so older records still load without it. Two more rules follow. The sustained rule applies the same way to every verdict, raw or compressed, long run or extrapolated (2026-11-03): when the sweep detected a cliff and measured a sustained rate, a configuration whose storage rate exceeds it gets `sustained-rate-exceeded`, a storage reason, so it is risky with its `burstSeconds` rather than ready. The storage rate is the raw stream at the achieved rate, the bytes per second a compressed long run stored (its burst estimate uses the ratio it measured), or, for an extrapolated twin, the stream over the benchmark's conservative ratio. An unknown sustained rate adds nothing. So on the Razr (233 MB/s past the cache) raw full@24 at about 375 MB/s is risky even though its long run kept up for its whole length. Such a long run still stops the descent and covers lighter rates, each of which the rule judges again at its own rate. And a long run that overflowed its ring with `fps-not-reached` as its only camera reason is weighed against a RAM-only camera check of the same configuration (since 2026-11-04 the cool check that ran before it; until then a check right after it): if the camera holds there, `fps-not-reached` becomes the informational `fps-dropped-under-storage-load` (the camera slowed only under the storage load, in no failure set), so the verdict is risky with `burstSeconds`; if not, it stays FAIL. The long run's JSON keeps the check in `storageLoadCheck` (status, reasons, evidence, whether it reclassified).

   Each record stores `method`, `coveredBy` and the evidence JSON. The disk estimate covers only the sweep and the long runs. The device test's `rawStages` (`sweep,long,camera,lj92`) can run a subset, e.g. `camera` alone for a long RAM-only check (`rawCameraCheckSeconds`). A subset stores no records.
10. **The qualification store** is a JSON file in app storage, keyed by `Build.FINGERPRINT`, protocol version, destination volume and configuration. It is invalidated when the fingerprint or protocol version changes. The RAW_VIDEO gate is AVAILABLE only when the current camera has at least one qualified or risky configuration. Otherwise the gate is CANDIDATE. Choosing the mode then asks whether to qualify the device now; declining keeps the previous mode, and accepting runs the qualification in the app (`rawvideo/RawVideoQualifier`, which `RawThroughputDeviceTest` now only wraps) and enters RAW_VIDEO only when at least one configuration passes. Its default plan covers full sensor at 15/24/30/60 fps and UHD and 1080p crops at 30/60 fps on the app's RAW_VIDEO graph (viewfinder plus RAW10). It runs them as the stages of decision 9, and judges the LJ92 twin of each when the native codec is available. Long runs measure 40 s, or 80 s from 500 MB/s up, because a UHD60 take on the S24 filled the ring after about 30 s. Every judged configuration, PASS or FAIL, is stored, so a later failure revokes an earlier pass. An LJ92 configuration is offered on its own record unless its twin's newest record failed for the camera. Protocol 3 invalidated the records of protocol 2.
11. **Phase 2 compares two lossless codecs in native NEON code** and picks the better one on S24 evidence:
    - **LJ92**, lossless JPEG with predictor 1, which DNG stores natively (Compression 7), so export needs no re-encoding;
    - **Bayer block-pack:** same-colour delta, blocks of 16 samples sized by their largest residual.

    Measured per codec: ratio, ms per frame, cores needed at 30 and 60 fps, and the skin temperature over 10 minutes. The NDK is enabled under ADR-0021, because this is the first JNI consumer.

    **Outcome (S24 Ultra, 2026-11-02):** LJ92 ships as "Save space": 2.0–2.2× on real frames, encode p95 15–23 ms per 4080×3060 frame with 4 threads and 8 strips, about 2.4 process cores at 30 fps, and zero loss at full@30 and UHD@60. Block-pack measured 1.85× at 0.3 cores but needed its own converter path, so it first stayed defined and unshipped. Compression is never counted on for throughput; the time-left estimate assumes 1.8×.

    **Decision (Razr Fold, 2026-11-03): block-pack replaces LJ92 as the recording codec.** On the Razr, LJ92 takes about 20 ms per frame on 4 threads, and that encode load is what slows the camera (decision 9, the camera must hold), while block-pack takes 6.6 ms on 1 thread and 3.5 ms on 2. The ratio it gives up is small: 2.67 against LJ92's 2.89 in daylight, 1.48 against 1.57 in the dark (ISO 14000). So "Save space" now records encoding 2 (`+bp` configurations, 2 threads, 8 strips; the default plan's twins are `+bp`), and the converter path exists (the desktop converter decodes it, the app's CinemaDNG export decodes it and, with the LJ92 option, re-encodes it to LJ92 like RAW10). LJ92 stays for CinemaDNG export, and `+lj92` specs and records from earlier runs stay valid: an LJ92 record is still offered unless a block-pack record of the same geometry is usable, since both read "Compressed". The benchmark reasons and the record method became codec-neutral (`compressed-camera-starved`, `compressed-encode-too-slow`, `compressed-bench-missing`, `compressed-twin-missing`, `compressed-extrapolated`); the old `lj92-…` names are still read. The stage `encode` keeps `lj92` as an alias, and the device test reads `rawEncodeThreads`, `rawEncodeStrips` and `rawEncodeBenchSeconds` (the `rawLj92…` names still work).

## 4. Slices

| Slice | Owner files | Depends on |
|---|---|---|
| A. OCRAW streaming core | `media/.../ocraw/*` (new `OcrawStreamWriter`, `OcrawStreamReader`, fast `Crc32c`), `media/.../raw/RawVideoRing.kt`, their tests, `docs/formats/opencine-raw-v1.md`, `desktop-tools/ocraw_recovery.py` | — |
| C. Qualification rule v2 and storage sinks | `camera/.../RawThroughput.kt` and its test; `core/model/.../StorageBenchmark.kt` and the app benchmark destinations: a `FileChannel` sink with direct buffers and forced syncs | — |
| B. Benchmark rewrite and S24 re-run | `app/src/androidTest/.../RawThroughputDeviceTest.kt` (camera, `RawVideoRing`, `OcrawStreamWriter`, rule v2, several configurations including 60 fps); the certification report | A, C |
| H. Desktop DNG converter | `desktop-tools/ocraw_to_dng.py`, `tools/tests/` | A (fixtures) |
| D. Engine RAW10 stream | `Camera2PreviewEngine.kt`, `GraphNegotiation.kt`: a RAW10 reader beside the GPU input in RAW_VIDEO sessions, a fixed fps range, `startRawVideo`/`stopRawVideo`, `RawVideoFrameConsumer` | A (interface shape) |
| E. Service, output, recovery and qualification store | `CaptureService.kt`, `storage/RawVideoOutput.kt`, recovery classes, `RawVideoQualificationStore.kt`, gate computation | A, C, D |
| F. UI | settings rows (qualified configuration chooser, Qualify action, capacity in minutes), preset version bump, mode sheet, gallery listing and badge, `DriverScreens.kt`, `AGENTS.md`, `SettingsHelp` in 6 locales | C, E |
| G. Phase 2 codecs | `native-pipeline/` codecs and ctest, Gradle NDK and JNI wiring, a codec comparison device test, encoding 1/2 in the writer and in the converter | A, B |

## 5. Acceptance

- Host tests pass for every slice.
- An `.ocraw` take from the S24 passes `ocraw_recovery.py verify`.
- A take killed mid-write recovers every complete frame.
- `ocraw_to_dng.py` turns frames into DNGs that pass `tools/validate_dng.py` and open in a raw developer.
- Only qualified configurations are selectable. The re-run benchmark replaces the 345 MB/s figure in the S24 certification report.
- Phase 2 ships one codec, chosen from the comparison numbers, behind an opt-in "Save space" switch, qualified separately.

## 6. Out of scope

- RAW from constrained high-speed sessions; the device refuses it.
- RAW12 and RAW_SENSOR video.
- Orientation: `.ocraw` carries no orientation key, so playback shows the sensor's landscape frame, like the DNG converter (Orientation 1).

## 7. Phase 3: in-app playback

RAW takes open in the review player instead of offering only Share. Two modes share one session.

- **Real time (default).** Frames play at the take's `fps`, timed by the sensor timestamps. The reader picks the frame due at the current clock rather than reading every frame in turn, so a slow device drops frames instead of falling behind. Dropped frames are counted.
- **Frame viewer.** No motion: show one frame at full resolution, step ±1, scrub to any frame. It is used when real time can't keep up, chosen automatically or by the operator.

Decisions:

1. **Opening a take never reads the whole file.** `OcrawStreamReader.indexFrames()` walks the chunk headers, reads only each 64-byte frame header and seeks past the payload. It decodes the metadata chunks (static and per frame) and stops at the first torn or invalid chunk header. `scan()` stays the integrity check. Playback reads frames with `readRawFrame` and its CRC check, which runs on the native ARMv8 CRC32C. A frame whose CRC fails shows as missing; the take still opens.
2. **The frame contract** between the source and the GL stage is a direct `ByteBuffer` of tight packed RAW10 rows (`width*5/4` bytes per row) plus its `OcrawFrameHeader` and the frame's develop parameters. LJ92 frames decode to the same packed RAW10 through `NativeRawCodec` (`decode`). Encoding is chosen per frame, so a mixed take plays. If the codec isn't available, LJ92 frames show as missing. A small pool (3 slots, about 47 MB for 4080×3060) bounds memory.
3. **The GPU stage uploads the packed bytes as an `R8UI` texture** (width `rowBytes`) and unpacks RAW10 in the shader, so nothing is widened to 16 bits on the CPU. Real time debayers each 2×2 CFA quad to one RGB pixel (half resolution, 2040×1530 from full sensor). The frame viewer debayers bilinearly at full resolution. The stage owns its EGL ES3 thread and window surface, after `LogPlaybackRenderer`, and draws to the dialog's existing `SurfaceView`.
4. **Develop, as the DNG converter does** (`ocraw_to_dng.py`):
   - Per CFA site: (raw − black) / (white − black). The per-frame `dynamicBlackLevel`/`whiteLevel` override the static values.
   - White balance from the per-frame `neutral` (the as-shot neutral). Until the first neutral arrives, use the nearest frame's neutral, else no gain.
   - Camera to XYZ D50 through `forwardMatrix1`, else through the inverted `colorMatrix1`. Then Bradford to linear Rec.709 and the sRGB transfer, with a gentle highlight shoulder.
   - The matrix maths is Kotlin, unit-tested, and passed as uniforms. MONO and a missing `cfa` follow the converter's defaults (MONO shows grey).
5. **Automatic fallback.** During the first 2 s of real-time playback, the session times each frame's read, decode and upload against 1/fps. It switches to the frame viewer when the median exceeds the interval, or when more than a quarter of the frames are dropped. Then a notice appears with a "Try real time" action. A per-device playback setting, *RAW playback: Automatic / Frame viewer*, never in presets, skips real time entirely. The decision rule is a pure, tested function.
6. **Seam.** The dialog talks to a `PlaybackController` interface. `MediaPlaybackSession` (MediaPlayer/MediaCodec) and the new `RawPlaybackSession` implement it, and both emit `PlaybackObservation`. RAW takes have no audio, no filmstrip from `MediaMetadataRetriever` and no `ClipMetadataProbe`. Their chips come from the `.ocraw` metadata: resolution, fps, RAW10/LJ92, frame count.
7. **Gallery.** `opensInApp()` admits RAW. RAW cards get Play, and the inspector text says that the player develops a preview, while the full-quality path stays the desktop DNG converter. Rename and delete stay disabled (unchanged).

| Slice | Owner files | Depends on |
|---|---|---|
| P1. Index and frame source | `media/.../ocraw/OcrawStreamReader.kt` (`indexFrames`), new `media/.../ocraw/OcrawFrameSource.kt` (pool, per-frame decode), their tests; decode timing in `RawCodecComparisonDeviceTest` | — |
| P2. GPU develop stage | new `app/.../playback/RawDevelop.kt` (matrices, black/white, WB; pure), new `app/.../playback/RawPlaybackRenderer.kt` (EGL thread, R8UI upload, quad and bilinear debayer shaders), tests for `RawDevelop` | contract only |
| P3. RAW session | `PlaybackController` interface, new `RawPlaybackSession.kt`, the fallback rule, `PlaybackSettings` (RAW playback mode) and its persistence and settings row, tests | P1, P2 |
| P4. Integration | `MediaPlaybackDialog.kt`, `MediaGrid.kt`, `MediaCatalogScreen.kt`, `MediaInspector.kt` and strings in 6 locales, `LocalMediaCatalog.kt` KDoc, `DriverScreens.kt`, `AGENTS.md` | P3 |

Acceptance: a full@30 RAW10 take and an LJ92 take play on the S24 Ultra, with the drop count shown. Forcing the frame viewer steps and scrubs, and the frame number in the header matches. A torn-tail take opens with its valid frames. A take on a slow device falls back on its own.

### 7.1 Audio

RAW takes record sound when the operator has audio on, and the player plays it.

1. **In the file, not beside it.** Audio goes into the `.ocraw` as PCM16 chunks (`0x0020`, interleaved little-endian), so a take stays one file and Share sends everything. A chunk carries about 100 ms of audio. Its `timestampTicks` is the capture time of its first sample, in the same time base as the RAW frame chunks, so sync needs no separate epoch. The static metadata gains `audioSampleRate`, `audioChannels` and `audioSource`. A take without these keys is silent.
2. **Recording.** The capture uses the same audio settings and input route as ordinary video: mic on or off, the input device, and the permission check. PCM16 only. AudioRecord timestamps (`TIMEBASE_BOOTTIME`) map to the sensor clock, which reports `REALTIME` (boot time) on the S24; a sensor on another timestamp source maps through the measured offset. Audio blocks reach the single writer thread through a small queue beside the frame ring. A full queue drops audio and records the gap in metadata; it never stops the take. Audio costs about 192 KB/s, which is negligible next to the frames.
3. **Playback.** In real time, when the take has audio and playback isn't muted, an `AudioTrack` plays the PCM and its playback position is the master clock: video picks the frame due at the audio time. Seeking restarts the audio at the new position. The frame viewer is silent.
4. **Desktop.** `ocraw_to_dng.py` can also export the take's audio as a WAV.

## 8. Interchange: CinemaDNG

`.ocraw` remains the capture format: one streaming file, qualified, and recoverable. For editing, a take becomes **CinemaDNG**: one folder per take, one DNG per frame (`<take>_000000.dng` …) and `<take>.wav` beside them. DaVinci Resolve reads that natively and links the WAV. There are three routes, and all produce the same files.

1. **Desktop converter** (`ocraw_to_dng.py`).
   - Each DNG gains the CinemaDNG tags: FrameRate (51044, from `fps`), TimeCodes (51043, SMPTE from the frame's offset to the take start at that rate), CameraLabel (51105) and ReelName (51081, the take name), and UniqueCameraModel/Make/Model from `model`.
   - The default names follow the folder convention above, and `--cinemadng DIR` writes the frames and the WAV together.
2. **In-app export.** From the gallery or the inspector, *Export CinemaDNG*.
   - The destination is `Documents/OpenCineCam/CinemaDNG/<take>/` through MediaStore, or a folder the operator picks with the storage access framework, such as a USB SSD over OTG. The folder holds a `.nomedia`, so thousands of frames never reach the gallery.
   - The export runs in a foreground service with progress, cancel and a space check up front. It never needs the camera.
   - Frames are written like the converter's: RAW10 and block-pack become uncompressed 16-bit and LJ92 passes through. An option re-encodes RAW10 and block-pack frames to LJ92 when the native codec is available, which roughly halves the size against uncompressed DNG.
3. **Network drive.** *Share over network* starts a read-only WebDAV server in a foreground service.
   - Each take shows as a folder: the CinemaDNG frames, generated on the fly when read, plus the WAV, plus the original `.ocraw`.
     *Original hidden by default (2026-11-05).* The original `.ocraw` is listed only when *Show original .ocraw files* is on in the Network drive page (a share-owned preference, off by default, read on every request). Windows' WebDAV client cannot copy a file over 4 GB (its `FileSizeLimitInBytes` is a DWORD; error 0x800700DF), a RAW take's original is far larger (14 GB for 32 s), and the DNG frames and the WAV are all an editor needs. Hidden, it is a 404; the page points to USB or WinSCP, Cyberduck or rclone.
   - Sizes are exact before any byte is generated: uncompressed DNG sizes follow from the dimensions, and LJ92 sizes from the index, so PROPFIND and Range work. Windows, macOS and Linux mount it with their built-in WebDAV clients. The guidance is to copy the folder to a disk before editing.
   - **Security:**
     - It is off by default.
     - It binds only to private interfaces: Wi-Fi LAN and the USB tethering or hotspot address, never cellular.
     - Every request needs a random per-session password over HTTP Digest (MD5, qop=auth: Windows' WebDAV client refuses Basic over plain HTTP), user `occ`, shown with the URL on screen; repeated failures lock the client IP out for 30 s.
     - It is read-only: PROPFIND, GET, HEAD and OPTIONS. Anything else gets 405, and the paths are confined to the virtual tree.
     - It stops when the operator closes it, after 15 idle minutes, or when the phone leaves the network.
     - It never runs while recording.

**One DNG builder in `:media`** (`CinemaDngWriter`) serves routes 2 and 3. It computes the size, then streams the bytes. Its output for a fixture take is byte-identical to the converter's, and a golden test checks this. The WAV builder shares the gap-fill rules of `ocraw_to_dng.py --wav`.

| Slice | Owner files | Depends on |
|---|---|---|
| I1. CinemaDNG builder | `ocraw_to_dng.py` (tags, naming, `--cinemadng`), `tools/tests/`, new `media/.../ocraw/CinemaDngWriter.kt` and `OcrawWavWriter.kt`, golden fixtures and tests | — |
| I2. In-app export | new `export/` package in `:app` (foreground service, MediaStore and SAF destinations, progress), gallery and inspector actions, `cinemadng_export_strings.xml` in 6 locales, driver screen | I1 |
| I3. Network drive | new `share/` package in `:app` (WebDAV server, virtual tree, auth, interface binding, foreground service), its screen and entry point, `network_share_strings.xml` in 6 locales, driver screen | I1 |
