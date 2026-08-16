import os
import sqlite3
import psycopg2
import psycopg2.extras
import requests
import builtins

# Erzwingt, dass print() sofort ins Docker-Log geschrieben wird
def print(*args, **kwargs):
    kwargs['flush'] = True
    builtins.print(*args, **kwargs)

SQLITE_DB = "eve_sde.sqlite"

# --- CONFIGURATION (Aus Environment-Variablen laden) ---
PG_HOST = os.getenv("PG_HOST", "postgres")
PG_PORT = os.getenv("PG_PORT", "5432")
PG_DB = os.getenv("PG_DB", "eve_own_auth")
PG_USER = os.getenv("PG_USER", "eve_user")
PG_PASS = os.getenv("PG_PASS", "secret_password")

# Nur die vier Tabellen, die der Buybot wirklich braucht:
#   invCategories/invGroups/invTypes -> Namensauflösung, Volumen, Kategorie-Whitelist
#   invTypeMaterials                 -> Reprocessing-Ausbeute
# Jede weitere Tabelle kostet nur Importzeit und Plattenplatz.
TABLES_TO_IMPORT = [
    "invCategories", "invGroups", "invTypes", "invTypeMaterials"
]

def map_sqlite_to_pg_type(sqlite_type):
    if not sqlite_type:
        return "TEXT"

    t = sqlite_type.upper()
    if "INT" in t: return "BIGINT"
    if "CHAR" in t or "TEXT" in t or "CLOB" in t: return "TEXT"
    if "DOUBLE" in t or "REAL" in t or "FLOAT" in t: return "DOUBLE PRECISION"
    if "BLOB" in t: return "BYTEA"
    if "BOOL" in t: return "SMALLINT"

    return "TEXT"

def download_sde():
    if os.path.exists(SQLITE_DB):
        print("Lokale SDE gefunden, überspringe Download.")
        return

    print("Suche nach dem aktuellsten SDE Release auf GitHub (noirsoldats/eve-sde-converter)...")
    api_url = "https://api.github.com/repos/noirsoldats/eve-sde-converter/releases/latest"

    response = requests.get(api_url)
    response.raise_for_status()
    release_info = response.json()

    download_url = None

    for asset in release_info.get("assets", []):
        if asset["name"] == "eve.db":
            download_url = asset["browser_download_url"]
            break

    if not download_url:
        raise Exception("Konnte eve.db im neuesten GitHub-Release nicht finden!")

    print(f"Lade SDE herunter von: {download_url}")
    print("Lade ca. 200MB herunter... Das kann einen Moment dauern...")

    r = requests.get(download_url, stream=True)
    r.raise_for_status()

    with open(SQLITE_DB, 'wb') as f:
        for chunk in r.iter_content(chunk_size=81920):
            if chunk:
                f.write(chunk)

    print("Download erfolgreich abgeschlossen.")

def replicate():
    print(f"Verbinde zu Postgres ({PG_HOST}:{PG_PORT})...")
    sqlite_conn = sqlite3.connect(SQLITE_DB)
    sqlite_cur = sqlite_conn.cursor()

    pg_conn = psycopg2.connect(host=PG_HOST, port=PG_PORT, database=PG_DB, user=PG_USER, password=PG_PASS)
    pg_cur = pg_conn.cursor()

    pg_cur.execute("CREATE SCHEMA IF NOT EXISTS evesde;")
    pg_conn.commit()

    for table in TABLES_TO_IMPORT:
        print(f"Repliziere Tabelle: {table}...")
        sqlite_cur.execute(f"PRAGMA table_info({table})")
        columns = sqlite_cur.fetchall()

        if not columns:
            print(f"WARNUNG: {table} in SQLite nicht gefunden!")
            continue

        pg_cols = []
        pk_cols = []

        # FIX: Alle Composite-Keys sammeln
        for col in columns:
            col_name = col[1]
            col_type = map_sqlite_to_pg_type(col[2])
            pg_cols.append(f'"{col_name}" {col_type}')

            # col[5] ist in SQLite > 0, wenn die Spalte Teil eines Primary Keys ist
            if col[5] > 0:
                pk_cols.append(f'"{col_name}"')

        pg_cur.execute(f'DROP TABLE IF EXISTS evesde."{table}" CASCADE;')

        # FIX: Primary Keys korrekt definieren
        create_query = f'CREATE TABLE evesde."{table}" ({", ".join(pg_cols)}'
        if pk_cols:
            create_query += f', PRIMARY KEY ({", ".join(pk_cols)})'
        create_query += ');'

        pg_cur.execute(create_query)

        col_names = [f'"{c[1]}"' for c in columns]
        sqlite_cur.execute(f'SELECT {", ".join(col_names)} FROM {table}')
        rows = sqlite_cur.fetchall()

        if rows:
            placeholders = ", ".join(["%s"] * len(columns))
            insert_query = f'INSERT INTO evesde."{table}" ({", ".join(col_names)}) VALUES ({placeholders})'
            psycopg2.extras.execute_batch(pg_cur, insert_query, rows, page_size=2000)
            pg_conn.commit()
            print(f"-> {len(rows)} Zeilen importiert.")
        else:
            print(f"-> Tabelle {table} ist leer.")

    sqlite_conn.close()
    pg_conn.close()
    print("SDE-Replikation komplett abgeschlossen!")

def sde_already_exists():
    """Prüft, ob das evesde-Schema und die Tabelle invTypes bereits in Postgres existieren."""
    try:
        conn = psycopg2.connect(host=PG_HOST, port=PG_PORT, database=PG_DB, user=PG_USER, password=PG_PASS)
        cur = conn.cursor()

        # Prüfen, ob die Tabelle 'invTypes' im Schema 'evesde' existiert
        cur.execute("""
                    SELECT EXISTS (
                        SELECT FROM information_schema.tables
                        WHERE table_schema = 'evesde'
                          AND table_name = 'invTypes'
                    );
                    """)
        exists = cur.fetchone()[0]
        cur.close()
        conn.close()
        return exists
    except Exception:
        # Falls die DB noch gar nicht existiert oder nicht erreichbar ist
        return False

if __name__ == "__main__":
    # Parameter aus Docker-Compose auslesen
    force_update = os.getenv("FORCE_SDE_UPDATE", "false").lower() == "true"

    print("Prüfe SDE-Status...")

    if sde_already_exists() and not force_update:
        print("--- SDE ist bereits in Postgres importiert! ---")
        print("-> Überspringe Download und Replikation.")
        print("-> Setze 'FORCE_SDE_UPDATE=true' in Docker-Compose, um ein Update zu erzwingen.")
        exit(0)

    try:
        print("SDE wird neu importiert/aktualisiert...")
        download_sde()
        replicate()
    except Exception as e:
        print(f"KRITISCHER FEHLER: {e}")
        import traceback
        traceback.print_exc()
        exit(1)
    finally:
        if os.path.exists(SQLITE_DB):
            os.remove(SQLITE_DB)
            print("Temporäre Datei gelöscht.")