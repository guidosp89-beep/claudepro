# 03 — Acoustic Communication

Copre AREA C (speaker→mic), AREA K (ultrasuoni con hardware esterno) e AREA M (dati attraverso
qualunque sistema audio esistente).

## 1. Principio fisico

Onde di pressione nell'aria (c ≈ 343 m/s). Il canale acustico indoor è molto simile al canale
acustico **subacqueo**: forte **multipath / riverbero** (RT60 di una stanza 0.3–1 s), **Doppler**
significativo anche a velocità di cammino (1 m/s → Δf/f ≈ 0.3%: 60 Hz a 20 kHz), rumore ambiente
colorato, risposta in frequenza non piatta di speaker e microfono, e catena di elaborazione del
telefono (AGC, noise suppression, echo cancellation) che può **distruggere** il segnale se non
bypassata. Il paper NUSC 2021 sfrutta esplicitamente questa analogia con il canale subacqueo.

## 2. Hardware e API Android

- **TX**: `AudioTrack` (Java) o **AAudio/Oboe** (NDK, bassa latenza), PCM 16 bit / float a 48 kHz
  (frequenza nativa più comune; 44.1 kHz su alcuni device).
- **RX**: `AudioRecord` / AAudio. Sorgente consigliata: `MediaRecorder.AudioSource.UNPROCESSED`
  (API 24+, disponibile se `AudioManager.getProperty(PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED)`
  è "true") altrimenti `VOICE_RECOGNITION`. Evitare `VOICE_COMMUNICATION` (attiva AEC/NS).
- **Near-ultrasound**: proprietà `AudioManager.PROPERTY_SUPPORT_MIC_NEAR_ULTRASOUND` e
  `PROPERTY_SUPPORT_SPEAKER_NEAR_ULTRASOUND` (API 23). Il CDD Android sez. 7.8.3 "raccomanda"
  il supporto near-ultrasound (18.5–20 kHz) e lo verifica con test CTS dedicati — **non è obbligatorio**
  e il valore della proprietà va letto a runtime. Confidenza: MEDIUM-HIGH.
  <https://source.android.com/compatibility/cts/near-ultrasound>
- Permesso `RECORD_AUDIO` (runtime, while-in-use); in background serve foreground service di tipo
  `microphone` (Android 14+), avviabile solo da contesto visibile all'utente.

## 3. Modulazioni

| Schema | Note | Bitrate tipico in aria (consumer) | Robustezza |
|---|---|---|---|
| AFSK/BFSK (Bell 202, 1200 baud) | Storico, semplice, `minimodem` | 300–1 200 bps a pochi cm–1 m | Media |
| **MFSK** (ggwave) | 6 toni contemporanei, Reed-Solomon | **8–16 B/s** | **Alta**, anche NLOS in stanza |
| PSK/QPSK + equalizzazione | Richiede sincronizzazione di fase, equalizzatore adattivo | 1–4 kbps (paper) | Media-bassa senza DSP serio |
| QAM | Sensibile a SNR/fase | poco adatto all'aria | Bassa |
| **OFDM** | Gestisce multipath con cyclic prefix; equalizzazione per sottoportante | 1–10 kbps a corto raggio (libquiet "audible-7k": ~7 kbps suggeriti dal nome del profilo, da verificare) | Media; teme Doppler |
| **Chirp spread spectrum** | Robusto a rumore e Doppler, ottimo per preamble/sync | decine–centinaia bps | **Molto alta** |
| DSSS | Robusto, guadagno di processo | decine–centinaia bps | Alta |

### 3.1 Riferimenti verificati

- **ggwave** (Georgi Gerganov, MIT) — MFSK con dF = 46.875 Hz, F0 = 1 875 Hz (udibile) o 15 000 Hz
  (ultrasonic profiles), RS-ECC, **8–16 byte/s**; C++ con binding Android/iOS/JS/Python. `[PROTO/PROD]`
  Confidenza HIGH. <https://github.com/ggerganov/ggwave>
- **libquiet / quiet** — modem su liquid-dsp, profili "audible", "audible-7k-channel-0/1",
  "ultrasonic-*" ("bitrate molto basso" sopra 16 kHz) e "cable-*" (spettro pieno via cavo).
  Bindings Android storici (quiet/org.quietmodem). Repository poco attivo → considerarlo
  **semi-abbandonato** (verificare commit recenti). `[PROTO]` <https://github.com/quiet/quiet>
