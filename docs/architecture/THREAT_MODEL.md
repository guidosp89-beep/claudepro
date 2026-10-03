# Threat Model (v0)

## 1. Obiettivi di sicurezza

| Proprietà | Obiettivo v1 |
|---|---|
| Confidenzialità del contenuto | ✔ E2E (Noise X / IK, AEAD) |
| Integrità e autenticità | ✔ AEAD autenticato; firme per oggetti pubblici/announce |
| Forward secrecy | ✔ sessioni interattive; ✘ DTN v1 (prekey in v2) |
| Minimizzazione metadati | Parziale: mittente cifrato, tag blinded, padding |
| Anonimato forte | ✘ fuori scope |
| Disponibilità | Best effort, resistenza DoS di base |
| Resistenza ad avversari statali | ✘ fuori scope v1 (ma nessuna scorciatoia crittografica) |

## 2. Asset

Chiavi d'identità, contenuti dei messaggi/file, grafo dei contatti, metadati di incontro (chi incontra
chi, quando, dove), storage dei relay, budget energetico/duty cycle, integrità del firmware del dongle.

## 3. Avversari

| A | Capacità |
|---|---|
| A1 Osservatore passivo locale | Registra schermo (camera terza), audio, radio LoRa nelle vicinanze |
| A2 Attaccante attivo locale | Inietta frame (QR falsi, audio, LoRa), replay, jamming |
| A3 Relay malevolo | Partecipa al DTN: legge header, scarta, duplica, ritarda, inonda |
| A4 Relay compromesso | Come A3 + accesso allo storage di un utente legittimo |
| A5 Furto/sequestro del telefono | Accesso fisico, eventualmente sbloccato |
| A6 Dongle/firmware malevolo | Vede tutti i frame, può iniettare, può violare limiti radio |
| A7 Collezionista di metadati su larga scala | Rete di ricevitori LoRa/telecamere |

## 4. Minacce e contromisure

| Minaccia | Avversario | Contromisura | Residuo |
|---|---|---|---|
| **Eavesdropping visivo/acustico/radio** | A1 | Tutto è ciphertext: la "fisica" non è un controllo di sicurezza | Metadati di traffico |
| **Replay** | A2, A3 | `packet_id` derivato dal contenuto + cache dedup fino a expiry; sessioni Noise con contatori | Replay dopo expiry+cache: rifiutato dall'expiry |
| **Spoofing / impersonation** | A2 | Identità = chiavi; Noise X autentica il mittente; verifica QR/SAS al primo contatto | TOFU per contatti non verificati |
| **Packet injection** | A2, A3 | CRC (errori), AEAD (falsi), firma (announce); tag blinded riconosciuti solo dal destinatario | Costo CPU di tentativi di decifratura → rate limiting |
| **Message modification** | A3 | AEAD con header come AD; `packet_id` hash del contenuto | — |
| **Falsi ACK (cancellazione in rete)** | A3 | ACK = preimmagine di `ack_commit`: solo il destinatario conosce `ack_secret` | — |
| **Compromised relay** | A4 | Il relay ha solo ciphertext; storage cifrato a riposo | Metadati dei pacchetti in transito |
| **Malicious relay (drop/black hole)** | A3 | Spray su più relay, multipath, ritrasmissione da mittente fino ad ACK | Ritardi |
| **Metadata collection** | A1, A7 | Mittente cifrato, tag blinded rotanti, nessun ID di device nel link layer, padding, announce opzionali | Correlazione temporale/fisica degli incontri |
| **DoS: flooding/storage exhaustion** | A2, A3 | Quote per tag/sorgente di link, priorità ai contatti verificati, expiry massimo, PoW leggero opzionale per pacchetti da sconosciuti, limite di simboli per oggetto non richiesto | Jamming fisico: non mitigabile via software |
| **Jamming** | A2 | Diversità di mezzi (visual/audio/LoRa); LoRa CSS robusto | Locale |
| **Parser exploit** | A2 | Parser in Rust, nessun `unsafe` nel parsing, fuzzing continuo, limiti di dimensione | — |
| **Furto del telefono** | A5 | Chiavi cifrate con Keystore (+ autenticazione utente opz.), DB cifrato, cancellazione rapida | Telefono sbloccato = compromesso |
| **Dongle malevolo** | A6 | Il dongle non ha chiavi; frame E2E; verifica firma firmware (v2); profili normativi verificati anche dall'app (sanity check) | Può violare limiti radio o fare DoS |
| **Downgrade di versione** | A2 | Suite fissata dalla versione; nessuna negoziazione per pacchetto; rifiuto versioni deprecate | — |
| **QR/ottico malevolo (es. URL)** | A2 | I frame GHOSTLINK non sono mai interpretati come URL/comandi; contenuti attivi non eseguiti | — |

## 5. Rischi di abuso e posizionamento

GHOSTLINK è progettato per **privacy, comunicazione offline, resilienza in emergenza e sperimentazione
tecnica**. Nessuna funzione è progettata per occultare attività illecite (es. niente "modalità
invisibile" come feature di prodotto, niente evasione di controlli normativi radio, niente
aggiramento di limiti di potenza/duty cycle). Il canale invisibile resta un tema di ricerca documentato
apertamente.
