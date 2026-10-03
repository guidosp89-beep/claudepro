# GHOSTLINK — Architettura (draft v0)

## 1. Strati

```text
┌──────────────────────────────────────────────────────────────────────┐
│ APP (Kotlin, Jetpack Compose)                                        │
│  chat · file · contatti/verifica · GhostDrop · benchmark · settings  │
├──────────────────────────────────────────────────────────────────────┤
│ GHOSTLINK CORE (Rust, via UniFFI)                                    │
│  ┌──────────────┐ ┌──────────────┐ ┌──────────────┐ ┌─────────────┐  │
│  │ Messaging    │ │ Objects      │ │ Identity &   │ │ Routing/DTN │  │
│  │ (LXMF-like)  │ │ manifest +   │ │ Crypto       │ │ spray&wait, │  │
│  │              │ │ RaptorQ      │ │ Noise, keys  │ │ anti-entropy│  │
│  └──────────────┘ └──────────────┘ └──────────────┘ └─────────────┘  │
│  ┌───────────────────────────────┐ ┌──────────────────────────────┐  │
│  │ GhostPacket v0 (codec)        │ │ Adaptive Transport Engine    │  │
│  └───────────────────────────────┘ │ scoring · scheduling · multi-│  │
│  ┌───────────────────────────────┐ │ path symbol allocation       │  │
│  │ Link layer: GhostFrame, frag, │ └──────────────────────────────┘  │
│  │ CRC, link sessions            │                                   │
│  └───────────────────────────────┘                                   │
├──────────────────────────────────────────────────────────────────────┤
│ TRANSPORT ABSTRACTION (trait/interface)                              │
├───────────┬───────────┬───────────┬───────────┬──────────────────────┤
│ Visual    │ Audio     │ Flash     │ IR(blaster)│ External (USB)      │
│ CameraX + │ AAudio +  │ Camera2   │ ConsumerIr │ ┌─────┬────┬──────┐ │
│ renderer  │ DSP(Rust) │ torch     │            │ │LoRa │FSK │IR/Opt│ │
│ (Kotlin)  │           │           │            │ └─────┴────┴──────┘ │
└───────────┴───────────┴───────────┴───────────┴──────────────────────┘
        hardware I/O in Kotlin (API Android) — logica di link/DSP nel core
```

Regola: **l'app e il routing non conoscono il mezzo fisico**. I transport espongono capacità e metriche;
il core decide.

## 2. Moduli Android / Gradle

```text
app/                    UI Compose, navigation, DI (Hilt o manuale)
protocol-core/          wrapper Kotlin generato da UniFFI + .so del core Rust
crypto/                 Keystore wrapping, binding identità (sopra il core)
transports/
  transport-api/        interfacce Kotlin (TransportInterface, TransportCapabilities, metrics)
  transport-visual/     renderer frame + CameraX ImageAnalysis + zxing-cpp
  transport-audio/      AAudio/Oboe I/O; modem nel core Rust
  transport-flash/      torcia (sperimentale)
  transport-ir/         ConsumerIrManager (sperimentale)
  transport-usb/        USB host, usb-serial-for-android, DongleProtocol
routing/                orchestrazione Kotlin di servizi/lifecycle (la logica sta nel core)
storage/                Room: dati UI (contatti visualizzati, impostazioni, metriche); bundle/object store nel core (§8)
hardware/               discovery dongle, capability registry
simulation/             (desktop, Rust) GhostTransportSimulator — fuori dall'APK
benchmark/              app/flavor di benchmark, export CSV
```

Lato Rust (workspace `core/`):

```text
core/
  ghostlink-packet/     GhostFrame, GhostPacket v0 (no_std-compatibile → condivisibile col firmware)
  ghostlink-object/     manifest, RaptorQ ObjectCodec, resume state
  ghostlink-crypto/     identità, Noise (snow), AEAD, tag blinded
  ghostlink-routing/    bundle store trait, spray-and-wait, anti-entropy (IBLT/Bloom)
  ghostlink-engine/     adaptive transport engine, multipath scheduler
  ghostlink-modem/      DSP audio (MFSK, OFDM, chirp sync), codici visivi custom (fase 2)
  ghostlink-ffi/        UniFFI bindings
  ghostlink-cli/        CLI desktop: encode/decode file ↔ frame PNG/WAV, test vector
  ghostlink-sim/        simulatore di rete/canale
```

