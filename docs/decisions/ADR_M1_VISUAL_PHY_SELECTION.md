# ADR M1 — Visual PHY selection (v0, PROVISIONAL)

- **Status**: PROVISIONAL — based on cloud-automated evidence only. Physical validation (two phones)
  is PENDING; this ADR pre-registers how the physical data will confirm or overturn it.
- **Date**: 2026-10-03
- **Deciders**: M1 lab (spike `spikes/physical_link_lab`), owner to confirm after the physical run.

## Context

M1 must find the best visual transport between two unmodified Android phones without any network
or extra hardware, measured with byte-exact verification. The cloud environment has no phones, no
camera and cannot reach Google's Android repositories, so M1 was split into:

- **CLOUD-AUTOMATED (done)**: codecs, erasure layer, decoders, a physically-grounded camera simulator
  (pinhole geometry with real phone FOV and screen size, ambient light, blur, noise, rolling-shutter
  mixing, JPEG, glare, occlusion), a display/camera timing model, an end-to-end virtual loop of the real
  TX/RX session code, CI-built APK.
- **PHYSICAL-DEVICE (pending)**: the AUTO BENCHMARK in the APK (`OWNER_PHYSICAL_TEST_GUIDE.md`).

Evidence tables: `spikes/physical_link_lab/results/cloud/CLOUD_TABLES.md` (generated from CSV).

## Candidates

| Id | Candidate | Implemented | Cloud evidence |
|---|---|---|---|
| A | Dynamic QR, sequential chunks (no cross-frame FEC) | Yes (`SEQUENTIAL`) | Loss bench |
| B | Dynamic QR + fountain (LT, RaptorQ) | Yes (`LT`, `RAPTORQ`) | Fountain + loss bench, camera sim |
| C | Dense binary grid (GLG, rectangular, full screen) | Yes (1 bit/cell) | Camera sim, virtual loop |
| D | Colour grid (2 bit / 3 bit per cell) | Yes | Camera sim |
| E | Temporal modulation (TX faster than the camera) | Model | Timing model |
| F | Rolling-shutter exploitation | Model | Timing model (tiling) |
| G | Quasi-invisible complementary frames | Model | Veil model |

## Experimental evidence (cloud, SIMULATED)

1. **Reliability layer** — under 5–50% random loss, bursts, duplicates and reordering, RaptorQ reaches
   ≈ the ideal efficiency (1 − loss): e.g. 0.80 at 20% loss vs 0.59 (LT + Gauss), 0.58 (LT peeling) and
   0.25 (sequential carousel). RaptorQ decoded from exactly K symbols in 162 of 163 benchmark decodes
   from K = 1 to K = 5 243 (the exception, at K = 5, needed K + 1), decode ≈ 50–58 MB/s on the cloud JVM
   — three orders of magnitude above any visual rate. Sequential QR needs 2.5–9× more frames.
2. **TX frame rate** — with a 30 fps camera and 8 ms exposure the number of *distinct clean* symbols
   captured per second peaks at **15** (TX 15/s); 20/s drops to 15.5 (average) and 9.9 (worst phase);
   30/s collapses (3.7). Higher rates only pay with a 60 fps camera or ≤ 1 ms exposure.
3. **Geometry dominates the PHY choice** — at 1080p analysis and 1× zoom a phone screen 40 cm away
   spans only ~240 camera pixels across its short side. Decoding needs roughly ≥ 3 camera px per grid
   cell and ≥ 3.5 px per QR module. QR is square and wastes the long side: QR v20 gets 2.3 px/module at
   40 cm and fails (0/5 frames); QR v10-M decodes 4/5 at 40 cm and 0/5 at 60 cm; QR v6-M reaches 60 cm
   but carries only 87 B per frame. Model goodput at 15 symbols/s: **QR ≤ 2.2 KB/s at ≥ 40 cm** (any
   version), so QR alone cannot meet the M1 gate in the model. The rectangular grid uses the whole
   screen: **GLG-B1 48 columns: 7.0 KB/s at 20–60 cm** (fails at 100 cm, 1.8 px/cell); 64 columns:
   13.2 KB/s but only to 40 cm; ≥ 96 columns fail at ≥ 40 cm. 4K analysis extends the range ~1.5–2×
   (48 columns B1 to 60 cm solid / 100 cm partial; 40 columns to 100 cm).
4. **Colour** — at 48 columns the colour grids decode as far as binary in the simulator: GLG-C2 1 054 B
   per frame, 11.8 KB/s at 60 cm (4/5 frames); GLG-C3 1 438 B per frame, 20.2 KB/s at 60 cm (5/5). At 64
   columns colour is clearly more fragile than binary (JPEG q70: C3 1/5; motion 6 px: C3 0/5; outdoor
   shade: C2 2/5). The simulator's colour model (uniform crosstalk, one white-balance error, no OLED
   sub-pixel or ISP colour processing) is **optimistic**, and cimbar's maintainers report 8 colours as
   "inconsistent" on real phones — the colour advantage is therefore a hypothesis for the physical run,
   not a result.
5. **Invisible channel** — complementary frames are detectable only if the camera exposure captures one
   frame without the other: with auto-exposure (≥ 8 ms at 120 Hz) the two frames cancel in the camera
   exactly as in the eye (BER ≈ 0.5). Needs manual short exposure, a guaranteed ≥ 120 Hz panel and
   A/B synchronisation.
