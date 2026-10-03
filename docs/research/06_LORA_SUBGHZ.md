# 06 — LoRa e radio Sub-GHz

Copre AREA H (LoRa) e AREA I (modem radio esterni FSK/GFSK).

## 1. Architettura

```text
PHONE ─USB-C (CDC-ACM)─ MCU ─SPI─ SX1262 / LR1121 / STM32WL ─ antenna 868 MHz
```

## 2. LoRa PHY

- **Chirp Spread Spectrum** proprietario Semtech. Parametri: **SF** 5/6–12, **BW** 7.8–500 kHz
  (in pratica 125/250/500), **CR** 4/5–4/8, preamble, header esplicito/implicito, CRC.
- Bitrate: `Rb = SF · BW / 2^SF · CR` (CR = 4/(4+cr)).
- Ogni +1 SF ≈ +2.5 dB di link budget, ≈ ×2 airtime.
- Payload max 255 byte per pacchetto (registri FIFO SX126x).

### 2.1 Airtime calcolato (formula Semtech AN1200.13, CR 4/5, preamble 8, header esplicito, CRC on, LDRO auto)

| BW | SF | Rb (bps) | ToA 50 B | ToA 200 B | ToA 255 B | Pacchetti 50 B/h @1% | @10% |
|---|---|---:|---:|---:|---:|---:|---:|
| 125 kHz | 7 | 5 469 | 98 ms | 318 ms | 400 ms | 369 | 3 691 |
| 125 kHz | 9 | 1 758 | 329 ms | 1 005 ms | 1 250 ms | 110 | 1 095 |
| 125 kHz | 10 | 977 | 616 ms | 1 845 ms | 2 296 ms | 58 | 584 |
| 125 kHz | 12 | 293 | 2 302 ms | 7 217 ms | 9 019 ms | 16 | 156 |
| 250 kHz | 7 | 10 938 | 49 ms | 159 ms | 200 ms | 738 | 7 382 |
| 250 kHz | 9 | 3 516 | 164 ms | 502 ms | 625 ms | 219 | 2 190 |
| 250 kHz | 11 | 1 074 | 575 ms | 1 681 ms | 2 091 ms | 63 | 626 |
| 250 kHz | 12 | 586 | 1 069 ms | 3 117 ms | 3 854 ms | 34 | 337 |

Confidenza: HIGH (calcolo; script in appendice). Coerente con i preset Meshtastic: LongFast =
SF11/250 kHz ≈ 1.07 kbps; ShortFast = SF7/250 kHz ≈ 10.94 kbps; ShortTurbo SF7/500 kHz ≈ 21.88 kbps
(500 kHz non entra nel sub-band EU g3 da 250 kHz).
<https://meshtastic.org/docs/overview/radio-settings/>

### 2.2 Implicazione chiave: in UE comanda il duty cycle

- Sub-band **869.40–869.65 MHz (g3 / "P")**: 500 mW e.r.p., **10%** duty cycle → 360 s di TX per ora.
  Con SF7/250 kHz, pacchetti da 255 B (≈ 200 ms) → ~1 800 pacchetti/h → **≈ 400 KB/h** (≈ 0.9 kbps medi).
- Sub-band 868.0–868.6 MHz (g1 / "M"): 25 mW, **1%** → 36 s/h → ~40 KB/h a SF7/250.
- Sub-band 863–865 / 868.7–869.2: 0.1% → 3.6 s/h.

**Conclusione**: LoRa è un canale per **messaggi, telemetria, coordinate, chiavi, ACK, voce PTT
brevissima** — non per file. Un file da 1 MB via LoRa in UE richiede ore anche nel sub-band 10%.

### 2.3 Range realistico

| Scenario | Range tipico SF7–SF12 | Confidenza |
|---|---|---|
| Urbano denso, nodi a livello strada | 0.3–3 km | MEDIUM |
| Suburbano / rurale | 2–10 km | MEDIUM |
| LOS (collina–collina, nodi in quota) | 10–100+ km (record > 100 km) | MEDIUM per il tipico, LOW per i record |
| Indoor → outdoor | forte penalità (10–30 dB) | MEDIUM |

Il telefono in tasca con antenna a stilo corta sul dongle peggiora molto il link budget: prevedere
antenna esterna e posizionamento (finestra, zaino in alto).

### 2.4 Chip

| Chip | Note | Confidenza |
|---|---|---|
| **SX1276/77/78** (1ª gen.) | +20 dBm (PA_BOOST), RX ≈ 10.8 mA; molto diffuso, end-of-life progressivo | MEDIUM |
| **SX1261/62** (2ª gen.) | SX1262 fino a +22 dBm, RX ≈ 4.6 mA (DC-DC), sensibilità fino a −148 dBm (BW minimo, SF12); stessa modulazione | MEDIUM-HIGH |
| **LR1110/LR1120/LR1121** | LoRa + (LR1110) scanner GNSS e Wi-Fi passivo per geolocalizzazione; LR1121 multibanda incl. 2.4 GHz e S-band | MEDIUM |
| **STM32WL** | MCU Cortex-M4 + radio sub-GHz LoRa/(G)FSK in un chip | HIGH |

Nota: i confronti "SX1262 −148 vs SX1276 −137 dBm" diffusi online confrontano parametri diversi;
a SF12/125 kHz entrambi sono intorno a −136/−137 dBm. Verificare sui datasheet ufficiali Semtech.

### 2.5 LoRaWAN vs LoRa P2P

