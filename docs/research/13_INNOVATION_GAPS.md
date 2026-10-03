# 13 — Innovation Gaps

Obiettivo: separare ciò che **esiste già** da ciò che sarebbe **realmente originale** e realizzabile.

## 1. Cosa NON è originale (da dichiarare onestamente)

| Idea | Esiste già in |
|---|---|
| File via QR animati con fountain | TXQR, Decimen, cimbar (codici colore) |
| Data-over-sound | ggwave, quiet, Chirp, Google Nearby (dismessi) |
| Messaggi E2E su carta via QR | LXMF paper messages (Sideband) |
| LoRa da smartphone via dongle | Meshtastic, RNode + Sideband/Columba |
| Stack crittografico multi-interfaccia su mezzi lenti | Reticulum |
| DTN / store-carry-forward | Ricerca 2000–2010, BPv7, Serval, Briar (sneakernet) |
| Screen-camera invisibile | HiLight, InFrame++, ChromaCode (paper) |

## 2. Combinazioni potenzialmente originali

Valutazione: **Novità** (rispetto a quanto trovato), **Fattibilità** (con hardware consumer +
dongle), **Valore** (per l'utente).

| # | Idea | Novità | Fattibilità | Valore | Note |
|---|---|---|---|---|---|
| 1 | **Multipath Fountain Transfer cross-medium**: simboli RaptorQ dello stesso oggetto inviati su visual + audio + LoRa + relay DTN, ricombinati dal ricevitore | **Alta** (non trovato in prodotti) | Alta (tecnica nota, integrazione nuova) | Alta | ESI casuali → relay non coordinati; cuore di GHOSTLINK |
| 2 | **Physical Store-and-Forward unificato**: lo stesso bundle cifrato attraversa phone→phone visual, WAV, carta, LoRa, chiavetta, senza cambiare formato | Media-alta | Alta | Alta | Reticulum+LXMF ci va vicino; la differenza è l'oggetto fountain e il formato unico "medium-agnostic" |
| 3 | **Transport Capability Negotiation su canale fisico**: due telefoni sconosciuti scoprono via audio/visual quali canali condividono (camera frontale? near-ultrasound? dongle LoRa?) e scelgono il migliore | Media | Alta | Media-alta | Discovery "out-of-band" senza radio standard |
| 4 | **Visual full-duplex face-to-face** (screen↔front camera su entrambi i lati) con rate adaptation chiusa | Media | Media (TO BENCHMARK) | Media | Elimina il bisogno di audio per ACK |
| 5 | **Asymmetric optical beacon**: LED su dongle (TX kHz) → qualunque telefono non modificato riceve via rolling shutter | Bassa-media (paper esistono) | Alta | Media | Dead-drop "attivi" e nodi infrastrutturali a basso costo |
| 6 | **Dongle come "regulatory enforcer"** con contabilità duty cycle e profili, l'app non può violare | Bassa (RNode/Meshtastic lo fanno in parte) | Alta | Alta | Progetto pulito e auditabile |
| 7 | **Adaptive Transport Engine** con scoring per classe di traffico e misure online (BER, goodput, costo energetico) | Media | Alta | Alta | Esiste in forma semplice altrove (multipath TCP, Reticulum sceglie path); qui su PHY eterogenei |
| 8 | **LoRa discovery → GFSK burst** sullo stesso SX1262 | Media | Alta | Media | Più dati nello stesso budget di duty cycle |
| 9 | **GhostDrop video/stampa multi-pagina fountain**: qualunque sottoinsieme sufficiente di pagine/frame ricostruisce l'oggetto | Media | Alta | Media | Generalizza i paper messages a oggetti grandi |
| 10 | **Canale invisibile "GhostVeil"** su display 120 Hz per token discreti | Bassa (paper) | Bassa-media | Bassa | Ricerca, non prodotto |
| 11 | **Audio "line mode" universale**: lo stesso AudioTransport su aria, cavo, walkie-talkie, WAV | Bassa | Alta | Media | Utile in emergenza |

## 3. La tesi originale di GHOSTLINK

> Un **Physical Communication Layer universale per Android** in cui ogni trasduttore (display,
> camera, speaker, microfono, torcia, dongle) è un'interfaccia intercambiabile di un unico stack con
> identità crittografiche offline, oggetti content-addressed codificati fountain, e routing
> delay-tolerant — così che **un messaggio o un file possa attraversare mezzi fisici diversi, in
> tempi diversi, tramite persone diverse, e arrivare a destinazione ricostruito da frammenti
> provenienti da qualunque combinazione di canali.**

Il valore non sta nel singolo PHY (tutti esistono) ma nella **composizione** (idee 1, 2, 3, 7).
Tecnicamente è realizzabile con componenti maturi. Il rischio principale non è la fattibilità ma
l'**esperienza utente** (allineare telefoni, tempi lunghi) e la **robustezza cross-device** (vedi 14).

## 4. Relazione con Reticulum: competere o integrare?

| Opzione | Pro | Contro |
|---|---|---|
| A. Stack proprio (raccomandato per il core) | Controllo su header compatti, fountain objects, Rust mobile-first, licenza libera | Duplicazione di lavoro già fatto |
| B. Costruire su Reticulum | Ecosistema esistente (Sideband, RNode, LXMF) | Python su Android, MTU 500 fisso, licenza con clausole, modello DTN diverso |
| C. **A + bridge RNS** (raccomandato a medio termine) | Interop con la comunità esistente; GHOSTLINK porta visual/audio come "nuove interfacce" | Lavoro di mapping indirizzi/crypto |

Raccomandazione: **A ora, C come milestone opzionale** dopo il secondo MVP.
