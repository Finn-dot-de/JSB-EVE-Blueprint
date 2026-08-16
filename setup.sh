#!/usr/bin/env bash
#
# Einrichtung des Buybots.
#
# Fragt die paar Angaben ab, die nur du haben kannst, wuerfelt alle Passwoerter
# selbst aus und startet den Server. Mehrfaches Ausfuehren ist ungefaehrlich:
# eine vorhandene .env wird gesichert, nicht ueberschrieben.
#
# Aufruf:  ./setup.sh

set -euo pipefail

GRUEN='\033[0;32m'
GELB='\033[1;33m'
ROT='\033[0;31m'
FETT='\033[1m'
AUS='\033[0m'

titel()   { echo -e "\n${FETT}$1${AUS}"; }
ok()      { echo -e "${GRUEN}  OK${AUS}  $1"; }
hinweis() { echo -e "${GELB}  !${AUS}   $1"; }
fehler()  { echo -e "${ROT}  Fehler:${AUS} $1" >&2; }

# ---------------------------------------------------------------------------
# 1. Voraussetzungen pruefen
# ---------------------------------------------------------------------------
titel "Schritt 1 von 5: Voraussetzungen"

if ! command -v docker >/dev/null 2>&1; then
    fehler "Docker ist nicht installiert."
    echo    "        Auf Ubuntu installierst du es mit diesen zwei Zeilen:"
    echo    "          sudo apt update"
    echo    "          sudo apt install -y docker.io docker-compose-plugin"
    exit 1
fi

if ! docker compose version >/dev/null 2>&1; then
    fehler "Das Docker-Compose-Plugin fehlt."
    echo    "        Nachinstallieren mit:  sudo apt install -y docker-compose-plugin"
    exit 1
fi

if ! docker info >/dev/null 2>&1; then
    fehler "Docker laeuft, aber du darfst es nicht bedienen."
    echo    "        Entweder mit sudo starten, oder dich einmalig berechtigen:"
    echo    "          sudo usermod -aG docker \$USER"
    echo    "        Danach ab- und wieder anmelden."
    exit 1
fi

ok "Docker ist einsatzbereit"

ARBEITSSPEICHER_MB=$(free -m 2>/dev/null | awk '/^Mem:/{print $2}' || echo 0)
if [ "${ARBEITSSPEICHER_MB}" -gt 0 ] && [ "${ARBEITSSPEICHER_MB}" -lt 3500 ]; then
    hinweis "Der Server hat nur ${ARBEITSSPEICHER_MB} MB Arbeitsspeicher."
    hinweis "Der erste Start braucht etwa 4 GB. Es kann sein, dass er abbricht."
fi

# ---------------------------------------------------------------------------
# 2. Vorhandene Konfiguration sichern
# ---------------------------------------------------------------------------
titel "Schritt 2 von 5: Konfiguration"

if [ -f .env ]; then
    SICHERUNG=".env.alt-$(date +%Y%m%d-%H%M%S)"
    cp .env "${SICHERUNG}"
    hinweis "Es gab schon eine Konfiguration. Sie liegt jetzt unter ${SICHERUNG}."
    echo -n "  Neu einrichten? Die alten Werte gehen dabei nicht verloren. [j/N] "
    read -r ANTWORT
    if [ "${ANTWORT}" != "j" ] && [ "${ANTWORT}" != "J" ]; then
        echo "  Abgebrochen. Es wurde nichts geaendert."
        exit 0
    fi
fi

echo
echo "  Du brauchst gleich vier Angaben. Woher du sie bekommst, steht in"
echo "  ANLEITUNG-SERVER.md - dort ist jeder Schritt einzeln beschrieben."
echo

frage() {
    # frage <Variablenname> <Beschriftung> <Beispiel>
    local var="$1" text="$2" beispiel="$3" eingabe=""
    while [ -z "${eingabe}" ]; do
        echo -e "  ${FETT}${text}${AUS}"
        echo    "  (Beispiel: ${beispiel})"
        echo -n "  > "
        read -r eingabe
        if [ -z "${eingabe}" ]; then
            fehler "Das Feld darf nicht leer bleiben."
        fi
    done
    printf -v "${var}" '%s' "${eingabe}"
    echo
}

