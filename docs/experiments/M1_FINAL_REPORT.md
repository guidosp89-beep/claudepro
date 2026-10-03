# M1 — Final report: Physical Link Lab / visual PHY bake-off

> **Status: M1-CLOUD COMPLETE · M1-PHYSICAL PENDING.** Every number in this report is either
> CLOUD-AUTOMATED (unit/property/fuzz tests, desktop benchmarks) or SIMULATED (camera simulator,
> timing model, virtual loop). **No physical run was executed** — the cloud has no phones. Nothing here
> is extrapolated into a physical claim; the physical procedure and automatic verdict are ready.

## A. Executive result

- **M1-CLOUD: COMPLETE.** The Physical Link Lab exists end to end: a single offline Android app (TX / RX /
  AUTO BENCHMARK, built and checked by CI), QR and custom-grid PHYs, three reliability schemes, full
  telemetry, a pre-registered analysis, and a cloud test bench (camera simulator, timing model, virtual
  loop) that already found and fixed a receiver bug.
- **M1-PHYSICAL: PENDING.** No phone was available; **0 physical runs**. The M1 gate (≥ 5 KB/s, ≥ 50 cm,
  ≥ 256 KB, 100% SHA-256, ≥ 2 devices, ≥ 100 runs) is therefore **neither passed nor failed**.
- **What the simulation predicts** (not a claim about phones): QR is capacity-limited to ≤ 2.2 KB/s at
  ≥ 40 cm; a full-screen **48-column binary grid + RaptorQ at 15 symbols/s** delivers ~6 KB/s at 60 cm with
  256 KB byte-exact (virtual loop: 6.19 KB/s); the 4-colour version ~13 KB/s if colour survives real
  screens. That would be **Case B** of the brief (5–20 KB/s → M1.1).
- **GhostPacket v0** objects crossed the simulated visual channel byte-identical (QR and grid).

## B. Hardware tested

| Kind | What | Notes |
|---|---|---|
| Physical phones | **None** | PENDING — `OWNER_PHYSICAL_TEST_GUIDE.md` |
| Cloud compute | 4 vCPU Linux container, OpenJDK 21 | JVM timings are desktop numbers, not phone numbers |
| CI | GitHub Actions `ubuntu-latest` (Temurin 17, Android SDK) | Builds the APK, runs all tests, checks the offline manifest |
| Simulated "phones" | Screen 1080×2400, 68×151 mm; main camera 69° HFOV; analysis 1280×720 / 1920×1080 / 3840×2160 | `CameraSimulator` (pinhole geometry + photometric/noise model) |

## C. Candidate technologies

A sequential QR · B QR + fountain (LT, RaptorQ) · C dense binary grid (GLG-B1) · D colour grid
(GLG-C2 2 bit, GLG-C3 3 bit) · E temporal modulation · F rolling shutter · G quasi-invisible frames.
Implementation status per candidate in `M1_VISUAL_TECH_BAKEOFF.md`.

## D. Benchmark methodology

- **Separation PHY / reliability** (brief §29): `payload → ErasureEncoder → LabFrame (19 B, CRC-32C) →
  VisualFrameEncoder → screen … camera → FrameDecoder → LabFrame → ErasureDecoder → payload`.
- **Deterministic payloads**: SplitMix64(session, trial, size) on both phones; the receiver recomputes
  SHA-256 and a run is PASS only if byte-identical (`SHA256_PASS`).
- **AUTO BENCHMARK** (APK): the transmitter runs a plan of trials (announce QR → data → periodic
  re-announce); the receiver detects trials from announces, records every metric of brief §17 plus
  pipeline stage timings, writes `runs.csv` + `raw/<run_id>.json`, ranks configurations (stage 2) and
  shows a PLAN QR the transmitter can scan for the fine search (stage 3). Robustness plans cover
  distance/angle/light/motion; a decoder-comparison plan runs zxing-cpp, ZXing Java and ML Kit on the
  same frames. One zip per phone (`EXPORT RESULTS`).
- **Cloud experiments** (`labtools`): fountain benchmark, frame-loss channel (iid, bursty
  Gilbert–Elliott, duplicates, reordering), timing model, camera-simulator sweep (22 PHY configs ×
  distance × resolution × angle × light × motion × JPEG × glare × occlusion × WB error ×
  rolling-shutter mixing × zoom), quasi-invisible model, pipeline stage timing, virtual end-to-end loop.
