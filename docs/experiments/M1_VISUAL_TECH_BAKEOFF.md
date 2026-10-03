# M1 — Visual technology bake-off (empirical matrix)

> **Evidence class.** CLOUD-AUTOMATED / SIMULATED only. Physical two-phone runs: **PENDING**.
> All numbers below are reproducible from `spikes/physical_link_lab` (`./gradlew :labtools:run --args=all`,
> then `python3 scripts/cloud_tables.py`; full tables in `results/cloud/CLOUD_TABLES.md`).
> Classification uses the brief's vocabulary; every SIMULATED verdict is provisional by construction.

## 1. Empirical matrix

Columns as required by the brief. **Net throughput** = model goodput at 15 symbols/s from the camera
simulator (1080p analysis, 1× zoom) unless marked *virtual* (whole protocol through the simulator,
section 6). **Range** = farthest simulated distance with ≥ 80% frame decode (1080p; 4K in brackets).
**Frame-loss tolerance** = reliability-layer efficiency at 20% iid loss (ideal 0.80). CPU = desktop-JVM
decode time per 1080p frame (phones: several times slower). Every classification is **SIMULATED**
and provisional until the physical AUTO run.

| Technology | Implementation | Net throughput | Range | Frame-loss tolerance | CPU (decode/frame) | Compatibility | Complexity | Result |
|---|---|---|---|---|---|---|---|---|
| **A** sequential carousel (any PHY; QR v10-M as the classic case) | Full: codec, TX/RX, APK | QR v10-M: ≤ 2.2 KB/s × carousel penalty; *virtual* on GLG-B1 48: 3.40 / 2.24 KB/s at 40 / 60 cm vs RaptorQ 5.85 / 6.04 on the same PHY | 40 cm (40) | **0.25** (0.50 already at 5% loss) | 6.8 ms (ZXing) | Universal QR decoders | Low | **REJECTED** |
| **B** LT (systematic, robust soliton) | Full | QR: ≤ 2.2 KB/s × 0.74; *virtual* on GLG-B1 48: 4.58 / 4.66 KB/s at 40 / 60 cm (37% / 34% extra symbols) | 40 cm (40) | 0.59 | 6.8 ms + LT negligible | Universal QR | Low–medium | **REJECTED** (dominated by RaptorQ) |
| **B** QR + RaptorQ (QR v10-M) | Full (raptorq-kotlin 1.0.0 + workaround) | 2.2 KB/s at 40 cm; *virtual*: 1.71 KB/s at 40 cm (10 fps, 16 KB, PASS), FAIL at 60 cm | 40 cm (40); v6-M: 60 cm at 1.0 KB/s | **0.80 (ideal)** | 6.8 ms + 0.01 ms/symbol | Universal QR | Low | **VIABLE** as "compat" / short-range PHY; below the 5 KB/s gate |
| **B** QR v15–v40 + RaptorQ | Full | 9.5 KB/s only at 20 cm (v20-L); 0 at ≥ 40 cm | 20 cm (20) | 0.80 | 7.4 ms | Universal QR | Low | **REJECTED** for ≥ 40 cm (kept in the coarse plan) |
| **C** GLG-B1 48 columns + RaptorQ | Full: GF(256) RS, finders, decoder, APK | **7.0 KB/s at 20–60 cm**; *virtual*: 5.85 / 6.04 KB/s (64 KB) and **6.15 / 6.19 KB/s with 256 KB** at 40 / 60 cm, SHA-256 PASS | 60 cm (60; 100 at 60%) | 0.80 | 8.7 ms | Custom decoder (same Kotlin code on JVM and phone); needs the announce QR | Medium | **PROMISING** (safe candidate for the M1 gate) |
| **C** GLG-B1 40 columns | Full | 4.8 KB/s | 60 cm (100) | 0.80 | 8.8 ms | Custom | Medium | **VIABLE** (range option, below gate) |
| **C** GLG-B1 64 columns (and `-f2`) | Full | 13.2 KB/s at ≤ 40 cm; 0 (f2: 4.9) at 60 cm | 40 cm (60) | 0.80 | 8.9 ms | Custom | Medium | **VIABLE** at ≤ 40 cm |
| **C** GLG-B1 ≥ 96 columns | Full | 31–45 KB/s only at 20 cm | 20 cm (40) | 0.80 | 8.4 ms | Custom | Medium | **REJECTED** for ≥ 40 cm at 1080p |
| **D** GLG-C2 48 columns (4 colours) | Full | 11.8 KB/s at 60 cm (4/5 frames); *virtual*: 12.5 / 13.3 KB/s at 40 / 60 cm | 60 cm (100) | 0.80 | 9.6 ms | Custom; colour rendering differs per phone | Medium–high | **PROMISING (sim) / EXPERIMENTAL** until ≥ 2 phones |
| **D** GLG-C3 48 columns (8 colours, RS 48) | Full | 20.2 KB/s at 60 cm | 60 cm (100) | 0.80 | 10.1 ms | Custom; cimbar reports 8 colours "inconsistent" on phones | High | **PROMISING (sim) / EXPERIMENTAL**, highest physical risk |
| **D** colour at ≥ 64 columns | Full | 27–37 KB/s at ≤ 40 cm; 0 at 60 cm | 40 cm (60) | 0.80 | 10 ms | Custom | High | **REJECTED** for ≥ 50 cm (fragile to JPEG/motion/outdoor) |
| **E** temporal modulation (TX > camera rate) | Timing model + TX rate knob in APK | 0 gain; 30 symbols/s on a 30 fps camera → 3.7 clean symbols/s | — | — | — | 60 fps cameras only for 20/s | Low | **MARGINAL** (rate rule: ≤ camera fps / 2) |
| **F** rolling-shutter exploitation | Timing model (band tiling) | +25% symbol-equivalents only at ≥ 30 symbols/s, parallel scan | — | — | — | Device/geometry specific | High | **EXPERIMENTAL** (stopped) |
| **G** quasi-invisible complementary frames | Model (`VeilSimulator`) | ≤ 2 KB/s raw at best | ~40 cm | BER 0.5 with auto-exposure | — | Needs manual ≤ 1 ms exposure + 120 Hz + sync | High | **MARGINAL** (feasibility: NOT PRACTICAL as default) |

