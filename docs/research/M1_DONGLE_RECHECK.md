# M1 — Dongle recheck (documentale, nessun acquisto né firmware)

Scopo (brief §60): verificare se la scelta di Phase 0 per il futuro dongle LoRa USB-C resta sensata.
Prezzi indicativi da store pubblici (ottobre 2026, IVA/spedizione escluse) — **da riverificare all'acquisto**.

## Confronto

| Criterio | **RAK4631** (nRF52840 + SX1262, WisBlock) | **ESP32-S3 + SX1262** (Heltec V4, LilyGO T3S3, XIAO ESP32S3 + Wio-SX1262) | **RP2040 + SX1262** (Waveshare RP2040-LoRa) | **STM32WL** (RAK3172, Seeed LoRa-E5) |
|---|---|---|---|---|
| USB verso Android | USB device nativo (CDC-ACM via TinyUSB/Arduino core) | S3: USB OTG nativo (CDC). **Heltec V3 usa bridge USB-UART**, V4 nativo | USB nativo FS (TinyUSB); su RP2040-LoRa tramite adapter board Type-C | Spesso solo UART (moduli); dev board con bridge CH340 |
| Interop Android (usb-serial-for-android) | CDC-ACM: sì, senza driver | CDC-ACM o CP210x/CH34x: sì | CDC-ACM: sì | CH340/CDC: sì |
| Consumo | **Il più basso** (nRF52 + SX1262 RX ~5 mA) | Più alto (S3 ~40–80 mA attivo; radio 2.4 GHz da tenere spenta) | Medio (~25 mA) | Basso (SoC radio integrata) |
| Ecosistema firmware | Meshtastic, RNode, RadioLib, RUI3, Arduino | Meshtastic, RNode, RadioLib, ESP-IDF, **il più diffuso** | RadioLib, Pico SDK, Meshtastic (supporto più recente) | STM32Cube, RadioLib, RUI3 (RAK3172); meno community "mesh" |
| Supporto LoRa | SX1262 (+22 dBm) | SX1262 | SX1262 | Radio integrata LoRa/(G)FSK, +22 dBm (HP) |
| Costo indicativo | ~18–24 $ core; starter kit con base da ~20 $ | XIAO kit ~11–20; Heltec V4 ~18–28 $ | ~13–16 $ | ~10–20 $ |
| Disponibilità | Buona (RAK store, distributori) | Ottima | Buona | Buona |
| Dimensioni | Modulare: core + base (più grande) | XIAO minuscolo; Heltec con OLED | Piccolo + adapter | Moduli minuscoli |
| Difficoltà sviluppo | Media (nRF52 Arduino/Zephyr) | **Bassa** (Arduino/PlatformIO, esempi ovunque) | Bassa-media; **PIO** utile per IR/ottico | Media-alta (STM32Cube) |

Fonti: <https://store.rakwireless.com/products/wisblock-starter-kit>, <https://openelab.io/blogs/learn/heltec-wifi-lora-32-v3-vs-v4-differences-and-buying-guide>,
<https://thepihut.com/products/xiao-esp32s3-wio-sx1262-kit-for-meshtastic-lora>, <https://www.waveshare.com/product/rp2040-lora.htm>.

## Verdetto

- La scelta Phase 0 **resta sensata** con una precisazione: per l'MVP-2 (M6) la candidata principale
  diventa **XIAO ESP32S3 + Wio-SX1262** o **Heltec V4** (USB nativo, costo minimo, ecosistema più ampio,
  stessi board usati da Meshtastic/RNode per confronti incrociati). **RAK4631** resta la scelta se il
  consumo in relay continuo (FGS `connectedDevice` in tasca) diventa prioritario.
- **Evitare board con bridge USB-UART** quando possibile (Heltec V3): funzionano con
  usb-serial-for-android ma aggiungono un chip, consumo e un livello di driver.
- **RP2040/RP2350** resta la base per il ramo ottico/IR (PIO), non per LoRa.
- **STM32WL**: interessante per un PCB futuro (radio + MCU in un chip) ma non per il prototipo.
- Nessuna dipendenza dall'esito M1: il dongle non è nel percorso critico finché il visual non è validato.
