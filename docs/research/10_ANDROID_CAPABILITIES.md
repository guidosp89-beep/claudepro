# 10 — Android Capabilities & Limits

Riferimento: Android 10–16 (API 29–36), app non privilegiata, nessun root.

## 1. Matrice API

| Risorsa | API | Permesso | Background | Limiti principali |
|---|---|---|---|---|
| Camera | CameraX (`ImageAnalysis`, `Preview`), Camera2 interop | `CAMERA` (runtime) | No (solo FGS `camera` avviato da contesto visibile) | fps reali 30 (talvolta 60) per l'analisi; **high-speed ≥120 fps pensato per preview + registrazione video** (`CameraConstrainedHighSpeedCaptureSession`); l'analisi frame-by-frame a 120 fps lato app non è garantita (MEDIUM, verificare per device); controllo manuale esposizione solo con `MANUAL_SENSOR` |
| Display | Compose/`SurfaceView`/OpenGL ES, `Choreographer`, `Surface.setFrameRate()` | — | — | Refresh variabile (LTPO), `setFrameRate` è solo hint; luminosità finestra forzabile |
| Torcia | `CameraManager.setTorchMode`, `turnOnTorchWithStrengthLevel` (API 33) | Nessuno per la torcia | — | Latenza/jitter decine ms (TO BENCHMARK); conflitto con camera aperta |
| Audio out | `AudioTrack`, AAudio/Oboe | — | Playback ok con FGS `mediaPlayback` | Volume di sistema, DSP del produttore sull'uscita |
| Audio in | `AudioRecord`, AAudio; `UNPROCESSED`/`VOICE_RECOGNITION` | `RECORD_AUDIO` | FGS `microphone` solo da contesto visibile | Concurrent capture limitata; indicatore privacy visibile; near-ultrasound non garantito |
| IR | `ConsumerIrManager` | `TRANSMIT_IR` (normal) | — | Solo TX, pochi device, pattern ≤ 2 s |
| USB | `UsbManager` host | Dialogo per device | FGS `connectedDevice` | Alimentazione VBUS variabile; permesso per device |
| Storage | Room/SQLite, SAF per supporti rimovibili | — | — | Scoped storage |
| Crypto | Android Keystore (AES/EC P-256 HW-backed), StrongBox (alcuni) | — | — | Curve25519 HW non uniforme |
| Sensori | `SensorManager` (accelerometro per rilevare "telefoni affiancati", magnetometro…) | — | — | — |

## 2. Foreground service (Android 14+)

Dal target API 34 ogni FGS deve dichiarare il tipo (`camera`, `microphone`, `connectedDevice`,
`dataSync`, …) e il permesso `FOREGROUND_SERVICE_<TYPE>`; camera e microfono sono "while-in-use":
il servizio va avviato mentre l'app è visibile (o da azione utente su notifica/tile).
Fonte: <https://developer.android.com/about/versions/14/changes/fgs-types-required>

Implicazioni GHOSTLINK:
- **Visual/audio = attività in primo piano** (l'utente sta tenendo i telefoni vicini: coerente con l'UX).
- **LoRa/USB = FGS `connectedDevice`** può restare attivo per relay DTN in background (con notifica
  persistente) → il telefono può fare da relay mentre è in tasca **solo** via dongle.
- Ascolto audio passivo continuo in background per "wake-up acustico": tecnicamente FGS `microphone`
  avviato in foreground, con indicatore privacy acceso: consumo e UX discutibili → non in v1.

## 3. Batteria e termica

- Camera + display al 100% + decodifica: carico tipico 1.5–3 W → 20–40 min di trasferimento continuo
  prima di throttling termico su alcuni device (TO BENCHMARK).
- Doze/App Standby non toccano un FGS attivo, ma i produttori (OEM "battery killers") possono
  terminare servizi: documentare whitelist per marca.

## 4. Frammentazione

| Aspetto | Variabilità | Strategia |
|---|---|---|
| Camera (sensore, fps, AF, exposure) | Alta | Calibrazione per sessione; parametri conservativi; profili per modello raccolti dal benchmark |
| Risposta audio (near-ultrasound) | Alta | Sweep di calibrazione; fallback udibile |
| Refresh display | Media | Leggere `Display.getSupportedModes()`; adattare k frame/codice |
| USB host / corrente VBUS | Media | Dongle a basso consumo, opz. batteria |
| ABI | arm64-v8a dominante | Build nativa arm64 + x86_64 (emulatori/Chromebook) |

## 5. NDK: quando è giustificato

| Componente | Kotlin | Nativo (Rust/C++) | Scelta |
|---|---|---|---|
| UI, permessi, CameraX, AudioRecord, USB | ✔ | ✘ | Kotlin |
| Decodifica QR in streaming | ZXing Java (lento) | zxing-cpp | **Nativo** |
| DSP audio (FFT, sync chirp, OFDM) | Possibile | Più veloce, condivisibile con desktop/firmware | Nativo (Rust) |
| Fountain codec, crypto, parser pacchetti | Possibile | Rust: memory safety su input non fidato + crate maturi | **Nativo (Rust)** |
| Routing/storage DTN | Possibile | Rust + persistenza via callback Kotlin o SQLite nativo | Core Rust, storage dietro interfaccia |

Vedi confronto completo dei linguaggi in `../architecture/ARCHITECTURE.md` §3.
