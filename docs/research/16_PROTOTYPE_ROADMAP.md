# 16 — Prototype Roadmap (rivista dopo la ricerca)

## 1. Critica della roadmap iniziale

La sequenza proposta (visual → audio → GhostPacket → crypto → adaptive → DTN → dongle → LoRa → ottico →
multipath) ha tre problemi:

1. **GhostPacket e crypto arrivano dopo due transport**: si rischia di costruire due app separate
   (esattamente ciò che il principio di progetto vieta) e di dover rifare i formati.
2. **Il dongle LoRa arriva tardi**, ma è la prova più forte dell'astrazione transport-independent ed è
   economico con board esistenti.
3. **Il rischio maggiore** (throughput visual reale su Android) non viene misurato prima di investire
   nell'architettura.

## 2. Roadmap raccomandata (a gate)

Ogni milestone ha **criteri di accettazione**; non si apre un nuovo transport finché il precedente
non li supera (mitigazione R19).

| M | Nome | Contenuto | Criteri di accettazione |
|---|---|---|---|
| **M0** | Research & architecture | Questo pacchetto di documenti | Revisione e approvazione |
| **M1** | **Visual Channel Spike** | App Kotlin "usa e getta": sender QR animati parametrico + receiver CameraX/zxing-cpp; misura raw | CSV con ≥ 100 run su ≥ 2 device; curva goodput vs (versione QR, fps, distanza); decisione documentata dei parametri di default |
| **M2** | `ghostlink-core` v0 (Rust) | GhostFrame/GhostPacket v0, ObjectCodec (RaptorQ), manifest, CLI, simulatore di canale, test vector | 1 MB attraverso canale simulato con 30% erasure → hash ok, overhead fountain < 5%; fuzzing parser 1 h senza crash |
| **M3** | **MVP-1: Visual file transfer** | Core via UniFFI nell'app; VisualTransport su core; resume | 1 MB phone→phone con hash ok in ≥ 9/10 tentativi a 20–30 cm; goodput ≥ soglia definita da M1; resume dopo interruzione |
| **M4** | Identity + offline key exchange + E2E | Identity card QR, Noise XX/X, SAS, Keystore wrap | Contatto verificato via QR; file cifrato E2E; test vector Noise; nessuna chiave in chiaro su disco |
| **M5** | AudioTransport + capability negotiation | ggwave-like control channel, poi OFDM dati; negoziazione canali | ACK/stop via audio funzionante durante M3; capability exchange audio in < 10 s; misure audio in CSV |
| **M6** | **MVP-2: USB dongle + LoRa** | Firmware (RP2040/nRF52840/ESP32-S3 + SX1262), protocollo dongle v0, regulatory profile EU868, `LoRaTransport` | Lo **stesso GhostPacket** (byte-identico) consegnato via visual e via LoRa; duty cycle verificato con SDR; hot-plug |
| **M7** | Store-carry-forward + GhostDrop | Storage bundle, spray-and-wait, anti-entropy, expiry, quote, export carta/video/WAV | Messaggio A→B→C con B relay offline per ore; drop stampato ricostruito; test DoS base |
| **M8** | Adaptive engine + multipath fountain | Scoring, scheduling simboli su più transport | Oggetto 100 KB completato da visual+audio+LoRa più velocemente del miglior canale singolo o con canale interrotto |
| **M9** | Ottico/IR sperimentale | LED dongle → rolling shutter; IR TX/RX dongle; torcia beacon | Misure in CSV; decisione go/no-go per prodotto |
| **M10** | Hardening | Threat model review, fuzzing esteso, audit esterno crypto, revisione normativa, bridge Reticulum (opz.) | Audit senza finding critici aperti |

Note:
- M1 e M2 possono procedere **in parallelo** (lo spike non dipende dal core).
- Il canale invisibile ("GhostVeil") e la voce Codec2 sono **track di ricerca** dopo M8.
- SDR resta strumento di misura, mai transport di prodotto.

## 3. Primo MVP (MVP-1)

```text
Android A: seleziona file → GhostObject (cifrato in M4) → simboli RaptorQ → GhostFrame → QR animati
Android B: CameraX → zxing-cpp → GhostFrame → core → oggetto completo → hash ok → file salvato
(opz.) B → A: "DONE"/rate hint via audio MFSK o via QR mostrato da B alla camera frontale di A
```

Dimostrabile: due telefoni in **modalità aereo**, nessun hardware extra, trasferimento di una foto
(~1–3 MB) con barra di avanzamento e verifica hash.

## 4. Secondo MVP (MVP-2)

```text
Android A ─USB-C─ dongle (MCU + SX1262) ~~~ LoRa 869.525 MHz ~~~ dongle ─USB-C─ Android B
```
Dimostrazione chiave: **lo stesso GhostPacket** (stessi byte, stessa firma/AEAD) consegnato una volta
via QR e una volta via LoRa; l'app ricevente non distingue l'origine se non nei log diagnostici.

## 5. Hardware per M6

| Opzione | Composizione | Pro | Contro |
|---|---|---|---|
| A (raccomandata) | **RAK WisBlock RAK4631** (nRF52840 + SX1262) + base RAK19007 + antenna | Modulare, USB nativo, compatibile Meshtastic/RNode per confronti, basso consumo | nRF52840 meno potente |
| B | **Heltec WiFi LoRa 32 V3** / LilyGO T3S3 (ESP32-S3 + SX1262) | Economico, display OLED, USB nativo (S3) | Consumo più alto, radio 2.4 GHz superflua |
| C | Seeed XIAO ESP32S3 + Wio-SX1262 | Minuscolo | Antenna/montaggio |
| D (fase ottica) | Raspberry Pi Pico 2 (RP2350) + LED/IR/fotodiodo | PIO per timing preciso | Nessuna radio |

Firmware: Rust (embassy) o C (Arduino/PlatformIO + RadioLib). Raccomandazione: **C++ con RadioLib per
M6** (driver SX126x maturi, meno rischio), framing condiviso con il core Rust via spec + test vector;
migrazione a Rust embassy valutata dopo.
