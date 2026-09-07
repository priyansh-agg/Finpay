# FinPay ML sidecar

Small FastAPI service the gateway calls for fraud probability and the Phase-3 auto-clear
prediction. Kept separate from the Java service so scikit-learn training pipelines don't
live inside Spring Boot (and to show clean service separation).

## Models

| Model | File | Live? | Trained on |
|---|---|---|---|
| **FinPay-native fraud** | `app/finpay_model.py` | ✅ yes — `/predict` | synthetic FinPay transactions with injected fraud *patterns* (`app/synthetic.py`) |
| **Auto-Clear Engine** | `app/autoclear_model.py` | ✅ yes — `/autoclear/predict` | the reference analyst's own CLEAR/REJECT decisions (silver labels) read from Postgres |
| **Kaggle Credit Card Fraud** | `app/kaggle_model.py` | ❌ offline demo | the real Kaggle dataset (`data/creditcard.csv`), if present |

The hidden `actually_fraudulent` ground truth is **never** a training input for any model.
It is used only to score the Auto-Clear Engine after the fact (`/autoclear/evaluation`) —
training on it would be data leakage and would make the numbers meaningless.

## Run

```bash
python -m venv .venv && . .venv/bin/activate
pip install -r requirements.txt
FINPAY_DB_URL=postgresql://finpay:finpay@localhost:5432/finpay \
  uvicorn app.main:app --port 9000
```

or `docker compose up ml-service`.

## Kaggle demo

```bash
mkdir -p data && cp ~/Downloads/creditcard.csv data/
curl -X POST localhost:9000/kaggle/train      # prints precision/recall/PR-AUC/ROC-AUC
```
