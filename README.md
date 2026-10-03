# GHOSTLINK (nome provvisorio)

Progetto Android sperimentale: un **Physical Communication Layer universale** in cui display, camera,
speaker, microfono, torcia e hardware esterno via USB-C (LoRa, IR, ottico) sono *transport*
intercambiabili dello stesso protocollo — con identità crittografiche offline, pacchetti cifrati
end-to-end, store-carry-forward e trasferimenti fountain multi-mezzo. Nessuna dipendenza obbligatoria da
Internet, rete cellulare, Wi-Fi, Bluetooth, NFC o cloud.

**Stato: Phase 0 — ricerca e architettura.** Nessun codice applicativo ancora.

## Da dove iniziare

- [`docs/FINAL_REPORT.md`](docs/FINAL_REPORT.md) — sintesi (A–L) e prossimo milestone
- [`docs/research/00_EXECUTIVE_SUMMARY.md`](docs/research/00_EXECUTIVE_SUMMARY.md)
- [`docs/research/TECHNOLOGY_MATRIX.md`](docs/research/TECHNOLOGY_MATRIX.md)
- [`docs/architecture/ARCHITECTURE.md`](docs/architecture/ARCHITECTURE.md)

## Principi

- Un solo core, molti mezzi fisici: niente "app QR + app LoRa + app audio".
- Solo primitive crittografiche mature (Curve25519, Noise, ChaCha20-Poly1305).
- Solo API Android pubbliche, niente root.
- Limiti normativi radio imposti dal firmware, mai aggirati.
- Ogni prestazione è etichettata con evidenza e confidenza; ciò che non è misurato è `TO BENCHMARK`.
