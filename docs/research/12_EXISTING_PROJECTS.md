# 12 — Analisi progetti esistenti

Riferimenti architetturali, **non** codice da copiare. Licenze da rispettare se si riusa qualcosa.

## Reticulum (RNS) — <https://github.com/markqvist/Reticulum>

- **Cos'è**: stack di rete crittografico per reti locali e geografiche resilienti; funziona su
  qualunque mezzo half-duplex con **> 5 bit/s e MTU 500 byte**; interfacce: Ethernet, LoRa (RNode),
  TNC packet radio, modem KISS, seriale, TCP/UDP, I2P, programmi esterni via stdio/pipe, hardware custom.
  Crypto: X25519/Ed25519, HKDF, AES-256-CBC + HMAC-SHA256, SHA-256/512. Indirizzi = hash troncati.
  Python (implementazione di riferimento); porte in altri linguaggi esistono (Go, Rust, Kotlin
  sperimentale). `[PROD]` Attivo.
- **Licenza**: implementazione di riferimento sotto "Reticulum License" (con clausola d'uso: non in
  sistemi che abbiano tra le funzioni il fare intenzionalmente del male a esseri umani; possibili
  altre clausole → **leggere il testo**); il protocollo è dichiarato di pubblico dominio dal 2016.
- **Cosa imparare**: indirizzamento per hash di chiave; announce; separazione netta interfacce/rete;
  progettazione per bitrate bassissimi; RNode come dongle LoRa standard; LXMF come formato messaggi.
- **Cosa non risolve**: non tratta **display/camera e speaker/mic del telefono** come interfacce native
  di prima classe (se non tramite modem esterni/pipe); non ha **fountain coding multipath
  cross-interfaccia** per oggetti grandi; DTN tra telefoni affidato a nodi di propagazione LXMF più che
  a spray/epidemic tra peer mobili; Python su Android (Chaquopy) è pesante.
- **Cosa fare diversamente**: core nativo (Rust) mobile-first; trasporti fisici del telefono come
  interfacce standard; oggetti fountain; header più compatti; **valutare un bridge/interop RNS**
  (GHOSTLINK come interfaccia Reticulum o gateway) invece di competere.

## Sideband / LXMF / Columba

- **Sideband**: client LXMF per Android/Linux/macOS/Windows; LoRa (RNode via USB/BT), packet radio,
  Wi-Fi, I2P e **"Encrypted QR Paper Messages"** (`lxm://` URI). `[PROD]`
- **LXMF**: formato messaggi su Reticulum, E2E, forward secrecy, store-and-forward via propagation nodes;
  **messaggi su carta come QR** → GhostDrop su carta *non è un'idea nuova*.
  <https://github.com/markqvist/lxmf>
- **Columba**: app Android nativa (Kotlin, Material 3) per LXMF: BLE, TCP, RNode LoRa; backend Python
  (Chaquopy) o Kotlin sperimentale. `[PROTO/PROD]`
- **Lezione**: UX mobile matura è possibile su questo modello; i QR paper message sono il precedente
  diretto di GhostDrop.

## Meshtastic — <https://meshtastic.org>

- **Cos'è**: firmware mesh LoRa per ESP32/nRF52/RP2040 + app Android/iOS/desktop; il telefono si
  collega al nodo via **BLE, Wi-Fi o USB seriale** (usb-serial-for-android). Preset radio documentati
  (LongFast SF11/250 kHz ≈ 1.07 kbps …). Crittografia: AES-256-CTR per canale (PSK; il canale default
  ha chiave nota) e, dal firmware 2.5, **PKC X25519 + AES-CCM per i messaggi diretti**. Limiti noti:
  spoofing possibile in certi casi su canali PSK. `[PROD]`, molto attivo. Licenza GPL-3.0 (verificare
  per componente).
- **Cosa imparare**: hardware di riferimento economico; esperienza enorme su flooding mesh LoRa,
  preset, duty cycle EU, protocollo telefono↔nodo via **protobuf** su seriale/BLE.
- **Cosa non risolve**: un solo PHY (LoRa); flooding con hop limit, non DTN con custodia a lungo
  termine sui telefoni; nessun trasferimento file serio; il telefono è un'interfaccia utente, non un
  nodo multi-trasporto.
- **Diversamente**: il dongle come semplice "PHY + regolatore normativo", con routing e crypto nel
  telefono; possibilità di usare gli stessi board con firmware GHOSTLINK.

## Briar / Bramble