6. **Virtual end-to-end loop** (real TX/RX session code through the simulator, 26 runs): on GLG-B1 48 at
   40 / 60 cm RaptorQ delivers 5.85 / 6.04 KB/s vs LT 4.58 / 4.66 and sequential 3.40 / 2.24; the
   gate-shaped trial (GLG-B1 48, RaptorQ, **256 KB**) passes SHA-256 at 40 and 60 cm with 6.15 / 6.19 KB/s;
   GLG-C2 48 reaches 12.5 / 13.3 KB/s; GhostPacket v0 objects arrive byte-identical over QR and grid. At
   100 cm no trial is detected because the announce QR (v8-M) is unreadable.

## Decision (provisional)

**Selected Visual PHY v0 (to be confirmed physically):**

| Layer | Choice |
|---|---|
| Reliability | **RaptorQ**, single source block, symbol = visual frame payload (raptorq-kotlin 1.0.0 with the documented `solve()` workaround). LT and sequential rejected. |
| Framing | LabFrame (19 B: header + CRC-32C), self-describing so the receiver can join mid-stream |
| TX rate | **15 symbols/s** (≤ camera fps / 2); 20/s only if the receiver reports ≥ 60 fps |
| PHY "compat" | **QR v10-M** (binary byte mode): universal decoders, short range (≤ ~40 cm at 1080p) |
| PHY "throughput" | **GLG-B1 rectangular grid, 48 columns, 1 bit/cell, RS 32/255** — the safe candidate for the M1 gate at ≥ 50 cm (model: 7.0 KB/s); 64 columns only at ≤ 40 cm |
| Colour | **GLG-C2 / GLG-C3 at 48 columns: PROMISING in simulation** (11.8 / 20.2 KB/s at 60 cm) but EXPERIMENTAL until measured on ≥ 2 phones; both are in the physical coarse plan and win only through the stage-2 ranking |
| Receiver | CameraX ImageAnalysis 1080p YUV, fixed 30 fps AE range; 4K analysis as a range option |
| QR decoder | zxing-cpp (primary hypothesis), ZXing Java (shared JVM reference), ML Kit only if the comparison shows a clear win (closed source) |

**Selection among GLG-B1, GLG-C2, GLG-C3 (48 columns) and QR v10-M as the default is delegated to the
physical AUTO run**: the receiver's stage-2 ranking (median goodput × success rate) picks the winner per
placement. The simulator ranks C3 > C2 > B1 ≫ QR on goodput and puts the three grids level on robustness (mean
success over the 17 robustness cases: B1 0.65, C2 0.61, C3 0.66 — within the noise of 5 frames per
case); only phones can say whether the colour gain survives real displays and ISPs.

## Rejected alternatives

| Alternative | Reason (evidence) |
|---|---|
| Sequential QR (Candidate A) | 0.25 efficiency at 20% loss; 0.50 already at 5% loss (coupon-collector effect) |
| LT fountain | 25–40% more frames than RaptorQ at 5–20% loss; systematic LT repairs mostly hit already-known blocks |
| Large QR (v15+) at ≥ 40 cm | 2.3–2.8 px/module at 40 cm (1080p / 1×): 0–1 of 5 frames decode (kept in the coarse plan to let physics disagree) |
| QR as the throughput PHY | ≤ 2.2 KB/s model goodput at ≥ 40 cm for every version: below the 5 KB/s gate |
| Grids ≥ 96 columns, and colour at ≥ 64 columns, for ≥ 50 cm | Below ~3 px/cell at 60 cm (1080p); 64-column colour also fails JPEG/motion/outdoor robustness cases |
| TX > camera fps / 2 (Candidate E) | No additional distinct symbols; fewer at worst phase |
| Rolling-shutter tiling (Candidate F) | Gains only at ≥ 30 symbols/s with parallel scan geometry; screens cannot modulate faster than refresh — EXPERIMENTAL, stopped |
| Quasi-invisible frames (Candidate G) | MARGINAL: requires manual exposure + guaranteed 120 Hz + sync; NOT PRACTICAL as a default path |

## Risks

- The camera simulator may be too pessimistic (phone ISP sharpening, better optics) or too optimistic
  (autofocus hunting, moiré, OLED PWM, colour rendering); both directions are tested by the coarse plan.
- Receiver CPU: grid/QR decode on a phone may limit analysis fps below 15/s (recorded as `decode_latency_ms`;
  NDK only if profiling shows it is the bottleneck).
- raptorq-kotlin is a young library (single 1.0.0 release) with an API defect we work around; RaptorQ IPR
  must be reviewed before any distribution.
- Fixed trial durations without a back-channel make sessions long (mitigation: acoustic DONE in M1.1).
- The announce QR (v8-M) limits trial detection to ~40–60 cm at 1080p / 1×; long-range robustness points
  need 4K analysis or 2× zoom until a grid-based announce exists (M1.1).

## Next validation (pre-registered)

Run `OWNER_PHYSICAL_TEST_GUIDE.md`, then `scripts/analyze_m1.py`. The script applies the brief's rule
mechanically: best *stable* configuration (≥ 3 runs, ≥ 80% SHA-256 PASS, ≥ 50 cm, ≥ 256 KB) median
goodput ≥ 20 KB/s → Case A (M2); 5–20 → Case B (M1.1); < 5 → Case C (M1B); passes only in one
direction/device → Case D (M1C). This ADR becomes ACCEPTED or SUPERSEDED accordingly.
