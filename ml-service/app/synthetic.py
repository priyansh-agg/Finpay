"""Synthetic FinPay transaction generator.

Fraud is injected as *realistic patterns* (velocity bursts, new-device high-value,
large deviation from a customer's norm, failed-attempt bursts, device shared across many
accounts) — NOT as a magic flag — so the rule + ML layers have a genuine signal to learn.
This mirrors what the Java simulator does and is what makes the Phase-3 "zero false
auto-clear" number meaningful rather than trivially gamed.
"""
from __future__ import annotations

import random
from typing import Any


def _legit(rng: random.Random) -> dict[str, Any]:
    tenure = rng.randint(30, 1500)
    avg = rng.choice([20_000, 50_000, 120_000, 300_000])
    amount = max(1000, int(rng.gauss(avg, avg * 0.35)))
    return {
        "amountMinor": amount,
        "method": rng.choice(["CARD", "CARD", "UPI"]),
        "velocity5m": rng.choices([0, 1, 2], weights=[70, 25, 5])[0],
        "velocity1h": rng.choices([1, 2, 3, 5], weights=[50, 30, 15, 5])[0],
        "failedAttemptsLastHour": rng.choices([0, 1], weights=[92, 8])[0],
        "customerTenureDays": tenure,
        "customerRollingAvgMinor": avg,
        "amountDeviationRatio": round(amount / max(avg, 1), 3),
        "deviceIsNew": rng.random() < 0.08,
        "ipIsNew": rng.random() < 0.15,
        "distinctAccountsForDevice": rng.choices([1, 2], weights=[90, 10])[0],
        "hourOfDay": rng.choices(range(24), weights=[2,1,1,1,1,2,4,6,8,9,9,9,9,9,8,8,8,8,7,6,5,4,3,2])[0],
        "avgSimilarityToKnownFraud": round(rng.uniform(0.0, 0.2), 4),
    }


def _fraud(rng: random.Random) -> dict[str, Any]:
    pattern = rng.choice(["velocity", "new_device_high_value", "deviation", "failed_burst", "device_ring"])
    avg = rng.choice([20_000, 50_000, 120_000])
    base = _legit(rng)
    base["customerRollingAvgMinor"] = avg
    base["customerTenureDays"] = rng.randint(0, 120)
    if pattern == "velocity":
        base["velocity5m"] = rng.randint(6, 15)
        base["velocity1h"] = base["velocity5m"] + rng.randint(2, 20)
        base["amountMinor"] = int(avg * rng.uniform(0.8, 2.0))
    elif pattern == "new_device_high_value":
        base["deviceIsNew"] = True
        base["ipIsNew"] = True
        base["amountMinor"] = int(avg * rng.uniform(4, 12))
    elif pattern == "deviation":
        base["amountMinor"] = int(avg * rng.uniform(3.5, 10))
    elif pattern == "failed_burst":
        base["failedAttemptsLastHour"] = rng.randint(4, 9)
        base["amountMinor"] = int(avg * rng.uniform(1.5, 4))
    else:  # device_ring
        base["distinctAccountsForDevice"] = rng.randint(3, 8)
        base["deviceIsNew"] = True
    base["amountDeviationRatio"] = round(base["amountMinor"] / max(avg, 1), 3)
    # overlaps with legit (0..0.25) on purpose — this signal is weak early in a real deployment
    base["avgSimilarityToKnownFraud"] = round(rng.uniform(0.05, 0.45), 4)
    base["_fraud_type"] = pattern
    return base


def _blur(rng: random.Random, row: dict[str, Any]) -> dict[str, Any]:
    """Make ~1 in 4 rows look partly like the other class, so the model isn't trivially perfect."""
    if rng.random() < 0.15:
        row["deviceIsNew"] = rng.random() < 0.5
        row["velocity5m"] = max(0, row["velocity5m"] + rng.choice([-2, -1, 1, 2]))
        row["amountDeviationRatio"] = round(max(0.1, row["amountDeviationRatio"] * rng.uniform(0.5, 1.6)), 3)
    return row


def generate(n: int = 8000, fraud_rate: float = 0.055, seed: int = 42, label_noise: float = 0.02
             ) -> list[tuple[dict[str, Any], int]]:
    rng = random.Random(seed)
    rows: list[tuple[dict[str, Any], int]] = []
    for _ in range(n):
        is_fraud = rng.random() < fraud_rate
        row = _fraud(rng) if is_fraud else _legit(rng)
        row = _blur(rng, row)
        label = 1 if is_fraud else 0
        if rng.random() < label_noise:          # a few mislabelled rows, as in real data
            label = 1 - label
        rows.append((row, label))
    return rows
