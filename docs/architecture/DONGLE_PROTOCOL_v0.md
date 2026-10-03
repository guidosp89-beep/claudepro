# Smartphone ↔ Dongle Protocol v0 (draft)

## 1. Principi

1. Il dongle è un **PHY intelligente + regolatore normativo**: non conosce identità, chiavi, routing.
   Vede solo GhostFrame opachi.
2. Il firmware è l'**unica autorità** su frequenze, potenza, duty cycle (profilo regionale compilato o
   firmato); l'app sceglie solo profili ammessi.
3. Trasporto USB: **CDC-ACM** (nessun driver), compatibile con usb-serial-for-android e con qualunque
   terminale su PC per debug.
4. Binario compatto per i dati; **CBOR** per i messaggi di controllo (estendibile); **JSON** solo in
   modalità debug.

## 2. Framing

```text
wire:   COBS( msg_type(1) ‖ seq(1) ‖ body(N) ‖ CRC-16/CCITT-FALSE(2) ) ‖ 0x00
```

- COBS: delimitatore 0x00 non ambiguo, overhead ≤ 1 B ogni 254.
- `seq`: correlazione richiesta/risposta (0 = non sollecitato, es. `RX_PACKET`).
- Dimensione massima messaggio: 2 048 B (sufficiente per un frame LoRa 255 B o un frame ottico/IR 1 KB).

## 3. Messaggi

| type | Nome | Direzione | Body |
|---|---|---|---|
| 0x01 | `HELLO` | phone→dongle | CBOR `{proto: 0, app: "ghostlink/x.y", nonce}` |
| 0x02 | `HELLO_ACK` | dongle→phone | CBOR `{proto: 0, fw: "x.y.z", hw: "rak4631", serial_hash, nonce}` |
| 0x03 | `GET_CAPABILITIES` | phone→dongle | — |
| 0x04 | `CAPABILITIES` | dongle→phone | CBOR (vedi §4) |
| 0x10 | `SEND_PACKET` | phone→dongle | `phy_id(1) ‖ profile_id(1) ‖ flags(1) ‖ frame(N)` |
| 0x11 | `TX_RESULT` | dongle→phone | `status(1) ‖ airtime_ms(u16) ‖ duty_remaining_ms(u32)` |
| 0x12 | `RX_PACKET` | dongle→phone | `phy_id(1) ‖ rssi(i8) ‖ snr_q4(i8) ‖ freq_err_hz(i16) ‖ ts_ms(u32) ‖ frame(N)` |
| 0x20 | `CONFIG` | phone→dongle | CBOR `{phy_id, profile_id, rx_enabled, cad, ...}` (solo profili ammessi) |
| 0x21 | `CONFIG_ACK` | dongle→phone | CBOR effettivo applicato |
| 0x30 | `SIGNAL_INFO` | dongle→phone | CBOR `{phy_id, noise_floor_dbm, channel_busy_pct, last_rssi}` |
| 0x31 | `STATUS` | dongle→phone | CBOR `{uptime, temp_c, vbus_mv, battery?, duty: {band: used_ms/limit_ms}, queue}` |
| 0x7E | `ERROR` | entrambi | CBOR `{code, msg}` — codici: `BAD_FRAME`, `UNSUPPORTED`, `REGULATORY_LIMIT`, `BUSY`, `TOO_LARGE`, `RADIO_FAULT` |
| 0x7F | `DEBUG_JSON` | entrambi | testo JSON (solo build di debug) |

Esempio debug (equivalente JSON di un `SEND_PACKET`):

```json
{ "type": "send_packet", "phy": "lora", "profile": "eu868_g3_sf9_bw250", "data": "base64..." }
```

## 4. CAPABILITIES

```cbor-diag
{
  "phys": [
    { "id": 0, "kind": "LORA", "dir": "HALF_DUPLEX", "mtu": 255,
      "profiles": [
        { "id": 1, "name": "eu868_g3_sf7_bw250",  "freq_hz": 869525000, "sf": 7,  "bw_hz": 250000, "cr": 5, "erp_mw": 500, "duty_pct": 10 },
        { "id": 2, "name": "eu868_g3_sf11_bw250", "freq_hz": 869525000, "sf": 11, "bw_hz": 250000, "cr": 5, "erp_mw": 500, "duty_pct": 10 },
        { "id": 3, "name": "eu868_g1_sf9_bw125",  "freq_hz": 868300000, "sf": 9,  "bw_hz": 125000, "cr": 5, "erp_mw": 25,  "duty_pct": 1 }
      ] },
    { "id": 1, "kind": "GFSK", "dir": "HALF_DUPLEX", "mtu": 255, "profiles": [ ... ] },
    { "id": 2, "kind": "IR",   "dir": "HALF_DUPLEX", "mtu": 1024, "wavelength_nm": 940, "bitrate": 115200 },
    { "id": 3, "kind": "OPTICAL_TX_LED", "dir": "TX_ONLY", "mtu": 512, "modes": ["RS_OOK_4KHZ"] }
  ],
  "sensors": ["GNSS", "TEMPERATURE"],
  "region": "EU868",
  "antenna_gain_dbi": 2.0
}
```

L'app mappa ogni `phy` in un `TransportInterface` (`usb:<serial_hash>:lora`, ...) e lo registra nel
`TransportRegistry`; al distacco USB i transport vengono rimossi e i pacchetti in coda ri-schedulati.

## 5. Regole firmware (normative)

- Contabilità duty cycle **per sub-banda su finestra mobile di 3 600 s**, persistita in RAM (e
  conservativamente azzerata *solo* dopo 1 h di inattività accertata al boot).
- Prima di ogni TX: CAD/LBT dove previsto, verifica budget; altrimenti `ERROR REGULATORY_LIMIT` con
  `retry_after_ms`.
- e.r.p. calcolata con il guadagno d'antenna dichiarato in build; profili oltre limite non compilati.

## 6. Scelta del formato: confronto

| Formato | Pro | Contro | Uso |
|---|---|---|---|
| Binario fisso | Minimo overhead, parsing banale su MCU | Rigidità | **Dati** (`SEND_PACKET`, `RX_PACKET`) |
| **CBOR** | Compatto, schemaless estendibile, lib piccole (tinycbor, minicbor) | Meno tipizzato | **Controllo/capability** |
| Protobuf (nanopb) | Tipizzato, usato da Meshtastic | Codegen, varianti di versione | Alternativa valida |
| FlatBuffers | Zero-copy | Pesante per MCU piccoli | No |
| JSON | Leggibile | Verboso, parsing costoso | Solo debug |
