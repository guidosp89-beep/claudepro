#!/usr/bin/env python3
"""GHOSTLINK M1 analysis.

Reads physical (or simulated) benchmark exports and the cloud experiment CSVs, and writes
summary tables, the M1 gate verdict and plots.

Usage (from spikes/physical_link_lab):
    python3 scripts/analyze_m1.py [inputs ...] [--cloud results/cloud] [--out results/analysis]

inputs: any mix of ghostlink_m1_results_*.zip files, session directories, or runs.csv files.
        Defaults to results/physical (owner exports) if present.
No row is ever invented: if there is no physical data the report says PHYSICAL: PENDING.
"""
from __future__ import annotations

import argparse
import io
import json
import sys
import zipfile
from pathlib import Path

import pandas as pd

try:
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
except ImportError:  # plots are optional
    plt = None

# Reference categorical palette (fixed order, never cycled) and text/surface tokens.
SERIES = ["#2a78d6", "#eb6834", "#1baf7a", "#eda100", "#e87ba4", "#008300", "#4a3aa7", "#e34948"]
SURFACE, TEXT, TEXT2, GRID = "#fcfcfb", "#0b0b0b", "#52514e", "#e4e3df"

M1_MIN_GOODPUT = 5 * 1024          # bytes/s
M1_MIN_DISTANCE = 50               # cm
M1_MIN_PAYLOAD = 256 * 1024        # bytes
M1_MIN_DEVICES = 2
M1_MIN_RUNS = 100


# ----------------------------------------------------------------------------------------- loading

def _read_runs_from_zip(path: Path) -> list[pd.DataFrame]:
    out = []
    with zipfile.ZipFile(path) as z:
        for name in z.namelist():
            if name.endswith("runs.csv"):
                df = pd.read_csv(io.BytesIO(z.read(name)))
                df["export_file"] = path.name
                out.append(df)
    return out


def load_runs(inputs: list[Path]) -> pd.DataFrame:
    frames: list[pd.DataFrame] = []
    for p in inputs:
        if p.is_file() and p.suffix == ".zip":
            frames += _read_runs_from_zip(p)
        elif p.is_file() and p.name.endswith(".csv"):
            df = pd.read_csv(p)
            df["export_file"] = str(p)
            frames.append(df)
        elif p.is_dir():
            for z in sorted(p.rglob("ghostlink_m1_results_*.zip")):
                frames += _read_runs_from_zip(z)
            for c in sorted(p.rglob("runs.csv")):
                df = pd.read_csv(c)
                df["export_file"] = str(c)
                frames.append(df)
    if not frames:
        return pd.DataFrame()
    df = pd.concat(frames, ignore_index=True)
    df = df.drop_duplicates(subset=["run_id"], keep="last")
    df["goodput_KBps"] = pd.to_numeric(df["goodput_bytes_sec"], errors="coerce").fillna(0) / 1024.0
    df["pass"] = df["RESULT"].eq("PASS") & df["SHA256_PASS"].astype(str).str.lower().eq("true")
    df["direction"] = df["sender_device"].fillna("?") + " -> " + df["receiver_device"].fillna("?")
    return df


# ----------------------------------------------------------------------------------------- tables

def q(s: pd.Series, p: float) -> float:
    s = s.dropna()
    return float(s.quantile(p)) if len(s) else float("nan")


def config_table(df: pd.DataFrame) -> pd.DataFrame:
    rows = []
    for key, g in df.groupby("config_key"):
        ok = g[g["pass"]]
        fails = g[~g["pass"]]["RESULT"].value_counts()
        rows.append({
            "config": key, "runs": len(g), "pass_rate": len(ok) / len(g),
            "goodput_p10_KBps": q(ok["goodput_KBps"], .10), "goodput_p50_KBps": q(ok["goodput_KBps"], .50),
            "goodput_p90_KBps": q(ok["goodput_KBps"], .90),
            "worst_KBps_incl_fail": g["goodput_KBps"].min(),
            "decode_ms_p50": q(pd.to_numeric(g["decode_latency_ms"], errors="coerce"), .5),
            "max_distance_pass_cm": ok["distance_cm"].max() if len(ok) else float("nan"),
            "failures": "; ".join(f"{k}:{v}" for k, v in fails.items()),
        })
    return pd.DataFrame(rows).sort_values(["goodput_p50_KBps", "pass_rate"], ascending=False)


