# GHOSTLINK — Report finale Phase 0

Sintesi dei documenti in `docs/research/` e `docs/architecture/`. Le stime riportano la confidenza
(H/M/L); `TB` = da misurare.

## A. Executive conclusion

Un Physical Communication Layer universale per Android è **fattibile con API pubbliche e senza root**.
Il telefono da solo offre due canali utili — **visual** (screen→camera: veloce, LOS, 10–50 cm) e
**audio** (speaker→mic: lento, NLOS, metri) — più beacon ottici lentissimi (torcia/schermo). Il raggio
oltre la stanza richiede **radio esterna via USB-C** (LoRa/FSK sub-GHz), limitata in UE dal duty cycle,
oppure **tempo** (store-carry-forward tra persone). Nessuno dei PHY è nuovo; il valore originale è il
**core unico** (identità offline, GhostPacket, DTN, oggetti fountain) in cui ogni trasduttore è
un'interfaccia intercambiabile e un file può essere ricostruito da simboli arrivati su mezzi diversi.

## B. Top technologies discovered

| # | Tecnologia | Perché conta | Evidenza |
|---|---|---|---|
| 1 | QR animati + fountain (RaptorQ/LT) | MVP più semplice e robusto; nessun ACK necessario | PROTO/PROD (TXQR, Decimen) |
| 2 | Codici colore densi (cimbar) | ~106 KB/s monitor→phone dichiarati; app Android ricevente esistente | PROTO |
| 3 | LoRa SX1262 via dongle USB CDC | Unico canale km senza LOS; ecosistema maturo | PROD (Meshtastic, RNode) |
| 4 | MFSK audio robusto (ggwave) | Control channel universale, 8–16 B/s | PROD |
| 5 | Near-ultrasonic coerente + equalizzazione | 4 kbps @5 m (laptop, lab) | SCI |
| 6 | Rolling-shutter OCC (LED → camera) | Ricezione kbps su telefoni non modificati | SCI |
| 7 | Noise Protocol + Curve25519 | Handshake maturi, one-way pattern per DTN | PROD |
| 8 | Spray-and-Wait / anti-entropy | DTN semplice senza conoscenza della topologia | SCI/PROD |
| 9 | Reticulum/LXMF | Riferimento architetturale più vicino (multi-interfaccia, paper QR) | PROD |

## C. Technologies usable with smartphone only

| Tecnologia | Goodput realistico | Range | Ruolo | Conf. |
|---|---|---|---|---|
| QR animati | 5–30 KB/s | 0.1–0.5 m | File, key exchange, GhostDrop | M |
| Codici colore | 20–100 KB/s | 0.15–0.4 m | File grandi (fase 2) | L/TB |
| Audio MFSK | 8–16 B/s | 1–5 m | Control/ACK/capability | H |
| Audio OFDM | 0.3–8 kbps | 0–2 m | Messaggi brevi | L/TB |
| Near-ultrasonic | 0.05–2 kbps | 0.1–3 m | Beacon discreti (device-dipendente) | L–M |
| Torcia | 10–60 bps | 1–30 m notte | SOS/beacon ID | L |
| Schermo beacon | 10–30 bps | 5–50 m notte | Beacon | L/TB |
| IR blaster | 0.5–2 kbps, solo TX | 1–10 m | Wake-up dongle (pochi modelli) | M |
| Invisible screen | 1–100 kbps (lab) | < 1 m | Ricerca | L |
| GhostDrop (carta/video/WAV) | — | fisico | Dead drop, DTN | H |

## D. Technologies requiring external hardware

| Tecnologia | Goodput | Range | Note | Conf. |
|---|---|---|---|---|
| LoRa SX1262 (EU868) | 0.3–11 kbps raw; ≤ ~0.9 kbps medi a 10% DC | 1–15 km | Messaggi, coordinate, voce PTT Codec2 | H/M |
| GFSK sub-GHz | 10–250 kbps raw | 0.1–2 km | Burst dopo discovery LoRa, stesso chip | M |
| IR dongle 850/940 nm | 2–115 kbps | 1–30 m | Discreto, eye-safe con EN 62471 | M |
| LED/photodiode dongle | 0.1–10 Mbps | 1–50 m LOS | "GHOSTLINK Optical" | M |
| LED dongle → camera RS | 1–10 kbps | 0.5–5 m | TX dongle, RX qualunque telefono | M |
| Laser classe 1 | 1–100 Mbps | puntato | Solo laboratorio | L |
| Ultrasuoni 40 kHz | 0.05–1 kbps | 1–10 m | Bassa priorità | M |
| SDR | — | — | Strumento di misura; TX restricted | M |
| Cavo USB/Ethernet, jack | Mbps / kbps | 0–2 m | Fallback | M |

