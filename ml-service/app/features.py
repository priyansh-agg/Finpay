"""Shared feature engineering for the FinPay-native model and the Auto-Clear model.

The gateway sends the exact key set produced by `RiskFeatures.toMap()` in the Java service.
Both models turn that dict into the same fixed numeric vector so training and serving agree.
"""
from __future__ import annotations

import math
from typing import Any

# Order matters: it defines the column order of the model input matrix.
FEATURE_NAMES = [
    "log_amount",
    "amount_deviation_ratio",
    "velocity_5m",
    "velocity_1h",
    "failed_attempts_1h",
    "customer_tenure_days",
    "device_is_new",
    "ip_is_new",
    "distinct_accounts_for_device",
    "hour_sin",
    "hour_cos",
    "method_upi",
    "avg_similarity_to_known_fraud",
]


def _num(d: dict[str, Any], key: str, default: float = 0.0) -> float:
    v = d.get(key, default)
    if isinstance(v, bool):
        return 1.0 if v else 0.0
    try:
        return float(v)
    except (TypeError, ValueError):
        return default


def to_vector(f: dict[str, Any]) -> list[float]:
    amount = _num(f, "amountMinor")
    hour = _num(f, "hourOfDay")
    method = str(f.get("method", "CARD")).upper()
    return [
        math.log1p(max(amount, 0.0)),
        _num(f, "amountDeviationRatio", 1.0),
        _num(f, "velocity5m"),
        _num(f, "velocity1h"),
        _num(f, "failedAttemptsLastHour"),
        _num(f, "customerTenureDays"),
        _num(f, "deviceIsNew"),
        _num(f, "ipIsNew"),
        _num(f, "distinctAccountsForDevice", 1.0),
        math.sin(2 * math.pi * hour / 24.0),
        math.cos(2 * math.pi * hour / 24.0),
        1.0 if method == "UPI" else 0.0,
        _num(f, "avgSimilarityToKnownFraud"),
    ]


# Auto-clear model also gets the risk score as an input.
AUTOCLEAR_FEATURE_NAMES = FEATURE_NAMES + ["risk_score"]


def to_autoclear_vector(f: dict[str, Any]) -> list[float]:
    return to_vector(f) + [_num(f, "risk_score", 50.0)]
