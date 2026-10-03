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
   0.25 (sequential carousel). RaptorQ decoded with **0 extra symbols** in every benchmark trial from
   K = 1 to K = 5 243 (failure at exactly K: 1.7% only at K = 5), decode ≈ 50–58 MB/s on the cloud JVM
   — three orders of magnitude above any visual rate. Sequential QR needs 2.5–9× more frames.
2. **TX frame rate** — with a 30 fps camera and 8 ms exposure the number of *distinct clean* symbols
   captured per second peaks at **15** (TX 15/s); 20/s drops to 15.5 (average) and 9.9 (worst phase);
   30/s collapses (3.7). Higher rates only pay with a 60 fps camera or ≤ 1 ms exposure.
3. **Geometry dominates the PHY choice** — at 1080p analysis and 1× zoom a phone screen 40 cm away
   spans only ~240 camera pixels across its short side. QR is square and wastes the long side: QR v20
   gets ~2.3 px/module at 40 cm and fails; QR v10-M is the largest QR that decodes reliably at 40 cm,
   QR v6–v8 at 60 cm. The rectangular grid uses the whole screen (see tables for success/goodput per
   distance).
4. **Colour** — 2-bit colour decodes in the simulator where binary does, but the simulator's colour model
   (uniform crosstalk, one white-balance error) is optimistic; cimbar's maintainers report 8 colours as
   "inconsistent" on real phones. 3 bits/cell is the most fragile configuration in our model too.
5. **Invisible channel** — complementary frames are detectable only if the camera exposure captures one
   frame without the other: with auto-exposure (≥ 8 ms at 120 Hz) the two frames cancel in the camera
   exactly as in the eye (BER ≈ 0.5). Needs manual short exposure, a guaranteed ≥ 120 Hz panel and
   A/B synchronisation.

## Decision (provisional)

**Selected Visual PHY v0 (to be confirmed physically):**

| Layer | Choice |
|---|---|
| Reliability | **RaptorQ**, single source block, symbol = visual frame payload (raptorq-kotlin 1.0.0 with the documented `solve()` workaround). LT and sequential rejected. |
| Framing | LabFrame (19 B: header + CRC-32C), self-describing so the receiver can join mid-stream |
| TX rate | **15 symbols/s** (≤ camera fps / 2); 20/s only if the receiver reports ≥ 60 fps |
| PHY "compat" | **QR v10-M** (binary byte mode): universal decoders, short range (≤ ~40 cm at 1080p) |
| PHY "throughput" | **GLG-B1 rectangular grid, 48–64 columns, 1 bit/cell, RS 32/255** — the candidate to meet the M1 gate at ≥ 50 cm |
| Colour | GLG-C2 (2 bit) kept EXPERIMENTAL until measured on ≥ 2 phones; GLG-C3 (3 bit) not pursued |
| Receiver | CameraX ImageAnalysis 1080p YUV, fixed 30 fps AE range; 4K analysis as a range option |
| QR decoder | zxing-cpp (primary hypothesis), ZXing Java (shared JVM reference), ML Kit only if the comparison shows a clear win (closed source) |

**Selection between QR v10-M and GLG-B1 as the default is delegated to the physical AUTO run**: the
receiver's stage-2 ranking (median goodput × success rate) picks the winner per placement.

## Rejected alternatives

| Alternative | Reason (evidence) |
|---|---|
| Sequential QR (Candidate A) | 0.25 efficiency at 20% loss; 0.50 already at 5% loss (coupon-collector effect) |
| LT fountain | 25–40% more frames than RaptorQ at 5–20% loss; systematic LT repairs mostly hit already-known blocks |
| Large QR (v20+) at ≥ 40 cm | Insufficient camera pixels per module at 1080p / 1× (kept in the coarse plan to let physics disagree) |
| 3 bits/cell colour | Most fragile in the model; independent field report (cimbar) of inconsistency |
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

## Next validation (pre-registered)

Run `OWNER_PHYSICAL_TEST_GUIDE.md`, then `scripts/analyze_m1.py`. The script applies the brief's rule
mechanically: best *stable* configuration (≥ 3 runs, ≥ 80% SHA-256 PASS, ≥ 50 cm, ≥ 256 KB) median
goodput ≥ 20 KB/s → Case A (M2); 5–20 → Case B (M1.1); < 5 → Case C (M1B); passes only in one
direction/device → Case D (M1C). This ADR becomes ACCEPTED or SUPERSEDED accordingly.
