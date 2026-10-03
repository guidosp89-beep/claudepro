# 08 — DTN, Routing, Multipath e trasferimento file

Copre AREA N (store-carry-forward), O (multi-transport routing), 19 (multipath), 26–27 (GhostDrop,
dead drop), 28–30 (file DTN, resume, content addressing).

## 1. Stato dell'arte DTN

| Riferimento | Idea | Lezione per GHOSTLINK |
|---|---|---|
| **Epidemic routing** (Vahdat & Becker, 2000) | Ogni incontro: scambio dei "summary vector" e copia di tutto ciò che manca | Massima consegna, costo di storage/banda enorme |
| **Spray-and-Wait** (Spyropoulos et al., 2005) | L copie iniziali; *binary spray* (ne cedi metà); poi solo consegna diretta | Ottimo compromesso, semplice, nessuna conoscenza della topologia → **default v1** |
| **PRoPHET** (RFC 6693) | Probabilità di consegna basate sulla storia degli incontri | Utile quando le persone hanno pattern regolari; v2 |
| **MaxProp, RAPID** | Priorità/costi per buffer limitati | Politiche di drop/priorità |
| **Bundle Protocol v7** (RFC 9171) + **BPSec** (RFC 9172) | Bundle CBOR, EID, custody, lifetime | Vocabolario e semantiche (lifetime, fragment, status report) da cui ispirarsi; header troppo verboso per LoRa/QR piccoli. Possibile gateway BPv7 futuro |
| **Reticulum** | Indirizzamento per hash di chiave pubblica, announce, path discovery, multi-interfaccia, MTU 500, ≥ 5 bps | Riferimento architetturale più vicino (vedi 12) |
| **LXMF propagation nodes** | Store-and-forward tramite nodi dedicati | Modello "postino" fisso, complementare al DTN epidemico tra telefoni |
| **Briar (Bramble)** | BTP: trasporto sicuro su stream "best effort" anche ritardati/riordinati; sync via chiavetta | DTN tra **contatti** fidati; nessun relay sconosciuto |
| **Serval Rhizome** | Distribuzione file store-and-forward su mesh Wi-Fi | Progetto inattivo (ultima release 0.93, 2016) — idee valide, stack morto |

## 2. Modello GHOSTLINK

```text
Alice ──(VISUAL)──> Bob ──(AUDIO)──> Charlie ──(LORA)──> David ──(QR stampato)──> Destinatario
         ↑ ogni nodo conserva bundle cifrati, li inoltra quando incontra altri nodi
```

### 2.1 Unità di trasporto

- **GhostPacket**: unità end-to-end (cifrata, indirizzata a un destinatario o a un gruppo).
- **Object**: contenuto grande (file), identificato da `ObjectID = BLAKE3-256(ciphertext)`, spezzato
  in **simboli fountain**; i simboli viaggiano in GhostPacket di tipo `OBJ_SYMBOL`.
- **Manifest**: GhostPacket piccolo che descrive l'oggetto (ObjectID, dimensione, OTI RaptorQ, chiave di
  decifratura cifrata per il destinatario, metadati). Il manifest è E2E; i simboli no (sono già
  ciphertext) → **i relay possono trasportare e combinare simboli senza poterli leggere**.

### 2.2 Routing v1: "Spray-and-Wait + anti-entropy per incontro"

All'incontro (qualunque transport bidirezionale):

1. Scambio **capability** (transport disponibili, budget batteria/storage).
2. Scambio **summary**: Bloom filter (o IBLT, per set-reconciliation efficiente) dei `PacketID` in
   possesso, filtrati per non scaduti.
3. Ciascuno invia ciò che l'altro non ha, **in ordine di priorità**:
   1. pacchetti destinati al peer (consegna diretta);
   2. pacchetti con copie residue `L > 1` (spray: cede `⌊L/2⌋`);
   3. simboli di oggetti richiesti dal peer;
   4. (opz.) epidemico per traffico di emergenza a bassa dimensione.
4. Su transport unidirezionali (QR stampato, WAV, beacon) si fa **broadcast cieco** della coda.

Politiche buffer: quota per mittente/destinatario (anti-DoS), drop prima dei pacchetti scaduti e di
quelli con copie esaurite; priorità per tipo (ACK di consegna > messaggi > simboli di file).

### 2.3 Consegna e conferme

- ACK end-to-end opzionale (piccolo pacchetto firmato/autenticato dal destinatario che "cancella" le
  copie in rete: *anti-packet* / vaccine). Riduce lo storage sprecato.
- Lifetime assoluto (expiry) + `hop_limit`.

### 2.4 Indirizzamento e privacy (dettagli in 09)

Due modalità:

| Modalità | dest nel header | Instradabile in modo mirato | Linkability |
|---|---|---|---|
| **Pubblica** | `dest_hash = H(identity_pub)[:16]` | Sì (PRoPHET, path discovery) | Alta |
| **Blinded** | `dest_tag = HMAC(K_pair, epoch)[:8]` | Solo epidemico/spray | Bassa |

