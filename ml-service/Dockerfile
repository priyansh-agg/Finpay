FROM python:3.12-slim

WORKDIR /app
ENV PYTHONUNBUFFERED=1 ARTIFACT_DIR=/app/artifacts

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY app ./app
RUN mkdir -p /app/artifacts

EXPOSE 9000
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "9000"]
