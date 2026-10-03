# 02 — Visual Communication (Screen → Camera)

Copre AREA A (screen-to-camera) e AREA B (canale invisibile / semi-invisibile).

## 1. Principio fisico

Il display emette una matrice di pixel modulata in **spazio** (pattern 2D), **colore** (canali RGB /
crominanza) e **tempo** (sequenza di frame). La fotocamera campiona questa scena con un sensore CMOS
quasi sempre a **rolling shutter** (le righe sono esposte in istanti diversi), a 30–60 fps utili per
l'app, attraverso ottica, autofocus, auto-esposizione, ISP (denoise, sharpening, tone mapping) e
compressione assente (se si leggono buffer YUV) o presente (se si registra video).

Il canale è quindi un canale MIMO spaziale enorme (milioni di "antenne" pixel) ma con:

- **sincronizzazione assente** tra refresh del display (60–144 Hz) e scatto della camera;
- **frame misti**: la camera può catturare metà del frame N e metà del frame N+1 (rolling shutter);
- **distorsione prospettica**, sfocatura, motion blur (mano), moiré tra griglia pixel del display e
  del sensore, riflessi, luce ambiente;
- **non-linearità colore** (gamut del display ≠ risposta del sensore, white balance automatico).

## 2. Stato dell'arte

### 2.1 Codici 2D standard (QR, Data Matrix, Aztec)

| Codice | Capacità max binaria (1 simbolo) | Note |
|---|---|---|
| QR v40-L | 2 953 byte | v40 = 177×177 moduli, troppo denso per telefoni a distanza normale |
| QR v25-L / v25-M | 1 273 / 997 byte | zona "pratica" su phone→phone a 20–40 cm (TO BENCHMARK) |
| QR v20-L / v20-M | 858 / 666 byte | molto robusto |
| Data Matrix 144×144 | 1 556 byte | buona densità, meno librerie veloci in streaming |
| Aztec 151×151 | ~1 914 byte | nessuna quiet zone, buono per tiling |

Confidenza: HIGH (valori da ISO/IEC 18004 e 16022/24778, tabelle standard). Tutti includono già
Reed-Solomon interno, che corregge errori *dentro* un frame; non corregge frame persi.

**Decoder Android**: ZXing (Java, lento), **zxing-cpp** (C++, molto più veloce, binding Android
disponibili), Google **ML Kit Barcode Scanning** (on-device, chiuso, via Play Services o bundled),
**BoofCV**. Per streaming ad alta frequenza conviene un decoder nativo (zxing-cpp) senza passare per
il thread UI. `[PROD]`

### 2.2 QR animati con fountain codes

- **TXQR** (Go, MIT, divan) — protocollo di trasferimento via QR animati con fountain codes (LT);
  l'autore ha successivamente riscritto in Dart/Flutter con RaptorQ. `[PROTO]`
  <https://github.com/divan/txqr>
- **Decimen Optical Transfer** (2026, MIT) — QR animati + LT; claim ≈ **128 KB/s phone→phone
  a mano**, ≈ 186 KB/s in condizioni ideali con display 120 Hz ProMotion (iPhone). `[PROTO]`,
  confidenza **LOW** (claim riportato da stampa tecnica, non verificato, hardware Apple).
  Fonti: <https://runtimewire.com/article/decimen-fountain-coded-qr-file-transfer-claude-code>,
  <https://www.remio.ai/post/decimen-streams-qr-codes-at-nearly-190-kb-s-without-a-network>
- Molte app "QR file transfer" minori; spesso sequenziali (senza fountain), quindi bloccate dai frame
  persi: lo stesso articolo su Decimen riporta ~4 KB/s per implementazioni sequenziali 2024 (LOW).

### 2.3 Codici colore ad alta densità

- **libcimbar / cimbar** (C++, MPL-2.0) — "color icon matrix barcode": tile colorati, Reed-Solomon
  per frame, **fountain codes (wirehair)** fra frame, compressione zstd. Claim: **~850 kbit/s
  (~106 KB/s) da monitor di computer a camera di smartphone**. Esiste l'app Android ricevente
  **CameraFileCopy (cfc)** su F-Droid/Play (solo arm64-v8a). `[PROTO]`, confidenza MEDIUM per il
  setup monitor→phone, **UNKNOWN / TO BENCHMARK** per phone→phone.
  <https://github.com/sz3/libcimbar> · <https://github.com/sz3/cfc>