- **Analysis**: `scripts/analyze_m1.py` (p10/p50/p90, failure rate, device matrix, worst runs, plots,
  M1 gate and case A/B/C/D) and `scripts/cloud_tables.py` (all cloud tables).

## E. Raw run count

| Source | Runs | Notes |
|---|---|---|
| PHYSICAL | **0** | Pending owner run (plan yields ≈ 150–200 runs per phone pair) |
| SIMULATED camera frames | 3 035 | camera-simulator sweep: 22 PHY configs, 607 conditions × 5 seeds |
| SIMULATED end-to-end runs (virtual loop) | 26 | 13 trials at 40 cm and at 60 cm, 19 PASS; the same 13 trials at 100 cm produced no run (announce unreadable) |
| Fountain / loss trials | 1 465 | 889 fountain-benchmark decodes + 576 loss-channel transfers (desktop JVM); plus 3 648 timing-model and 144 quasi-invisible-model configurations |
| Automated tests | 26 JUnit methods + 1 Python suite | codecs 16, decoder 3, benchmark 5, generators 2 (unit, property-based with saved seeds, parser fuzzing) + `test_analyze_m1.py` (decision rule); all green locally and in CI |

## F. Results (cloud)

All figures below are **SIMULATED / CLOUD-AUTOMATED**. Full tables: `M1_VISUAL_TECH_BAKEOFF.md` and
`spikes/physical_link_lab/results/cloud/CLOUD_TABLES.md`; plots in `results/analysis/cloud/`.

**F.1 Reliability layer** (256 KB, K = 263, frame-loss channel without camera; efficiency = K / frames sent,
ideal = 1 − loss):

| Channel | RaptorQ | LT + Gauss | Sequential |
|---|---:|---:|---:|
| 5% iid loss | 0.953 | 0.709 | 0.500 |
| 20% iid loss | 0.799 | 0.591 | 0.248 |
| 50% iid loss | 0.494 | 0.388 | 0.114 |
| 20% bursty (mean burst 5) | 0.819 | 0.616 | 0.287 |
| 20% loss + bursts + duplicates + reordering | 0.769 | 0.605 | 0.268 |

RaptorQ (163 decodes, K = 1 … 5 243): decoded from exactly K symbols in every trial except one of 60 at K = 5,
which needed K + 1; decode 50–58 MB/s for K ≥ 66 (desktop JVM).

**F.2 Timing** (60 Hz display, 30 fps camera, 8 ms exposure): distinct clean symbols captured per second =
10.0 / **14.9** / 15.5 (worst phase 9.9) / 3.7 at 10 / **15** / 20 / 30 TX symbols per second.

**F.3 Camera simulator** (1080p analysis, 1× zoom, 5 frames per point; model goodput at 15 symbols/s):

| PHY | Bytes/frame | Max distance ≥ 80% (720p / 1080p / 4K) | Model KB/s at 40 cm | at 60 cm |
|---|---:|---|---:|---:|
| QR v6-M | 106 | 40 / 60 / 60 cm | 1.2 | 1.0 |
| QR v10-M | 213 | 20 / 40 / 40 cm | 2.2 | 0 |
| QR v20-L | 858 | < 20 / 20 / 20 cm | 0 | 0 |
| GLG-B1 40 columns | 356 | 40 / 60 / 100 cm | 4.8 | 4.8 |
| **GLG-B1 48 columns** | 510 | 40 / 60 / 60 cm | 7.0 | **7.0** |
| GLG-B1 64 columns | 946 | 20 / 40 / 60 cm | 13.2 | 0 |
| GLG-C2 48 columns | 1 054 | 40 / 60 / 100 cm | 14.7 | 11.8 |
| GLG-C3 48 columns (RS 48) | 1 438 | 40 / 60 / 100 cm | 20.2 | 20.2 |
| GLG-B1 96 columns | 2 218 | 20 / 20 / 40 cm | 0 | 0 |

**F.4 Virtual end-to-end loop** (real `TransmitterSession` → display/camera timing model → camera simulator
→ real `ReceiverSession` with ZXing Java and the GLG decoder; 1080p, 30 fps camera, 60 Hz display):