- **minimodem** — Bell 103/202, RTTY, ecc. Desktop. `[PROD]`
- **NUSC (2021)** — near-ultrasonic 18–20 kHz, modulazione coerente + equalizzazione adattiva
  phase-coherent: **4 kbps fino a 5 m tra laptop**; i lavori precedenti nella stessa banda riportavano
  **15–94.5 bps a 2–25 m**. `[SCI]` Confidenza MEDIUM per smartphone (testato su laptop).
  <https://arxiv.org/abs/2103.11261>
- **Dhwani** (Microsoft Research, SIGCOMM 2013) — "acoustic NFC" fino a **2.4 kbps a ~10 cm**, con
  self-jamming per sicurezza. `[SCI]`
- **Google Nearby Messages** usava near-ultrasound per co-presenza; il supporto ultrasonico è stato
  rimosso nel 2021 e l'API è stata deprecata (fine 2023). **Chirp.io** (data-over-sound) è stato
  acquisito da Sonos. Lezione: tecnologia funzionante ma fragile su larga scala di device. `[PROD→dismesso]`

## 4. Bande

### Udibile (≈ 500 Hz – 15 kHz)
- Speaker e microfoni sono ottimizzati per la voce (300 Hz–8 kHz): banda più affidabile.
- Fastidiosa per gli utenti; utilizzabile in emergenza o per "handshake" brevi.

### Near-ultrasonic (≈ 17–22 kHz)
- Con Fs = 48 kHz il limite di Nyquist è 24 kHz, ma il filtro anti-aliasing e la risposta dello
  speaker crollano spesso già sopra 18–20 kHz.