## 2. Reliability layer (independent of the PHY)

Frame-loss channel without camera, 256 KB object (K = 263 symbols of 1 000 B), median of 12 repetitions.
Efficiency = K / frames transmitted until byte-exact reconstruction (ideal = 1 − loss).

| Channel | RaptorQ | LT + Gauss | LT peeling | Sequential | Ideal |
|---|---:|---:|---:|---:|---:|
| 0% loss | 1.000 | 1.000 | 1.000 | 1.000 | 1.00 |
| 5% iid | **0.953** | 0.709 | 0.709 | 0.500 | 0.95 |
| 10% iid | **0.904** | 0.694 | 0.694 | 0.360 | 0.90 |
| 20% iid | **0.799** | 0.591 | 0.581 | 0.248 | 0.80 |
| 30% iid | **0.687** | 0.517 | 0.489 | 0.186 | 0.70 |
| 40% iid | **0.590** | 0.441 | 0.440 | 0.160 | 0.60 |
| 50% iid | **0.494** | 0.388 | 0.366 | 0.114 | 0.50 |
| 20% bursty (Gilbert–Elliott, mean burst 5) | **0.819** | 0.616 | 0.592 | 0.287 | 0.80 |
| 40% bursty | **0.605** | 0.456 | 0.426 | 0.139 | 0.60 |
| 10% loss + 20% duplicates | **0.898** | 0.688 | 0.688 | 0.362 | 0.90 |
| 10% loss + reorder window 8 | **0.877** | 0.658 | 0.658 | 0.344 | 0.90 |
| 20% loss + bursts + dup + reorder | **0.769** | 0.605 | 0.570 | 0.268 | 0.80 |

Fountain benchmark (20% random erasure, shuffled order, 1 000 B symbols, cloud JVM):

| Payload | K | RaptorQ overhead p50 / p90 / max | LT+Gauss overhead p50 / p90 | RaptorQ decode | RaptorQ encode |
|---|---:|---|---|---:|---:|
| 4 KB | 5 | 0 / 0 / 0.2 (1 of 60 trials needed K+1) | 0.20 / 0.60 | 6 MB/s | 9 MB/s |
| 64 KB | 66 | 0 / 0 / 0 | 0.06 / 0.17 | 51 MB/s | 82 MB/s |
| 256 KB | 263 | 0 / 0 / 0 | 0.03 / 0.06 | 50 MB/s | 52 MB/s |
| 1 MB | 1 049 | 0 / 0 / 0 | 0.03 / 0.03 | 58 MB/s | 106 MB/s |
| 5 MB | 5 243 | 0 / 0 / 0 | 0.015 / 0.015 | 53 MB/s | 95 MB/s |

