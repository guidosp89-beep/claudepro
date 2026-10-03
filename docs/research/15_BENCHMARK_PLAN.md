# 15 — Benchmark Plan

Obiettivo: sostituire ogni `UNKNOWN / TO BENCHMARK` con misure ripetibili e confrontabili tra transport.

## 1. Metriche comuni (per ogni run)

| Campo | Unità | Note |
|---|---|---|
| `run_id`, `timestamp`, `git_sha` | — | Riproducibilità |
| `transport`, `transport_params` | — | es. `visual/qr`, `{version:25, ecc:M, fps_tx:15, tiles:1}` |
| `tx_device`, `rx_device` | modello + Android version | `Build.MODEL`, `Build.VERSION.SDK_INT` |
| `distance_m`, `angle_deg` | m, ° | Misurati con metro/goniometro |
| `environment` | enum | indoor_lab / indoor_office / outdoor_shade / outdoor_sun / night |
| `ambient_lux`, `ambient_dba` | lx, dB(A) | Da sensore luce / app fonometro (indicativi) |
| `payload_bytes` | B | Dimensione oggetto |
| `raw_bitrate` | bit/s | Simboli/frame emessi |
| `goodput` | bit/s | Byte utili verificati per hash / tempo totale |
| `latency_first_byte_ms` | ms | Dal "start" al primo frame valido |
| `time_to_complete_s` | s | |
| `frame_error_rate` / `packet_error_rate` | % | CRC falliti / frame attesi |
| `fountain_overhead` | % | simboli ricevuti / K − 1 |
| `retries` | n | Per transport con ARQ |
| `snr_db`, `rssi_dbm` | dB | Dove misurabile (audio, LoRa) |
| `battery_mAh`, `power_mW` | | `BatteryManager.BATTERY_PROPERTY_CURRENT_NOW` campionato (indicativo) o power monitor esterno |
| `cpu_load_pct` | % | `/proc/self/stat` |
| `temp_c` | °C | `PowerManager.getThermalHeadroom()` / thermal status |
| `success` | bool | Hash verificato |

Formato: **CSV + JSONL** in `benchmarks/results/`, uno per run; schema versionato.

## 2. Matrici di test

### 2.1 Visual (M1 spike, poi M3)
- Codici: QR v10/15/20/25/30 × ECC L/M/Q × tiles 1/2×2 × fps_tx 6/10/15/20/30.
- Distanze: 10, 20, 30, 50 cm; angoli 0°, 15°, 30°.
- Mano libera vs supporto fisso.
- Refresh sender: 60 vs 90/120 Hz (se disponibile).
- Camera: risoluzione analisi 720p/1080p; AF continuo vs fisso; esposizione auto vs breve.
- Baseline esterne: **cimbar** (cfc come ricevitore) per confronto con codici colore.

### 2.2 Audio (M5)
- Modem: ggwave (protocolli normal/fast/fastest, audible/ultrasound) + OFDM GHOSTLINK.
- Distanze: 0 (a contatto), 10 cm, 50 cm, 1 m, 3 m, 5 m; LOS e stanza adiacente con porta aperta.
- Rumore: silenzio, ufficio, strada (playback di rumore registrato a livello noto).
- Calibrazione: sweep 100 Hz–22 kHz TX/RX per coppia di device.

### 2.3 Flash / ottico (M9)
- Torcia: misurare latenza `setTorchMode` (fotodiodo + oscilloscopio/ADC dongle, o camera ad alta
  velocità); throughput OOK/Manchester 5–100 baud.
- LED dongle → rolling shutter: frequenze 1–20 kHz, distanze 0.5–5 m.

### 2.4 LoRa / FSK (M6)
- Preset SF7–SF12 × BW 125/250; GFSK 50/100/250 kbps.
- Percorsi: indoor, urbano 500 m/1 km/2 km, LOS su altura.
- Verifica duty-cycle accounting con SDR (RTL-SDR) che registra le emissioni per 1 h.

### 2.5 Multipath (M8)
- Stesso oggetto (100 KB) su visual + audio + LoRa simultanei; misurare contributo di ciascun canale e
  tempo di completamento vs singolo canale.

## 3. Procedura

1. Ogni configurazione ripetuta **≥ 5 volte**; riportare mediana e IQR (non solo la media).
2. Device "di riferimento": almeno **3 modelli** di fasce diverse (es. Pixel recente, Samsung mid-range,
   un Xiaomi economico) + i device dell'utente.
3. Modalità aereo attiva durante i test (dimostra assenza di radio standard).
4. Ogni run salva anche i primi N frame/finestre audio grezzi per debug offline (opz.).
5. Report automatico: script (Python/pandas) → grafici goodput vs distanza, PER vs parametro, energia
   per KB, confronto tra transport sulla stessa scala (bit/s logaritmica).

## 4. App di benchmark

Modulo `benchmark/` dell'app con:
- schermata "Sender" e "Receiver" per ogni transport, parametri da UI o da file JSON di campagna;
- sincronizzazione della campagna tra i due telefoni via QR (il receiver scansiona la configurazione);
- export CSV tramite SAF.

## 5. Simulatore (complementare)

`GhostTransportSimulator` (desktop, Rust) riproduce i profili misurati (loss, burst loss Gilbert-Elliott,
latenza, bandwidth, disconnessioni) per testare routing e fountain senza hardware. I parametri dei
modelli vengono **calibrati dai dati reali** di questo piano.
