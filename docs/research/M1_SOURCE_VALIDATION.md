# M1 — Source validation supplement

Data: 2026-10-03. Scopo: verificare solo ciò che può cambiare le scelte implementative di M1.
Metodo: lettura di README/sorgenti tramite `raw.githubusercontent.com`, metadati di Maven Central
(`repo1.maven.org`), ricerche web. Domini **non raggiungibili** da questo ambiente cloud (policy di
rete): `dl.google.com` (Android SDK, Google Maven: AGP, CameraX, ML Kit), `github.com` HTML/API,
`arxiv.org`, `divan.github.io`, `developer.android.com` (contenuto). Dove una fonte primaria non era
leggibile è indicato.

## 1. Progetti e librerie

| Progetto | Repository | Licenza | Linguaggio | Ultima attività significativa | Android | Codec | Throughput dichiarato | Throughput misurato da noi | Dipendenze | Stato |
|---|---|---|---|---|---|---|---|---|---|---|
| **libcimbar / cimbar** | github.com/sz3/libcimbar | MPL-2.0 | C++ | attivo (mode B dalla 0.6.0) | Solo decoder (arm64) via **cfc** | Tile 8×8 con 16 forme (4 bit) + 4 colori (2 bit), RS 30/155, wirehair fountain, zstd | **852 kbit/s (~106 KB/s)**, 4.69 MB in 44 s, sender = monitor (cimbar.org), receiver = Snapdragon 625; mode 8C (8 colori) ~118 KB/s ma "always been inconsistent", rimosso | Nessuno (richiede due telefoni) | OpenCV, wirehair, zstd | Mantenuto |
| **cfc (CameraFileCopy)** | github.com/sz3/cfc | MIT (+MPL per libcimbar) | Java/C++ | rilasci F-Droid/Play | Sì, **solo ricevitore**, arm64-v8a | cimbar | — | — | OpenCV Android SDK, NDK | Demo app mantenuta |
| **TXQR** | github.com/divan/txqr | MIT | Go | fermo; autore riscritto in Dart con RaptorQ (non verificato) | Lettore iOS via gomobile | QR + LT (Luby) | Misure nel blog dell'autore (dominio bloccato: **non verificato**) | — | — | Inattivo |
| **TXQR-Android** | github.com/ThePlasmaRailgun/TXQR-Android | n.d. | Flutter + Kotlin | vecchio (Flutter 1.5) | Sì | QR + LT, anche GIF | n.d. | — | Flutter | **Abbandonato** |
| **Decimen Optical Transfer** | github.com/tongatron/decimen-optical-transfer | MIT | Web (JS) | 2026 | Via browser | QR + LT | ~128–129 KB/s phone→phone a mano, ~186 KB/s ideale (display 120 Hz) — solo stampa/README, nessun dataset | — | Browser | Nuovo, non verificato |
| **LighteningSend** | github.com/intelQong/LighteningSend | n.d. | Web | 2025–26 | Via browser | QR animati | n.d. | — | — | Fork di airgapped-qr-code-transfer |
| **qr_steam** | pub.dev/packages/qr_steam | n.d. | Dart/Flutter | 2025–26 | Flutter | QR sequenziale o LT | n.d. | — | — | Piccolo |
| **ggwave** | github.com/ggerganov/ggwave | MIT | C++ (+ Java/KMP/JS bindings) | mantenuto | Sì (ggwave-java, ggwave-kmm) | MFSK 6 toni, dF = 46.875 Hz, RS | **8–16 B/s** | — (M1B) | — | Mantenuto |
| **ZXing (Java)** | github.com/zxing/zxing | Apache-2.0 | Java | 3.5.4 (Maven Central, 2025) — "maintenance mode" | `core` puro Java gira su Android; l'app Barcode Scanner non supporta Android 14 | QR e altri | — | **Cloud: vedi M1 bakeoff (decode su immagini simulate)** | nessuna | Manutenzione |
| **zxing-cpp** | github.com/zxing-cpp/zxing-cpp | Apache-2.0 | C++ con wrapper Android (Kotlin + JNI) | 3.1.1 su Maven Central (lug 2026) | `io.github.zxing-cpp:android` (AAR, NDK incluso) | QR, Micro QR, rMQR, DataMatrix, Aztec… | "più veloce e migliore detection del Java" (README) | Pending (fisico) | 3.x: kotlin-stdlib 2.3.20, CameraX 1.5.2; **2.3.0: stdlib 1.9.10, CameraX 1.4.1** | Molto attivo |
| **ML Kit Barcode Scanning** | developers.google.com/ml-kit | Proprietaria (ML Kit Terms) | closed | 17.3.0 bundled | Sì; **bundled** `com.google.mlkit:barcode-scanning:17.3.0` funziona offline; la variante "unbundled" (Play Services) scarica il modello → **non conforme al requisito offline** | QR, ecc. | — | Pending (fisico) | Google Maven, Play Services Tasks | Mantenuto, closed source |
| **raptorq (Rust)** | github.com/cberner/raptorq | Apache-2.0 | Rust | attivo | Solo via JNI/NDK | RaptorQ RFC 6330 | 7–30 Gbit/s encode su desktop (README) | Non usato in M1 (no NDK prematuro) | — | Attivo |
| **raptorq-kotlin** | github.com/andreypfau/raptorq-kotlin | Apache-2.0 | Kotlin Multiplatform puro | 1.0.0 (Maven Central, ago 2025) | Sì (artefatto `-android` e `-jvm`) | RaptorQ RFC 6330 | n.d. | **Cloud: vedi fountain benchmark** | nessuna nativa | Giovane, 1 sola release → rischio maturità |
| **OpenRQ** | (Java, Apache-2.0) | Apache-2.0 | Java | storico (~2015) | Possibile | RaptorQ | — | Non trovato su Maven Central (`net.fec.openrq` assente) | — | **Abbandonato** |