def device_matrix(df: pd.DataFrame) -> pd.DataFrame:
    rows = []
    for (snd, rcv, codec), g in df.groupby(["sender_device", "receiver_device", "codec"], dropna=False):
        ok = g[g["pass"]]
        rate = len(ok) / len(g)
        rows.append({"sender": snd, "receiver": rcv, "codec": codec, "runs": len(g),
                     "stable": "yes" if rate >= 0.9 and len(g) >= 5 else ("partial" if rate >= 0.5 else "no"),
                     "goodput_p50_KBps": q(ok["goodput_KBps"], .5),
                     "max_tested_distance_cm": g["distance_cm"].max(),
                     "max_pass_distance_cm": ok["distance_cm"].max() if len(ok) else float("nan")})
    return pd.DataFrame(rows)


def m1_gate(df: pd.DataFrame) -> dict:
    if df.empty or "pass" not in df.columns:
        return {"verdict": "PENDING", "physical_runs": 0, "recommended_case": "PENDING (no physical data)"}
    phys = df[df["source"].eq("PHYSICAL")]
    qual = phys[phys["pass"] & (phys["goodput_bytes_sec"] >= M1_MIN_GOODPUT) &
                (phys["distance_cm"] >= M1_MIN_DISTANCE) & (phys["payload_bytes"] >= M1_MIN_PAYLOAD)]
    devices = set(qual["sender_device"].dropna()) | set(qual["receiver_device"].dropna())
    best_cfg = None
    if len(qual):
        best_cfg = qual.groupby("config_key")["goodput_KBps"].median().sort_values(ascending=False).index[0]
    verdict = "PENDING"
    if len(phys):
        verdict = "PASS" if (len(qual) and len(devices) >= M1_MIN_DEVICES and len(phys) >= M1_MIN_RUNS) else (
            "PARTIAL" if len(qual) else "FAIL (BELOW TARGET)")
    stable = phys[phys["pass"]]["goodput_KBps"]
    # "Stable" = a config whose runs at >= 50 cm with >= 256 KB pass >= 80% of the time (>= 3 runs);
    # its median goodput drives the pre-registered decision rule of brief §58.
    elig = phys[(phys["distance_cm"] >= M1_MIN_DISTANCE) & (phys["payload_bytes"] >= M1_MIN_PAYLOAD)]
    stable_cfgs = []
    for cfg, g in elig.groupby("config_key"):
        if len(g) >= 3 and g["pass"].mean() >= 0.8:
            stable_cfgs.append((float(g[g["pass"]]["goodput_KBps"].median()), cfg))
    stable_cfgs.sort(reverse=True)
    best_stable = stable_cfgs[0][0] if stable_cfgs else 0.0
    directions_ok = set(qual["direction"]) if len(qual) else set()
    directions_all = set(elig["direction"]) if len(elig) else set()
    if not len(phys):
        case = "PENDING (no physical data)"
    elif directions_ok and len(directions_all) > 1 and len(directions_ok) == 1:
        case = "D — device-specific: M1C compatibility investigation"
    elif best_stable >= 20:
        case = "A — visual excellent: M2 GhostPacket + identity + encrypted visual session"
    elif best_stable >= 5:
        case = "B — visual viable: M1.1 visual optimization (or M2 if further gains look low-ROI)"
    else:
        case = "C — visual weak: M1B audio + alternative visual PHY bake-off"
    return {
        "best_stable_config": stable_cfgs[0][1] if stable_cfgs else None,
        "best_stable_median_KBps": best_stable if stable_cfgs else None,
        "recommended_case": case,
        "physical_runs": int(len(phys)), "qualifying_runs": int(len(qual)), "devices_in_qualifying_runs": sorted(devices),
        "best_qualifying_config": best_cfg, "verdict": verdict,
        "best_measured_KBps": float(stable.max()) if len(stable) else None,
        "median_pass_KBps": float(stable.median()) if len(stable) else None,
        "stretch_20KBps": bool((qual["goodput_KBps"] >= 20).any()) if len(qual) else False,
        "excellent_50KBps": bool((qual["goodput_KBps"] >= 50).any()) if len(qual) else False,
    }


