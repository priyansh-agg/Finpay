"""FinPay ML sidecar.

Endpoints the gateway calls on the hot path:
  POST /predict              -> FinPay-native fraud probability (live model)
  POST /autoclear/predict    -> P(reference analyst clears this flagged txn)

Ops / demo endpoints:
  GET  /health
  GET  /models
  POST /finpay/train
  POST /autoclear/train      -> retrain from the reference analyst's decisions in Postgres
  GET  /autoclear/evaluation -> auto-clear vs hidden ground truth (evaluation only)
  GET  /kaggle/status, POST /kaggle/train
"""
from __future__ import annotations

import contextlib
import threading
import time

from fastapi import FastAPI
from pydantic import BaseModel

from . import kaggle_model
from .autoclear_model import AutoClearModel
from .db import autoclear_eval_against_ground_truth
from .finpay_model import FinPayModel

finpay = FinPayModel()
autoclear = AutoClearModel()


def _periodic_autoclear_retrain(stop: threading.Event) -> None:
    while not stop.wait(60):
        with contextlib.suppress(Exception):
            autoclear.train()


@contextlib.asynccontextmanager
async def lifespan(app: FastAPI):
    finpay.load_or_train()
    autoclear.load()
    with contextlib.suppress(Exception):
        autoclear.train()
    stop = threading.Event()
    t = threading.Thread(target=_periodic_autoclear_retrain, args=(stop,), daemon=True)
    t.start()
    yield
    stop.set()


app = FastAPI(title="FinPay ML sidecar", lifespan=lifespan)


class Features(BaseModel):
    model_config = {"extra": "allow"}


@app.get("/health")
def health() -> dict:
    return {"status": "ok", "finpay_model": finpay.version, "autoclear_model": autoclear.version}


@app.get("/models")
def models() -> dict:
    return {
        "finpay_native": {"version": finpay.version, "metrics": finpay.metrics,
                          "coefficients": finpay.coefficients(), "live": True},
        "autoclear": {"version": autoclear.version, "stats": autoclear.stats, "live": True},
        "kaggle": kaggle_model.status(),
    }


@app.post("/predict")
def predict(features: Features) -> dict:
    return finpay.predict(features.model_dump())


@app.post("/finpay/train")
def finpay_train() -> dict:
    return finpay.train()


@app.post("/autoclear/predict")
def autoclear_predict(features: Features) -> dict:
    return autoclear.predict(features.model_dump())


@app.post("/autoclear/train")
def autoclear_train() -> dict:
    return autoclear.train()


@app.get("/autoclear/evaluation")
def autoclear_evaluation() -> dict:
    try:
        return autoclear_eval_against_ground_truth()
    except Exception as e:
        return {"error": str(e)}


@app.get("/kaggle/status")
def kaggle_status() -> dict:
    return kaggle_model.status()


@app.post("/kaggle/train")
def kaggle_train() -> dict:
    return kaggle_model.train()
