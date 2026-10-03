# Technology Matrix

Valori **realistici consumer** (non record di laboratorio). Ogni stima riporta la confidenza:
`H` = HIGH, `M` = MEDIUM, `L` = LOW. `TB` = UNKNOWN / TO BENCHMARK.
Fonti e dettagli nei documenti 02–07.

## 1. Matrice principale

| Technology | Extra HW | Range | Throughput (goodput) | LOS | Power | Android difficulty | Maturity | Confidence |
|---|---|---:|---:|---|---|---|---|---|
| QR Dynamic (animati + fountain) | No | 0.1–0.5 m | 40–250 kbps (5–30 KB/s) | Yes | Alto (display+camera) | Bassa | PROTO/PROD | Range H · Thr M · Power L |
| RGB / color code (cimbar-like) | No | 0.15–0.4 m | 150–800 kbps | Yes | Alto | Media (NDK) | PROTO | Thr L (TB phone→phone) |
| Invisible screen (InFrame/ChromaCode-like) | No (≥120 Hz) | < 1 m | 1–100 kbps | Yes | Alto | Alta | SCI | L |
| Face-to-face visual full-duplex | No | 0.1–0.3 m | TB | Yes | Alto | Media | THEORY | TB |
| Audio MFSK (ggwave-like) | No | 1–5 m | 64–128 bps | No (stanza) | Medio | Bassa | PROD | H |
| Audio OFDM audible | No | 0–2 m | 0.3–8 kbps | No (corto) | Medio | Media-alta (DSP) | SCI/PROTO | L |
| Ultrasonic 18–20 kHz (phone) | No | 0.1–3 m | 0.05–2 kbps | Quasi | Medio | Alta (compatibilità) | SCI | M (laptop) / L (phone) |
| Flash VLC (torcia) | No | 1–30 m (notte) | 10–60 bps | Yes | Medio | Media | SCI | L |
| Screen beacon (notte) | No | 5–50 m | 10–30 bps | Yes | Alto | Bassa | THEORY | L |
| IR blaster (TX only) | No (raro) | 1–10 m | 0.5–2 kbps | Yes | Basso | Bassa | PROD | M |
| IR USB dongle | Yes | 1–30 m | 2–115 kbps | Yes | Basso | Media | PROD (comp.) | M |
| LED dongle → camera (rolling shutter) | Yes (TX) | 0.5–5 m | 1–10 kbps | Yes | Basso | Media | SCI | M |
| LED/photodiode USB dongle | Yes | 1–50 m | 0.1–10 Mbps | Yes | Medio | Media | PROTO | M |
| Laser USB (classe 1) | Yes | 10 m–km (puntato) | 1–100 Mbps | Yes, rigido | Basso | Media | PROTO | L (puntamento) |
| Ultrasonic 40 kHz dongle | Yes | 1–10 m | 0.05–1 kbps | Quasi | Medio | Media | PROTO | M |
| LoRa USB (EU868) | Yes | 1–15 km (0.3–3 km urbano) | 0.3–11 kbps raw; ≤ 0.9 kbps medi a 10% DC | No | Basso (RX 5 mA) | Media | PROD | Thr H (formula) · Range M |
| FSK/GFSK sub-GHz USB | Yes | 0.1–2 km | 10–250 kbps raw (DC limita la media) | No | Basso | Media | PROD | M |
| SDR (RX) | Yes | dipende | dipende | — | Alto | Alta | PROD (RX) | M |
| SDR (TX) | Yes | — | — | — | Alto | Alta | PROTO | Restricted (vedi 11) |
| USB cable / USB-Ethernet | Yes (cavo/adattatori) | 0–2 m | 10–100 Mbps | n/a | Basso | Media | PROD | M |
| Audio cable / walkie-talkie | Cavo / radio | radio | 0.3–20 kbps | n/a | Basso | Bassa | PROD | M |
| GhostDrop carta/video/WAV/SD | Supporto | ∞ (fisico) | n/a (latenza = trasporto fisico) | n/a | — | Bassa | PROD (LXMF paper) | H |

## 2. Ranking tecnico (non commerciale)

| Categoria | Vincitore | Secondo | Motivazione tecnica |
|---|---|---|---|
| **EASIEST MVP** | QR animati + fountain | Audio MFSK (ggwave) | API standard (CameraX, Compose), decoder maturi, nessun DSP custom; fountain elimina sincronizzazione e ACK |
| **BEST RANGE** | LoRa SX1262 | FSK sub-GHz | CSS fino a ~−137 dBm a SF12/125 kHz; km reali; l'unico canale oltre i 100 m senza LOS |
| **BEST BANDWIDTH** | LED/photodiode o laser dongle (classe 1) | Codici colore screen→camera | Fotodiodi hanno banda MHz; tra i "solo smartphone" vince il visual denso |
| **BEST RELIABILITY** | Visual QR (a corto raggio) | LoRa | RS intra-frame + fountain inter-frame; canale insensibile a rumore acustico/RF; LoRa robusto ma soggetto a collisioni |
| **BEST LOW POWER** | LoRa (RX 4.6 mA, TX in burst) | IR dongle | Telefono può restare con schermo spento; visual/audio richiedono display/DSP attivi |
| **BEST STEALTH / VISUAL DISCRETION** | IR dongle 940 nm | Canale screen "invisibile" | IR invisibile e direzionale; lo screen invisibile resta fragile (≥120 Hz, frame drop) |
| **BEST FOR FILE TRANSFER** | Visual (QR → colore) | LED/photodiode dongle | 10–100 KB/s senza HW; LoRa/audio troppo lenti per file |
| **BEST FOR MESSAGING** | LoRa via dongle | Audio (corto raggio) + DTN | Messaggi < 200 B a km di distanza; senza dongle: DTN + visual/audio all'incontro |
| **BEST FOR HARDWARE DONGLE** | LoRa + GFSK su SX1262 | IR TX/RX | Massimo valore aggiunto per costo (~20–40 €), PHY maturo, conformità chiara |
| **MOST EXPERIMENTAL** | Invisible screen modulation | Face-to-face full-duplex / laser | Risultati solo in laboratorio, dipendenza forte da hardware display |

## 3. Combinazioni raccomandate per traffico

| Traffico | Transport preferito | Fallback |
|---|---|---|
| File grande, persone vicine | Visual | Cavo/SD (GhostDrop) |
| Control/ACK/negoziazione | Audio MFSK | Visual inverso |
| Messaggio a distanza | LoRa | DTN (spray-and-wait) |
| Emergenza/beacon notturno | Schermo/torcia beacon | LoRa |
| Key exchange | QR reciproco | Audio, codice umano |
| Dead drop | QR stampato / microSD | WAV, video |