Findings:
- **RaptorQ reaches the ideal efficiency** on every channel; the receiver never needs to ask for a specific
  symbol → **no ACK/NACK is needed for bulk transfer** (brief §39).
- **Sequential QR (Candidate A)** degrades sharply already at 5% loss (coupon-collector: the last missing
  chunks require whole extra carousel cycles): **REJECTED** as a transfer mode.
- **LT** needs 25–40% more frames than RaptorQ at 5–20% loss: in the systematic case the robust-soliton
  repairs mostly cover blocks the receiver already has. Our Gaussian fallback helps only with random
  arrival orders (256 KB: overhead 0.17 → 0.03), not with in-order streams.
- raptorq-kotlin 1.0.0 needed a workaround (explicit `solve()`; `decodeFully*` throws for many K/T);
  documented in `RaptorQCodec.kt`.

## 3. Transmitter rate vs camera (Candidates E and F)

Timing model (display refresh, symbol hold, camera fps, exposure, rolling-shutter readout, OLED response,
both phones portrait), averaged over 8 display/camera phases. Distinct *clean* symbols captured per second:

| TX symbols/s | 60 Hz / cam 30 | 60 Hz / cam 60 | 90 Hz / cam 30 | 120 Hz / cam 30 | 120 Hz / cam 60 |
|---:|---:|---:|---:|---:|---:|
| 10 | 10.0 | 10.0 | — | 10.0 | 10.0 |
| **15** | **14.9** | 15.0 | 14.9 | 14.9 | 15.0 |
| 20 | 15.5 (worst phase 9.9) | 19.9 | — | 19.4 | 20.0 |
| 30 | 3.7 (worst 0) | 11.2 | 11.2 | 11.2 | 22.5 |
| 60 | 0 | 0 | — | 0 | 0 |

(exposure 8 ms; with 1 ms exposure, 30/s reaches 18.7 at cam 60 / 60 Hz and 29.9 at 120 Hz / cam 60.)

- **Candidate E (temporal modulation)**: throughput is bounded by *camera* rate, not display rate; above
  `camera_fps / 2` symbols per second the gain vanishes and the worst phase can collapse → default
  **15 symbols/s**; 20/s only when the receiver confirms ≥ 60 fps. Classification: **MARGINAL** (no capacity
  beyond the camera frame rate; useful only as a rate rule).
- **Candidate F (rolling shutter)**: a screen cannot change faster than its refresh, so rolling shutter cannot
  read "sub-frame" data from a display; splitting the code into independent bands only recovers mixed
  frames at ≥ 30 symbols/s with parallel scan geometry (60 Hz / cam 30: 15.0 → 18.7 symbols/s with 2–4 bands).
  Device- and geometry-specific → **EXPERIMENTAL**, stopped as instructed. (The LED→camera rolling-shutter
  channel of Phase 0 is a different, hardware-assisted case.)

## 4. Quasi-invisible complementary frames (Candidate G) — feasibility only

Model: frame A = image + sΔ, frame B = image − sΔ per block, alternated each refresh; receiver differences
one capture of A and one of B (perfect geometry assumed). BER by Δ (8-bit levels), block 48 px, 40 cm:

| Channel | exposure mixes A/B by | Δ=1 | Δ=2 | Δ=4 | Δ=8 |
|---|---|---:|---:|---:|---:|
| Chroma (R−B) | 0% (clean, ≤ ~1 ms exposure) | 0.020 | 0.001 | 0.000 | 0.000 |
| Chroma | 25% | 0.105 | 0.020 | 0.001 | 0.000 |
| Chroma | 50% (auto-exposure ≥ 8 ms at 120 Hz) | 0.508 | 0.508 | 0.508 | 0.508 |
| Luma | 0% | 0.031 | 0.009 | 0.000 | 0.000 |
| Luma | 50% | 0.506 | 0.506 | 0.506 | 0.506 |

