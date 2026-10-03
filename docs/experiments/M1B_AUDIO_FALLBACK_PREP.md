# M1B — Audio fallback: preparazione (non implementato in M1)

Scopo (brief §37): avere pronto un confronto serio se il canale visuale fallisse, e definire il ruolo
dell'audio come canale di controllo (§39). Nessun modem audio è stato implementato in M1: i numeri sotto
sono **di letteratura/progetto**, non misurati da noi, salvo dove indicato.

## 1. Candidati

| Schema | Throughput atteso (aria, smartphone) | Robustezza | Compatibilità Android | CPU | Latenza tipica | Fonte / confidenza |
|---|---|---|---|---|---|---|
| **ggwave** (MFSK 6 toni, RS) | **8–16 B/s** | Alta (stanza, NLOS corto) | Alta: binding Java/KMP, AudioRecord/AudioTrack | Molto bassa | 2–5 s per 32 B | README ggwave — HIGH |
| **AFSK** (Bell 202, 1200 baud) | 300–1 200 bit/s a < 1 m | Media; teme riverbero | Alta | Trascurabile | ~0.1 s + preamble | Storico APRS/minimodem — MEDIUM per l'aria |
| **MFSK lento** (Olivia/JS8-like) | 10–100 bit/s | **Molto alta** (sotto il rumore) | Alta | Bassa | secondi | Modi HF digitali — MEDIUM |
| **OFDM** (profili libquiet, modem custom) | 0.5–8 kbit/s a 0–50 cm; < 2 kbit/s in stanza | Media; teme Doppler e AGC | Media: richiede `UNPROCESSED`, calibrazione | Media (FFT) | 0.2–0.5 s/frame | Dhwani 2.4 kbps @10 cm; NUSC 4 kbps @5 m (laptop) — LOW–MEDIUM |
| **Chirp / CSS** | 20–500 bit/s | **Altissima** (rumore, Doppler, multipath) | Alta | Bassa-media (correlazione) | 0.1–1 s | Principio LoRa applicato all'acustica — MEDIUM |
| Near-ultrasonic 18–20 kHz (qualsiasi modulazione) | 0.05–2 kbit/s | Variabile per device | **Bassa/variabile** (`PROPERTY_SUPPORT_*_NEAR_ULTRASOUND`) | Come sopra | come sopra | CDD/CTS; NUSC — LOW per phone |

## 2. Confronto con il visual (ordine di grandezza)

Anche il miglior audio realistico (OFDM a contatto, ~1 KB/s) è **5–100× sotto** l'obiettivo M1 del visual
(≥ 5 KB/s). L'audio **non è un sostituto per i file**: è un canale per messaggi brevi, chiavi, capability.

## 3. Serve un canale di controllo per il bulk? (brief §39)

Dalle misure cloud (vedi `M1_FINAL_REPORT.md` §F): con RaptorQ l'efficienza resta ≈ (1 − perdita) fino al
50% di frame persi, con duplicati e riordino, **senza alcuna richiesta di ritrasmissione**. Quindi:

| Funzione | Necessaria? | Perché | Canale minimo |
|---|---|---|---|
| ACK per simbolo / NACK | **No** | Il fountain rende ogni frame utile; nessun simbolo specifico va richiesto | — |
| "DONE" (fine trasferimento) | Utile | Senza, il TX trasmette per una durata fissa (spreco di tempo/batteria; nel lab allunga le sessioni) | 1 evento, ~1 bit: tono doppio o 2–4 byte ggwave |
| Rate/densità adattiva | Utile | Scegliere QR/griglia/fps in base alla qualità misurata dal RX | pochi byte ogni 1–2 s |
| Negoziazione sessione/capability | Sì (prodotto) | Scegliere PHY comuni, chiavi, sessione | 50–200 B una tantum: ggwave basta (10–20 s) o QR inverso |
| Resume di oggetti grandi | Opzionale | Il ricevitore conserva i simboli; un "have/need" accelera | pochi byte |

Conclusione: **per il bulk transfer l'ACK non serve**; l'audio (o un QR mostrato dal ricevitore) serve per
DONE, rate hint e negoziazione. Primo passo pratico (M1.1): "DONE" acustico a due toni per fermare il
trasmettitore e accorciare il benchmark.

## 4. Piano M1B (solo se il visual fallisse il gate)

1. Integrare ggwave (C++ via JNI o binding Java esistente) come baseline robusta — misurare B/s e tasso
   di successo a 0.1/0.5/1/3 m, stanza silenziosa e rumorosa.
2. Modem OFDM sperimentale (Kotlin, FFT) a contatto/10 cm per il massimo throughput.
3. Chirp preamble + MFSK per robustezza.
4. Stesso harness: payload deterministici, SHA-256, `runs.csv` con le stesse colonne (PHY = AUDIO-*).
