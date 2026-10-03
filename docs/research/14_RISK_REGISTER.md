# 14 — Risk Register

Scala: Probabilità (P) e Impatto (I) da 1 (basso) a 5 (alto). Score = P × I.

| ID | Rischio | Categoria | P | I | Score | Mitigazione | Owner/fase |
|---|---|---|---|---|---|---|---|
| R01 | Throughput visual phone→phone molto sotto le stime (es. < 5 KB/s) su Android mid-range | Tecnico | 3 | 4 | 12 | **Spike M1 di misura prima di tutto**; fallback a QR più piccoli; codici colore come fase 2 | M1 |
| R02 | Frammentazione camera (fps, AF, esposizione manuale assente) rende la UX inaffidabile | Tecnico | 4 | 3 | 12 | Parametri adattivi, profili per modello dal benchmark, AF continuo + soglie conservative | M1–M3 |
| R03 | Near-ultrasound non supportato o instabile su molti device | Tecnico | 4 | 2 | 8 | Calibrazione per device; fallback udibile; audio solo come control channel | M5 |
| R04 | AGC/NS/AEC dei produttori distruggono il segnale audio | Tecnico | 3 | 3 | 9 | `UNPROCESSED`, test loopback all'avvio, MFSK robusto | M5 |
| R05 | Torcia troppo lenta/jitterata anche per beacon | Tecnico | 3 | 1 | 3 | Usare lo schermo come beacon; LED su dongle | M9 |
| R06 | Complessità Rust+JNI/UniFFI rallenta lo sviluppo | Progetto | 3 | 3 | 9 | Spike M1 in Kotlin puro; core Rust piccolo e testato su desktop; UniFFI per binding generati | M2 |
| R07 | Brevetti su RaptorQ (IPR Qualcomm) | Legale | 2 | 4 | 8 | Codec dietro interfaccia; alternative LT/Wirehair/RS; parere legale prima della distribuzione | M2/M10 |
| R08 | Violazione involontaria limiti SRD (duty cycle/e.r.p.) | Normativo | 2 | 5 | 10 | Enforcement nel firmware, test con SDR, antenna dichiarata, profili bloccati | M6 |
| R09 | Regime italiano incerto per LoRa (dichiarazioni/autorizzazioni) | Normativo | 3 | 3 | 9 | Verifica con MIMIT; uso personale P2P; nessun servizio a terzi finché non chiarito | M6 |
| R10 | Uso improprio percepito (comunicazioni "invisibili") → danno reputazionale/store | Etico/Legale | 2 | 4 | 8 | Posizionamento chiaro (emergenza, privacy, ricerca); nessuna funzione di occultamento; codice aperto | sempre |
| R11 | Crittografia implementata male (nonce reuse, parser vulnerabili) | Sicurezza | 3 | 5 | 15 | Solo Noise/`snow` e crate audit-ati; parser Rust + fuzzing (cargo-fuzz); test vector; review esterna prima di v1 | M4/M10 |
| R12 | Metadati DTN (chi trasporta per chi) esposti | Privacy | 3 | 3 | 9 | Blinded tags, mittente cifrato, padding; documentare i limiti | M7 |
| R13 | DoS sui relay (flood di pacchetti, riempimento storage) | Sicurezza | 4 | 3 | 12 | Quote per sorgente/tag, PoW leggero opzionale per mittenti sconosciuti, priorità ai contatti, expiry | M7 |
| R14 | Consumo/termica: trasferimenti lunghi impossibili | Tecnico/UX | 3 | 3 | 9 | Misurare (M1); preferire burst brevi; incoraggiare alimentazione | M1+ |
| R15 | Restrizioni Android future (FGS, accesso camera/mic) | Piattaforma | 3 | 3 | 9 | Uso in primo piano come default; seguire le policy; nessuna API nascosta | sempre |
| R16 | Alimentazione VBUS insufficiente per il dongle | Hardware | 2 | 3 | 6 | Budget < 300 mA, condensatori, opz. batteria | M6 |
| R17 | Disponibilità di moduli LoRa certificati / costo certificazione RED | Hardware/Normativo | 2 | 3 | 6 | Moduli pre-certificati; nessuna vendita prima di prove | M6+ |
| R18 | UX: allineare due telefoni per minuti è scomodo | UX | 4 | 3 | 12 | Supporto/treppiede di carta, feedback aptico/sonoro, trasferimenti brevi, resume | M3 |
| R19 | Scope creep (troppi transport in parallelo) | Progetto | 4 | 4 | 16 | **Roadmap a gate**: nessun nuovo transport prima che il precedente superi i criteri di accettazione | sempre |
| R20 | Interoperabilità versioni del formato pacchetto | Tecnico | 3 | 3 | 9 | Versioning nel header, test vector, spec scritta prima del codice | M2 |
| R21 | Licenze di terze parti incompatibili (MPL cimbar, GPL Meshtastic, Reticulum License) | Legale | 2 | 3 | 6 | Non copiare codice; usare solo come riferimento/benchmark; scelta licenza GHOSTLINK chiara (es. Apache-2.0/MIT o GPL) | M0 |
| R22 | Post-quantum: identità Curve25519 non resistenti a lungo termine | Sicurezza | 1 | 3 | 3 | Crypto agility nel versioning; ML-KEM ibrido quando i canali lo permettono | v2+ |
| R23 | Fotosensibilità: frame lampeggianti ad alto contrasto | Sicurezza utenti | 2 | 4 | 8 | Avviso, limitare contrasto/frequenze 3–30 Hz di flash a tutto schermo, modalità "riduci flicker" | M1 |
| R24 | Esposizione ultrasuoni (dongle) | Sicurezza utenti | 1 | 3 | 3 | Limiti SPL di progetto, burst brevi | M9 |

## Top 5 (score)

1. **R19** Scope creep (16) → gate rigorosi.
2. **R11** Crittografia/parser (15) → Noise + Rust + fuzzing + audit.
3. **R01/R02/R13/R18** (12) → misurare presto (M1), progettare DoS-resistance in M7, UX fisica.