## E. Most promising innovations

1. **Multipath Fountain Transfer cross-medium** — simboli RaptorQ con ESI casuali su visual + audio +
   LoRa + relay non coordinati; ricostruzione da qualunque combinazione.
2. **Physical Store-and-Forward unificato** — lo stesso bundle cifrato attraversa schermo, aria, radio,
   carta, video, SD.
3. **Transport Capability Negotiation fuori banda** — due telefoni scoprono via audio/visual i canali
   comuni e scelgono il migliore.
4. **Dongle come regulatory enforcer** — duty cycle ed e.r.p. garantiti dal firmware, app non in grado
   di violarli.
5. **LoRa discovery → GFSK burst** sullo stesso SX1262 per massimizzare byte per budget di duty cycle.
6. **Visual full-duplex face-to-face** (TB) e **beacon ottico asimmetrico** LED→rolling shutter.

## F. Biggest technical risks

| Rischio | Score | Mitigazione |
|---|---|---|
| Scope creep tra troppi transport | 16 | Roadmap a gate con criteri di accettazione |
| Crittografia/parser implementati male | 15 | Noise (`snow`), Rust, fuzzing, test vector, audit |
| Throughput visual reale insufficiente su Android | 12 | **M1 spike di misura prima di tutto** |
| Frammentazione camera/audio tra modelli | 12 | Calibrazione, profili per device dai benchmark |
| DoS su relay DTN | 12 | Quote, priorità contatti, expiry, PoW opzionale |
| UX fisica (allineare telefoni) | 12 | Supporti, resume, trasferimenti brevi |

Registro completo: `research/14_RISK_REGISTER.md`.

## G. Regulatory risks

- **LoRa/FSK EU868**: lecito senza licenza entro e.r.p./duty cycle delle sub-bande SRD (es. 869.40–869.65
  MHz: 500 mW, 10%); da imporre nel firmware. Regime italiano per reti a servizio di terzi **da
  verificare con MIMIT**.
- **Radioamatori**: richiede licenza e **vieta di fatto la cifratura** (ITU RR 25.2A + norme nazionali)
  → incompatibile con l'E2E di GHOSTLINK.
- **SDR in TX**: restricted (apparato non certificato come SRD).
- **Laser**: solo classe 1 (EN 60825-1; EN 50689 per consumer). LED IR: EN 62471.
- **Ultrasuoni**: nessuna licenza, ma limiti di esposizione di riferimento (IRPA/Health Canada:
  75 dB @20 kHz, 110 dB 25–100 kHz).
- **Prodotto hardware**: RED/CE, eventuale cybersecurity RED e CRA, dual-use crittografia — da verificare
  prima di qualunque vendita. Visual/audio/carta: nessun vincolo spettrale.

Dettagli: `research/11_REGULATORY_EU_ITALY.md`.

## H. Recommended architecture

```text
App (Kotlin/Compose) ── UniFFI ── GHOSTLINK Core (Rust)
                                   ├─ identity & crypto (Curve25519, Noise X/IK/XX, ChaCha20-Poly1305)
                                   ├─ GhostPacket v0 + GhostFrame (header 6 B + dest; packet_id calcolato)
                                   ├─ objects (BLAKE3 content addressing, RaptorQ, manifest, resume)
                                   ├─ routing DTN (spray-and-wait, anti-entropy, ACK a preimmagine)
                                   └─ adaptive transport engine + multipath scheduler
Transport API ── Visual · Audio · Flash · IR · USB(LoRa, GFSK, IR, Optical) · FileDrop · Sim
```
Documenti: `architecture/ARCHITECTURE.md`, `GHOSTPACKET_v0.md`, `DONGLE_PROTOCOL_v0.md`, `THREAT_MODEL.md`.

## I. Recommended MVP

**MVP-1 "GhostBeam"**: due telefoni Android in **modalità aereo**; A seleziona un file → QR animati con
simboli RaptorQ in GhostFrame/GhostPacket v0 → B lo riceve con la camera, ricostruisce e verifica l'hash.
Opzionale: B segnala "DONE"/rate hint via audio MFSK. Criteri: 1 MB in ≥ 9/10 tentativi a 20–30 cm,
goodput misurato e pubblicato, resume dopo interruzione. Nessun hardware extra.

## J. Recommended external hardware prototype

