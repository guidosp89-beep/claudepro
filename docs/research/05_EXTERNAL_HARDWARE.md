# 05 — External Hardware via USB-C

Copre AREA G (dongle USB-C), AREA L (canali cablati) e i fondamenti per AREA H/I/J/K.

## 1. Android USB Host / OTG

- API: `android.hardware.usb` — `UsbManager`, `UsbDevice`, `UsbInterface`, `UsbEndpoint`,
  `UsbDeviceConnection.bulkTransfer()/controlTransfer()`, `UsbRequest` (asincrono). Feature
  `android.hardware.usb.host`. `[PROD]`
- **Permesso**: dialogo utente per ogni device (`UsbManager.requestPermission`), oppure
  intent-filter `USB_DEVICE_ATTACHED` + `device_filter.xml` (VID/PID) → l'app si apre all'aggancio e,
  se l'utente spunta "usa sempre", il permesso persiste per quel device.
- **Driver**: lato Android non esistono driver kernel utilizzabili dall'app: tutto avviene in user
  space. La libreria di riferimento è **usb-serial-for-android** (mik3y, MIT): FTDI, PL2303, CP210x,
  CH340/CH341, e qualunque **CDC-ACM** rilevato per classe d'interfaccia (dalla v3.5.0).
  Meshtastic Android la usa per i nodi LoRa via USB. `[PROD]` <https://github.com/mik3y/usb-serial-for-android>
- **Alimentazione**: in modalità host il telefono fornisce VBUS 5 V; corrente disponibile tipicamente
  **fino a ~500 mA–1.5 A**, dipende dal modello (spesso non documentato) → TO BENCHMARK; progettare il
  dongle per < 300 mA di picco (SX1262 TX +22 dBm ≈ 120 mA) e tollerare brown-out.
- **USB-C**: il dongle deve presentare Rd (5.1 kΩ su CC) per essere visto come UFP/device e far
  passare il telefono in modalità host.
