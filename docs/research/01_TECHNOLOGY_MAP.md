# 01 — Technology Map

> Stato: Phase 0 (ricerca). Ultimo aggiornamento: 2026-10-03.
> Convenzioni usate in tutti i documenti:
>
> **Livello di evidenza**
> - `[SCI]` dimostrato scientificamente (paper peer-reviewed, condizioni controllate)
> - `[PROTO]` prototipo realmente funzionante / open source utilizzabile
> - `[PROD]` prodotto commerciale o app pubblicata e mantenuta
> - `[THEORY]` teoricamente possibile, non visto dimostrato su Android
> - `[IMPRACTICAL]` possibile ma poco pratico
>
> **Confidenza della stima**: `HIGH` (fonte primaria o calcolo da formula), `MEDIUM` (fonte secondaria o
> paper in condizioni diverse dalle nostre), `LOW` (estrapolazione, claim non verificato).
> `UNKNOWN / TO BENCHMARK` = nessun dato affidabile: va misurato.

## 1. Il quadro in una pagina

Il canale "fisico" di uno smartphone Android senza radio standard (no cellulare, Wi-Fi, BT, NFC) si
riduce a quattro famiglie di trasduttori già presenti, più qualunque cosa colleghiamo alla USB-C:

| Trasduttore TX | Trasduttore RX | Canale | Famiglia |
|---|---|---|---|
| Display (OLED/LCD, 60–144 Hz) | Fotocamera (rolling shutter, 30–60 fps utili) | Ottico, LOS, corto raggio | **Visual** |
| Speaker (1–2, banda utile ~0.3–20 kHz) | Microfono (1–4, 48 kHz) | Acustico, NLOS limitato, corto/medio | **Audio** |
| LED flash/torcia | Fotocamera / fotodiodo esterno | Ottico, LOS, medio | **Flash VLC** |
| IR blaster (pochi modelli, solo TX) | — (nessun RX IR pubblico) | Ottico IR | **IR (parziale)** |
| USB-C (host/OTG) → dongle | USB-C ← dongle | Qualunque PHY esterno | **External** |

Tutto il resto (LoRa, FSK sub-GHz, IR bidirezionale, laser/LED focalizzati, ultrasuoni a 40 kHz, SDR) richiede
hardware esterno, e su Android l'unica porta generale e non-root per farlo è **USB Host API** (più, in
teoria, il jack audio dove ancora esiste).

## 2. Mappa per famiglia

```text
                         ┌──────────────── SOLO SMARTPHONE ────────────────┐
                         │                                                  │
 VISUAL  ── QR animati ──┤ [PROD/PROTO] 5–30 KB/s realistici (TO BENCHMARK)   │
         ── codici colore (cimbar) [PROTO] fino a ~106 KB/s monitor→phone    │
         ── invisibile (HiLight/InFrame/ChromaCode) [SCI] 1–120 kbps lab     │
                         │                                                  │
 AUDIO   ── MFSK (ggwave) [PROTO] 8–16 B/s, robusto                         │
         ── OFDM/PSK audible [SCI/PROTO] 1–10 kbps a < 1–2 m (TO BENCHMARK)   │
         ── near-ultrasonic 18–20 kHz [SCI] ~4 kbps @5 m su laptop (lab)      │
                         │                                                  │
 FLASH   ── torcia via Camera2 [SCI] decine di bps; solo beacon              │
                         │                                                  │
 IR      ── IR blaster (TX only) [PROD] ~ centinaia bps, nessun RX           │
                         └──────────────────────────────────────────────────┘

                         ┌──────────── CON HARDWARE USB-C ────────────────┐
 LoRa SX126x       [PROD] 0.3–22 kbps, km, duty-cycle limitato (EU)         │
 FSK/GFSK sub-GHz  [PROD] 1–300 kbps, centinaia m – km, duty-cycle           │
 IR TX/RX          [PROD comp.] 1–115 kbps (IrDA-like), 1–10 m, LOS           │
 LED/photodiode    [PROTO] kbps–Mbps, metri–decine di m, LOS                 │
 LED → camera RS   [SCI]   1–10 kbps verso telefoni non modificati            │
 Ultrasuoni 40 kHz [PROTO] decine–centinaia bps, pochi m, banda stretta       │
 SDR (RX)          [PROD]  strumento di misura; TX = vincoli normativi       │
 Cavo USB/jack     [PROD]  Mbps, 0 m, fallback e "dead drop" su supporto     │
                         └─────────────────────────────────────────────────┘

                         ┌──────────── TRASVERSALE (non PHY) ─────────────┐
 Store-carry-forward (DTN, spray-and-wait)       [SCI/PROD]               │
 Fountain codes (RaptorQ/LT/Wirehair)            [PROD]                   │
 GhostDrop: payload su carta/video/WAV           [PROD: LXMF paper msg]   │
                         └─────────────────────────────────────────────────┘
```

