# 04 — Optical Communication (Flash VLC, IR, LED/Laser point-to-point)

Copre AREA D (flash/VLC), AREA E (infrarosso), AREA F (laser/ottico punto-punto).

## 1. Flash / Visible Light Communication

### 1.1 Principio
Modulazione d'intensità del LED (OOK, Manchester, PWM, PPM) ricevuta da:
- **fotodiodo** (banda MHz → bitrate alti, serve hardware);
- **fotocamera** a frame (1 campione/frame → bitrate ≤ fps/2);
- **fotocamera rolling shutter**: ogni riga del sensore è esposta in un istante diverso → una
  sorgente che lampeggia a kHz produce **bande** chiare/scure nella stessa immagine; con readout di
  ~10–30 µs/riga si ottengono **migliaia di campioni per frame**. `[SCI]`

### 1.2 TX dal telefono: la torcia
- API pubbliche: `CameraManager.setTorchMode(cameraId, on)` (API 23) e, da Android 13,
  `turnOnTorchWithStrengthLevel(cameraId, level)` con `FLASH_INFO_STRENGTH_MAXIMUM_LEVEL > 1` se l'HAL
  supporta più livelli. `[PROD]`
  <https://source.android.com/docs/core/camera/torch-strength-control>
- Queste API **non sono pensate per la modulazione**: ogni chiamata attraversa binder → camera service
  → HAL → driver del LED; latenza e jitter dell'ordine di **decine di ms** (TO BENCHMARK per device).
  La torcia è inoltre condivisa con la camera (non usabile se la camera posteriore è aperta da un'altra
  sessione, in alcuni device anche dalla stessa app).
- Letteratura: smartphone LED flash con bi-phase a 60 baud → **30 bps** (iPhone 4, ~60 toggle/s);
  sistemi Android con LED → fino a 5 kbps ma con condizioni/hardware specifici; IoTorch: "decine di
  bps" long-range e "migliaia di bps" short-range. `[SCI]`, confidenza LOW-MEDIUM per Android
  moderno senza root.
- **Stima per GHOSTLINK: 10–60 bps** affidabili con OOK/Manchester lento. Utile per **beacon**,
  presenza, wake-up, ID brevi (16–32 byte in pochi secondi), SOS. Non per dati.

### 1.3 Alternativa migliore come trasmettitore: lo schermo
Lo schermo intero a luminosità massima che alterna bianco/nero a 30–60 Hz è un trasmettitore più veloce
e più controllabile della torcia (frame-accurate via `Choreographer`), visibile a decine di metri al
buio. È un **beacon ottico a lungo raggio "povero"** (decine di bps), ricevibile da una camera con
zoom. `[THEORY]` → TO BENCHMARK.

### 1.4 RX sul telefono: rolling shutter
- Lavori su smartphone: 1.2 kbps a 3 m; 2 kbps a 0.3 m; record dichiarato 10.32 kb/s con pattern
  beacon-jointed; error-free (BER < 10⁻⁴) a 4.9–7.5 ksym/s in QVGA con shutter veloce. `[SCI]`
  Confidenza MEDIUM: dipende dal sensore (tempo di riga), dall'esposizione minima impostabile
  (`SENSOR_EXPOSURE_TIME` con capability `MANUAL_SENSOR`), dalla dimensione della sorgente nell'immagine.
- Il rolling shutter richiede che la sorgente **copra molte righe** dell'immagine → sorgente grande o
  vicina, o diffusore (es. LED dietro un pannello opalino).
- **Combinazione interessante**: un **LED pilotato da MCU su dongle** (TX a kHz, preciso) → **qualunque
  telefono non modificato** riceve con la camera a 1–10 kbps. Asimmetrico ma pratico per beacon di
  dead-drop, kiosk offline, nodi infrastrutturali.

### 1.5 Luce ambiente, outdoor, notte
- Sole diretto: ~100 klux, satura i sensori e riduce il contrasto → outdoor diurno pessimo senza filtri.
- Notte: eccellente, range lungo (decine di m per beacon, centinaia per luce forte).
- Lampade a 50/100 Hz e LED con PWM introducono interferenza periodica (notch/filtri).

## 2. Infrarosso

### 2.1 Telefoni con IR blaster
- API: `ConsumerIrManager` (API 19), `FEATURE_CONSUMER_IR`; `transmit(carrierFrequency, pattern[])`
  con pattern on/off in µs, **max 2 s per chiamata**, sincrona. **Nessuna API di ricezione IR**. `[PROD]`
  <https://developer.android.com/reference/android/hardware/ConsumerIrManager>
- Diffusione: pochi marchi (soprattutto Xiaomi/Redmi/POCO, alcuni Huawei/Honor/Vivo); assente su
  Pixel/Samsung recenti. Compatibilità: bassa.
- Bitrate: protocolli telecomando (NEC/RC5) ≈ 0.5–2 kbps grezzi; con portante 38 kHz e pattern custom
  forse qualche kbps; TO BENCHMARK. Ricevibile da un **ricevitore TSOP 38 kHz su dongle** o da una
  camera (molti sensori vedono 940 nm debolmente, 850 nm meglio — dipende dal filtro IR-cut).