| Trial (64 KB unless noted) | 40 cm | 60 cm |
|---|---|---|
| QR v10-M, RaptorQ, 10 fps, 16 KB | PASS 1.71 KB/s | FAIL (incomplete) |
| QR v20-L, RaptorQ | FAIL | FAIL (no data) |
| GLG-B1 48, **sequential** | PASS 3.40 KB/s | PASS 2.24 KB/s |
| GLG-B1 48, **LT** | PASS 4.58 KB/s (+37% symbols) | PASS 4.66 KB/s (+34%) |
| GLG-B1 48, **RaptorQ** | PASS **5.85 KB/s** | PASS **6.04 KB/s** |
| GLG-B1 48, RaptorQ, **256 KB** | PASS **6.15 KB/s** | PASS **6.19 KB/s** |
| GLG-B1 40, RaptorQ | PASS 4.33 KB/s | PASS 4.28 KB/s |
| GLG-B1 64, RaptorQ | PASS 12.23 KB/s | FAIL (incomplete) |
| GLG-B1 96, RaptorQ | FAIL | FAIL (no data) |
| GLG-C2 40, RaptorQ | PASS 8.57 KB/s | PASS 8.81 KB/s |
| GLG-C2 48, RaptorQ | PASS 12.47 KB/s | PASS **13.33 KB/s** |
| GhostPacket v0 over QR v10-M, 8 KB | PASS, 143/143 packets identical | FAIL (incomplete; 58/58 identical) |
| GhostPacket v0 over GLG-B1 48 | PASS, 271/271 identical | PASS, 272/272 identical |

All PASS rows are SHA-256 verified. Full per-run table: `M1_VISUAL_TECH_BAKEOFF.md` §6.

## G. Best configuration

| | Configuration | Value | Evidence class |
|---|---|---|---|
| Best measured (physical) | — | **PENDING** | no physical runs |
| Best simulated goodput, any distance | GLG-C2 48 columns + RaptorQ, 15 symbols/s | 13.33 KB/s at 60 cm (1 virtual run) | SIMULATED |
| Best simulated meeting the gate *shape* (≥ 50 cm, ≥ 256 KB, SHA-256) | GLG-B1 48 columns + RaptorQ, 15 symbols/s | 6.19 KB/s at 60 cm (1 virtual run) | SIMULATED |
| Highest model goodput at ≥ 50 cm | GLG-C3 48 columns (RS 48) | 20.2 KB/s at 60 cm | SIMULATED (camera model only, not in the virtual loop) |
| Maximum simulated range with data | GLG-B1/C2 40 columns, GLG-C2/C3 48 columns, 4K analysis | 100 cm (frame decode) | SIMULATED; trial detection stops at ~60 cm with the QR v8-M announce |

## H. Median configuration

- Median goodput over the 19 SHA-256-verified virtual runs: **5.78 KB/s** (p10 2.13, p90 12.28); pass rate
  85% at 40 cm, 62% at 60 cm across the deliberately wide plan (it includes configurations expected to fail).
- Median of the leading binary configuration (GLG-B1 48 + RaptorQ, 6 runs incl. 256 KB and GhostPacket):
  **5.95 KB/s**, 6/6 PASS.
- Physical median: **PENDING**.

## I. Failure modes

Observed in the cloud bench (camera simulator + virtual loop). Every one is a hypothesis to confirm or
refute on phones.

1. **Too few camera pixels per cell / module** (dominant). Below ~3 px per grid cell or ~3.5 px per QR
   module, decoding collapses: QR ≥ v15 at ≥ 40 cm, grids ≥ 64 columns at 60 cm, everything at 100 cm with
   1080p / 1×. Levers: fewer, larger cells; 4K analysis; 2× (telephoto) zoom.
2. **Trial detection limited by the announce QR (v8-M).** At 100 cm the receiver never learned the trial
   parameters, so no run was recorded although a 40-column grid frame still decodes there with 4K
   analysis. M1.1 item: a low-density grid announce (or 4K / zoom for long-range plans).
3. **Frames mixing two symbols (rolling shutter)** fail for every code (0/5 at 50% mix). This is the frame
   loss the fountain absorbs and the reason for the ≤ camera fps / 2 TX rate.
4. **45° viewing angle** fails every configuration at 40 cm (foreshortening on one axis); 30° works for
   QR v6–v10 and grids ≤ 48 columns.
5. **Glare spot / finger occlusion**: grids lose the frame when a finder or a large area is hit (0/5;
   GLG-C3 with RS 48: 1/5 under occlusion); small QRs survive partially (occlusion v6–v8: 3/5; glare v8 3/5,
   v10 2/5). Mitigation candidates: more RS parity, redundant finders.
