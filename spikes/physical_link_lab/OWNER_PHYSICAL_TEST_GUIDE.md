# GHOSTLINK M1 — Guida al test fisico (per il proprietario)

Tempo totale consigliato: **~2 ore** (in gran parte automatiche). Servono **2 telefoni Android 8.0+**,
un metro/righello, un supporto stabile per il telefono trasmettitore (libri, treppiede, porta-telefono).
Non serve compilare nulla e non bisogna scrivere CSV a mano.

## 0. Procurarsi l'APK (una volta)

**Opzione A (consigliata)** — GitHub → repository `claudepro` → tab **Actions** → workflow **"M1 Link Lab"**
→ l'esecuzione più recente sul branch `claude/ghostlink-tech-research-t9zc9d` → sezione *Artifacts* →
scaricare **`ghostlink-link-lab-apk`** → estrarre lo zip → `app-debug.apk`.

**Opzione B** — Android Studio: aprire la cartella `spikes/physical_link_lab/android`, collegare il
telefono, premere *Run*.

Installare l'APK su **entrambi** i telefoni (Android chiederà di consentire l'installazione da
"origini sconosciute" per il file manager/browser usato). L'app si chiama **GHOSTLINK Link Lab**.

## 1. Preparazione (2 minuti)

Su **entrambi** i telefoni:
1. **Modalità aereo ON**, Wi-Fi OFF, Bluetooth OFF, NFC OFF, dati mobili OFF.
2. Batteria > 50% (meglio se in carica).
3. Disattivare il blocco schermo automatico breve (l'app tiene comunque lo schermo acceso).

Chiamiamo **A** il telefono che *trasmette* (mostra i codici) e **B** quello che *riceve* (usa la
fotocamera posteriore).

## 2. Posizionamento (sessione base, 40 cm)

```text
   [ A ]  schermo verso B, in verticale, fermo sul supporto
     |
     |  40 cm (dallo schermo di A all'obiettivo della fotocamera posteriore di B)
     |
   [ B ]  in verticale, fotocamera posteriore verso lo schermo di A, ferma
```

- Entrambi in **verticale (portrait)**; B inquadra A al **centro** dell'anteprima.
- Evitare riflessi di lampade/finestre sullo schermo di A.

## 3. AUTO BENCHMARK — Stage 1 (≈ 10 minuti, automatico)

**Su B (ricevitore):**
1. Aprire l'app → impostare *Distance cm* = **40**, *Angle* = **0**, *Light* = la classe che descrive la
   stanza (INDOOR_NORMAL per un ufficio/casa illuminata), *Motion* = **STATIC**.
2. *QR decoder* = **zxing-cpp**, *Exposure* = **AUTO**, *Analysis res* = **1920x1080**, *Zoom* = **1.0**.
3. Premere **RECEIVER** (concedere il permesso fotocamera). Deve comparire "Waiting for transmitter announce…".

**Su A (trasmettitore):**
4. Aprire l'app → *Transmitter plan* = **AUTO 1: coarse sweep + GhostPacket proof** → **TRANSMITTER**.
5. Toccare il riquadro per **avviare**. Lo schermo diventa una sequenza di codici.

**Non toccare nulla** finché B non mostra **SESSION COMPLETE** (circa 10 minuti).
Se B mostra un **QR "PLAN"** sotto l'anteprima, lasciarlo visibile per il passo 4.

## 4. AUTO BENCHMARK — Stage 2→3 (opzionale ma consigliato, ≈ 40 minuti)

1. Su **A**: tornare indietro alla schermata principale → **SCAN PLAN FROM RECEIVER** → inquadrare il
   QR PLAN mostrato da B finché compare "Plan loaded" (2–3 secondi).
2. Su **B**: tornare indietro e premere di nuovo **RECEIVER** (nuova sessione).
3. Su **A**: *Transmitter plan* = **AUTO 2: fine search + confirm** → **TRANSMITTER** → avvio.
4. Attendere SESSION COMPLETE su B.

Se il QR PLAN non compare (nessuna configurazione ha superato lo stage 1), saltare questo passo e
segnalarlo: è già un risultato.

## 5. Robustezza (≈ 5 minuti per impostazione)

Su **A** scegliere il piano **ROBUSTNESS**. Per ciascuna impostazione: su **B** aggiornare l'etichetta
corrispondente, premere RECEIVER, poi avviare A.

| Ordine | Cosa cambiare fisicamente | Etichette su B |
|---|---|---|
| 1 | Distanza **20 cm** | Distance 20 |
| 2 | Distanza **60 cm** | Distance 60 |
| 3 | Distanza **100 cm** | Distance 100 |
| 4 | Distanza **150 cm** (solo se a 100 cm almeno un run è PASS) | Distance 150 |
| 5 | Distanza **200 cm** (solo se a 150 cm almeno un run è PASS) | Distance 200 |
| 6 | 40 cm, B ruotato di **15°** rispetto ad A | Angle 15 |
| 7 | 40 cm, **30°** | Angle 30 |
| 8 | 40 cm, **45°** | Angle 45 |
| 9 | 40 cm, luce **fioca** (solo una lampada lontana) | Light INDOOR_DIM |
| 10 | 40 cm, luce **forte** (tutte le luci / vicino a finestra senza sole diretto) | Light INDOOR_BRIGHT |
| 11 | 40 cm, **all'ombra all'aperto** | Light OUTDOOR_SHADE |
| 12 | 40 cm, **B tenuto in mano** | Motion HANDHELD_RX |
| 13 | 40 cm, **entrambi in mano** | Motion BOTH_HANDHELD |

Se a una distanza **nessun run** termina con PASS, non provare le distanze maggiori.
Opzionale: ripetere la distanza 100 cm con *Zoom* = 2.0 su B (alcuni telefoni passano al teleobiettivo).

## 6. Confronto decoder (≈ 3 minuti)

Su B: *QR decoder* = **compare-all**, RECEIVER. Su A: piano **DECODER COMPARE**.

## 7. Direzione inversa (importante: display e fotocamere sono diversi)

Scambiare i ruoli (B trasmette, A riceve) e ripetere almeno: **AUTO 1** (passo 3) e **ROBUSTNESS** a 40 e
60 cm.

## 8. Esportare (1 minuto)

Su **ciascun** telefono: schermata principale → **EXPORT RESULTS (all sessions, one zip)**.
Il file `ghostlink_m1_results_all_<telefono>_<data>.zip` viene salvato in **Download/GhostlinkLab**
(Android 10+) e si apre il menu di condivisione. Inviare i **due zip** (uno per telefono).

Per analizzarli localmente (facoltativo):
```bash
cd spikes/physical_link_lab
mkdir -p results/physical && cp ~/Downloads/ghostlink_m1_results_*.zip results/physical/
python3 -m pip install pandas matplotlib
python3 scripts/analyze_m1.py            # scrive results/analysis/M1_ANALYSIS.md e i grafici
```

## Note

- Ogni "trial" mostrato da A è un **run**: con i passi 3–7 si superano ampiamente i **100 run** richiesti.
- Il ricevitore verifica ogni trasferimento **byte per byte (SHA-256)**: nessun risultato è stimato.
- Se l'app si chiude o un run si blocca: tornare alla schermata principale, rifare RECEIVER su B e
  ripartire A; i risultati già salvati restano.
- Sicurezza: i codici lampeggiano a 5–30 Hz; chi soffre di fotosensibilità non deve guardare lo schermo di A.
- Riscaldamento: se un telefono diventa molto caldo, fare una pausa di 5 minuti (lo stato termico viene registrato).