# ----------------------------------------------------------------------------------------- plots

def _style(ax, title, xlabel, ylabel, source):
    ax.set_facecolor(SURFACE)
    ax.figure.set_facecolor(SURFACE)
    ax.set_title(title, color=TEXT, fontsize=11, loc="left", pad=14)
    ax.text(0, 1.015, f"source: {source}", transform=ax.transAxes, fontsize=8, color=TEXT2, va="bottom")
    ax.set_xlabel(xlabel, color=TEXT2)
    ax.set_ylabel(ylabel, color=TEXT2)
    ax.grid(True, color=GRID, linewidth=0.8)
    ax.set_axisbelow(True)
    for s in ("top", "right"):
        ax.spines[s].set_visible(False)
    for s in ("left", "bottom"):
        ax.spines[s].set_color(GRID)
    ax.tick_params(colors=TEXT2)


def _save(fig, out: Path, name: str) -> str:
    out.mkdir(parents=True, exist_ok=True)
    fig.tight_layout()
    fig.savefig(out / name, dpi=130)
    plt.close(fig)
    return name


def plot_runs(df: pd.DataFrame, out: Path, label: str) -> list[str]:
    if plt is None or df.empty:
        return []
    made = []
    top = config_table(df).head(4)["config"].tolist()
    d = df[df["config_key"].isin(top)]
    # 1+2: throughput and success vs distance
    for metric, fname, ylab in (("goodput", "throughput_vs_distance.png", "net goodput (KB/s), median with p10–p90"),
                                ("success", "success_vs_distance.png", "success rate (SHA-256 verified)")):
        fig, ax = plt.subplots(figsize=(7, 4))
        for i, cfg in enumerate(top):
            g = d[d["config_key"] == cfg].groupby("distance_cm")
            if metric == "goodput":
                med = g["goodput_KBps"].median(); lo = g["goodput_KBps"].quantile(.1); hi = g["goodput_KBps"].quantile(.9)
                ax.plot(med.index, med.values, color=SERIES[i], linewidth=2, marker="o", markersize=6, label=cfg)
                ax.fill_between(med.index, lo.values, hi.values, color=SERIES[i], alpha=0.12, linewidth=0)
            else:
                rate = g["pass"].mean()
                ax.plot(rate.index, rate.values, color=SERIES[i], linewidth=2, marker="o", markersize=6, label=cfg)
        if metric == "goodput":
            ax.axhline(5, color=TEXT2, linewidth=1, linestyle="--")
            ax.text(ax.get_xlim()[1], 5, " M1 target 5 KB/s", color=TEXT2, fontsize=8, va="bottom", ha="right")
        _style(ax, ("Goodput" if metric == "goodput" else "Success") + " vs distance (top configs)", "distance (cm)", ylab, label)
        ax.legend(fontsize=8, frameon=False)
        made.append(_save(fig, out, fname))
    # 3: throughput vs FPS (QR configs with several fps values)
    fps = df[df["codec"].eq("QR")].groupby(["visual_key", "target_visual_fps"])["goodput_KBps"].median().reset_index()
    keys = [k for k, g in fps.groupby("visual_key") if g["target_visual_fps"].nunique() > 1][:4]
    if keys:
        fig, ax = plt.subplots(figsize=(7, 4))
        for i, k in enumerate(keys):
            g = fps[fps["visual_key"] == k]
            ax.plot(g["target_visual_fps"], g["goodput_KBps"], color=SERIES[i], linewidth=2, marker="o", markersize=6, label=k)
        _style(ax, "Goodput vs transmitter FPS", "target visual FPS", "median goodput (KB/s)", label)
        ax.legend(fontsize=8, frameon=False)
        made.append(_save(fig, out, "throughput_vs_fps.png"))
    # 4: decode latency per codec / decoder
    lat = df.assign(dec=df["codec"].astype(str) + " / " + df["qr_decoder"].astype(str))
    lat["decode_latency_ms"] = pd.to_numeric(lat["decode_latency_ms"], errors="coerce")
    groups = [(k, g["decode_latency_ms"].dropna().values) for k, g in lat.groupby("dec")]
    groups = [(k, v) for k, v in groups if len(v)]
    if groups:
        fig, ax = plt.subplots(figsize=(7, 3.6))
        orient = {"orientation": "horizontal"} if tuple(int(x) for x in matplotlib.__version__.split(".")[:2]) >= (3, 10) else {"vert": False}
        ax.boxplot([v for _, v in groups], **orient, widths=0.5, patch_artist=True,
                   boxprops=dict(facecolor="#d7e6f8", color=SERIES[0]), medianprops=dict(color=TEXT, linewidth=2),
                   whiskerprops=dict(color=TEXT2), capprops=dict(color=TEXT2), flierprops=dict(markeredgecolor=TEXT2, markersize=3))
        ax.set_yticks(range(1, len(groups) + 1), [k for k, _ in groups])
        _style(ax, "Per-frame decode latency (median per run)", "ms", "", label)
        made.append(_save(fig, out, "decode_latency_vs_codec.png"))
    return made