Poiché lo spray-and-wait **non ha bisogno di sapere chi è il destinatario**, la modalità blinded è
gratuita in v1: il destinatario precalcola i tag dei propri contatti per l'epoca corrente.

## 3. Multi-transport routing (AREA O)

Il routing opera su **interfacce astratte** (`TransportInterface`, vedi
`../architecture/ARCHITECTURE.md`). Un pacchetto non sa su quale mezzo viaggerà; ogni hop sceglie
il transport con l'**Adaptive Transport Engine**. Il `TRANSPORT_HINT` *non* sta nel pacchetto
end-to-end (sarebbe metadato inutile e linkabile): sta nei metadati locali della coda (es. "preferisci
canale ad alta banda: è un simbolo di file").

## 4. Multipath fountain (sez. 19 e 28)

```text
ObjectID ─ RaptorQ ─┬─ simboli ESI ∈ S_visual ─> VisualTransport
                    ├─ simboli ESI ∈ S_audio  ─> AudioTransport
                    └─ simboli ESI ∈ S_lora   ─> LoRaTransport
receiver: decodifica appena ha ≈ K(+ε) simboli DISTINTI, da qualunque mezzo / relay
```

- RaptorQ (RFC 6330): per blocco sorgente fino a 56 403 simboli sorgente; ESI su 24 bit → oltre 16 M
  simboli di riparazione possibili per blocco; SBN 8 bit (256 blocchi).
- **Senza coordinamento**: ogni sorgente/relay che *genera* simboli sceglie ESI casuali nello spazio
  di riparazione (24 bit) → probabilità di duplicati trascurabile. Chi possiede l'oggetto completo
  (mittente originale o un relay che l'ha già decodificato) può generare nuovi simboli; un relay che
  ha solo simboli parziali li inoltra così come sono.
- Il destinatario può ricevere 20% da Alice (visual), 30% da Bob (audio), 50% da Charlie (LoRa nel
  tempo) e ricostruire. Questo è il **cuore originale di GHOSTLINK** (vedi 13).

## 5. Resume e bitmap (sez. 29)

Con fountain non serve una bitmap dei chunk: lo stato è "ho n simboli distinti dell'oggetto X". Il
messaggio di resume è:

```text
OBJ_STATUS { object_id, have_count, need_estimate = K + 2 − have_count, [bloom degli ESI posseduti] }
```

Per oggetti molto grandi si usano **più blocchi sorgente** (SBN): bitmap *per blocco* (256 bit max) →
"blocchi completati" + `have_count` del blocco corrente. Lo stato è persistito in Room: nessun
trasferimento riparte da zero, nemmeno dopo riavvio o cambio di mezzo.

## 6. Content addressing (sez. 30)

- `ObjectID = BLAKE3(ciphertext)` (o SHA-256 per interoperabilità): chunk/simboli da peer diversi si
  combinano perché si riferiscono allo stesso identificatore.
- Hash del **ciphertext**, non del plaintext: evita di rivelare quale file pubblico noto si sta
  scambiando (attacco di conferma del file).
- Per file pubblici da distribuire a molti (mappe, manuali d'emergenza) si può usare un **object
  pubblico** (non cifrato, firmato dall'autore): stesso meccanismo, ObjectID = hash del plaintext.
- Da IPFS/BitTorrent prendiamo: indirizzamento per contenuto, verifica per hash, scambio di "have";
  **non** prendiamo: DHT, Merkle-DAG generico, tracker → inutili offline e costosi.
  Integrità a blocchi: per oggetti multi-blocco, hash per blocco nel manifest (albero Merkle piatto).

## 7. GhostDrop e Dead Drop digitale (sez. 26–27)

Un GhostPacket o un oggetto codificato fountain può essere "congelato" su un supporto fisico:

| Supporto | Codifica | Capacità indicativa | Note |
|---|---|---|---|
| Foglio A4 stampato | griglia di QR/Data Matrix | 10–30 KB a pagina (TO BENCHMARK) | Durevole, fotografabile |
| Poster | QR grandi | 1–3 KB | Leggibile da metri |
| Video MP4/GIF | sequenza di frame fountain | MB | La compressione video richiede codici robusti (QR, non colore fine) |
| File WAV/OGG | modem audio | KB | Trasportabile via qualunque canale audio |
| microSD / USB | file `.ghost` (bundle binari) | GB | Il più capiente; Briar fa lo stesso con "removable drive" |
| Display/kiosk | loop visuale | continuo | Beacon/dead drop "attivo" |

- Il bundle su supporto deve essere **autoportante**: header + manifest + simboli sufficienti
  (K + margine), così chi lo trova e lo legge senza back-channel può ricostruire.
- Precedente: **LXMF "paper messages"** (messaggi cifrati come QR o URI `lxm://`, Sideband) → l'idea di
  messaggi E2E su carta **non è nuova**; GHOSTLINK la generalizza a oggetti grandi multi-pagina/video e
  all'integrazione con il DTN (un drop è "un nodo che non si muove").
- Privacy: il drop contiene solo ciphertext + `dest_tag` blinded; senza la chiave è rumore.
