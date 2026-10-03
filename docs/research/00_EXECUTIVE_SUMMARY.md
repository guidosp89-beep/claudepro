# 00 — Executive Summary

**Progetto**: GHOSTLINK — Physical Communication Layer universale per Android.
**Fase**: 0 (ricerca + architettura). **Data**: 2026-10-03.

## Risposta alla domanda di fondo

> È possibile costruire un vero Physical Communication Layer universale per Android, in cui luce, suono,
> radio e hardware esterno siano transport diversi dello stesso protocollo?

**Sì, tecnicamente**, usando solo API pubbliche e senza root — con tre limiti da accettare:

1. **Senza hardware extra il raggio è di centimetri–metri.** Visual (10–50 cm, 5–30 KB/s realistici,
   fino a ~100 KB/s con codici colore densi da verificare) e audio (metri, decine di byte/s robusti o
   pochi kbps a corto raggio). Il "lungo raggio" senza dongle si ottiene solo **nel tempo**, con
   store-carry-forward tra persone.
2. **Il lungo raggio reale richiede radio esterna** (LoRa/FSK sub-GHz via USB-C), e in UE è vincolato
   più dal **duty cycle (0.1–10%)** che dal PHY: LoRa è per messaggi, non per file.
3. **Le singole tecnologie esistono già** (QR animati con fountain, data-over-sound, LoRa da telefono,
   Reticulum multi-interfaccia, LXMF paper messages). L'originalità di GHOSTLINK sta nella
   **composizione**: un unico core di identità/pacchetti/DTN con **oggetti fountain ricostruibili da
   frammenti arrivati su mezzi fisici diversi**.

## Raccomandazioni principali

| Tema | Raccomandazione |
|---|---|
| MVP-1 | Trasferimento file **visual (QR animati + RaptorQ)** tra 2 telefoni in modalità aereo; audio MFSK opzionale come canale di controllo |
| MVP-2 | **Dongle USB-C (nRF52840/ESP32-S3 + SX1262)** con lo **stesso GhostPacket** via LoRa EU868 (869.525 MHz, 10% duty) |
| Core | **Rust + UniFFI** (parser sicuri, Noise `snow`, `raptorq`, condivisione con firmware e simulatore); spike iniziale in Kotlin |
| Crypto | Curve25519 (Ed25519 + X25519), **Noise X** per DTN, **Noise IK/XX** per sessioni, ChaCha20-Poly1305; nessuna primitiva inventata |
| Routing | **Spray-and-Wait + anti-entropy** all'incontro; tag di destinazione blinded; ACK a preimmagine |
| Hardware | Firmware del dongle = autorità normativa (duty cycle/e.r.p.); dongle senza chiavi |
| Prossimo passo | **M1 — Visual Channel Spike**: misurare il throughput reale su 2+ device prima di tutto il resto |

## Cosa non fare (per ora)

Canale "invisibile" su schermo, laser, SDR in trasmissione, voce in tempo reale, ultrasuoni a 40 kHz:
restano track di ricerca o strumenti di misura, non parte degli MVP.

## Mappa dei documenti

`01` mappa · `02–07` tecnologie · `08` DTN/multipath · `09` crypto · `10` Android · `11` normativa ·
`12` progetti esistenti · `13` innovazione · `14` rischi · `15` benchmark · `16` roadmap ·
`TECHNOLOGY_MATRIX.md` · `../architecture/*` · `../FINAL_REPORT.md` (sintesi A–L).
