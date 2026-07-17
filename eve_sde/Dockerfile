FROM python:3.13-slim

WORKDIR /app

# System-Abhängigkeiten für psycopg2 (Postgres-Treiber) installieren
RUN apt-get update && apt-get install -y libpq-dev gcc && rm -rf /var/lib/apt/lists/*

# Python-Bibliotheken installieren
RUN pip install --no-cache-dir requests psycopg2-binary

# Skript kopieren
COPY update_sde.py .

CMD ["python", "update_sde.py"]