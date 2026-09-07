"""Thin Postgres access for the ML service. Reads the same DB the gateway writes.

Only used for training the Auto-Clear model on the reference analyst's decisions and for
the Kaggle-model status endpoints. The gateway never depends on this service being up.
"""
from __future__ import annotations

import json
import os
from typing import Any

import psycopg

DB_URL = os.environ.get("FINPAY_DB_URL", "postgresql://finpay:finpay@localhost:5432/finpay")


def fetch_reference_analyst_decisions() -> list[tuple[dict[str, Any], int]]:
    """Returns (feature_snapshot, cleared) rows from the reference analyst's history.

    `cleared` = 1 when the analyst CLEARED the transaction, 0 when REJECTED. These are the
    SILVER LABELS for the Auto-Clear model. The hidden `actually_fraudulent` column is
    deliberately NOT selected here — training on it would be data leakage.
    """
    q = """
        SELECT r.features_at_review, r.risk_score_at_review, r.decision
        FROM analyst_reviews r
        WHERE r.reviewer_type = 'REFERENCE_ANALYST'
          AND r.decision IN ('CLEAR', 'REJECT')
    """
    out: list[tuple[dict[str, Any], int]] = []
    with psycopg.connect(DB_URL, connect_timeout=5) as conn, conn.cursor() as cur:
        cur.execute(q)
        for features, risk_score, decision in cur.fetchall():
            f = features if isinstance(features, dict) else json.loads(features or "{}")
            if risk_score is not None:
                f = {**f, "risk_score": float(risk_score)}
            out.append((f, 1 if decision == "CLEAR" else 0))
    return out


def autoclear_eval_against_ground_truth() -> dict:
    """How auto-clears fared against the hidden ground truth (EVALUATION ONLY)."""
    q = """
        SELECT
          COUNT(*) FILTER (WHERE auto_cleared) AS auto_cleared,
          COUNT(*) FILTER (WHERE auto_cleared AND later_confirmed_fraud IS NOT NULL) AS resolved,
          COUNT(*) FILTER (WHERE auto_cleared AND later_confirmed_fraud = true) AS false_auto_clears
        FROM autoclear_decisions
    """
    with psycopg.connect(DB_URL, connect_timeout=5) as conn, conn.cursor() as cur:
        cur.execute(q)
        auto_cleared, resolved, false_ac = cur.fetchone()
    return {
        "auto_cleared": int(auto_cleared or 0),
        "resolved": int(resolved or 0),
        "false_auto_clears": int(false_ac or 0),
        "false_auto_clear_rate": round((false_ac or 0) / resolved, 6) if resolved else 0.0,
    }
