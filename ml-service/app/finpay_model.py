"""FinPay-native fraud model — the one that is actually LIVE in the system.

Trained on synthetic FinPay transactions (app/synthetic.py) using FinPay's own features:
velocity, amount deviation, device/IP newness, customer history, failed attempts. Logistic
regression, class-weight balanced, wrapped in a StandardScaler pipeline so it stays
interpretable. See app/kaggle_model.py for the separate "ML competency" demo on the real
Kaggle Credit Card Fraud dataset.
"""
from __future__ import annotations

import os
import time
from datetime import datetime, timezone

import joblib
import numpy as np
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import average_precision_score, roc_auc_score
from sklearn.model_selection import train_test_split
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import StandardScaler

from .features import FEATURE_NAMES, to_vector
from .synthetic import generate

ARTIFACT = os.path.join(os.environ.get("ARTIFACT_DIR", "artifacts"), "finpay_model.joblib")


class FinPayModel:
    def __init__(self) -> None:
        self.pipeline: Pipeline | None = None
        self.version: str = "untrained"
        self.metrics: dict = {}

    def load_or_train(self) -> None:
        if os.path.exists(ARTIFACT):
            blob = joblib.load(ARTIFACT)
            self.pipeline, self.version, self.metrics = blob["pipeline"], blob["version"], blob.get("metrics", {})
        else:
            self.train()

    def train(self, n: int = 8000, seed: int = 42) -> dict:
        rows = generate(n=n, seed=seed)
        X = np.array([to_vector(f) for f, _ in rows], dtype=float)
        y = np.array([label for _, label in rows], dtype=int)
        Xtr, Xte, ytr, yte = train_test_split(X, y, test_size=0.25, random_state=seed, stratify=y)

        pipe = Pipeline([
            ("scale", StandardScaler()),
            ("clf", LogisticRegression(max_iter=1000, class_weight="balanced")),
        ])
        pipe.fit(Xtr, ytr)
        proba = pipe.predict_proba(Xte)[:, 1]
        self.pipeline = pipe
        self.version = "finpay-lr-" + datetime.now(timezone.utc).strftime("%Y%m%d%H%M%S")
        self.metrics = {
            "n_train": int(len(ytr)),
            "n_test": int(len(yte)),
            "fraud_rate": round(float(y.mean()), 4),
            "roc_auc": round(float(roc_auc_score(yte, proba)), 4),
            "pr_auc": round(float(average_precision_score(yte, proba)), 4),
        }
        os.makedirs(os.path.dirname(ARTIFACT), exist_ok=True)
        joblib.dump({"pipeline": pipe, "version": self.version, "metrics": self.metrics}, ARTIFACT)
        return self.metrics

    def predict(self, features: dict) -> dict:
        if self.pipeline is None:
            self.load_or_train()
        x = np.array([to_vector(features)], dtype=float)
        p = float(self.pipeline.predict_proba(x)[0, 1])
        return {"probability": round(p, 6), "model_version": self.version}

    def coefficients(self) -> dict:
        if self.pipeline is None:
            return {}
        clf: LogisticRegression = self.pipeline.named_steps["clf"]
        return {name: round(float(c), 4) for name, c in zip(FEATURE_NAMES, clf.coef_[0])}