Single displayed frame PSNR vs original: 44 dB (chroma Δ=2), 38 dB (Δ=4). Capacity at block 48 px:
1 100 bits per A/B pair (≈ 15 pairs/s at best → ≤ 2 KB/s raw before FEC).

Verdict: **MARGINAL / NOT PRACTICAL as a default path** — the signal is detectable only with manual short
exposure (MANUAL_SENSOR), a guaranteed ≥ 120 Hz panel (Android treats frame-rate requests as hints) and A/B
synchronisation; with auto-exposure the two frames cancel in the camera exactly as in the eye. Perceptual
invisibility cannot be verified in the cloud.

## 5. Camera simulator: geometry and robustness (Candidates A–D)

`CameraSimulator`: the TX screen (1080×2400 px, 68×151 mm) is rendered through a pinhole camera with a
real phone's field of view (69° HFOV main camera) into the analysis frame, then ambient light, defocus /
motion blur, sensor noise, colour crosstalk, white-balance error, rolling-shutter mixing of two TX frames,
JPEG, glare and occlusion are applied; the real decoders (ZXing Java, GLG grid decoder) read the result.
**3 035 simulated frames**, 22 configurations, 5 seeds per condition. "Success" = fraction of frames whose
LabFrame CRC-32C verifies. Model goodput = success × (frame − 19 B) × 15 symbols/s × 0.97 (RaptorQ +
announce overhead).

| Config | Bytes/frame | px/cell @40 / 60 cm | Success 1080p 20/40/60/100 cm | Max cm (≥ 80%) 720p / 1080p / 4K | Model KB/s @60 cm (1080p) | Robustness mean (17 cases) | Fails (≤ 40%) at 40 cm |
|---|---:|---|---|---|---:|---:|---|
| QR-v6-M | 106 | 4.8 / 3.2 | 1.0/1.0/0.8/0.0 | 40 / 60 / 60 | 1.0 | 0.69 | angle 45°, glare, 50% mixed frame |
| QR-v8-M | 152 | 4.2 / 2.8 | 1.0/1.0/0.2/0.0 | 40 / 40 / 60 | 0.4 | 0.69 | angle 45°, 50% mixed frame |
| QR-v10-M | 213 | 3.7 / 2.4 | 1.0/0.8/0.0/– | 20 / 40 / 40 | 0.0 | 0.48 | angle 45°, motion 3 px, motion 6 px, occlusion, glare, 50% mixed frame |
| QR-v15-L | 520 | 2.8 / 1.9 | 0.8/0.2/0.0/– | <20 / 20 / 20 | 0.0 | 0.08 | angle 15°, angle 30°, angle 45°, INDOOR_DIM, INDOOR_BRIGHT, OUTDOOR_SHADE, motion 3 px, motion 6 px, JPEG q70, JPEG q40, occlusion, glare, wb_error, 50% mixed frame |
| QR-v20-L | 858 | 2.3 / 1.5 | 0.8/0.0/0.0/– | <20 / 20 / 20 | 0.0 | 0.00 | angle 15°, angle 30°, angle 45°, INDOOR_DIM, INDOOR_BRIGHT, OUTDOOR_SHADE, motion 3 px, motion 6 px, JPEG q70, JPEG q40, occlusion, glare, wb_error, 50% mixed frame |
| QR-v25-L | 1273 | 1.9 / 1.3 | 0.4/0.0/0.0/– | <20 / <20 / <20 | 0.0 | 0.00 | angle 15°, angle 30°, angle 45°, INDOOR_DIM, INDOOR_BRIGHT, OUTDOOR_SHADE, motion 3 px, motion 6 px, JPEG q70, JPEG q40, occlusion, glare, wb_error, 50% mixed frame |
| GRID-40x92-b1-p32 | 356 | 5.4 / 3.6 | 1.0/1.0/1.0/0.0 | 40 / 60 / 100 | 4.8 | 0.71 | angle 45°, occlusion, glare, 50% mixed frame |
| GRID-48x108-b1-p32 | 510 | 4.6 / 3.0 | 1.0/1.0/1.0/0.0 | 40 / 60 / 60 | 7.0 | 0.65 | angle 45°, occlusion, glare, 50% mixed frame |
| GRID-64x144-b1-p32 | 946 | 3.5 / 2.3 | 1.0/1.0/0.0/– | 20 / 40 / 60 | 0.0 | 0.49 | angle 15°, angle 45°, occlusion, glare, 50% mixed frame |
| GRID-64x144-b1-p32-f2 | 886 | 3.5 / 2.3 | 1.0/1.0/0.4/0.0 | 20 / 40 / 60 | 4.9 | 0.62 | angle 45°, occlusion, glare, 50% mixed frame |
| GRID-96x216-b1-p32 | 2218 | 2.4 / 1.6 | 1.0/0.0/0.0/– | 20 / 20 / 40 | 0.0 | 0.00 | angle 15°, angle 30°, angle 45°, INDOOR_DIM, INDOOR_BRIGHT, OUTDOOR_SHADE, motion 3 px, motion 6 px, JPEG q70, JPEG q40, occlusion, glare, wb_error, 50% mixed frame |
| GRID-40x92-b2-p32 | 714 | 5.4 / 3.6 | 1.0/1.0/0.8/0.0 | 40 / 60 / 100 | 7.9 | 0.69 | angle 45°, occlusion, glare, 50% mixed frame |
| GRID-48x108-b2-p32 | 1054 | 4.6 / 3.0 | 1.0/1.0/0.8/0.0 | 40 / 60 / 100 | 11.8 | 0.61 | angle 45°, occlusion, glare, 50% mixed frame |
| GRID-64x144-b2-p32 | 1926 | 3.5 / 2.3 | 1.0/1.0/0.0/– | 20 / 40 / 60 | 0.0 | 0.44 | angle 30°, angle 45°, OUTDOOR_SHADE, motion 3 px, JPEG q40, occlusion, glare, 50% mixed frame |
| GRID-48x108-b3-p48 | 1438 | 4.6 / 3.0 | 1.0/1.0/1.0/0.0 | 40 / 60 / 100 | 20.2 | 0.66 | angle 45°, occlusion, glare, 50% mixed frame |
| GRID-64x144-b3-p48 | 2650 | 3.5 / 2.3 | 1.0/1.0/0.0/– | 20 / 40 / 60 | 0.0 | 0.45 | angle 45°, motion 6 px, JPEG q70, JPEG q40, glare, 50% mixed frame |

