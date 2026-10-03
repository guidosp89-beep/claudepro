# GHOSTLINK — M1 Physical Link Lab

Isolated experimental environment for the M1 **visual PHY bake-off** (screen → camera between two
ordinary Android phones, no network, no extra hardware). Nothing here is a dependency of `core/`,
`android/` or `firmware/` (those do not exist yet; when they do, they must not import this spike).

Reports: [`docs/experiments/M1_FINAL_REPORT.md`](../../docs/experiments/M1_FINAL_REPORT.md),
[`M1_VISUAL_TECH_BAKEOFF.md`](../../docs/experiments/M1_VISUAL_TECH_BAKEOFF.md),
[`ADR_M1_VISUAL_PHY_SELECTION.md`](../../docs/decisions/ADR_M1_VISUAL_PHY_SELECTION.md).
Physical test procedure: [`OWNER_PHYSICAL_TEST_GUIDE.md`](OWNER_PHYSICAL_TEST_GUIDE.md).

## Layout

```text
physical_link_lab/
├── codecs/      (JVM, Android-safe) lab frame, CRC-32C, deterministic payloads, erasure layer
│                (sequential / LT / RaptorQ), visual encoders (QR via ZXing, GLG grid 1/2/3 bpc),
│                minimal GhostPacket v0 (OBJ_SYMBOL) for the transport proof
├── decoder/     (JVM, Android-safe) YUV frame model, ZXing Java QR decoder, finder detector,
│                GLG grid image decoder (homography, orientation keys, palette references, RS)
├── benchmark/   (JVM, Android-safe) announces, sweep plans (coarse/fine/confirm/robustness),
│                TransmitterSession / ReceiverSession, ranking (stage 2), runs.csv + raw JSON + zip
├── generators/  (JVM only, AWT) camera simulator, display/camera timing model, invisible-channel model
├── labtools/    (JVM only) cloud experiment CLIs -> results/cloud/
├── android/     separate Gradle build: the single TX/RX/AUTO-BENCHMARK app (includes the JVM build)
├── scripts/     analyze_m1.py (tables, M1 gate, plots), cloud_tables.py (results/cloud/CLOUD_TABLES.md)
├── datasets/    generated/ (git-ignored) simulated camera frames
└── results/     cloud/ (committed, SIMULATED), physical/ (owner exports), analysis/ (generated)
```

PHY and reliability are separate layers (brief §29):
`payload → ErasureEncoder → LabFrame → VisualFrameEncoder → screen … camera → FrameDecoder → LabFrame → ErasureDecoder → payload`.

## Build & test

```bash
cd spikes/physical_link_lab
./gradlew test                                   # unit, property (seeded) and fuzz tests
./gradlew :labtools:run --args="all"             # cloud experiments (≈ 60–75 min on 4 vCPU; the virtual loop is ~45 min); add --quick for a smoke run
python3 scripts/cloud_tables.py                  # regenerate results/cloud/CLOUD_TABLES.md
python3 scripts/analyze_m1.py [exports...]       # analysis + plots (pandas, matplotlib)
./gradlew -p android :app:assembleDebug          # APK (needs Android SDK + Google Maven access)
```

The APK is also built by GitHub Actions (`.github/workflows/m1-link-lab.yml`) on every push touching
this directory; download the `ghostlink-link-lab-apk` artifact.

## Reproducibility (versions)

| Component | Version |
|---|---|
| Gradle (wrapper) | 8.14.3 |
| Android Gradle Plugin | 8.10.1 (needs an Android Studio release that supports AGP 8.10 — verify in Studio's compatibility table) |
| Kotlin | 2.1.21 |
| JDK | 17 (CI: Temurin 17; JDK 21 also works locally) |
| compileSdk / targetSdk / minSdk | 35 / 35 / 26 |
| CameraX | 1.4.2 |
| ZXing core | 3.5.3 (Apache-2.0) |
| zxing-cpp Android | 2.3.0 (Apache-2.0; 3.x needs Kotlin ≥ 2.3) |
| ML Kit barcode-scanning (bundled) | 17.3.0 (proprietary terms, on-device model) |
| raptorq-kotlin | 1.0.0 (Apache-2.0) |
| JUnit | 5.11.4 |

## Offline guarantee

The app requests only `CAMERA`; `INTERNET` and `ACCESS_NETWORK_STATE` are stripped from the merged
manifest (`tools:node="remove"`) and CI fails if they reappear. ML Kit is the bundled variant (no model
download).

## Known limitations of the spike

- Fixed trial durations (no back-channel): the transmitter cannot stop early when the receiver is done.
- Trial parameters travel in a QR v8-M announce, readable only to ~40–60 cm at 1080p / 1× (simulated):
  long-range points need 4K analysis or 2× zoom.
- Grid decoder assumes the whole code is in view and roughly planar (no lens-distortion model).
- `ImageHolder` hands the current `ImageProxy` to zxing-cpp / ML Kit through a field (single analysis thread).
- The camera simulator is a pre-screening model; every cloud number is labelled SIMULATED.