- **Accessory mode (AOA)**: inverso (l'accessorio è host e alimenta il telefono). Utile per
  dongle con batteria propria, ma più complesso e meno supportato; non raccomandato per v1.

## 2. Quale classe USB usare

| Opzione | Pro | Contro | Verdetto |
|---|---|---|---|
| **CDC-ACM (seriale virtuale)** | Nessun driver custom; usb-serial-for-android; funziona anche su PC/Linux/macOS senza driver; debug con terminale | Overhead minimo, ma throughput limitato dall'MCU (FS 12 Mbps teorici, ~1 MB/s reali) | **Raccomandato v1** |
| Bridge UART (CP2102/CH340) | Moduli pronti economici | Chip extra, baud rate fisso, niente segnali di controllo ricchi | OK per prototipi con board esistenti |
| HID | Driver-less su desktop | Su Android serve comunque il permesso USB per l'accesso raw; report da 64 B a 1 kHz → ~64 KB/s | Non conviene |
| **Vendor class + bulk** | Massimo throughput (HS 480 Mbps), protocollo libero | Driver non standard su desktop (WinUSB/libusb), più codice | v2 per SDR/ottico veloce |
| USB Ethernet (CDC-NCM/ECM) | Android la supporta come interfaccia di rete | Ritorniamo a uno stack IP gestito dal sistema, meno controllo | Interessante per "cavo diretto" |
| USB Audio Class | Il dongle appare come scheda audio → riusa AudioTransport! | Banda audio (48–192 kHz), latenza | Idea per dongle ultrasuoni/IR "trasparenti" |

**Scelta**: CDC-ACM con framing binario proprio (vedi `../architecture/DONGLE_PROTOCOL_v0.md`).
Vendor/bulk solo quando un PHY richiede > 1 MB/s.

## 3. Microcontrollori candidati

| MCU | USB nativo | Radio integrata | Pro | Contro |
|---|---|---|---|---|
| **RP2040 / RP2350** | FS device (TinyUSB) | No | Economico, PIO per modulazioni ottiche/IR precise, ottimo supporto Rust (embassy) e C | Nessuna radio, consumo non minimo |
| **ESP32-S3** | FS OTG | Wi-Fi/BLE (da tenere spenti) | Potente, PSRAM, molti board LoRa (LilyGO T3S3, Heltec V3) | Consumo, radio 2.4 GHz superflua |
| **nRF52840** | FS device | BLE/802.15.4 | Basso consumo, RAK WisBlock (RAK4631 = nRF52840 + SX1262), board Meshtastic/RNode comuni | Meno RAM/CPU |
| **STM32 (G4/L4/U5/WL)** | FS (alcuni HS) | STM32WL integra **LoRa/FSK sub-GHz** | Industriale, DSP (G4), WL = radio + MCU in un chip | Toolchain più pesante |

Riferimento pratico: tutti i board LoRa diffusi (Heltec V3, LilyGO T-Beam/T3S3, RAK4631, Seeed
XIAO ESP32S3 + Wio-SX1262) sono già **compatibili con firmware Meshtastic e/o RNode** → permettono
benchmark comparativi con lo stesso hardware.

## 4. AREA L — Canali cablati come fallback

| Canale | Bitrate | Note |
|---|---|---|
| **USB-C ↔ USB-C diretto** tra due telefoni | Mbps | Uno dei due deve fare da host: dipende dal ruolo negoziato (DRP); non garantito senza adattatore/dongle intermedio. Con un **dongle "bridge" a due porte** (MCU con due USB, o due MCU collegati via UART) funziona sempre. TO BENCHMARK |
| **Adattatore USB-Ethernet** su entrambi + cavo | 100 Mbps | Android supporta Ethernet USB; rete IP locale *senza Internet*: canale ad alta banda "di prossimità". Fuori dal vincolo "no Wi-Fi/BT" ma ammissibile come cavo |
| **Jack audio 3.5 mm** (dove esiste) o adattatore USB-C audio | 1–20 kbps (profili "cable" di libquiet) | AudioTransport in modalità cavo; pochi telefoni nuovi hanno il jack |
| **Supporti rimovibili** (microSD, chiavetta USB-C via SAF) | Illimitato | Base per "dead drop" fisico; Briar lo supporta già |
| **GPIO via dongle** | – | Trigger, sensori, interfacce verso apparati esistenti (seriale RS-232/485, radio TNC KISS) |

Nota: il cavo seriale/KISS verso un **TNC radio esistente** (APRS/AX.25) è un ponte gratuito verso
infrastrutture radioamatoriali — ma con i vincoli su cifratura (vedi 11_REGULATORY).

## 5. Discovery hardware

```text
ACTION_USB_DEVICE_ATTACHED
  → match VID/PID (o classe CDC + iProduct "GHOSTLINK")
  → requestPermission
  → apri CDC-ACM, SET_LINE_CODING, DTR on
  → HELLO (versione protocollo, nonce)
  → CAPABILITIES (CBOR: lista PHY, parametri, limiti normativi caricati, fw version)
  → TransportRegistry.register(LoRaTransport(dongle), IrTransport(dongle), ...)
  → ACTION_USB_DEVICE_DETACHED → unregister, ri-routing dei pacchetti in coda
```

Dettaglio dei messaggi in `../architecture/DONGLE_PROTOCOL_v0.md`.

## 6. Modulare vs specializzato

| Approccio | Pro | Contro |
|---|---|---|
| Un "GHOSTLINK Node" modulare (MCU base + moduli: LoRa, IR, ottico, audio) | Un solo firmware e protocollo, capability dinamiche | Più costoso, più grande, certificazione più complessa (ogni combinazione) |
| Dongle specializzati (Nano/LR/Optical/SDR) | Semplici, piccoli, economici, certificabili separatamente | Più SKU, più firmware |

**Raccomandazione**: **un solo firmware + un solo protocollo**, hardware **specializzato** derivato da
una base comune (stesso MCU, stessa interfaccia USB, stesso connettore d'espansione). Per i prototipi
usare un sistema già modulare (**RAK WisBlock**: core nRF52840/SX1262 + moduli I/O) o board
LoRa esistenti, e un RP2040/RP2350 per gli esperimenti ottici/IR (PIO per timing preciso).
Dettagli in 16_PROTOTYPE_ROADMAP e nel report finale (sez. J).