- **COBRA** (MobiCom 2012), **RainBar** (2016), **LightSync** (MobiCom 2013: gestione frame non
  sincronizzati + erasure code lineare tra frame, "più che raddoppia il throughput" rispetto agli
  approcci precedenti) `[SCI]`. Numeri assoluti dipendono dai telefoni dell'epoca: non riportati qui
  come prestazione attesa.
- **PixNet** (MIT, 2010): OFDM 2D LCD→camera, **fino a 12 Mb/s a 10 m** `[SCI]` — con camera
  dedicata e post-processing, *non* smartphone real-time. Confidenza che si applichi a GHOSTLINK: LOW.
  <https://dspace.mit.edu/handle/1721.1/61553>

### 2.4 Standard

IEEE 802.15.7-2018 (Optical Wireless Communications) include PHY per **Optical Camera
Communication** (OCC): PHY IV/V per camere rolling/global shutter e **PHY VI per "2D screen codes"**.
Le modalità RS-FSK fisse sono molto lente (RS-FSK-C8/C16: 90/180 bps). Standard utile come vocabolario,
non come target di prestazione. `[SCI/standard]`

## 3. Modello di throughput realistico (phone → phone)

```text
goodput ≈ N_codes_per_frame × payload_per_code × f_decoded × (1 − overhead_FEC) × (1 − overhead_header)
```

dove `f_decoded = min(f_display/k, f_camera_analysis, f_decoder)` × probabilità di decodifica.

- Un codice deve restare a schermo per **k ≥ 2 refresh** perché la camera (non sincronizzata) ne
  catturi almeno un'esposizione "pulita": a 60 Hz → ≤ 30 codici/s, in pratica 10–20.
- `ImageAnalysis` di CameraX tipicamente fornisce 30 fps (talvolta 60) a 720p–1080p.
- zxing-cpp decodifica un QR v20–v25 in pochi ms su SoC moderni (TO BENCHMARK).

Stima (QR singolo v25-M, ~1 000 B utili, 12–15 codici/s decodificati, 85% successo, 10% overhead):
**≈ 10–13 KB/s (80–100 kbit/s)**. Con tiling 2×2 di QR più piccoli a distanza ravvicinata, o codice
colore tipo cimbar: **30–100 KB/s** è plausibile ma **TO BENCHMARK**. Confidenza: MEDIUM sul primo
range, LOW sul secondo.

Tempo per 1 MB: ~80–100 s con QR singolo; ~10–30 s con codici densi.

## 4. Pipeline di trasferimento file

```text
FILE
 → hash (BLAKE3/SHA-256) = ObjectID
 → (opz.) compressione zstd
 → (opz.) cifratura AEAD (chiave per-file)          ← vedi 09_CRYPTO
 → RaptorQ: K simboli sorgente da T byte → stream infinito di simboli (ESI crescenti/casuali)
 → GhostFrame: [hdr: ObjectID corto, OTI, ESI] [simbolo] [CRC-32C]
 → codice visivo (QR/colore) con RS interno
 → display a f_tx
 ⋮
 camera → decoder → CRC ok? → RaptorQ decoder → quando ricevuti ≈ K(+2) simboli → oggetto
 → verifica hash → decifratura → file
```

### 4.1 Scelta del codice di correzione

| Codice | Ruolo | Pro | Contro |
|---|---|---|---|
| **Reed-Solomon** | Intra-frame (già dentro QR) | MDS, nessun brevetto, ovunque | Blocchi piccoli, costoso su blocchi grandi |
| **LDPC** | Intra-frame per codici custom | Prestazioni vicino Shannon | Complessità, inutile se usiamo QR |
| **LT** (Luby 2002) | Inter-frame, fountain | Semplice, brevetti originali scaduti/vicini alla scadenza (verificare) | Overhead 5–20% su K piccoli |
| **RaptorQ** (RFC 6330) | Inter-frame, fountain | Overhead quasi nullo (K+2 simboli → fallimento ~10⁻⁶), crate Rust `raptorq` veloce | IPR Qualcomm dichiarata in IETF (vedi §4.2) |
| **Wirehair** | Inter-frame, fountain | BSD, O(N), usato da cimbar | Meno standard, stato brevettuale non analizzato |
| **Interleaving** | Contro burst | Banale | Inutile se ogni frame è un simbolo fountain indipendente |

