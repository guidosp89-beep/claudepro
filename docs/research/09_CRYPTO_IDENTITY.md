# 09 — Crittografia, Identità, Trust, Privacy

Copre sezioni 21–25. Principio: **nessun algoritmo inventato**; solo primitive e framework maturi.
Threat model completo in `../architecture/THREAT_MODEL.md`.

## 1. Identità locale

```text
Identity      = { Ed25519 signing keypair, X25519 DH keypair }   (generate on-device, nessun account)
GhostID       = BLAKE2b-256( "GL-ID-v0" || ed25519_pub || x25519_pub )[:16]   (128 bit)
Fingerprint   = GhostID completo in base32 a gruppi + rappresentazione a parole (wordlist) per l'umano
```

- **Perché due chiavi** e non conversione Ed25519→X25519: la conversione (birational map) è usata in
  pratica (libsodium la offre) ma l'uso della stessa chiave per firma e DH è sconsigliato in assenza di
  analisi specifica; due chiavi separate costano 32 byte in più nella "carta d'identità" e nulla altrove.
- **128 bit** per l'identificatore in rete: stessa scelta di Reticulum (hash troncati a 128 bit);
  sufficienti contro collisioni casuali, **non** contro attacchi di seconda preimmagine mirati su
  scala nazionale → per la verifica umana (fingerprint/SAS) si usa l'hash completo.
- Primitive alternative valutate: P-256 (supportata dal Keystore hardware ovunque, ma meno comoda per
  Noise e più lunga); **Curve25519 è lo standard de facto** (Signal, WireGuard, Noise, Reticulum).

### 1.1 Custodia delle chiavi su Android

- Android Keystore garantisce chiavi hardware-backed (TEE/StrongBox) soprattutto per **AES e EC P-256**.
  Il supporto Ed25519/X25519 nel Keystore esiste nelle versioni recenti ma **non è uniforme né sempre
  hardware-backed**: da verificare per device (UNKNOWN / TO BENCHMARK).
- Schema raccomandato: chiavi Curve25519 generate in software (libreria Rust/libsodium), **cifrate a
  riposo** con una chiave AES-256-GCM **non esportabile** nel Keystore (opz. con autenticazione utente).
- Backup: esportazione identità cifrata con passphrase (Argon2id) → QR/file, solo su richiesta.

## 2. Cifratura

| Scelta | Motivo |
|---|---|
| **AEAD ChaCha20-Poly1305** (default) | Veloce in software anche su MCU/CPU senza AES-NI; tempo costante; usata da Noise/WireGuard |
| AES-256-GCM (alternativa) | Accelerata su ARMv8 Crypto Extensions; nonce 96 bit, attenzione al riuso |
| XChaCha20-Poly1305 | Nonce 192 bit casuali sicuri → utile per oggetti/file cifrati "una tantum" |
| Hash | BLAKE2s/BLAKE2b (Noise), BLAKE3 per content addressing, SHA-256 dove serve interoperabilità |
| KDF | HKDF (in Noise), Argon2id per passphrase |

### 2.1 Noise Protocol Framework

Noise fornisce handshake ben analizzati, combinabili, con implementazioni mature (Rust `snow`,
C `noise-c`, Java `noise-java`). Pattern rilevanti:

| Pattern | Messaggi | Uso GHOSTLINK | Proprietà |
|---|---|---|---|
| **XX** | 3 | Primo contatto interattivo senza chiavi note | Mutua autenticazione, FS, identità nascoste a osservatori passivi |
| **IK** | 2 | Sessione interattiva con contatto noto (LoRa, USB, visual bidirezionale) | 0-RTT-ish, FS dopo il 2° messaggio |
| **KK** | 2 | Entrambi si conoscono | FS |
| **X** (one-way) | 1 | **Messaggi DTN/store-and-forward**, GhostDrop | Mittente autenticato, cifrato per il destinatario; **niente FS rispetto alla chiave statica del destinatario** |
| **N** (one-way) | 1 | Messaggio anonimo verso destinatario noto | Nessuna autenticazione del mittente |

Suite raccomandata: `Noise_X_25519_ChaChaPoly_BLAKE2s` per DTN, `Noise_IK_25519_ChaChaPoly_BLAKE2s`
/ `Noise_XX_…` per sessioni interattive.

### 2.2 Forward secrecy in un contesto DTN