| | LoRaWAN | LoRa P2P/mesh (Meshtastic, RNode, GHOSTLINK) |
|---|---|---|
| Topologia | Stella: end-device → gateway → network server (Internet) | Peer-to-peer, mesh, DTN |
| Dipendenza infrastruttura | Sì (gateway + server) | **No** |
| Downlink | Limitato, finestre RX1/RX2 | Libero (nei limiti di duty cycle) |
| Sicurezza | AES-128 con chiavi di rete/app | Da definire (GHOSTLINK: E2E Noise/X25519) |
| Adatto a GHOSTLINK | No (richiede infrastruttura) | **Sì** |

### 2.6 Accesso al canale

- Su un canale condiviso servono **CAD** (Channel Activity Detection, supportato dai SX126x) +
  **backoff casuale** (ALOHA con carrier sense), e un **budget di duty cycle** contabilizzato dal
  firmware del dongle (non dall'app: il firmware deve essere la "fonte di verità" normativa).
- EN 300 220 consente in alcune bande l'alternativa al duty cycle tramite tecniche di accesso
  (LBT + AFA): **da verificare** prima di farne uso (vedi 11_REGULATORY).

## 3. AREA I — Modem FSK/GFSK sub-GHz

- Chip: SX1262 stesso (modalità GFSK fino a 300 kbps), **CC1101/CC1310/CC1352** (TI), **Si4463**
  (Silicon Labs), RFM69 (HopeRF), STM32WL.
- Bitrate tipici 1.2–250 kbps; sensibilità molto inferiore a LoRa (ordine di grandezza −100/−110 dBm a decine di kbps
  vs ≈ −137 dBm di LoRa SF12/125 kHz; valori esatti da datasheet) → range 5–20× più corto a parità di potenza, ma **airtime 10–100× minore**.

| Aspetto | LoRa | (G)FSK |
|---|---|---|
| Range | Massimo | Minore |
| Throughput per pacchetto | Basso | Alto |
| Efficienza sotto duty cycle | Bassa (airtime lungo) | **Alta**: più byte per secondo di TX |
| Robustezza interferenza | Alta (CSS, sotto il rumore) | Media |
| Costo/consumo | Simile | Simile |

**Idea GHOSTLINK**: un unico SX1262 può commutare tra **LoRa (long range, controllo, scoperta)** e
**GFSK (burst veloci a corto raggio)**: dopo che due nodi si scoprono via LoRa e misurano RSSI/SNR
buoni, passano a GFSK 50–250 kbps per trasferire più dati nello stesso budget di duty cycle. Il
Transport Engine vede due transport (`LoRaTransport`, `FskTransport`) sullo stesso dongle.

- **433.05–434.79 MHz**: SRD non specifico, 10 mW e.r.p. con duty cycle ≤ 10% (e varianti a 1 mW);
  banda molto affollata (telecomandi, sensori meteo), antenne più grandi. Valori **da verificare**
  sull'ERC/REC 70-03 vigente (vedi 11).

## 4. Voce su LoRa (collegamento ad AREA "Voice")

- Codec2: modi 3200, 2400, 1600, 1400, 1300, 1200, 700(C), 450 bit/s. Opus: 6–510 kbit/s.
- Codec2 1200 bps + overhead pacchetto (~30–40 B ogni 320–480 ms) ≈ **1.8–2.2 kbps on-air**.
- LoRa SF7/250 kHz (≈ 10.9 kbps raw) regge lo stream, ma con **duty cycle 10% → max ~6 minuti di
  parlato per ora** per nodo, e 36 s/h nel sub-band 1%. → **Voce = messaggi vocali PTT brevi**
  (store-and-forward), non chiamate.
- Calcolo: messaggio vocale di 10 s a Codec2 700C (700 bps) = 875 B + overhead ≈ 1.1 KB → 5–6
  pacchetti SF7/250 → ~1.1 s di airtime. Fattibile e conforme. Confidenza HIGH (aritmetica).

## 5. Scheda sintetica

| Parametro | LoRa USB | FSK sub-GHz USB |
|---|---|---|
| HW extra | Sì (dongle ~15–40 € in componenti) | Sì |
| Root | No | No |
| API Android | USB Host + usb-serial-for-android | idem |
| Bitrate teorico | 0.3–22 kbps | 1–300 kbps |
| Bitrate medio UE | limitato da duty cycle (0.1–10%) | idem, ma 10–100× più byte/airtime |
| Range | 1–15 km tipico | 0.1–2 km |
| Latenza | 0.05–9 s per pacchetto + accesso canale | ms–100 ms |
| Consumo | TX ~120 mA @+22 dBm; RX ~5 mA | simile |
| LOS | No (degrada) | No (degrada) |
| Compatibilità | Totale (USB) | Totale |
| Normativa | SRD 863–870 MHz, duty cycle, e.r.p. | idem |
| Maturità | `[PROD]` (Meshtastic, RNode) | `[PROD]` |
| Casi d'uso | Messaggi, coordinate, telemetria, voce PTT, discovery | Burst dati tra nodi vicini |

## Appendice — script airtime

```python
import math
def toa(pl, sf, bw, cr=1, preamble=8, crc=1, ih=0):
    de = 1 if (sf >= 11 and bw == 125e3) else 0
    ts = (2**sf) / bw
    n = 8 + max(math.ceil((8*pl - 4*sf + 28 + 16*crc - 20*ih) / (4*(sf - 2*de))) * (cr + 4), 0)
    return (preamble + 4.25) * ts + n * ts
```