- Ruolo: **TX-only beacon** verso dongle o verso telecamere.

### 2.2 Dongle IR USB-C
```text
USB-C ── MCU ── driver LED IR (850/940 nm) ── TX
              └─ fotodiodo PIN + TIA / ricevitore IrDA ── RX
```
- **850 nm**: fotodiodi al silicio più sensibili, LED più efficienti; debolmente visibile (rosso tenue).
- **940 nm**: invisibile, meno rumore solare (assorbimento atmosferico), standard telecomandi.
- Modulazioni: OOK con portante 38–56 kHz (TSOP, robusto alla luce ambiente, ≤ 2–4 kbps), oppure
  baseband IrDA SIR (fino a 115.2 kbps) / FIR (4 Mbps, con transceiver dedicati). `[PROD componenti]`
- Range: IrDA standard 1 m; con LED potenti, lente e ricevitore a portante 10–30 m. Direzionalità
  ±15–30° tipica (regolabile con ottiche).
- Sicurezza: LED IR ad alta potenza rientrano nella sicurezza fotobiologica **IEC/EN 62471**
  (gruppi di rischio); l'occhio non ha riflesso di ammiccamento all'IR → limitare irradianza.

## 3. Laser / LED focalizzati punto-punto

```text
Android A ─USB-C─ modem ottico ─ LED/laser + ottica ───(aria, LOS)───> ottica + PD/APD ─ modem ─USB-C─ Android B
```

- **Bitrate**: con LED veloci + fotodiodo PIN + TIA, **1–10 Mbps** sono comuni in progetti hobby/accademici;
  con laser diode modulati direttamente decine–centinaia di Mbps (FSO commerciale fino a Gbps). Il
  limite pratico di un dongle via USB 2.0 Full/High Speed è comunque l'MCU/USB (1–40 Mbps). `[PROTO]`
  Confidenza MEDIUM.
- **Distanza**: LED con lente 5–50 m; laser centinaia di m–km, ma...
- **Puntamento**: il fascio laser collimato a 100 m ha spot di pochi cm–dm → serve un treppiede e un
  sistema di allineamento (mirino, fascio di guida visibile a bassa potenza, beacon LED largo per
  acquisizione). Con un telefono in mano **non è praticabile**. LED con fascio largo (10–20°) sono molto
  più tolleranti.
- **Interferenza**: sole (filtri ottici passabanda + modulazione AC), nebbia/pioggia (attenuazione forte),
  scintillazione termica outdoor.
- **Sicurezza occhi**: classificazione **IEC/EN 60825-1** (classe 1, 1M, 2, 2M, 3R, 3B, 4). Per prodotti
  consumer in UE **EN 50689:2021**: prodotti "child-appealing" solo classe 1; prodotti consumer non
  ammessi in 1M, 2M, 3B, 4. → **Prototipi GHOSTLINK: solo classe 1 (o LED IR conformi a EN 62471
  gruppo esente)**. Nessun utilizzo di laser puntati verso persone, veicoli o aeromobili.
  <https://ul.com/insights/understand-new-laser-product-safety-standards-europe>
- **Raccomandazione**: privilegiare **LED IR/visibili a fascio largo** (eye-safe, tolleranti al puntamento)
  per il "GHOSTLINK Optical". Il laser resta un esperimento di laboratorio, classe 1, su banco ottico.

## 4. Scheda sintetica

| Parametro | Flash→camera | Schermo→camera beacon | LED dongle→camera RS | IR blaster | IR dongle | LED/PD dongle | Laser dongle |
|---|---|---|---|---|---|---|---|
| HW extra | No | No | Sì (TX) | No (raro) | Sì | Sì (entrambi) | Sì |
| Root | No | No | No | No | No | No | No |
| Bitrate realistico | 10–60 bps | 10–30 bps | 1–10 kbps | 0.5–2 kbps (TX only) | 2–115 kbps | 0.1–10 Mbps | 1–100 Mbps (lab) |
| Range | 1–30 m (notte) | 5–50 m (notte) | 0.5–5 m | 1–10 m | 1–30 m | 1–50 m | 10 m–km (con puntamento) |
| LOS | Sì | Sì | Sì | Sì | Sì | Sì | Sì, rigido |
| Outdoor giorno | Scarso | Scarso | Scarso | Discreto (portante) | Discreto | Discreto con filtri | Buono con filtri |
| Consumo TX | ~0.5–1.5 W (LED) | ~1–2 W display | dal dongle | basso | basso–medio | medio | basso |
| Compatibilità | Alta (TX), media (RX) | Alta | Alta (RX) | Bassa | Totale (dongle) | Totale | Totale |
| Maturità | `[SCI]` | `[THEORY]` | `[SCI]` | `[PROD]` | `[PROD]` componenti | `[PROTO]` | `[PROTO]`/`[PROD]` FSO |
| Normativa | Fotosensibilità | Fotosensibilità | EN 62471 | — | EN 62471 | EN 62471 | EN 60825-1, EN 50689 |
| Casi d'uso | SOS, beacon ID | Beacon notturno | Dead-drop beacon, kiosk | Wake-up dongle | Link discreto 1–10 m | File veloci LOS | Ricerca |
