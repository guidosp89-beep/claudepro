# GhostPacket v0 — Draft di formato

Stato: **draft per discussione**, non congelato. Diventerà normativo in M2 con test vector.

## 1. Analisi del formato proposto inizialmente

| Campo proposto | Valutazione | Decisione v0 |
|---|---|---|
| MAGIC | Inutile dentro un frame già delimitato (QR, pacchetto LoRa, COBS su USB). Serve solo nei **container file** (GhostDrop `.ghost`) | Solo nel container file (`"GHST"`) |
| VERSION | Necessario | 4 bit |
| PACKET_ID | Necessario per dedup, ma **derivabile**: `H(campi immutabili ‖ body)[:16]` | **Non trasmesso**: calcolato da chi riceve; non falsificabile, niente riuso |
| SESSION_ID | Concetto di link (sessione Noise interattiva), non end-to-end | Spostato nel link layer |
| SOURCE_ID | In chiaro = metadato forte | **Solo dentro il payload cifrato** (Noise X cifra la chiave statica del mittente) |
| DESTINATION_ID | Necessario per routing mirato; linkabile | Modalità: hash pubblico 16 B / **tag blinded 8 B** / broadcast 0 B |
| CREATION_TIME | Metadato; utile all'utente | Dentro il payload cifrato |
| TTL | Necessario | `expiry` assoluto (minuti, 32 bit) |
| HOP_LIMIT | Utile e diverso da TTL | 1 B, **mutabile** (escluso dall'AD) |
| TRANSPORT_HINT | Metadato inutile end-to-end | **Rimosso**: metadato locale della coda |
| PAYLOAD_TYPE | Necessario | 4 bit (`ptype`) |
| PAYLOAD_LENGTH | Ridondante (lunghezza del frame) | Rimosso |
| CHUNK_INDEX / TOTAL_CHUNKS | Appartiene a frammentazione di link o al codec fountain | Link: frammentazione GhostFrame; oggetti: OTI + ESI |
| FEC_PARAMETERS | Per oggetto, non per pacchetto | Nel **manifest** (OTI RaptorQ 12 B) |
| AUTH_TAG | Necessario | Dentro il messaggio Noise (tag Poly1305) |
| SIGNATURE | Ridondante con AEAD autenticato da Noise X; costa 64 B | Solo per `ANNOUNCE` e oggetti pubblici |
| CRC | Integrità di link, non E2E | Solo nel **GhostFrame** |

## 2. Due livelli

```text
GhostFrame   (per hop, per transport)   = link header + [GhostPacket | frammento | controllo link] + CRC-32C
GhostPacket  (end-to-end, immutabile*)  = header compatto + body (sigillato Noise o simboli d'oggetto)
                                          * tranne hop_limit e spray_copies
```

## 3. GhostFrame (link layer)

```text
offset  size   campo
0       1      lh: ver(2) | ftype(3) | flags(3)
                  ftype: 0=PACKET 1=FRAGMENT 2=LINK_CTRL 3..7 riservati
                  flags: bit0 has_link_seq · bit1 ack_request · bit2 riservato
1       var    link_seq (LEB128)                    se has_link_seq
        var    [FRAGMENT] frag_ref(2) frag_idx(var) frag_cnt(var)
        N      payload
N+..    4      CRC-32C (Castagnoli) su tutto il frame precedente
```

- Il CRC è sempre presente (anche dove il PHY ha già CRC): 4 B, semplifica il codice e protegge da
  decodifiche errate che il PHY non vede (es. QR "valido" ma di un'altra app).
- Su transport con preamble/sync propri (audio, LoRa, QR) non serve MAGIC; su USB il framing è COBS.
- `LINK_CTRL` (CBOR): `HELLO`, `CAPS` (transport condivisi), `OBJ_STATUS`, `RATE_HINT`, `BYE`,
  messaggi di handshake Noise per sessioni interattive.

## 4. GhostPacket v0 — header comune (6 B + destinazione)

```text
offset  size   campo                         AD?
0       1      ver(4)=0 | ptype(4)            sì
1       1      flags: addr_mode(2) | prio(2) | has_ack_commit(1) | rsv(3)   sì
2       1      hop_limit                      NO (mutabile)
3       1      spray_copies                   NO (mutabile)
4       4      expiry (u32, minuti da 2020-01-01T00:00Z)   sì
8       0/8/16 dest: 0=broadcast, 1=blinded tag 8 B, 2=public hash 16 B, 3=group tag 8 B   sì
..      0/16   ack_commit = BLAKE2s(ack_secret)[:16]  se has_ack_commit   sì
..      N      body (dipende da ptype)
```

`packet_id = BLAKE2s-128(ver‖ptype‖flags‖expiry‖dest‖ack_commit‖body)` — calcolato, mai trasmesso.

### 4.1 `ptype`

| Valore | Nome | Body |
|---|---|---|
| 0 | `MSG` | Messaggio Noise X sigillato → payload CBOR (testo, coordinate, telemetria, piccolo file inline) |
| 1 | `MANIFEST` | Noise X sigillato → `{object_id, oti, file_key, name, mime, size, block_hashes?}` |
| 2 | `OBJ_SYMBOL` | `object_id(16) ‖ n(1) ‖ n×(sbn(1) esi(3)) ‖ n×symbol(T)` — nessuna cifratura aggiuntiva (già ciphertext) |
| 3 | `ACK` (anti-packet) | `ack_secret(16)` → i relay verificano `BLAKE2s(ack_secret)[:16] == ack_commit` e cancellano le copie |
| 4 | `ANNOUNCE` | Identity card firmata Ed25519 (+ prekey opzionali) |
| 5 | `PUBLIC_MANIFEST` | Manifest in chiaro firmato dall'autore (oggetti pubblici) |
| 6–15 | riservati | |

### 4.2 Body `MSG`/`MANIFEST` (Noise X)

```text
e (32) ‖ ENC(s_sender) (32+16) ‖ ENC(payload) (len+16)
prologue / AD Noise = "GL0" ‖ campi AD dell'header
payload (CBOR): { t: created_at, ct: content_type, b: body, r?: reply_to, a?: ack_secret }
```

Overhead: header 6 + 8 (tag blinded) + 96 (Noise X) = **110 B**; con ack_commit +16 → 126 B.
Su LoRa (255 B) restano ~129–145 B di payload: sufficiente per testo breve, coordinate, chiavi.

### 4.3 Simboli e MTU eterogenei

RaptorQ richiede la **stessa dimensione di simbolo T** per tutto l'oggetto. Con multipath su transport
con MTU diversi:

- scegliere `T` ≤ MTU utile del transport più piccolo previsto (es. T = 192 B se LoRa è nel set);
- sui transport con MTU grande **impacchettare n simboli per pacchetto** (`n` fino a 255);
- il visual con frame da ~1 000 B trasporta ~5 simboli da 192 B (overhead header ~6 + 16 + 1 + 4n).

Esempio overhead visual (QR v25-M ≈ 997 B utili): frame 1+4(CRC) + header 6 + object_id 16 + n 1 +
5×4 + 5×192 = 1 008 B → ridurre a 4 simboli (≈ 808 B) o T = 180 B. Il valore esatto si fissa dopo M1.

## 5. Container GhostDrop (`.ghost`, file/video/carta)

```text
"GHST" ‖ ver(1) ‖ flags(1) ‖ count(varint) ‖ count × (len(varint) ‖ GhostFrame)
```
Per QR stampati ogni QR contiene un GhostFrame; la pagina ha un indice visivo leggibile dall'uomo.

## 6. Codifiche

- Header: binario fisso (niente CBOR: ogni byte conta su LoRa/QR).
- Interi variabili: LEB128.
- Payload applicativi e messaggi di controllo: **CBOR deterministico** (RFC 8949 §4.2).
- Endianness: little-endian per i campi fissi multi-byte.

## 7. Questioni aperte

1. 8 B di tag blinded bastano? Collisioni casuali tra ~10⁶ tag attivi: p ≈ n²/2⁶⁵ ≈ 3·10⁻⁸ → ok.
2. `expiry` in minuti rivela approssimativamente l'ora di creazione (se TTL standard): arrotondare a ore
   con jitter.
3. FS per DTN (prekey, v2) cambierà il body di `MSG`: riservato `ptype` 6.
4. Crypto agility: la `ver` di header implica la suite; nessuna negoziazione per pacchetto.
