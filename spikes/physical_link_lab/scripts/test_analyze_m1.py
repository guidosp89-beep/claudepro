"""Unit tests for analyze_m1.py decision logic (synthetic fixtures, NOT measurements).

Run: python3 -m pytest scripts/test_analyze_m1.py   (or: python3 scripts/test_analyze_m1.py)
"""
import sys
from pathlib import Path

import pandas as pd

sys.path.insert(0, str(Path(__file__).parent))
import analyze_m1 as a  # noqa: E402


def fixture(goodput_kbps: float, both_directions: bool = True, n: int = 60, distance: int = 60) -> pd.DataFrame:
    rows = []
    dirs = [("PhoneA", "PhoneB"), ("PhoneB", "PhoneA")] if both_directions else [("PhoneA", "PhoneB")]
    for i in range(n):
        snd, rcv = dirs[i % len(dirs)]
        ok = True
        rows.append({
            "run_id": f"T{i}", "session_id": "S", "source": "PHYSICAL", "sender_device": snd, "receiver_device": rcv,
            "config_key": "GRID|fps15|RAPTORQ", "codec": "GRID", "visual_key": "GRID", "target_visual_fps": 15,
            "distance_cm": distance, "payload_bytes": 262144, "RESULT": "PASS" if ok else "FAIL_INCOMPLETE",
            "SHA256_PASS": ok, "goodput_bytes_sec": goodput_kbps * 1024, "decode_latency_ms": 20.0,
            "qr_decoder": "zxing-cpp", "failure_top": None,
        })
    if not both_directions:  # the other direction exists but never passes
        for i in range(10):
            rows.append({**rows[0], "run_id": f"F{i}", "sender_device": "PhoneB", "receiver_device": "PhoneA",
                         "RESULT": "FAIL_INCOMPLETE", "SHA256_PASS": False, "goodput_bytes_sec": 0.0})
    df = pd.DataFrame(rows)
    df["goodput_KBps"] = df["goodput_bytes_sec"] / 1024.0
    df["pass"] = df["RESULT"].eq("PASS") & df["SHA256_PASS"].astype(str).str.lower().eq("true")
    df["direction"] = df["sender_device"] + " -> " + df["receiver_device"]
    return df


def test_cases():
    assert a.m1_gate(fixture(25))["recommended_case"].startswith("A")
    assert a.m1_gate(fixture(9))["recommended_case"].startswith("B")
    assert a.m1_gate(fixture(3))["recommended_case"].startswith("C")
    assert a.m1_gate(fixture(9, both_directions=False))["recommended_case"].startswith("D")
    g = a.m1_gate(fixture(9, n=120))
    assert g["verdict"] == "PASS" and g["physical_runs"] >= 100
    assert a.m1_gate(fixture(9, n=40))["verdict"] == "PARTIAL"  # < 100 aggregate physical runs
    assert a.m1_gate(fixture(9, n=120, distance=40))["verdict"].startswith("FAIL")  # < 50 cm never qualifies
    empty = pd.DataFrame(columns=["source"])
    assert a.m1_gate(empty)["verdict"] == "PENDING"


if __name__ == "__main__":
    test_cases()
    print("ok")