## 2. API Android rilevanti (confermate dalla documentazione nota; pagine developer.android.com non leggibili da qui)

| API | Uso nello spike | Nota |
|---|---|---|
| CameraX `ImageAnalysis` (YUV_420_888, `STRATEGY_KEEP_ONLY_LATEST`) | Receiver | Un solo frame in analisi; i frame arrivati nel frattempo sono scartati → contare i "drop" via timestamp |
| `Camera2Interop.Extender.setCaptureRequestOption(CONTROL_AE_TARGET_FPS_RANGE, …)` | Forzare 30/60 fps | Solo range annunciati in `CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES` |
| `Camera2CameraControl.setCaptureRequestOptions(CONTROL_AE_LOCK=true)` | LOCK EXPOSURE | Disponibile su tutti i livelli hardware |
| `CONTROL_AE_MODE_OFF` + `SENSOR_EXPOSURE_TIME`/`SENSOR_SENSITIVITY` | SHORT EXPOSURE | Solo se `REQUEST_AVAILABLE_CAPABILITIES` contiene `MANUAL_SENSOR` |
| `CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW` | Device profile | Valore per frame (ns), letto via session capture callback; non esiste come caratteristica statica |
| `CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE` | Latenza capture→analysis | Solo `REALTIME` è confrontabile con `SystemClock.elapsedRealtimeNanos()` |
| `Display.getSupportedModes()`, `WindowManager.LayoutParams.preferredDisplayModeId`, `Surface.setFrameRate()` (API 30) | Transmitter 60/90/120 Hz | Il sistema può ignorare la richiesta (LTPO, risparmio energetico) → misurare il refresh reale con `Choreographer` |
| `SensorManager` TYPE_LIGHT | Lux ambientale (receiver) | Sensore frontale: misura la luce verso lo schermo, non sul display del TX |
| `PowerManager.getCurrentThermalStatus()` (API 29), `getThermalHeadroom()` (API 30) | Thermal | |

## 3. Implicazioni per l'implementazione M1

1. **cimbar dimostra che il collo di bottiglia è la camera, non la CPU** (dichiarato dall'autore) e che
   ~7 500 B/frame a ~14 frame/s sono ottenibili *monitor→phone*. È il riferimento "alto" da battere o
   eguagliare; **8 colori sono instabili** anche per loro → il nostro candidato 3 bit/cella va trattato
   come ipotesi debole.
2. **zxing-cpp 2.3.0** (non 3.x) per compatibilità con Kotlin 2.1 e CameraX 1.4.x; API `read(ImageProxy)`
   e `Result.bytes` (binario) verificate sul sorgente del tag v2.3.0.
3. **ML Kit solo in variante bundled** per rispettare il requisito offline; nessuna garanzia sul campo
   `rawBytes` per QR in byte mode → il benchmark lo verifica con CRC.
4. **RaptorQ su JVM/Android senza NDK**: unica opzione mantenuta è `raptorq-kotlin` 1.0.0 (giovane);
   va validata empiricamente (overhead, correttezza, velocità) contro un LT implementato nello spike.
5. Nessun progetto trovato pubblica un **dataset riproducibile phone→phone** con mediane/percentili:
   il claim di Decimen e quello di cimbar restano non verificati per il caso telefono→telefono.

Fonti: <https://raw.githubusercontent.com/sz3/libcimbar/master/PERFORMANCE.md>,
<https://raw.githubusercontent.com/sz3/cfc/master/README.md>, <https://raw.githubusercontent.com/divan/txqr/master/README.md>,
<https://raw.githubusercontent.com/zxing-cpp/zxing-cpp/v2.3.0/wrappers/android/zxingcpp/src/main/java/zxingcpp/BarcodeReader.kt>,
<https://repo1.maven.org/maven2/io/github/zxing-cpp/android/>, <https://raw.githubusercontent.com/andreypfau/raptorq-kotlin/main/README.md>,
<https://repo1.maven.org/maven2/io/github/andreypfau/raptorq-kotlin/maven-metadata.xml>, <https://raw.githubusercontent.com/cberner/raptorq/master/README.md>,
<https://raw.githubusercontent.com/ggerganov/ggwave/master/README.md>, <https://github.com/tongatron/decimen-optical-transfer>,
<https://github.com/ThePlasmaRailgun/TXQR-Android>, <https://github.com/intelQong/LighteningSend>, <https://pub.dev/packages/qr_steam>,
<https://developers.google.com/ml-kit/vision/barcode-scanning/android>.