## 3. Dove sono le prestazioni reali (sintesi)

| Canale | Throughput realistico consumer | Range realistico | Confidenza |
|---|---|---|---|
| QR animati phone→phone | 5–30 KB/s | 10–50 cm | MEDIUM (stima da capacità QR × fps) |
| Codice colore denso (cimbar-like) | 20–100 KB/s | 15–40 cm | LOW–MEDIUM (claim progetto, monitor→phone) |
| Audio MFSK robusto | 8–16 B/s | 1–5 m (anche NLOS in stanza) | HIGH (doc ggwave) |
| Audio OFDM udibile | 0.5–5 kbps | 0.1–2 m | LOW → TO BENCHMARK |
| Near-ultrasonic | 0.05–4 kbps | 0.1–5 m | MEDIUM (paper laptop, phone variabile) |
| Flash torcia | 10–60 bps | 1–20 m (notte) | LOW–MEDIUM |
| LoRa SX1262 (EU, SF7–SF12) | 0.3–11 kbps raw, media limitata dal duty-cycle | 1–15 km LOS, 0.3–3 km urbano | HIGH (formula) / MEDIUM (range) |
| FSK sub-GHz | 10–100 kbps raw | 0.1–2 km | MEDIUM |
| IR dongle | 10–115 kbps | 1–10 m LOS | MEDIUM |
| LED/photodiode dongle | 0.1–10 Mbps | 1–50 m LOS (con ottica) | MEDIUM (dipende da ottica/puntamento) |

Dettagli e fonti nei documenti 02–07 e nella `TECHNOLOGY_MATRIX.md`.

## 4. Osservazioni chiave emerse dalla ricerca

1. **Nessun canale "solo smartphone" è simultaneamente veloce, a lungo raggio e NLOS.** Il visuale è
   veloce ma LOS e corto; l'audio è NLOS ma lento; la torcia è lontana ma lentissima.
2. **Il collo di bottiglia del visuale non è il display ma la pipeline camera→decoder** (fps effettivi
   accessibili via `ImageAnalysis`/`ImageReader`, motion blur, rolling shutter, tempo di decodifica).
   Le sessioni Camera2 "constrained high speed" (≥120 fps) sono pensate per preview + encoder video,
   non per l'analisi frame-by-frame dall'app (vedi 10_ANDROID_CAPABILITIES).
3. **La torcia è un transmitter pessimo** (API non pensate per modulazione), ma la **camera è un buon
   receiver** di luce modulata grazie al rolling shutter → asimmetria sfruttabile: LED veloce su dongle
   → qualunque telefono riceve.
4. **Il canale long-range realistico è solo radio esterna** (LoRa/FSK sub-GHz), e in UE è limitato
   più dalla normativa (duty-cycle 0.1–10%) che dal PHY.
5. **La parte più innovativa non è un singolo PHY** (quasi tutti esistono già come progetti isolati),
   ma l'**unione**: un core di pacchetto/crittografia/DTN unico in cui display, speaker, torcia, LoRa
   ecc. sono interfacce intercambiabili, con fountain coding cross-medium. Il riferimento più vicino è
   **Reticulum** (multi-interfaccia, ma non sfrutta canali visual/audio nativi del telefono come
   interfacce di prima classe, salvo i "paper messages" QR di LXMF) — vedi 12_EXISTING_PROJECTS.

## 5. Indice dei documenti

- 02 Visual · 03 Acoustic · 04 Optical (flash, IR, LED, laser) · 05 External HW (USB) · 06 LoRa/Sub-GHz
- 07 SDR · 08 DTN/Routing · 09 Crypto/Identity · 10 Android · 11 Normative · 12 Progetti esistenti
- 13 Innovation gaps · 14 Risk register · 15 Benchmark plan · 16 Roadmap · `TECHNOLOGY_MATRIX.md`
- Architettura: `../architecture/`