def plot_cloud(cloud: Path, out: Path) -> list[str]:
    if plt is None:
        return []
    made = []
    lb = cloud / "loss_bench.csv"
    if lb.exists():
        df = pd.read_csv(lb)
        iid = df[df["channel"].str.startswith("iid")]
        fig, ax = plt.subplots(figsize=(7, 4))
        for i, v in enumerate(["raptorq", "lt+gauss", "lt-peeling", "sequential"]):
            g = iid[iid["variant"] == v].sort_values("loss_rate")
            ax.plot(g["loss_rate"] * 100, g["efficiency_p50"], color=SERIES[i], linewidth=2, marker="o", markersize=6, label=v)
        g = iid[iid["variant"] == "raptorq"].sort_values("loss_rate")
        ax.plot(g["loss_rate"] * 100, g["ideal_efficiency"], color=TEXT2, linewidth=1, linestyle="--", label="ideal (1 − loss)")
        _style(ax, "Frame loss vs reliability-layer efficiency (K / frames sent)", "random frame loss (%)", "efficiency (median of reps)", "CLOUD-SIMULATED")
        ax.legend(fontsize=8, frameon=False)
        made.append(_save(fig, out, "frame_loss_vs_fountain_efficiency.png"))
    cs = cloud / "camsim_sweep.csv"
    if cs.exists():
        df = pd.read_csv(cs)
        d = df[(df["group"] == "distance") & (df["analysis_res"] == "1920x1080")]
        picks = [c for c in ["QR-v10-M", "QR-v20-L", "GRID-48x108-b1-p32", "GRID-48x108-b2-p32", "GRID-64x144-b1-p32"] if c in set(d["config"])]
        for metric, fname, ylab in (("success_rate", "sim_success_vs_distance.png", "decode success (simulated frames)"),
                                    ("predicted_goodput_KBps_at_15fps", "sim_goodput_vs_distance.png", "model goodput @15 fps (KB/s)")):
            fig, ax = plt.subplots(figsize=(7, 4))
            for i, c in enumerate(picks):
                g = d[d["config"] == c].sort_values("distance_cm")
                ax.plot(g["distance_cm"], g[metric], color=SERIES[i], linewidth=2, marker="o", markersize=6, label=c)
            if "goodput" in metric:
                ax.axhline(5, color=TEXT2, linewidth=1, linestyle="--")
            _style(ax, ("Decode success" if "success" in metric else "Model goodput") + " vs distance (camera simulator, 1080p, 1×)",
                   "distance (cm)", ylab, "CLOUD-SIMULATED")
            ax.legend(fontsize=8, frameon=False)
            made.append(_save(fig, out, fname))
    ts = cloud / "timing_sweep.csv"
    if ts.exists():
        df = pd.read_csv(ts)
        sel = df[(df["exposure_ms"] == 8) & (df["readout_ms"] == 25) & (df["perpendicular"]) & (df["response_ms"] == 0.5) & (df["tiles"] == 1)]
        fig, ax = plt.subplots(figsize=(7, 4))
        i = 0
        for (hz, cam), g in sel.groupby(["refresh_hz", "camera_fps"]):
            if hz not in (60, 120):
                continue
            g = g.sort_values("tx_fps")
            ax.plot(g["tx_fps"], g["unique_symbols_per_s"], color=SERIES[i], linewidth=2, marker="o", markersize=6, label=f"display {hz:.0f} Hz, camera {cam:.0f} fps")
            i += 1
        _style(ax, "Distinct clean symbols captured per second vs TX frame rate", "transmitter symbols per second", "clean distinct symbols / s", "CLOUD-SIMULATED (timing model)")
        ax.legend(fontsize=8, frameon=False)
        made.append(_save(fig, out, "timing_clean_symbols_vs_txfps.png"))
    return made