- **Cos'è**: messaggistica P2P (Tor, Wi-Fi, Bluetooth) + **sync via supporti rimovibili**; Bramble
  Transport Protocol (BTP) = sicurezza di trasporto per reti delay-tolerant su qualunque stream
  best-effort (ritardi, perdite, riordino, duplicati), con gestione chiavi a base temporale e FS. `[PROD]`
  <https://code.briarproject.org/briar/briar-spec>
- **Imparare**: specifica pubblica rigorosa; modello di sync tra contatti; "sneakernet" già in prodotto.
- **Non risolve**: niente canali visual/audio/LoRa; nessun relay da sconosciuti (solo contatti) →
  copertura limitata.
- **Diversamente**: relay non fidati che trasportano ciphertext opaco (spray-and-wait), canali fisici
  non radio standard.

## Serval Project (Serval Mesh, Rhizome)

- Mesh Wi-Fi ad hoc, voce/SMS/file, Rhizome per distribuzione file store-and-forward. Ultima release
  Serval Mesh **0.93 (maggio 2016)**; repository inattivo → **abbandonato**. `[PROD → dismesso]`
- **Imparare**: Rhizome (bundle firmati, distribuzione epidemica) e i problemi pratici di Wi-Fi ad hoc
  su Android (richiedeva root). **Lezione**: dipendere da funzioni di sistema non pubbliche uccide il
  progetto → GHOSTLINK usa solo API pubbliche.

## GNU Radio

- Framework DSP/SDR di riferimento (C++/Python). Su Android esistono build sperimentali, non pratiche.
- **Imparare**: blocchi di sincronizzazione, OFDM, equalizzazione; prototipare modem acustici su desktop
  con GNU Radio prima di portarli in Rust.

## Modem acustici

| Progetto | Stato | Nota |
|---|---|---|
| **ggwave** (MIT) | Attivo | MFSK + RS, 8–16 B/s, binding Android: candidato control channel |
| **libquiet / quiet** | Poco attivo (verificare) | liquid-dsp, profili OFDM/GMSK, binding Android storici |
| **minimodem** | Stabile | Bell 103/202, utile per test |
| Chirp.io SDK | Dismesso (acquisito da Sonos) | Prova che il data-over-sound commerciale è fragile |
| Google Nearby (ultrasuoni) | Rimosso 2021 | Stessa lezione |
| **FreeDV / Codec2** | Attivo | OFDM su canali voce, codec voce a 450–3200 bps |

## Optical camera communication / file via schermo

| Progetto | Stato | Nota |
|---|---|---|
| **libcimbar + CameraFileCopy** (MPL-2.0 / MIT) | Attivo | Codice colore + wirehair fountain + zstd; ~106 KB/s monitor→phone (claim) |
| **TXQR** (MIT) | Fermo (riscritto dall'autore in Dart con RaptorQ) | QR animati + LT |
| **Decimen** (MIT, 2026) | Nuovo | QR + LT, ~128 KB/s phone→phone dichiarati (iPhone, non verificato) |
| HiLight, InFrame++, ChromaCode, LightSync, PixNet | Paper | Vedi 02 |

## LoRa messaging

Meshtastic, RNode/Reticulum, **MeshCore** (mesh LoRa più recente, routing diverso — da analizzare),
goTenna (commerciale, proprietario, banda MURS/ISM), Disaster.radio (inattivo). LoRaWAN escluso
(dipende da infrastruttura).

## DTN

| Implementazione | Nota |
|---|---|
| **ION** (NASA JPL) | BPv7 di riferimento, C, orientato allo spazio |
| **µD3TN** | BPv7 leggero, embedded |
| **DTN7** (Go/Rust: dtn7-go, dtn7-rs) | BPv7 moderno, utile per un gateway futuro |
| IBR-DTN | Storico, inattivo |

## Sintesi: dove si colloca GHOSTLINK

| Capacità | Reticulum/LXMF | Meshtastic | Briar | cimbar | **GHOSTLINK (obiettivo)** |
|---|---|---|---|---|---|
| Multi-interfaccia | ✔ | ✘ | ✔ (radio standard) | ✘ | ✔ |
| Canali visual/audio del telefono come interfacce | Parziale (QR paper) | ✘ | ✘ | Solo visual, one-way | ✔ |
| LoRa via dongle | ✔ | ✔ | ✘ | ✘ | ✔ |
| DTN tra telefoni con relay non fidati | Parziale (propagation nodes) | Flooding | Solo contatti | ✘ | ✔ |
| Fountain multipath cross-medium | ✘ | ✘ | ✘ | Solo intra-canale | ✔ |
| E2E con chiavi offline | ✔ | Parziale | ✔ | ✘ | ✔ |
| Dongle come regolatore normativo | RNode (firmware) | ✔ | — | — | ✔ |