Readings (all SIMULATED):

- **Pixels per cell decide everything.** Grids decode down to ~3 camera px per cell, QR down to ~3.5 px
  per module (ZXing's binarizer needs more margin). At 1080p / 1× the screen spans ~240 px across its
  short side at 40 cm, so the code's *cell count* — not its ECC level — sets the range: QR v20 at L, M, Q
  or H fails identically at 40 cm.
- **QR is capacity-starved at range.** The densest QR that decodes at 40 cm is v10-M (213 B/frame,
  2.2 KB/s); at 60 cm only v6-M survives (106 B/frame, 1.0 KB/s). No QR version reaches the 5 KB/s gate
  at ≥ 40 cm in the model.
- **The rectangular grid is the range/throughput sweet spot.** GLG-B1 48 columns: 510 B/frame, 7.0 KB/s
  from 20 to 60 cm (1080p). 64 columns doubles the frame (946 B) but stops at 40 cm; the 2-cell finder
  variant (`-f2`) buys partial 60 cm decoding (2/5). 4K analysis moves every limit out by ~1.5–2×.
- **Colour (Candidate D) at 48 columns is free in the model** (C2 11.8 KB/s, C3 20.2 KB/s at 60 cm) because
  the simulated colour channel is clean; at 64 columns it becomes fragile (JPEG, motion, outdoor shade).
  The model omits OLED sub-pixel layout, PWM, ISP tone mapping and per-phone colour rendering →
  **PROMISING, not proven**.
- **Common failure cases at 40 cm**: 45° viewing angle (foreshortening below the cell threshold on one
  axis), a glare spot or an occluding finger over a finder/large area, and frames that mix two TX symbols
  (rolling shutter) — the last is the frame loss the fountain layer absorbs (section 2) and the reason for
  the `camera_fps / 2` rate rule (section 3).

## 6. Virtual end-to-end loop (whole protocol through the simulator)

The **real** `TransmitterSession` (announce → data → re-announce every 3 s → END) is played on a simulated
60 Hz display; a simulated 30 fps camera (1080p analysis, 8 ms exposure, ±2° hand roll, frames mixing two
TX symbols with the timing model's probability) feeds the **real** `ReceiverSession` (ZXing Java + GLG
decoder, RaptorQ/LT/sequential decoders, SHA-256 check, GhostPacket check). Same plan at 40, 60 and 100 cm.
Camera work is skipped on data slots once a trial is complete or provably hopeless (no join, or nothing
decoded for 4 s); announce slots are always simulated. **26 recorded runs, 19 PASS — all SIMULATED.**

| Distance | Trial | PHY | fps | Scheme | Payload | Result | SHA-256 | Goodput KB/s | Frames decoded / seen | Unique / needed symbols | GhostPackets identical | Top failure |
|---:|---:|---|---:|---|---:|---|---|---:|---|---|---|---|
| 40 cm | 0 | QR-v10-M | 10 | RAPTORQ | 16 KB | PASS | PASS | 1.71 | 213 / 358 | 85 / 85 |  | not_found:27 |
| 40 cm | 1 | QR-v20-L | 15 | RAPTORQ | 64 KB | FAIL_INCOMPLETE | n/a | 0.00 | 4 / 694 | 4 / 79 |  | not_found:593 |
| 40 cm | 2 | GRID-48x108-b1-p32 | 15 | SEQUENTIAL | 64 KB | PASS | PASS | 3.40 | 453 / 643 | 134 / 134 |  | finders:80 |
| 40 cm | 3 | GRID-48x108-b1-p32 | 15 | LT | 64 KB | PASS | PASS | 4.58 | 327 / 477 | 184 / 134 |  | finders:62 |
| 40 cm | 4 | GRID-48x108-b1-p32 | 15 | RAPTORQ | 64 KB | PASS | PASS | 5.85 | 254 / 388 | 134 / 134 |  | finders:53 |
| 40 cm | 5 | GRID-40x92-b1-p32 | 15 | RAPTORQ | 64 KB | PASS | PASS | 4.33 | 353 / 508 | 195 / 195 |  | finders:66 |
| 40 cm | 6 | GRID-64x144-b1-p32 | 15 | RAPTORQ | 64 KB | PASS | PASS | 12.23 | 128 / 226 | 71 / 71 |  | finders:52 |
| 40 cm | 7 | GRID-40x92-b2-p32 | 15 | RAPTORQ | 64 KB | PASS | PASS | 8.57 | 166 / 302 | 95 / 95 |  | finders:63 |
| 40 cm | 8 | GRID-48x108-b2-p32 | 15 | RAPTORQ | 64 KB | PASS | PASS | 12.47 | 118 / 222 | 64 / 64 |  | orientation:30 |
| 40 cm | 9 | GRID-96x216-b1-p32 | 15 | RAPTORQ | 64 KB | FAIL_INCOMPLETE | n/a | 0.00 | 11 / 693 | 11 / 30 |  | finders:605 |
| 40 cm | 10 | GRID-48x108-b1-p32 | 15 | RAPTORQ | 256 KB | PASS | PASS | 6.15 | 987 / 1327 | 534 / 534 |  | finders:110 |
| 40 cm | 11 | QR-v10-M | 10 | RAPTORQ | 8 KB | PASS | PASS | 1.33 | 145 / 261 | 222 / 222 | 143/143 | checksum:16 |
| 40 cm | 12 | GRID-48x108-b1-p32 | 15 | RAPTORQ | 64 KB | PASS | PASS | 5.61 | 275 / 438 | 591 / 591 | 271/271 | finders:75 |
| 60 cm | 0 | QR-v10-M | 10 | RAPTORQ | 16 KB | FAIL_INCOMPLETE | n/a | 0.00 | 58 / 697 | 53 / 85 |  | not_found:617 |
| 60 cm | 1 | QR-v20-L | 15 | RAPTORQ | 64 KB | FAIL_NO_DATA | n/a | 0.00 | 0 / 227 | 0 / 79 |  | not_found:204 |
| 60 cm | 2 | GRID-48x108-b1-p32 | 15 | SEQUENTIAL | 64 KB | PASS | PASS | 2.24 | 703 / 920 | 134 / 134 |  | finders:119 |
| 60 cm | 3 | GRID-48x108-b1-p32 | 15 | LT | 64 KB | PASS | PASS | 4.66 | 334 / 451 | 179 / 134 |  | finders:65 |
| 60 cm | 4 | GRID-48x108-b1-p32 | 15 | RAPTORQ | 64 KB | PASS | PASS | 6.04 | 255 / 394 | 134 / 134 |  | finders:95 |
| 60 cm | 5 | GRID-40x92-b1-p32 | 15 | RAPTORQ | 64 KB | PASS | PASS | 4.28 | 382 / 548 | 195 / 195 |  | finders:128 |
| 60 cm | 6 | GRID-64x144-b1-p32 | 15 | RAPTORQ | 64 KB | FAIL_INCOMPLETE | n/a | 0.00 | 11 / 999 | 11 / 71 |  | finders:936 |
| 60 cm | 7 | GRID-40x92-b2-p32 | 15 | RAPTORQ | 64 KB | PASS | PASS | 8.81 | 182 / 301 | 95 / 95 |  | finders:94 |
| 60 cm | 8 | GRID-48x108-b2-p32 | 15 | RAPTORQ | 64 KB | PASS | PASS | 13.33 | 115 / 211 | 64 / 64 |  | finders:67 |
| 60 cm | 9 | GRID-96x216-b1-p32 | 15 | RAPTORQ | 64 KB | FAIL_NO_DATA | n/a | 0.00 | 0 / 220 | 0 / 30 |  | finders:211 |
| 60 cm | 10 | GRID-48x108-b1-p32 | 15 | RAPTORQ | 256 KB | PASS | PASS | 6.19 | 1006 / 1311 | 534 / 534 |  | finders:159 |
| 60 cm | 11 | QR-v10-M | 10 | RAPTORQ | 8 KB | FAIL_INCOMPLETE | n/a | 0.00 | 58 / 695 | 200 / 222 | 58/58 | not_found:611 |
| 60 cm | 12 | GRID-48x108-b1-p32 | 15 | RAPTORQ | 64 KB | PASS | PASS | 5.78 | 273 / 393 | 591 / 591 | 272/272 | finders:77 |

At **100 cm no trial was detected at all**: the announce is a QR v8-M, unreadable at 100 cm with 1080p / 1×
(camera simulator: 0/5 frames), so the receiver never learns the trial parameters even though a 40-column
grid would still decode there with 4K analysis. Goodput = payload / (first data frame → reconstruction).

Readings (SIMULATED):

- **Reliability scheme on the same PHY** (GLG-B1 48, 64 KB): at 40 cm RaptorQ **5.85**, LT 4.58, sequential
  3.40 KB/s; at 60 cm RaptorQ **6.04**, LT 4.66, sequential 2.24 KB/s. LT needed 34–37% more unique symbols
  than K; RaptorQ needed exactly K in every completed run.
- **The M1-gate shape passes in simulation**: GLG-B1 48, RaptorQ, **256 KB, SHA-256 PASS at 40 and 60 cm,
  6.15 / 6.19 KB/s** (one run per distance — a simulation, not evidence for the physical gate).
- **Colour**: GLG-C2 48 reaches 12.5 / 13.3 KB/s (40 / 60 cm), about 2.1× binary — the simulator's colour
  channel is clean, see section 5.
- **Model vs protocol**: the protocol delivers 84–88% of the camera-model goodput for GLG-B1 48 (5.85–6.19
  vs 7.0 KB/s): re-announce slots (4 of every 49), frames mixing two symbols, and the first-frame latency.
- **GhostPacket v0 over the visual channel**: OBJ_SYMBOL GhostPackets in GhostFrame v0 were reconstructed
  **byte-identical** (143/143 over QR v10-M at 40 cm; 271/271 and 272/272 over GLG-B1 48 at 40 / 60 cm) and
  the object's SHA-256 verified. QR v10-M at 60 cm did not complete (58 packets, all identical).
- **Lab bug found here**: the receiver reopened a completed trial on later re-announces (duplicate run_id,
  spurious failures). Fixed in `ReceiverSession` with a regression test before these numbers were taken.

## 7. QR decoders: ZXing Java vs zxing-cpp vs ML Kit

| | ZXing Java 3.5.3 | zxing-cpp 2.3.0 | ML Kit barcode 17.3.0 (bundled) |
|---|---|---|---|
| License | Apache-2.0 | Apache-2.0 | Proprietary ML Kit terms (closed) |
| Offline | Yes | Yes | Yes (bundled model; the Play-Services variant downloads the model → excluded) |
| Dependency size | core jar 608 KB | AAR 2.7 MB (native .so 1.2–1.7 MB per ABI) | native model + libs, several MB (APK impact TO MEASURE) |
| Integration | Pure JVM (same code in cloud tests and on phone) | `BarcodeReader.read(ImageProxy)`, NDK prebuilt | Async `Task` API, `InputImage.fromMediaImage` |
| Binary payload | Byte segments (verified in tests) | `Result.bytes` (API verified) | `rawBytes` for byte-mode QR **unverified** → checked by CRC on device |
| Cloud JVM latency per frame (median of per-condition medians) | 720p 3.4 ms · 1080p 6.8 ms · 4K 26.7 ms (desktop CPU) | not runnable in cloud | not runnable in cloud |
| Physical latency / success | PENDING (compare-all mode records all three on the same frames) | PENDING | PENDING |

Default hypothesis: zxing-cpp (open, native, fast in the project's own benchmarks); the decision is made on
device by the DECODER COMPARE plan.

## 8. Camera pipeline (brief §40)

The receiver records per frame: capture→analysis latency (when the sensor timestamp source is REALTIME),
plane copy, binarisation/preprocess, detection, sampling/classification, Reed–Solomon, fountain add.
Cloud JVM breakdown (25 cm simulated frames, 25 decodes each, desktop CPU — phones expected several times slower):

| Config | Analysis | Frames OK | Preprocess (luma/binarise) ms | Detect ms | Sample + classify ms | RS ms | Total p50 / p90 ms | RaptorQ add per symbol ms |
|---|---|---:|---:|---:|---:|---:|---|---:|
| QR-v10-M | 1080p | 19/25 | 5.76 | 1.10 | (inside ZXing) | (inside ZXing) | 6.8 / 7.0 | 0.01 |
| QR-v10-M | 720p | 25/25 | 2.51 | 0.68 | (inside ZXing) | (inside ZXing) | 3.2 / 3.3 | 0.01 |
| QR-v20-L | 1080p | 25/25 | 5.61 | 1.51 | (inside ZXing) | (inside ZXing) | 7.4 / 8.8 | 0.01 |
| QR-v20-L | 720p | 0/25 | 2.53 | 0.68 | — | — | 3.1 / 3.6 | 0.01 |
| GLG-B1 48 | 1080p | 25/25 | 6.07 | 2.63 | 0.09 | 0.20 | 8.7 / 10.6 | 0.01 |
| GLG-B1 48 | 720p | 25/25 | 2.70 | 1.43 | 0.11 | 0.21 | 3.9 / 6.4 | 0.01 |
| GLG-B1 64 | 1080p | 25/25 | 5.74 | 2.63 | 0.15 | 0.37 | 8.9 / 9.1 | 0.01 |
| GLG-C2 48 | 1080p | 25/25 | 6.24 | 2.74 | 0.71 | 0.42 | 9.6 / 10.7 | 0.02 |
| GLG-C3 48 | 1080p | 25/25 | 5.97 | 2.66 | 0.77 | 0.84 | 10.1 / 10.9 | 0.02 |
| GLG-C3 48 | 720p | 25/25 | 2.39 | 1.18 | 0.74 | 0.80 | 5.0 / 5.5 | 0.02 |

Over all simulated frames: grid decoder median 4.2 ms (720p), 8.6 ms (1080p), 33 ms (4K); ZXing 3.4 / 6.8 /
26.7 ms. Two-thirds of the time is the full-frame luma pass (preprocess), which the phone receiver can
shrink with a region of interest once the code is locked. The 25 decodes cycle over 4 distinct simulated frames:
QR-v10-M's 19/25 means one of the four 1080p frames never decodes (cause not investigated), while v20-L
reads all four at the same distance — a reminder that a few frames per condition is a coarse estimate.

NDK is **not** introduced: on the cloud JVM every stage is far below the 67 ms budget of 15 symbols/s; the
physical `decode_latency_ms` decides whether a native path is ever justified (brief §41).

## 9. Bidirectional control channel (brief §39)

Not implemented. With RaptorQ the bulk needs **no ACK** (section 2). What a back-channel would add: "DONE"
(stop the TX early — biggest practical win for benchmark time and battery), rate hints, session/capability
negotiation. Minimal design: acoustic two-tone DONE (M1.1) or a QR shown by the receiver to the TX front
camera. Details: `M1B_AUDIO_FALLBACK_PREP.md`.