frage DOMAIN        "Deine Domain (ohne https:// davor)"        "buybot.net"
frage EVE_CLIENT    "EVE Client ID"                            "a1b2c3d4e5f6..."
frage EVE_SECRET    "EVE Secret Key"                           "xY9zAb..."
frage CORP_ID       "ID deiner Corporation"                    "98378388"
frage ADMIN_CHAR    "Name deines EVE-Charakters (wird Administrator)" "CharakterXYZ"

if ! echo "${CORP_ID}" | grep -qE '^[0-9]+$'; then
    fehler "Die Corporation-ID muss eine reine Zahl sein."
    exit 1
fi

# ---------------------------------------------------------------------------
# 3. Geheimnisse erzeugen
# ---------------------------------------------------------------------------
titel "Schritt 3 von 5: Passwoerter erzeugen"

zufall() { openssl rand -base64 "$1" | tr -d '\n=' | tr '+/' 'ab'; }

JWT_SECRET=$(zufall 48)
AES_KEY=$(openssl rand -base64 32)          # muss genau 32 Byte ergeben
DB_PASSWORT=$(zufall 24)

ok "Drei Passwoerter gewuerfelt - du musst sie dir nicht merken"

# ---------------------------------------------------------------------------
# 4. .env schreiben
# ---------------------------------------------------------------------------
titel "Schritt 4 von 5: Konfiguration schreiben"

cat > .env <<ENDE
# Vom Einrichtungsskript erzeugt am $(date '+%d.%m.%Y %H:%M')
# Diese Datei enthaelt Passwoerter. Nicht weitergeben, nicht einchecken.

# --- EVE SSO / ESI ---
EVE_CLIENT_ID=${EVE_CLIENT}
EVE_CLIENT_SECRET=${EVE_SECRET}
EVE_ESI_BASE_URL=https://esi.evetech.net/latest
EVE_SCOPES="publicData esi-characters.read_corporation_roles.v1 esi-search.search_structures.v1 esi-universe.read_structures.v1 esi-contracts.read_character_contracts.v1 esi-mail.send_mail.v1"
EVE_ALLOWED_CORP=${CORP_ID}

# Wer das Admin-Panel oeffnen darf. Weitere durch Komma trennen,
# Charakternamen oder Charakter-IDs sind beides erlaubt.
ADMIN_CHARACTERS=${ADMIN_CHAR}

# --- Adressen ---
BASE_URL=https://${DOMAIN}
APP_FRONTEND_URL=https://${DOMAIN}

# --- Automatisch erzeugte Geheimnisse ---
APP_JWT_SECRET=${JWT_SECRET}
KEYYY=${AES_KEY}
POSTGRES_PASSWORD=${DB_PASSWORT}

# --- Protokoll ---
AUDIT_RETENTION_DAYS=30
AUDIT_LOG_READS=false
ENDE

chmod 600 .env
ok "Konfiguration liegt in .env (nur fuer dich lesbar)"

# ---------------------------------------------------------------------------
# 5. Starten
# ---------------------------------------------------------------------------
titel "Schritt 5 von 5: Starten"

echo "  Der erste Start dauert 10 bis 20 Minuten: die Anwendung wird gebaut"
echo "  und die EVE-Item-Datenbank heruntergeladen. Das Fenster darf zu bleiben,"
echo "  es laeuft im Hintergrund weiter."
echo
echo -n "  Jetzt starten? [J/n] "
read -r STARTEN
if [ "${STARTEN}" = "n" ] || [ "${STARTEN}" = "N" ]; then
    echo "  Gut. Wenn du soweit bist:  docker compose --profile prod up -d --build"
    exit 0
fi

docker compose --profile prod up -d --build

titel "Fertig"
cat <<ENDE
  Es fehlen noch zwei Dinge, beide in ANLEITUNG-SERVER.md beschrieben:

  1. Die Domain ${DOMAIN} auf diesen Server zeigen lassen
     (A-Record beim Anbieter deiner Domain).

  2. HTTPS einrichten - Abschnitt "Schloss-Symbol" der Anleitung.

  Nachsehen, ob alles laeuft:
     docker compose --profile prod ps

  Fehler suchen:
     docker compose --profile prod logs -f backend

  In der EVE-Anwendung muss als Callback-URL eingetragen sein:
     https://${DOMAIN}/api/auth/callback

  Als Administrator eingetragen: ${ADMIN_CHAR}
  Nur dieser Charakter sieht nach dem Login das Admin-Panel. Weitere kannst du
  spaeter in der .env unter ADMIN_CHARACTERS ergaenzen (Komma getrennt).
ENDE