6. **Colour fragility at higher density**: 64-column colour fails at 60 cm and, at 40 cm, degrades under
   JPEG compression (C3: 1/5 at q70; C2 and C3: 0/5 at q40), motion blur (C2 2/5 at 3 px; C3 0/5 at 6 px)
   and outdoor shade (C2 2/5, C3 3/5).
7. **Sequential carousel**: coupon-collector tail (efficiency 0.50 at 5% loss, 0.25 at 20%).
8. **LT**: 34–37% extra symbols on the visual channel (systematic repairs mostly hit known blocks).
9. **Software defects found and fixed during M1-cloud**: raptorq-kotlin `decodeFully*` crash (worked
   around with explicit `solve()`); camera-simulator mirror error; grid config key collision; receiver
   reopening completed trials (duplicate run_id) — all with tests where applicable.

## J. Device differences

No physical devices yet. The simulator shows the levers that will differ between phones: analysis
resolution (720p → 4K changes the maximum distance by ~1.5–2×), camera FOV/zoom (telephoto at 2× roughly
doubles pixels per cell), display size/aspect (grid rows adapt to the TX aspect), refresh rate (60 vs
120 Hz changes the clean-capture window) and camera frame rate (30 vs 60 fps). The plan therefore runs
**both directions** (A→B, B→A) and the device matrix in `analyze_m1.py` reports stability per direction.

## K. Selected technology (provisional)

Provisional **Visual PHY v0** (`docs/decisions/ADR_M1_VISUAL_PHY_SELECTION.md`, status PROVISIONAL):

| Layer | Choice | Why (cloud evidence) |
|---|---|---|
| Reliability | **RaptorQ**, single source block, one symbol per visual frame | Ideal efficiency under iid/bursty loss, duplicates and reordering; K symbols sufficed in 162 of 163 benchmark decodes (K + 1 once, at K = 5) |
| Framing | LabFrame (19 B, CRC-32C), self-describing | Receiver joins mid-stream; a flipped bit is rejected by the CRC (unit test) and 20 000 fuzzed inputs never crash the parsers |
| TX rate | **15 symbols/s** (≤ camera fps / 2) | Timing model peak for 30 fps cameras |
| Throughput PHY | **GLG-B1, 48 columns, 1 bit/cell, RS 32** | Only binary configuration at ≥ 5 KB/s to 60 cm in the simulator (7.0 KB/s) |
| Upside PHYs (measured physically, not default) | GLG-C2 / GLG-C3 at 48 columns | 11.8 / 20.2 KB/s at 60 cm in the simulator; colour model optimistic |
| Compat PHY | QR v10-M (≤ 40 cm) / v6-M (≤ 60 cm) | Universal decoders; ≤ 2.2 KB/s |
| Receiver | CameraX ImageAnalysis 1080p YUV, 30 fps AE range; 4K analysis as a range option | 4K extends range ~1.5–2× at ~4× decode cost |
| QR decoder | zxing-cpp (hypothesis), ZXing Java reference, ML Kit only on a clear win | Decided by the on-device DECODER COMPARE plan |

The default among GLG-B1/C2/C3 and QR is chosen by the physical stage-2 ranking; the pre-registered rule
in `analyze_m1.py` maps the physical result to Case A/B/C/D.

## L. Rejected technologies

| Candidate | Status | Evidence (cloud) |
|---|---|---|
| A — sequential QR carousel | **REJECTED** as a transfer mode (kept as a baseline in the plan) | Efficiency 0.50 at 5% loss, 0.25 at 20% loss (RaptorQ 0.95 / 0.80) |
| B — LT fountain | **REJECTED** in favour of RaptorQ | 25–40% more frames than RaptorQ at 5–20% loss; Gaussian fallback does not help in-order streams |
| B — large QR (v15+) at ≥ 40 cm | **REJECTED for range ≥ 40 cm at 1080p / 1×** (kept in the coarse plan so the physical run can disagree) | 2.3–2.8 px/module at 40 cm: 0–1 of 5 frames decode |
| A/B — QR as the throughput PHY | **REJECTED** (kept as "compat" PHY) | ≤ 2.2 KB/s model goodput at ≥ 40 cm for every version (below the 5 KB/s gate) |
| D — colour at ≥ 64 columns | **REJECTED for ≥ 50 cm** | 0/5 frames at 60 cm (2.3 px/cell, 1080p); fragile to JPEG q70 / 6 px motion / outdoor shade at 40 cm |
| C — grids ≥ 96 columns | **REJECTED for ≥ 40 cm at 1080p** | 0/5 frames at 40 cm (2.4 px/cell); only useful with 4K analysis or 2× zoom at short range |
| E — TX rate above `camera_fps / 2` | **MARGINAL** (a rate rule, not a capacity gain) | Timing model: 30 symbols/s at a 30 fps camera yields 3.7 clean symbols/s |
| F — rolling-shutter exploitation | **EXPERIMENTAL — stopped** | Gains only at ≥ 30 symbols/s with parallel scan geometry; screens cannot modulate faster than their refresh |
| G — quasi-invisible frames | **MARGINAL / NOT PRACTICAL as default** | BER ≈ 0.5 with auto-exposure; works only with ≤ 1 ms manual exposure, guaranteed ≥ 120 Hz and A/B sync |