**Raccomandazione**: RS dentro il simbolo visivo (gratuito con QR) + **fountain tra frame**. Con un
fountain, il ricevitore **non deve chiedere frame specifici**: qualunque frame nuovo è utile, che
elimina il bisogno di ACK fini e di sincronizzazione — ideale per un canale unidirezionale.

### 4.2 Nota brevetti (solo informativa)

Qualcomm ha depositato dichiarazioni IPR su RFC 6330 (RaptorQ) presso l'IETF
(<https://datatracker.ietf.org/ipr/1188/> e successive). Le condizioni di licenza dichiarate fanno
riferimento a prodotti broadcast/multicast con standard wireless WAN; l'applicabilità a un'app open
source va **verificata da un legale** prima della distribuzione. Mitigazione architetturale: il
codec fountain dietro un'interfaccia (`ObjectCodec`) sostituibile con LT o Wirehair.

### 4.3 Sincronizzazione frame e robustezza

- **Nessuna sincronizzazione esplicita**: ogni frame è autosufficiente (header + simbolo + CRC).
- **Anti-mixed-frame**: alternare un "bit di parità di frame" visivo (es. colore del bordo) per
  scartare catture a cavallo di due frame; oppure semplicemente fidarsi di CRC.
- **Exposure**: preferire esposizione breve (riduce motion blur e mixing tra frame); su CameraX via
  Camera2 interop (`CaptureRequest.SENSOR_EXPOSURE_TIME`) dove `MANUAL_SENSOR` è supportato.
- **Brightness**: sender con luminosità massima e `FLAG_KEEP_SCREEN_ON`; disabilitare adaptive brightness
  per la finestra (`WindowManager.LayoutParams.screenBrightness = 1f`).
- **Perspective / motion**: con QR la correzione prospettica è nel decoder; per codici custom
  servono finder pattern + omografia (OpenCV o implementazione propria).
- **Rate adaptation**: senza back-channel → schema "ladder" (alterna densità); con back-channel
  (audio o visual inverso) → il ricevitore comunica tasso di decodifica e il sender adatta densità/fps.

### 4.4 Idea: link visivo full-duplex "face-to-face"

Due telefoni posti schermo contro schermo a 10–30 cm: ognuno mostra i propri frame e legge quelli
dell'altro con la **camera frontale**. Si ottiene un canale bidirezionale senza audio. Rischi: messa a
fuoco fissa delle camere frontali (spesso ottimizzata per ~30–60 cm), riflessi, interferenza tra
retroilluminazioni. `[THEORY]` → **TO BENCHMARK** (economico da provare nello spike M1).

## 5. AREA B — Canale invisibile / semi-invisibile

### 5.1 Tecniche

| Tecnica | Principio | Evidenza | Throughput riportato |
|---|---|---|---|
| **HiLight** (MobiSys 2015) | Variazioni di trasparenza (alpha) dei pixel a frequenza sopra la fusione visiva | `[SCI]` | **1.1 kbps** con 84–91% accuratezza; real-time su smartphone |
| **InFrame / InFrame++** (HotNets 2014, MobiSys 2015) | Frame complementari: dato + video si annulla percettivamente a 120 Hz | `[SCI]` | **150–240 kbps @120 FPS su monitor LCD 24"**, fino a 360 kbps con rapporto 1:6 |
| **ChromaCode** (MobiCom 2018) | Modulazione in spazio colore uniforme percettivamente (crominanza) | `[SCI]` | **>700 kbps raw, 120 kbps goodput (BER 0.05)**, flicker impercettibile nello user study |
| **TextureCode** (INFOCOM 2016) | Inserisce dati in regioni con texture | `[SCI]` | variabile |
| **DeepLight** (2021) | Codifica robusta per display reali + decoder neurale | `[SCI]` | basso, alta robustezza |
| Rolling shutter + flicker HF | Il sensore "vede" righe di flicker che l'occhio integra | `[SCI]` | kbps, molto dipendente dal device |

Fonti: <https://dartnets.cs.dartmouth.edu/hilight>, <https://www.cs.purdue.edu/homes/chunyi/pubs/mobisys15-inframe++.pdf>,
<https://www.cs.purdue.edu/homes/chunyi/pubs/mobicom18-zhang.pdf>, <https://arxiv.org/pdf/2105.05092>.

### 5.2 Quanto è realmente invisibile e da cosa dipende

- **Refresh rate**: le tecniche a frame complementari richiedono ≥120 Hz perché la coppia di frame
  (+Δ, −Δ) si fonda a 60 Hz percepiti. A 60 Hz il flicker a 30 Hz è **visibile**. Molti telefoni
  moderni hanno 90–144 Hz, ma il refresh effettivo è **variabile (LTPO/VRR)** e controllato dal sistema
  (`Surface.setFrameRate()` è solo un suggerimento). Confidenza HIGH su questo vincolo.
- **OLED vs LCD**: gli OLED a bassa luminosità usano **PWM dimming** (spesso 240–2 160 Hz) che si
  somma come rumore periodico al canale; gli LCD hanno retroilluminazione più stabile ma risposta dei
  cristalli più lenta (smearing tra frame). Effetto netto: **TO BENCHMARK** per modello.
- **Esposizione camera**: esposizioni lunghe integrano i frame complementari e cancellano il segnale;
  serve esposizione corta (≤ 1/500 s) → richiede luce sufficiente o rumore elevato.
- **Compressione video**: se il contenuto passa per encoder (registrazione/streaming), la modulazione a
  bassa ampiezza viene **distrutta** dalla quantizzazione. Il canale invisibile funziona solo
  "display live → camera", non via video salvato/ricompresso.
- **Distanza**: dipende dall'ampiezza Δ e dalla dimensione dei blocchi; nei paper < 1–2 m.

### 5.3 Valutazione per GHOSTLINK

- **Fattibilità Android**: rendering a frame esatti richiede `SurfaceView`/OpenGL ES/Vulkan con
  `Choreographer`; frame drop del compositore rompono la coppia complementare → robustezza bassa su
  telefoni consumer. `[THEORY→SCI]`
- **Valore**: discrezione visiva (es. una foto "normale" che trasporta un token), non throughput.
- **Raccomandazione**: **non** nell'MVP. Track di ricerca "GhostVeil" dopo il trasporto visuale
  standard. Uso realistico: piccoli payload (identity token, URL di dead-drop, chiavi effimere) a
  10–1 000 bps con FEC pesante. Non va presentato come "invisibile garantito" finché uno user study
  interno non lo misura sui nostri device.

## 6. Scheda sintetica

| Parametro | QR animati | Codice colore denso | Canale invisibile |
|---|---|---|---|
| Fattibilità Android | Alta | Media (decoder custom/NDK) | Bassa–media |
| HW extra | No | No | No (ma ≥120 Hz) |
| API | CameraX `ImageAnalysis`, Compose/`SurfaceView`, zxing-cpp/ML Kit | + OpenCV/NDK | OpenGL ES, `Choreographer`, Camera2 interop |
| Bitrate teorico | ~2.9 KB × 30/s ≈ 700 kbps | Mbps | 100–700 kbps (lab, monitor) |
| Bitrate realistico | 40–250 kbps (5–30 KB/s) | 150–800 kbps (TO BENCHMARK) | 1–100 kbps (TO BENCHMARK) |
| Range | 0.1–0.5 m (1 m con display grandi) | 0.15–0.4 m | < 1 m |
| Latenza | 100–300 ms al primo frame | idem | idem |
| Consumo | Alto: display al 100% + camera + decode (≈ 1–3 W, TO BENCHMARK) | Alto | Alto |
| Rumore | Robusto a rumore acustico/RF, sensibile a riflessi/sole | Più sensibile a colore/WB | Molto sensibile |
| Indoor/outdoor | Indoor ottimo; outdoor ok all'ombra | Indoor | Indoor |
| LOS | Obbligatorio | Obbligatorio | Obbligatorio |
| Root | No | No | No |
| Compatibilità modelli | Alta | Media (colore) | Bassa |
| Normativa | Nessuna | Nessuna | Nessuna (attenzione fotosensibilità/epilessia con flicker visibile) |
| Casi d'uso | File transfer, key exchange, GhostDrop | File grandi | Token discreti, beacon in contenuti |

## 7. Domande aperte (→ 15_BENCHMARK_PLAN)

1. fps reali di `ImageAnalysis` a 720p/1080p su 3+ modelli; latenza per frame del decoder.
2. Densità QR ottimale vs distanza; tiling N×M; L vs M ECC.
3. Phone→phone con cimbar (cfc ricevente + encoder su telefono) come baseline "codici colore".
4. Fattibilità face-to-face con camera frontale.
5. Impatto refresh 60/90/120 Hz del sender.
