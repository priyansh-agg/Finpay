"""Kaggle Credit Card Fraud model — the "ML methodology" demo. OFFLINE ONLY.

This model is NOT wired into the live payment flow (the FinPay-native model is). It exists
to demonstrate proper handling of the standard Kaggle Credit Card Fraud dataset:
severe class imbalance (~0.17% fraud), class weights, and precision/recall/AUC reporting.

Drop the dataset at ml-service/data/creditcard.csv (from
https://www.kaggle.com/datasets/mlg-ulb/creditcardfraud) and call POST /kaggle/train.
"""
from __future__ import annotations

import os

import joblib
import numpy as np

DATA = os.path.join(os.path.dirname(os.path.dirname(__file__)), "data", "creditcard.csv")
ARTIFACT = os.path.join(os.environ.get("ARTIFACT_DIR", "artifacts"), "kaggle_model.joblib")


def status() -> dict:
    return {
        "dataset_present": os.path.exists(DATA),
        "artifact_present": os.path.exists(ARTIFACT),
        "dataset_path": DATA,
        "note": "wired into live flow: NO (see app/finpay_model.py for the live model)",
    }


def train() -> dict:
    if not os.path.exists(DATA):
        return {"trained": False, "reason": f"place creditcard.csv at {DATA}"}

    import pandas as pd
    from sklearn.linear_model import LogisticRegression
    from sklearn.metrics import average_precision_score, classification_report, roc_auc_score
    from sklearn.model_selection import train_test_split
    from sklearn.pipeline import Pipeline
    from sklearn.preprocessing import StandardScaler

    df = pd.read_csv(DATA)
    X = df.drop(columns=["Class"]).values
    y = df["Class"].values.astype(int)
    Xtr, Xte, ytr, yte = train_test_split(X, y, test_size=0.3, random_state=42, stratify=y)

    pipe = Pipeline([
        ("scale", StandardScaler()),
        ("clf", LogisticRegression(max_iter=2000, class_weight="balanced")),
    ])
    pipe.fit(Xtr, ytr)
    proba = pipe.predict_proba(Xte)[:, 1]
    pred = (proba >= 0.5).astype(int)
    report = classification_report(yte, pred, output_dict=True, zero_division=0)

    os.makedirs(os.path.dirname(ARTIFACT), exist_ok=True)
    joblib.dump({"pipeline": pipe}, ARTIFACT)
    return {
        "trained": True,
        "n_train": int(len(ytr)),
        "n_test": int(len(yte)),
        "fraud_rate": round(float(y.mean()), 6),
        "roc_auc": round(float(roc_auc_score(yte, proba)), 4),
        "pr_auc": round(float(average_precision_score(yte, proba)), 4),
        "precision_fraud": round(report["1"]["precision"], 4),
        "recall_fraud": round(report["1"]["recall"], 4),
        "f1_fraud": round(report["1"]["f1-score"], 4),
    }
