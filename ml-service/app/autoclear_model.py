"""Auto-Clear Engine — the Phase-3 USP.

A SEPARATE classifier from the fraud model. Its job: given a flagged transaction and real (and
demo-simulated) analysts' historical decision pattern, estimate P(analyst would CLEAR this).
The gateway only auto-captures when this confidence exceeds a high threshold (default 0.97).

Trained on SILVER LABELS (HUMAN and SIMULATED_ANALYST CLEAR/REJECT calls — never AUTO_CLEAR
itself). The hidden `actually_fraudulent` ground truth is used ONLY to score the result
afterwards, never as a training input — see app/db.py.

Cold start / too little data => returns a low, conservative confidence so nothing is
auto-cleared until the model has evidence.
"""
from __future__ import annotations

import os
from datetime import datetime, timezone

import joblib
import numpy as np
from sklearn.linear_model import LogisticRegression
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import StandardScaler

from .db import fetch_analyst_decisions
from .features import AUTOCLEAR_FEATURE_NAMES, to_autoclear_vector

ARTIFACT = os.path.join(os.environ.get("ARTIFACT_DIR", "artifacts"), "autoclear_model.joblib")
MIN_SAMPLES = 40
MIN_PER_CLASS = 8


class AutoClearModel:
    def __init__(self) -> None:
        self.pipeline: Pipeline | None = None
        self.version: str = "cold-start"
        self.stats: dict = {"trained": False, "reason": "not trained yet"}

    def load(self) -> None:
        if os.path.exists(ARTIFACT):
            blob = joblib.load(ARTIFACT)
            self.pipeline, self.version, self.stats = blob["pipeline"], blob["version"], blob["stats"]

    def train(self) -> dict:
        try:
            rows = fetch_analyst_decisions()
        except Exception as e:  # DB unreachable
            self.stats = {"trained": False, "reason": f"db error: {e}"}
            return self.stats

        y = np.array([lbl for _, lbl in rows], dtype=int)
        if len(rows) < MIN_SAMPLES or y.sum() < MIN_PER_CLASS or (len(y) - y.sum()) < MIN_PER_CLASS:
            self.pipeline = None
            self.stats = {
                "trained": False,
                "reason": f"insufficient data (n={len(rows)}, clears={int(y.sum())}, rejects={int(len(y) - y.sum())}; "
                          f"need >= {MIN_SAMPLES} and >= {MIN_PER_CLASS}/class)",
            }
            return self.stats

        X = np.array([to_autoclear_vector(f) for f, _ in rows], dtype=float)
        pipe = Pipeline([
            ("scale", StandardScaler()),
            ("clf", LogisticRegression(max_iter=1000, class_weight="balanced")),
        ])
        pipe.fit(X, y)
        self.pipeline = pipe
        self.version = "autoclear-lr-" + datetime.now(timezone.utc).strftime("%Y%m%d%H%M%S")
        train_acc = float((pipe.predict(X) == y).mean())
        self.stats = {
            "trained": True,
            "n_samples": int(len(rows)),
            "n_clear": int(y.sum()),
            "n_reject": int(len(y) - y.sum()),
            "train_accuracy": round(train_acc, 4),
        }
        os.makedirs(os.path.dirname(ARTIFACT), exist_ok=True)
        joblib.dump({"pipeline": pipe, "version": self.version, "stats": self.stats}, ARTIFACT)
        return self.stats

    def predict(self, features: dict) -> dict:
        if self.pipeline is None:
            # Conservative: never enough confidence to auto-clear.
            return {
                "clear_confidence": 0.0,
                "model_version": self.version,
                "top_features": [],
                "note": self.stats.get("reason", "model not trained"),
            }
        vec = to_autoclear_vector(features)
        x = np.array([vec], dtype=float)
        conf = float(self.pipeline.predict_proba(x)[0, 1])

        clf: LogisticRegression = self.pipeline.named_steps["clf"]
        scaler: StandardScaler = self.pipeline.named_steps["scale"]
        contributions = clf.coef_[0] * scaler.transform(x)[0]
        order = np.argsort(np.abs(contributions))[::-1][:3]
        top = [
            {"feature": AUTOCLEAR_FEATURE_NAMES[i], "contribution": round(float(contributions[i]), 4)}
            for i in order
        ]
        return {"clear_confidence": round(conf, 6), "model_version": self.version, "top_features": top}
