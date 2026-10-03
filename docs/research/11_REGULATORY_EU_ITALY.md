# 11 — Normativa UE / Italia

> **Disclaimer**: questo documento è un'analisi tecnica preliminare, non un parere legale. Prima di
> qualunque trasmissione radio fuori laboratorio, e prima di mettere sul mercato hardware, verificare
> i testi vigenti (Gazzetta Ufficiale, EUR-Lex, CEPT ECO Documentation Database, MIMIT) e consultare
> un professionista. Nessuna parte di GHOSTLINK deve aggirare limiti normativi: i limiti (potenza,
> duty cycle, bande) vanno **imposti dal firmware del dongle**, non lasciati all'app.

## 1. Quadro normativo

| Livello | Atto | Contenuto rilevante |
|---|---|---|
| ITU | Radio Regulations | Definizione di "onde radio" fino a 3 000 GHz → **luce visibile/IR non è "radio"**; art. 25 servizio d'amatore |
| CEPT | **ERC/REC 70-03** (Annex 1 SRD non specifici; ultimo emendamento 2024) | Bande SRD, potenze, duty cycle |
| UE | **Decisione 2006/771/CE** e modifiche successive (armonizzazione SRD) | Rende vincolanti le condizioni SRD negli Stati membri |
| UE | **Direttiva RED 2014/53/UE** | Requisiti essenziali e marcatura CE degli apparati radio |
| ETSI | **EN 300 220** (SRD 25–1 000 MHz) | Standard armonizzato: duty cycle, LBT/AFA, maschere |
| UE | Reg. delegato (UE) 2022/30 (cybersecurity RED) | Applicabile dal 1/8/2025 a certe categorie di apparati: **verificare** se un dongle senza connessione Internet rientra |
| UE | Cyber Resilience Act (UE) 2024/2847 | Obblighi progressivi per prodotti con elementi digitali (principali dal 2027): **verificare** in caso di vendita |
| UE | Reg. (UE) 2021/821 dual-use, Cat. 5 parte 2 | Crittografia; software open source pubblicamente disponibile e prodotti "mass market" generalmente esclusi/agevolati: **verificare** |
| IT | **Codice delle comunicazioni elettroniche** (D.Lgs. 259/2003 come sostituito dal D.Lgs. 207/2021, corretto dal D.Lgs. 48/2024) | Regime di "libero uso" e autorizzazioni generali; servizio d'amatore |
| IT | Piano Nazionale di Ripartizione delle Frequenze (PNRF) | Attribuzioni nazionali |
| IT | D.Lgs. 81/2008, Titolo VIII (agenti fisici) | Valutazione rischio da rumore, **ultrasuoni**, radiazioni ottiche artificiali (ambito lavoro) |
| UE | EN 60825-1, EN 50689:2021, EN 62471 | Sicurezza laser, laser consumer, sicurezza fotobiologica LED |

## 2. Bande radio sub-GHz (SRD non specifici, Annex 1 ERC/REC 70-03)

Valori ampiamente riportati e coerenti tra più fonti (ETSI EN 300 220, regional parameters LoRaWAN
EU868). **Confermare sul testo vigente prima dell'uso.**

| Sub-banda | Potenza max | Duty cycle (o alternativa) | Uso tipico | Confidenza |
|---|---|---|---|---|
| 433.050–434.790 MHz | 10 mW e.r.p. | ≤ 10% (varianti con 1 mW / canali ≤ 25 kHz: verificare) | Telecomandi, sensori | MEDIUM |
| 863.0–865.0 MHz ("K") | 25 mW e.r.p. | ≤ 0.1% o LBT+AFA | | MEDIUM-HIGH |
| 865.0–868.0 MHz ("L") | 25 mW e.r.p. | ≤ 1% o LBT+AFA | | MEDIUM-HIGH |
| 868.0–868.6 MHz ("M"/g1) | 25 mW e.r.p. | ≤ 1% | LoRaWAN canali base | HIGH |
| 868.7–869.2 MHz ("N"/g2) | 25 mW e.r.p. | ≤ 0.1% | | HIGH |
| **869.40–869.65 MHz ("P"/g3)** | **500 mW e.r.p.** | **≤ 10%** | Meshtastic EU_868 default, downlink LoRaWAN | HIGH |
| 869.7–870.0 MHz ("Q") | 25 mW e.r.p. (5 mW senza limiti DC: verificare) | ≤ 1% | | MEDIUM |

Fonti: <https://www.thethingsnetwork.org/docs/lorawan/regional-parameters/eu868/>,
<https://www.actility.com/understanding-duty-cycle-lorawan/>, <https://docdb.cept.org/download/248>
(ERC/REC 70-03), <https://cdn-shop.adafruit.com/product-files/5684/EN300%C2%A0220+V3.1.1-868.pdf>.

Duty cycle: misurato su **1 ora** (0.1% = 3.6 s/h, 1% = 36 s/h, 10% = 360 s/h).

## 3. Classificazione richiesta

### ✅ Legale senza licenza (alle condizioni indicate)