## 3. Linguaggio del core: confronto

| Criterio | Kotlin puro | Kotlin Multiplatform | **Rust + UniFFI** | C++ + JNI |
|---|---|---|---|---|
| Velocità di iterazione Android | **Ottima** | Buona | Media | Media-bassa |
| Memory safety su input non fidato (parser pacchetti dall'aria) | Buona (JVM) | Buona | **Ottima** | Scarsa |
| Crypto matura | Tink/BouncyCastle/lazysodium | Limitata in commonMain | **RustCrypto, dalek, `snow` (Noise)** | libsodium, noise-c |
| Fountain codec | Pochi (OpenRQ, inattivo) | Pochi | **`raptorq` crate (veloce, attivo)** | libraptorq, wirehair |
| DSP | Possibile, GC/latency | idem | Buono (`rustfft`) | Ottimo |
| Condivisione con firmware MCU | ✘ | ✘ | **✔ (`no_std` per framing/CRC/packet)** | ✔ |
| Simulatore desktop ad alta scala | JVM ok | ok | **Ottimo** | Ottimo |
| iOS/desktop futuri | ✘ | ✔ | ✔ | ✔ |
| Costo build/debug | Basso | Medio | Medio-alto (cargo-ndk, ABI) | Alto |
| Dimensione APK | — | — | +1–3 MB per ABI (stima) | simile |

**Decisione raccomandata**: **Rust core + UniFFI**, Kotlin per tutto ciò che tocca API Android.
Motivi decisivi: parser di input ostile, Noise/RaptorQ maturi, condivisione del formato con firmware e
simulatore. Mitigazione del costo: lo **spike M1 è in Kotlin puro** (usa e getta); il core Rust nasce
in M2 testato su desktop prima di entrare nell'app. KMP resta valida se in futuro iOS diventa prioritario
e il team è Kotlin-only — ma perderebbe il vantaggio firmware/no_std.

## 4. TransportInterface

### 4.1 Kotlin (lato I/O)

```kotlin
interface TransportInterface {
    val id: TransportId                       // es. "visual", "audio", "usb:lora:0"
    val capabilities: StateFlow<TransportCapabilities>
    val state: StateFlow<TransportState>      // UNAVAILABLE, IDLE, ACTIVE, ERROR
    val metrics: StateFlow<LinkMetrics>       // EWMA goodput, PER, SNR, latency, energy/bit

    suspend fun start(config: TransportConfig)
    suspend fun stop()
    suspend fun send(frame: ByteArray, priority: Priority): SendResult   // frame = GhostFrame già serializzato
    val received: Flow<ReceivedFrame>         // byte grezzi + qualità (snr, rssi, timestamp)
}

data class TransportCapabilities(
    val direction: Direction,                 // TX_ONLY, RX_ONLY, HALF_DUPLEX, FULL_DUPLEX
    val mtu: Int,                             // byte per frame
    val nominalBitrate: Long,                 // bit/s
    val typicalRangeM: ClosedFloatingPointRange<Double>,
    val lineOfSight: Boolean,
    val broadcast: Boolean,
    val dutyCycleBudget: DutyCycleBudget?,    // null = nessun limite normativo
    val energyCostNjPerBit: Double?,          // stimato/misurato
    val requiresUserAttention: Boolean,       // es. tenere i telefoni allineati
)
```

### 4.2 Rust (lato logica)

```rust
pub trait Transport: Send + Sync {
    fn id(&self) -> TransportId;
    fn capabilities(&self) -> TransportCapabilities;
    fn metrics(&self) -> LinkMetrics;
    fn send(&self, frame: &[u8], prio: Priority) -> Result<(), TransportError>;
}
// Le implementazioni concrete sono callback verso Kotlin (UniFFI foreign trait)
// oppure sintetiche nel simulatore (SimTransport).
```

Implementazioni previste: `VisualTransport`, `AudioTransport`, `FlashTransport`, `IrTransport`,
`UsbDongleTransport` (che espone sotto-transport `LoRaTransport`, `FskTransport`, `IrDongleTransport`,
`OpticalTransport`), `FileDropTransport` (carta/video/WAV/SD: TX-only o RX-only), `SimTransport`.

## 5. Adaptive Transport Engine

Per ogni coppia (destinazione/vicino raggiungibile, classe di traffico) calcola uno score per ogni
transport disponibile:

```text
score_t = Σ_k w_k(class) · norm_k(metric_t,k)          (0..100)

metriche k: goodput, latency, PER/BER, range_fit, energy_per_bit, duty_cycle_remaining,
            privacy (LOS/direzionale > broadcast), user_attention_cost, hardware_available
norm: funzioni monotone saturanti (es. log per goodput), 0 se il transport non è utilizzabile
```

Pesi per classe (esempio iniziale, da tarare coi benchmark):

| Classe | goodput | latency | reliability | energy | range | privacy | attention |
|---|---|---|---|---|---|---|---|
| `BULK` (simboli file) | 0.45 | 0.05 | 0.15 | 0.10 | 0.05 | 0.05 | 0.15 |
| `CONTROL` (ACK, negoziazione) | 0.05 | 0.40 | 0.30 | 0.10 | 0.05 | 0.05 | 0.05 |
| `MESSAGE` | 0.10 | 0.20 | 0.25 | 0.15 | 0.20 | 0.05 | 0.05 |
| `EMERGENCY` | 0.00 | 0.20 | 0.30 | 0.05 | 0.45 | 0.00 | 0.00 |

Le metriche sono **misurate online** (EWMA) e inizializzate dai profili del benchmark per modello di
device. Il risultato è una lista ordinata, mostrabile in UI (es. `Visual 92 · LoRa 71 · Audio 40`).

### 5.1 Multipath scheduler

Per oggetti fountain: ogni transport attivo riceve simboli in proporzione al proprio goodput misurato
(`share_t = goodput_t / Σ goodput`), ESI distinti per transport; quando il ricevitore segnala
`OBJ_STATUS` con `have ≥ K+2` lo scheduler interrompe tutti i flussi. Messaggi piccoli: invio sul miglior
transport + (opz.) duplicazione su un secondo per affidabilità (classe `EMERGENCY`).

## 6. Flusso dei dati (invio file)

```text
UI → core.send_object(file, dest)
   → encrypt (XChaCha20-Poly1305, chiave random) → ObjectID = BLAKE3(ct)
   → manifest (Noise X verso dest: ObjectID, OTI, chiave file, nome, mime)
   → bundle store (persistito)
   → engine: transport disponibili verso dest/vicino → scheduling
   → GhostPacket(OBJ_SYMBOL) → GhostFrame (fragmentation se MTU piccolo) → Transport.send
```

## 7. Simulatore (`ghostlink-sim`)

- Nodi virtuali con mobilità (random waypoint, trace reali), transport sintetici con profili
  (bandwidth, latenza, perdita i.i.d./Gilbert-Elliott, corruzione, duty cycle, range).
- Esegue **lo stesso codice core** dell'app.
- Metriche: delivery ratio, latenza di consegna, overhead (copie), occupazione storage, energia stimata.
- Scenari: "festival" (molti incontri brevi), "rurale" (pochi incontri, LoRa), "blackout urbano".

## 8. Persistenza

Room (Kotlin) come implementazione del trait `BundleStore` del core, oppure SQLite gestito da Rust
(`rusqlite`). Scelta: **storage nel core (rusqlite)** per poter usare lo stesso codice nel simulatore e
nei test; Room solo per dati UI (contatti visualizzati, impostazioni). Da confermare in M2.