- **Non tutti i device generano/ricevono**: va **misurato per device** con una procedura di
  calibrazione (sweep 15–22 kHz all'installazione o alla prima connessione).
- Udibile da bambini/giovani e **da animali domestici**; possibili armoniche e intermodulazione
  udibili per non-linearità dello speaker (click a inizio/fine burst → serve finestratura).

## 5. Problemi di canale e contromisure

| Problema | Contromisura |
|---|---|
| Sincronizzazione | Preamble **chirp** (LFM) + correlazione; stima offset di frequenza dal chirp |
| AGC | Usare UNPROCESSED; normalizzare livelli; preamble di training |
| Echo cancellation / NS | Evitare `VOICE_COMMUNICATION`; verificare via test loopback |
| Risposta in frequenza | Calibrazione con sweep; bit-loading per sottoportante OFDM |
| Riverbero/multipath | Cyclic prefix > delay spread (5–20 ms in stanza) → simboli lunghi |
| Doppler | Chirp/FSK robusti; OFDM con spaziatura sottoportanti ampia + tracking |
| Rumore ambiente | FEC (LDPC/convoluzionale + RS), interleaving, ARQ |
| Half-duplex | Un solo device trasmette alla volta (feedback dello speaker sul proprio mic) |

## 6. Prestazioni realistiche attese per GHOSTLINK

| Modalità | Bitrate | Range | Confidenza |
|---|---|---|---|
| Control channel MFSK (ggwave-like) | 8–16 B/s | 1–5 m, anche NLOS in stanza | HIGH |
| Data OFDM udibile, telefoni a contatto | 2–8 kbps | 0–20 cm | LOW → TO BENCHMARK |
| Data OFDM udibile, stanza | 0.3–2 kbps | 1–3 m | LOW → TO BENCHMARK |
| Near-ultrasonic dati | 0.1–2 kbps | 0.1–3 m | LOW–MEDIUM (device dipendente) |

Consumo: speaker a volume alto + DSP: centinaia di mW (TO BENCHMARK). Latenza: 100–500 ms per
frame (preamble + simboli lunghi).

**Ruolo raccomandato in GHOSTLINK**: canale di **controllo/ACK/negoziazione** (capability
exchange, "ho ricevuto K simboli, stop", richiesta rate diverso) e messaggi brevi; **non** canale per
file. È anche il canale più "universale" (qualunque telefono ha speaker + mic).

## 7. AREA M — Dati attraverso sistemi audio esistenti

```text
DATA → waveform audio → [walkie-talkie | radio FM | interfono | PA | WAV su chiavetta] → mic → DATA
```

- Precedenti storici: modem telefonici (V.21–V.34), **AFSK 1200 / APRS / AX.25** su radio VHF,
  **SSTV**, **FT8/JS8Call** (HF, bitrate di pochi bps), **FreeDV** (Codec2 + OFDM in 1.25 kHz),
  **M17** (digitale aperto), modem "cable-" di libquiet.
- Vincoli: banda dei sistemi voce (300–3 000 Hz su radio/telefono), compander, **VOX** che taglia
  l'inizio della trasmissione (servono preamble lunghi), codec vocali (AMR/Opus) che distruggono
  modulazioni non vocali.
- Bitrate realistico su walkie-talkie analogico: 300–1 200 bps (AFSK) — confidenza MEDIUM.
- **Attenzione normativa**: trasmettere dati su apparati radio (es. PMR446) dipende dalle regole d'uso
  della banda — vedi 11_REGULATORY. Il file WAV, un PA o un interfono non hanno vincoli radio.
- **Valore per GHOSTLINK**: un `AudioTransport` in modalità "line/cable" + un formato WAV
  autocontenuto (GhostDrop audio) permettono di trasportare pacchetti su **qualsiasi** catena audio.

## 8. AREA K — Ultrasuoni con hardware esterno

```text
PHONE → USB-C → MCU/DSP → driver → trasduttore piezo 40 kHz TX | RX → ampli → ADC → MCU
```

- Trasduttori piezo 40 kHz (tipo quelli dei sensori di parcheggio/HC-SR04) hanno **banda stretta**
  (Q alto, ~1–4 kHz di banda utile): bitrate di **decine–centinaia di bps** con OOK/FSK; alcuni kbps
  con trasduttori a banda larga (MEMS ultrasonici, ceramiche dedicate, più costosi). `[PROTO]`
  Confidenza MEDIUM.
- Assorbimento atmosferico a 40 kHz ≈ 1 dB/m (alto, dipende da umidità) → range pratico pochi metri,
  10 m con trasduttori potenti e direzionali. Confidenza MEDIUM.
- **Vantaggi** rispetto a speaker/mic del telefono: banda dedicata, nessuna interferenza con l'audio
  udibile, nessuna dipendenza dalla risposta del telefono, possibilità di directional link.
- **Svantaggi**: hardware, direzionalità, consumo del driver, limiti di esposizione (vedi 11).
- **Esposizione**: linee guida IRPA / Health Canada Safety Code 24 indicano limiti occupazionali
  di **75 dB a 20 kHz (banda 1/3 ottava)** e **110 dB fra 25 e 100 kHz**. Non sono una norma UE
  vincolante per prodotti consumer: trattarle come limite di progetto prudente.
  <https://www.canada.ca/en/health-canada/services/environmental-workplace-health/reports-publications/radiation/guidelines-safe-use-ultrasound-part-industrial-commercial-applications-safety-code-24.html>
- Verdetto: **interessante ma non prioritario** — offre poco più dell'IR a parità di complessità,
  con range minore. Utile come ricerca su link "near-field" discreti.

## 9. Scheda sintetica

| Parametro | Audio udibile | Near-ultrasonic | Ultrasuoni dongle 40 kHz |
|---|---|---|---|
| HW extra | No | No | Sì |
| Root | No | No | No |
| API | AudioTrack/AudioRecord, AAudio/Oboe | idem + proprietà NEAR_ULTRASOUND | USB Host |
| Bitrate realistico | 0.1–8 kbps | 0.05–2 kbps | 0.05–1 kbps |
| Range | 0–5 m | 0.1–3 m | 1–10 m |
| LOS | No (stanza) | Quasi (ostacoli attenuano molto) | Quasi sì |
| Indoor/outdoor | Indoor migliore; outdoor rumore/vento | Indoor | Entrambi (assorbimento) |
| Compatibilità | Altissima | Variabile, da calibrare | Dipende solo dal dongle |
| Normativa | Nessuna specifica (rumore) | Esposizione (prudenza) | Esposizione (IRPA/HC come riferimento) |
| Casi d'uso | Control channel, handshake, SAS audio, WAV drop | Beacon discreti | Link discreti a corto raggio |