| Attività | Condizioni |
|---|---|
| Visual screen→camera, QR, GhostDrop stampati/video | Nessuna normativa spettrale; attenzione a contenuti lampeggianti (fotosensibilità) |
| Audio udibile/near-ultrasonic via telefono | Nessuna licenza; buon senso su volume/rumore (L. 447/1995 inquinamento acustico per contesti pubblici/continuativi) |
| Torcia/schermo come beacon | Nessuna licenza; non abbagliare conducenti/piloti |
| LED visibili/IR su dongle | Nessuna licenza (non è radio); progettare entro gruppo esente/1 di **EN 62471** |
| Laser **classe 1** (o 2 per puntamento visibile in laboratorio) | EN 60825-1; per prodotti consumer EN 50689 (no 1M/2M/3B/4; child-appealing solo classe 1) |
| LoRa/FSK in 863–870 MHz con dongle conforme | Entro potenza/duty cycle della sub-banda; apparato conforme RED (moduli certificati + integrazione conforme); antenna con guadagno coerente con l'e.r.p. |
| 433 MHz SRD | Entro 10 mW e.r.p. e duty cycle |
| Ricezione SDR | In generale libera; obblighi di riservatezza su comunicazioni intercettate non destinate al pubblico |
| Cavi, USB, supporti rimovibili | Nessuna |

### 🔑 Richiede licenza / autorizzazione

| Attività | Note |
|---|---|
| Trasmissioni su **bande radioamatoriali** | Patente di operatore + autorizzazione generale (MIMIT). **ITU RR art. 25.2A**: le trasmissioni tra stazioni d'amatore di paesi diversi *non devono essere codificate allo scopo di oscurarne il significato*; le norme nazionali sono in genere ancora più restrittive. → **La cifratura E2E di GHOSTLINK è incompatibile con l'uso su bande amatoriali**; ammissibile al più una modalità firmata ma in chiaro (verificare con le norme italiane) |
| Potenze/bande fuori dai limiti SRD | Richiede assegnazione/autorizzazione: **non perseguito** |
| Rete LoRa che offre servizio a terzi / pubblico (es. gateway pubblici) | In Italia sono riportati casi di richiesta di autorizzazione generale e contributi per gateway LoRa (fonte non ufficiale: forum TTN). **Uso privato P2P tra propri dispositivi = probabile libero uso, ma da verificare con MIMIT** |

### ⛔ Restricted (non fare)

- Trasmettere con SDR (HackRF, Pluto, Lime) fuori dall'ambiente di laboratorio/cablato o fuori dai
  parametri SRD: l'apparato non è certificato come SRD e le emissioni spurie non sono garantite.
- Disturbare/jammare qualunque servizio (reato).
- Modificare moduli certificati per superare potenza/duty cycle; antenne ad alto guadagno che portano
  l'e.r.p. oltre il limite.
- Laser classe 3B/4 in prototipi consumer; puntare laser verso persone, veicoli, aeromobili.
- Ultrasuoni ad alta pressione in ambienti con persone/animali oltre i riferimenti di esposizione.

### ❓ Incerto / da verificare

| Tema | Domanda aperta |
|---|---|
| Regime italiano SRD "libero uso" vs "dichiarazione" | Quali usi SRD a 868 MHz richiedono una dichiarazione/autorizzazione generale secondo il Codice vigente (D.Lgs. 207/2021 + 48/2024)? Uso personale P2P vs rete con terzi |
| 433 MHz dettagli | Esatte varianti di potenza/duty cycle nella revisione vigente di ERC/REC 70-03 e della decisione UE |
| LBT+AFA | Condizioni esatte per usare LBT+AFA in luogo del duty cycle in 863–868 MHz (EN 300 220 v3.x) |
| PMR446 per dati | Gli apparati PMR446 sono omologati per voce (analogica/digitale) con antenna integrata; l'uso come canale dati audio (AFSK via walkie-talkie) è conforme? **Da verificare (ECC/DEC/(15)05 e normativa nazionale)** |
| CB 27 MHz | Regime italiano attuale (autorizzazione/contributo) e ammissibilità dati |
| Cifratura su bande non amatoriali | Nessun divieto generale noto per SRD; verificare eventuali restrizioni settoriali |
| Puntatori laser in Italia | Esistono ordinanze ministeriali su vendita/uso di puntatori laser oltre certe classi: verificare testo vigente |
| RED per prototipi | L'Allegato I della RED esclude i *kit di valutazione su misura destinati a professionisti per uso esclusivo in strutture di R&S*: verificare l'applicabilità ai nostri prototipi |
| Cybersecurity RED / CRA | Applicabilità a un dongle USB senza IP |
| Dual-use | Conferma dell'esenzione per software open source e per un eventuale dongle venduto |

## 4. Requisiti di progetto derivati

1. **Regulatory profile nel firmware** (`region = EU868`): tabella sub-bande, e.r.p. massima (tenendo conto
   del guadagno d'antenna dichiarato), **contabilità del duty cycle su finestra mobile di 1 h**, rifiuto
   dei comandi non conformi (`ERROR: REGULATORY_LIMIT`).
2. L'app **non** può impostare potenza/frequenza arbitrarie: sceglie solo tra profili conformi.
3. Modalità "amateur" (se mai implementata) separata, **senza cifratura**, con callsign obbligatorio,
   disponibile solo a utenti che dichiarano la licenza.
4. Hardware: moduli radio pre-certificati (es. SX1262 in moduli con certificazione RED) per ridurre il
   costo di conformità; prove EMC/RED prima di qualunque vendita.
5. Ottica: progettare entro EN 62471 gruppo esente / EN 60825-1 classe 1; etichettatura.
6. Ultrasuoni: limitare la SPL a ≤ 75 dB nella banda 20 kHz e ≤ 110 dB a 25–100 kHz (riferimento
   IRPA/Health Canada) a 10 cm, con margine; nessuna emissione continua.