## M. Architecture implications

- **PHY / reliability separation holds.** The same `ErasureEncoder`/`ErasureDecoder` ran unchanged over
  QR and grid PHYs, over the loss channel and through the virtual loop. Interfaces needed by the
  production core are minimal: `VisualFrameEncoder(bytes) → image`, `FrameDecoder(image) → bytes?`,
  `ErasureEncoder.symbol(id)`, `ErasureDecoder.add(id, bytes)`.
- **No ACK/NACK for bulk.** RaptorQ at ideal efficiency means a one-way visual stream is sufficient; a
  back-channel only shortens the session (early DONE) and negotiates capabilities — a control-plane
  concern, not a reliability one (brief §39).
- **GhostPacket v0 is transport-agnostic.** OBJ symbols are carried as opaque erasure symbols; the packet
  id (BLAKE2s) and bytes survive the visual path unchanged in the cloud loop. **No crypto change** is
  implied by M1.
- **Receiver must expose capabilities** (camera fps, analysis resolution, zoom) because the optimal TX
  rate (≤ camera fps / 2) and code density depend on the receiver, not the sender.
- **The spike's dependencies stay in the spike.** The repository has no production `core/`, `android/`
  or `firmware/` tree yet (Phase 0 is documentation); M1 changes nothing outside
  `spikes/physical_link_lab`, `docs/` and its own CI workflow. RaptorQ (young library + IPR review),
  zxing-cpp and ML Kit must be re-evaluated before any production adoption.

## N. M1 PASS / PARTIAL / FAIL

**M1 verdict: M1-CLOUD COMPLETE — M1 GATE PENDING (physical).**

| Criterion | Required | Status |
|---|---|---|
| Net goodput | ≥ 5 KB/s | PENDING (simulated: 6.2 KB/s binary, 13.3 KB/s colour) |
| Distance | ≥ 50 cm | PENDING (simulated pass at 60 cm) |
| Payload | ≥ 256 KB | PENDING (simulated pass) |
| Integrity | 100% SHA-256 | PENDING (simulated: every completed run verified) |
| Devices | ≥ 2 Android | **0** |
| Physical runs | ≥ 100 aggregate | **0** |

Not PASS, not FAIL: no physical data. The decision is pre-registered in `scripts/analyze_m1.py` and will be
applied mechanically to the owner's exports; the simulation's prediction is **Case B** (5–20 KB/s).

## O. Exact next milestone

**Exact next step: M1-PHYSICAL** — the owner runs `spikes/physical_link_lab/OWNER_PHYSICAL_TEST_GUIDE.md`
with two Android phones (AUTO 1 → AUTO 2 → robustness → decoder comparison → reverse direction → export),
then `python3 scripts/analyze_m1.py results/physical`. The script's case decides the milestone after that:

| Physical result (best stable config, ≥ 50 cm, ≥ 256 KB) | Case | Next milestone |
|---|---|---|
| median ≥ 20 KB/s | A | M2 — GhostPacket + identity + encrypted visual session |
| 5–20 KB/s | B (predicted by simulation) | M1.1 — visual optimisation: grid announce for ≥ 100 cm, ROI preprocessing, colour calibration, acoustic DONE back-channel |
| < 5 KB/s | C | M1B — audio fallback + alternative visual PHY bake-off |
| passes in one direction / device only | D | M1C — device compatibility investigation |

Per the brief, M1 stops here: no M1.1 / M2 work has been started.