Nei messaggi one-way (DTN) la FS piena richiede **prekey** del destinatario (come X3DH/PQXDH di
Signal). Proposta in due fasi:
- **v1**: Noise X (chiave statica del destinatario). Documentare chiaramente la mancanza di FS.
- **v2**: ad ogni incontro/scambio QR il destinatario consegna un lotto di **prekey X25519 firmate**
  monouso o a rotazione settimanale; il mittente usa la prekey (pattern Noise "X" verso la prekey +
  firma di binding). FS a granularità di prekey. Non inventare: seguire la struttura X3DH documentata.
- Post-quantum (ML-KEM ibrido) → fuori scope v1; annotato nel risk register (overhead di 1–1.5 KB
  incompatibile con LoRa).

### 2.3 Overhead crittografico (importante per LoRa/QR piccoli)

| Modalità | Overhead per messaggio |
|---|---|
| Noise X (e + s cifrata + tag) | 32 + (32+16) + 16 = **96 B** |
| Noise N | 32 + 16 = 48 B |
| Sessione stabilita (transport message) | **16 B** tag (+ nonce implicito/contatore) |
| Firma Ed25519 | 64 B |

Su LoRa (≤ 255 B) un messaggio Noise X lascia ~110–120 B utili dopo l'header → accettabile per
messaggi brevi; per traffico ripetuto tra due nodi conviene una sessione IK (16 B/messaggio).

## 3. Offline key exchange (sez. 23)

```text
1. Alice mostra "Identity Card" QR:   { ver, ed25519_pub, x25519_pub, name?, prekeys?, sig }
2. Bob scansiona → Bob mostra la propria card + commit (QR o visual burst)
3. Entrambi eseguono Noise XX/KK sul canale disponibile (visual bidirezionale o audio)
4. SAS a 6 cifre / 4 parole derivato dal transcript hash del handshake → confronto umano a voce
5. Contatto marcato "verificato (in persona)"
```

Canali utilizzabili: QR (default), visual burst, audio (card ~100–150 B → 10–20 s con MFSK lento: ok
come fallback), USB, codice umano (fingerprint base32 dettato: ultimo fallback).

## 4. Trust / autenticazione (sez. 24)

| Meccanismo | Uso |
|---|---|
| **Fingerprint** (hash completo) | Verifica manuale, confronto stampato |
| **SAS** (Short Authentication String) | Dopo handshake interattivo, contro MITM sul canale (es. qualcuno mostra un QR diverso) |
| **Scansione QR reciproca** | Verifica forte "in persona" (modello Signal safety number) |
| **TOFU** | Contatti ricevuti via DTN/annunci: accettati ma marcati "non verificati" |
| **Pre-shared identities** | Gruppi/organizzazioni (es. squadra di protezione civile) con lista firmata distribuita offline |
| Introduzioni firmate | "Alice garantisce per Charlie" → web-of-trust minimale, opzionale |

## 5. Privacy e metadati (sez. 25)

| Rischio | Contromisura |
|---|---|
| Header in chiaro rivela mittente | **Mittente solo dentro il payload cifrato** (Noise X cifra `s`) |
| Destinatario linkabile | `dest_tag` blinded per epoca (HMAC con segreto di coppia), vedi 08 §2.4 |
| PacketID linkabile tra hop | PacketID casuale (non derivato da identità); stesso ID end-to-end è necessario per deduplica — accettato |
| Session ID persistenti | ID di sessione effimeri, rinnovati per sessione |
| Replay | Finestra di dedup per PacketID + expiry; nelle sessioni Noise contatore/nonce monotono |
| Timing/size | Padding a classi di dimensione (64/128/256/…); ritardi casuali opzionali (costo latenza) |
| Beacon/announce | Announce pubblici solo se l'utente vuole essere raggiungibile; altrimenti silenzio radio |
| Fingerprinting del transport | Parametri PHY standardizzati, nessun ID di device nel link layer |

Limite dichiarato: un avversario che osserva fisicamente gli incontri (chi incontra chi) ottiene
metadati che nessuna crittografia nasconde. GHOSTLINK non mira all'anonimato forte; mira a
**confidenzialità, integrità, autenticità e minimizzazione dei metadati**.

## 6. Cosa NON fare

- Non inventare cifrari, modalità, KDF o schemi di firma.
- Non usare nonce casuali a 96 bit con chiavi di lunga durata su volumi elevati (preferire contatori o XChaCha).
- Non firmare *e* cifrare con la stessa chiave.
- Non mettere la cifratura nel dongle: il dongle vede solo ciphertext (minimo trust nell'hardware).
- Non esportare chiavi private in chiaro su QR/log/backup cloud.