**GHOSTLINK Long Range v0** (MVP-2):
- Base: **RAK WisBlock RAK4631** (nRF52840 + SX1262) *oppure* Heltec V3 / LilyGO T3S3 (ESP32-S3 + SX1262).
- USB-C **CDC-ACM**, framing COBS + CRC-16, controllo CBOR (`DONGLE_PROTOCOL_v0.md`).
- Firmware C++ con **RadioLib**; profilo `EU868` con contabilità duty cycle su 1 h; CAD prima di TX.
- Profili: 869.525 MHz SF7–SF11 / 250 kHz (10%); 868.x MHz 125 kHz (1%); GFSK in fase successiva.
- Antenna esterna 868 MHz con guadagno dichiarato; budget < 300 mA da VBUS.
- Demo: **lo stesso GhostPacket byte-identico** consegnato via QR e via LoRa.
- Fase ottica successiva: Raspberry Pi Pico 2 (RP2350, PIO) con LED/IR/fotodiodo.

Strategia hardware: un firmware e un protocollo, hardware specializzato su base comune.

## K. Proposed repository structure

```text
claudepro/                      (repo; nome definitivo da decidere)
├── README.md
├── docs/
│   ├── FINAL_REPORT.md
│   ├── research/               00–16 + TECHNOLOGY_MATRIX.md
│   ├── architecture/           ARCHITECTURE, GHOSTPACKET_v0, DONGLE_PROTOCOL_v0, THREAT_MODEL
│   └── specs/                  (M2) specifiche normative + test vector
├── spikes/
│   └── visual-capacity/        (M1) app Kotlin usa-e-getta
├── core/                       (M2) workspace Rust
│   ├── ghostlink-packet/  ghostlink-object/  ghostlink-crypto/  ghostlink-routing/
│   ├── ghostlink-engine/  ghostlink-modem/   ghostlink-ffi/     ghostlink-cli/   ghostlink-sim/
├── android/                    (M3) progetto Gradle
│   ├── app/  protocol-core/  crypto/  routing/  storage/  hardware/  benchmark/
│   └── transports/ transport-api/ transport-visual/ transport-audio/ transport-flash/ transport-ir/ transport-usb/
├── firmware/                   (M6) dongle (PlatformIO/RadioLib)
├── hardware/                   (M6+) schemi, BOM
├── benchmarks/                 campagne JSON, results/ CSV, notebook di analisi
└── tools/                      script (airtime LoRa, generatori test vector)
```

## L. Exact next implementation milestone

### M1 — Visual Channel Capacity Spike

**Obiettivo unico**: misurare il goodput reale screen→camera tra due telefoni Android con QR animati,
per fissare i parametri del VisualTransport e confermare/smentire le stime (rischio R01, il maggiore
non ancora misurato).

**Scope** (piccolo, usa-e-getta, Kotlin puro in `spikes/visual-capacity/`):
1. **Sender**: genera frame casuali di dimensione configurabile, ciascuno `seq(4 B) ‖ payload ‖ CRC-32C`,
   li mostra come QR (versione, ECC, fps, tiling 1 o 2×2 configurabili) a luminosità massima.
2. **Receiver**: CameraX `ImageAnalysis` + zxing-cpp; conta frame validi unici, CRC falliti, fps di
   analisi, latenza di decodifica; scrive CSV.
3. **Campagna**: configurazione condivisa tra i due telefoni via QR (il receiver la scansiona).

**Fuori scope**: fountain, crypto, GhostPacket, audio, file reali.

**Criteri di accettazione (verificabili)**:
- [ ] APK installabile su 2 device; funziona in modalità aereo.
- [ ] ≥ 100 run registrati su almeno 2 modelli diversi, matrice: QR v15/20/25 × ECC L/M × fps 10/15/20
      × distanza 20/30/50 cm (mano libera e supporto fisso).
- [ ] CSV con lo schema di `research/15_BENCHMARK_PLAN.md` §1 (campi applicabili).
- [ ] Breve report (`benchmarks/M1_REPORT.md`) con: goodput mediano e IQR per configurazione, migliore
      configurazione per distanza, fps reali di `ImageAnalysis`, aggiornamento delle righe "visual" della
      `TECHNOLOGY_MATRIX.md` da `M`/`TB` a valori misurati.
- [ ] Decisione scritta: parametri di default del VisualTransport e dimensione del simbolo RaptorQ `T`.

Se il goodput mediano migliore risulta < 5 KB/s, si apre prima una revisione (codici colore/tiling) invece
di procedere con M3 così com'è.