# ----------------------------------------------------------------------------------------- report

def md_table(df: pd.DataFrame, floatfmt: str = "{:.2f}") -> str:
    if df.empty:
        return "_no rows_\n"
    cols = list(df.columns)
    lines = ["| " + " | ".join(cols) + " |", "|" + "---|" * len(cols)]
    for _, r in df.iterrows():
        cells = []
        for c in cols:
            v = r[c]
            cells.append(floatfmt.format(v) if isinstance(v, float) and v == v else ("" if isinstance(v, float) else str(v)))
        lines.append("| " + " | ".join(cells) + " |")
    return "\n".join(lines) + "\n"


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("inputs", nargs="*", type=Path)
    ap.add_argument("--cloud", type=Path, default=Path("results/cloud"))
    ap.add_argument("--out", type=Path, default=Path("results/analysis"))
    a = ap.parse_args()
    inputs = a.inputs or [p for p in [Path("results/physical")] if p.exists()]
    runs = load_runs(inputs)
    a.out.mkdir(parents=True, exist_ok=True)
    report = ["# M1 analysis (generated by scripts/analyze_m1.py)\n"]
    for source in ("PHYSICAL", "SIMULATED"):
        sub = runs[runs["source"].eq(source)] if not runs.empty else runs
        report.append(f"\n## {source} runs\n")
        if sub.empty:
            report.append("**PENDING / none** — no rows with this source in the inputs.\n")
            continue
        report.append(f"Runs: {len(sub)} · PASS: {int(sub['pass'].sum())} · sessions: {sub['session_id'].nunique()} · "
                      f"directions: {', '.join(sorted(sub['direction'].unique()))}\n\n")
        ct = config_table(sub)
        report.append("### Per configuration (goodput percentiles over SHA-256-verified runs)\n\n" + md_table(ct))
        report.append("\n### Device matrix\n\n" + md_table(device_matrix(sub)))
        worst = sub.sort_values("goodput_KBps").head(5)[["run_id", "config_key", "distance_cm", "RESULT", "failure_top"]]
        report.append("\n### Worst runs\n\n" + md_table(worst))
        plots = plot_runs(sub, a.out / source.lower(), source)
        if plots:
            report.append("\nPlots: " + ", ".join(f"`{source.lower()}/{p}`" for p in plots) + "\n")
        ct.to_csv(a.out / f"{source.lower()}_config_table.csv", index=False)
    gate = m1_gate(runs) if not runs.empty else {"verdict": "PENDING", "physical_runs": 0}
    report.append("\n## M1 gate (physical runs only)\n\n```json\n" + json.dumps(gate, indent=2) + "\n```\n")
    cp = plot_cloud(a.cloud, a.out / "cloud")
    if cp:
        report.append("\n## Cloud experiment plots\n\n" + "\n".join(f"- `cloud/{p}`" for p in cp) + "\n")
    (a.out / "M1_ANALYSIS.md").write_text("".join(report))
    (a.out / "m1_gate.json").write_text(json.dumps(gate, indent=2))
    print("".join(report)[:4000])
    return 0


if __name__ == "__main__":
    sys.exit(main())
