# 07 — Software Defined Radio su Android

Copre AREA J.

## 1. Distinzione fondamentale

| Uso | Stato legale (UE/IT) | Ruolo in GHOSTLINK |
|---|---|---|
| **Ricezione** (spettro, decodifica, misure) | Generalmente libera; vincoli sul segreto delle comunicazioni e sull'uso delle informazioni ricevute | **Strumento di ricerca/benchmark** |
| **Trasmissione** | Soggetta a: regole di banda (SRD), conformità RED dell'apparato, oppure licenza radioamatoriale | **Non come transport di produzione**; solo laboratorio, con apparati/parametri conformi |

## 2. Dispositivi

| Device | TX | Banda | Supporto Android | Note |
|---|---|---|---|---|
| **RTL-SDR** (Blog V3/V4) | No | ~24–1 766 MHz | **Sì**: driver "RTL-SDR driver" (Martin Marinov, Play Store), SDR Touch, RF Analyzer, QuestaSDR | Economico (~30–40 €), 2.4 MS/s stabili | 
| **Airspy R2/Mini/HF+** | No | varie | RF Analyzer supporta Airspy (incl. HF+) | |
| **HackRF One** | **Sì** (half-duplex, ~1 MHz–6 GHz, pochi mW–15 dBm) | ampia | **RF Analyzer** (Dennis Mantz, F-Droid, FOSS) supporta HackRF; libreria hackrf_android | 20 MS/s richiedono USB 2.0 HS stabile |
| **SDRplay RSP** | No | 1 kHz–2 GHz | App terze parti (es. SDR Touch con driver) — verificare | Driver proprietario |
| **ADALM-Pluto** | Sì (full-duplex, 325 MHz–3.8 GHz nominali) | | Supporto Android via libiio: **UNKNOWN / da verificare** | Si presenta anche come device USB-Ethernet/seriale |
| **LimeSDR Mini** | Sì | 10 MHz–3.5 GHz | Nessun supporto Android maturo noto: **UNKNOWN** | Consumo elevato |

Fonti: <https://github.com/mediainbox/RFAnalyzer>, <https://www.rtl-sdr.com/rf-analyzer-android-app-hackrf/>,
<https://www.rtl-sdr.com/questasdr-new-rtl-sdr-software-for-android/>.

## 3. Fattibilità tecnica su Android

- **USB bandwidth**: 2.4 MS/s × 2 byte (I/Q 8 bit) ≈ 4.8 MB/s (RTL-SDR) — ok su USB 2.0 HS; HackRF
  a 20 MS/s ≈ 40 MB/s — al limite, dipende dal telefono.
- **Driver**: `librtlsdr`/`libhackrf` compilati con NDK, accesso via file descriptor ottenuto da
  `UsbDeviceConnection.getFileDescriptor()` + libusb in modalità "no device discovery"
  (`libusb_wrap_sys_device`). Pattern noto e funzionante. `[PROD]`
- **DSP**: demodulazione in C/C++/Rust via JNI (GNU Radio esiste in build Android sperimentali, ma è
  pesante; preferibile DSP dedicato e minimale, es. liquid-dsp o Rust).
- **Consumo**: RTL-SDR ~250–300 mA; HackRF ~300–500 mA → **batteria del telefono in 2–4 h**,
  surriscaldamento; non adatto a uso continuo.
- **Potenza TX**: HackRF senza amplificatore è di pochi mW–decine di mW, ma **la purezza spettrale
  (armoniche, spurie) non è garantita** e il device non è certificato come radio per un uso specifico.

## 4. Ruolo raccomandato

1. **Spectrum survey** prima dei test LoRa/FSK (occupazione della banda 868, interferenze).
2. **Misure di benchmark** (RSSI reale, conformità duty cycle del dongle osservata "da fuori").
3. **Ricerca** su PHY sperimentali **solo in ricezione** o in ambiente schermato/conduttivo (cavo +
   attenuatori) per la parte TX.
4. **Non** un transport di GHOSTLINK per utenti finali: un dongle con transceiver certificato
   (SX1262/STM32WL) fa lo stesso lavoro con 1/10 del consumo e conformità normativa chiara.

## 5. Scheda sintetica

| Parametro | Valore |
|---|---|
| HW extra | Sì (30–400 €) |
| Root | No (USB Host + libusb wrap) |
| Bitrate | Dipende dal PHY implementato; vincolato da CPU/USB |
| Range | Dipende dal PHY e dalla potenza (TX: vincoli normativi) |
| Consumo | Alto (1–3 W complessivi) |
| Maturità Android | RX: `[PROD]`; TX: `[PROTO]` |
| Normativa | RX libero (con limiti d'uso); TX: vedi 11 — **restricted / license required** salvo conformità SRD |
| Verdetto | Strumento di laboratorio, non transport di prodotto |
