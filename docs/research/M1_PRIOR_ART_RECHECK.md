# M1 — Prior art recheck (dopo lo spike)

Scopo (brief §62): capire lo spazio tecnologico, non stabilire validità brevettuale. Ricerca mirata
a sistemi che combinino **visual transfer dinamico + fountain + controllo audio + LoRa esterno +
stesso formato pacchetto + store-carry-forward + routing cross-medium**.

## Cosa esiste (per componente)

| Componente | Esempi trovati | Combinato con altri componenti? |
|---|---|---|
| QR animati + fountain (LT/RaptorQ/wirehair) | TXQR (LT), Decimen (LT, 2026), libcimbar (wirehair + RS + colore), TXQR-Android, qr_steam | Solo visual, one-way, nessun routing |
| Fountain codes in DTN / multipath | Letteratura accademica: "Forward correction and fountain codes in DTN" (2008), file transfer in Vehicular DTN con fountain a livello applicativo, opportunistic networks multi-source | Simulazioni/paper; nessuna app Android con canali fisici non-radio |
| Multi-interfaccia con stesso formato | **Reticulum** (LoRa/RNode, packet radio, seriale, KISS, TCP/UDP, I2P; roadmap: filesystem, optical, IR, SDR, HF modem) + **LXMF paper messages** (QR) | Sì, ma senza interfacce visual/audio native del telefono e senza oggetti fountain multipath |
| LoRa + QR per pairing contatti | MeshCore (QR dei contatti + LoRa companion), sistema off-grid Stellenbosch (QR di pairing + Heltec V3 + app Flutter) | QR usato per pairing, non come canale dati |
| Data-over-sound | ggwave, quiet, Chirp (dismesso), Google Nearby ultrasuoni (rimosso) | Solo audio |
| Sneakernet | Briar removable drive | Solo contatti, nessun fountain |

Fonti: <https://arxiv.org/pdf/0808.3747>, <https://facultyprofile.csuohio.edu/en/publications/application-of-fountain-code-to-high-rate-delay-tolerant-networks-7/>,
<https://git.hackliberty.org/Git-Mirrors/Reticulum/src/tag/0.9.0/Changelog.md>, <https://reticulum-go.quad4.io/docs/interfaces>,
<https://apkmirror.com/apk/liam-cottle/meshcore>, <https://www.su.ac.za/en/faculties/engineering/departments/electrical-electronic-engineering/news/when-mobile-networks-fail-phone-can-still-reach-your-group>,
<https://github.com/tongatron/decimen-optical-transfer>, <https://github.com/sz3/libcimbar>, <https://github.com/markqvist/lxmf>.

## Cosa NON è stato trovato

Nessun sistema pubblico (prodotto, repository o paper) che, in un'unica app Android:
1. usi **schermo→camera** come canale dati bidirezionale/one-way *e* **audio** come canale di controllo,
2. con lo **stesso formato di pacchetto** trasportato anche via **LoRa USB**,
3. con **oggetti fountain i cui simboli provengono da mezzi e relay diversi**,
4. dentro un modello **store-carry-forward**.

Reticulum è il più vicino sul piano architetturale (e la sua roadmap cita interfacce ottiche/IR), ma
non tratta display/camera/speaker/mic del telefono come interfacce e non usa fountain cross-medium.

## Aggiornamento della valutazione di originalità (vs `13_INNOVATION_GAPS.md`)

| Idea | Valutazione Phase 0 | Dopo M1 |
|---|---|---|
| Multipath fountain cross-medium | Alta novità | **Confermata come gap** nello spazio trovato; tecnicamente supportata dallo spike (ESI casuali, RaptorQ a overhead ~0 nei test cloud) |
| GhostPacket identico su visual e LoRa | Media-alta | La prova byte-identica via canale visuale è implementata (OBJ_SYMBOL); manca il lato LoRa (M6) |
| QR animati con fountain | Non originale | Confermato: TXQR, Decimen, cimbar |
| Griglia rettangolare full-screen | — | Non originale in sé (cimbar, COBRA, RainBar usano layout custom); l'originalità sta solo nell'integrazione |
| Audio come canale di controllo | Media | Rimane plausibile ma **lo spike mostra che con fountain l'ACK non è necessario per il bulk** (vedi M1 report §M): il valore dell'audio è capability/session negotiation, non affidabilità |

Conclusione: il posizionamento "Physical Communication Layer unificato" resta difendibile come
**integrazione**; nessun singolo componente è nuovo.
