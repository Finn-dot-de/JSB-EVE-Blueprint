@echo off
setlocal enabledelayedexpansion
cd /d "%~dp0"
title Buybot einrichten

REM ===========================================================================
REM  Einrichtung des Buybots unter Windows.
REM
REM  Diese Datei macht dasselbe wie setup.sh auf dem Linux-Server: sie fragt
REM  die noetigen Angaben ab, wuerfelt alle Passwoerter selbst aus, schreibt
REM  die .env und startet den Stack.
REM
REM  Zusaetzlich kennt sie einen Testlauf: dann laeuft alles nur auf diesem
REM  Rechner, ohne Domain und ohne Server.
REM
REM  Aufruf: Doppelklick, oder in der Eingabeaufforderung  setup.cmd
REM
REM  Absichtlich ohne Umlaute geschrieben - die Eingabeaufforderung stellt sie
REM  je nach Zeichensatz falsch dar.
REM ===========================================================================

echo.
echo  ========================================================
echo    BUYBOT - EINRICHTUNG
echo  ========================================================
echo.

REM ---------------------------------------------------------------------------
REM  Schritt 1 von 6: Voraussetzungen
REM ---------------------------------------------------------------------------
echo  [Schritt 1 von 6] Voraussetzungen pruefen
echo.

where docker >nul 2>&1
if errorlevel 1 (
    echo    FEHLER: Docker Desktop ist nicht installiert.
    echo.
    echo    Hol es dir hier und installiere es:
    echo      https://www.docker.com/products/docker-desktop/
    echo.
    echo    Danach den Rechner neu starten und diese Datei erneut ausfuehren.
    goto :ende
)

docker compose version >nul 2>&1
if errorlevel 1 (
    echo    FEHLER: Deine Docker-Version ist zu alt.
    echo    Bitte Docker Desktop aktualisieren.
    goto :ende
)

docker info >nul 2>&1
if errorlevel 1 (
    echo    FEHLER: Docker Desktop laeuft nicht.
    echo.
    echo    Starte Docker Desktop ueber das Startmenue und warte, bis das
    echo    Wal-Symbol unten rechts ruhig steht ^(nicht mehr blinkt^).
    echo    Danach diese Datei erneut ausfuehren.
    goto :ende
)

echo    OK  Docker laeuft
echo.

REM ---------------------------------------------------------------------------
REM  Schritt 2 von 6: Testlauf oder echter Betrieb
REM ---------------------------------------------------------------------------
echo  [Schritt 2 von 6] Was soll eingerichtet werden?
echo.
echo    [1] Testlauf auf diesem Rechner
echo        Laeuft nur lokal, keine Domain noetig. Zum Ausprobieren.
echo.
echo    [2] Echter Betrieb mit eigener Domain
echo        Nur sinnvoll, wenn dieser Rechner dauerhaft laeuft und
echo        aus dem Internet erreichbar ist.
echo.

set "MODUS="
:frage_modus
set /p "MODUS=   Deine Wahl [1/2]: "
if "!MODUS!"=="1" goto :modus_gewaehlt
if "!MODUS!"=="2" goto :modus_gewaehlt
echo    Bitte 1 oder 2 eingeben.
goto :frage_modus
:modus_gewaehlt
echo.

REM ---------------------------------------------------------------------------
REM  Schritt 3 von 6: Vorhandene Konfiguration sichern
REM ---------------------------------------------------------------------------
echo  [Schritt 3 von 6] Vorhandene Konfiguration
echo.

if exist ".env" (
    for /f "delims=" %%D in ('powershell -NoProfile -Command "Get-Date -Format yyyyMMdd-HHmmss"') do set "STEMPEL=%%D"
    copy /y ".env" ".env.alt-!STEMPEL!" >nul
    echo    Es gab schon eine Konfiguration.
    echo    Sie liegt jetzt als  .env.alt-!STEMPEL!  daneben.
    echo.
    set "WEITER="
    set /p "WEITER=   Neu einrichten? [j/N]: "
    if /i not "!WEITER!"=="j" (
        echo.
        echo    Abgebrochen. Es wurde nichts geaendert.
        goto :ende
    )
    echo.
) else (
    echo    OK  Keine vorhandene Konfiguration, wir fangen frisch an.
    echo.
)

REM ---------------------------------------------------------------------------
REM  Schritt 4 von 6: Angaben abfragen
REM ---------------------------------------------------------------------------
echo  [Schritt 4 von 6] Deine Angaben
echo.
if "!MODUS!"=="1" (
    echo    Woher du die Werte bekommst, steht in ANLEITUNG-LOKAL.md, Schritt 2.
) else (
    echo    Woher du die Werte bekommst, steht in ANLEITUNG-SERVER.md, Teil 3.
)
echo.

if "!MODUS!"=="1" (
    set "DOMAIN=localhost"
    echo    Testlauf: als Adresse wird localhost benutzt.
    echo.
    echo    WICHTIG: In deiner EVE-Anwendung muss dann diese Callback-URL stehen:
    echo      http://localhost:8080/api/auth/callback
    echo.
    echo    EVE erlaubt nur eine Callback-URL je Anwendung. Fuer den Test legst
    echo    du dir also am besten eine zweite EVE-Anwendung an.
    echo.
) else (
    :frage_domain
    set "DOMAIN="
    echo    Deine Domain, ohne https:// davor
    echo    ^(Beispiel: buybot.net^)
    set /p "DOMAIN=   > "
    if "!DOMAIN!"=="" (
        echo    Das Feld darf nicht leer bleiben.
        goto :frage_domain
    )
    echo.
)

:frage_client
set "EVE_CLIENT="
echo    EVE Client ID
echo    ^(Beispiel: a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4^)
set /p "EVE_CLIENT=   > "
if "!EVE_CLIENT!"=="" (
    echo    Das Feld darf nicht leer bleiben.
    goto :frage_client
)
echo.

:frage_secret
set "EVE_SECRET="
echo    EVE Secret Key
echo    ^(Beispiel: xY9zAbCdEfGhIjKlMnOpQrStUvWxYz012345^)
set /p "EVE_SECRET=   > "
if "!EVE_SECRET!"=="" (
    echo    Das Feld darf nicht leer bleiben.
    goto :frage_secret
)
echo.

:frage_corp
set "CORP_ID="
echo    ID deiner Corporation
echo    ^(Beispiel: 98378388 - findest du auf evewho.com^)
set /p "CORP_ID=   > "
if "!CORP_ID!"=="" (
    echo    Das Feld darf nicht leer bleiben.
    goto :frage_corp
)
echo !CORP_ID!| findstr /r "^[0-9][0-9]*$" >nul
if errorlevel 1 (
    echo    Die Corporation-ID muss eine reine Zahl sein.
    goto :frage_corp
)
echo.

:frage_admin
set "ADMIN_CHAR="
echo    Name deines EVE-Charakters
echo    ^(dieser Charakter wird Administrator - Beispiel: Konsti Miner^)
set /p "ADMIN_CHAR=   > "
if "!ADMIN_CHAR!"=="" (
    echo    Das Feld darf nicht leer bleiben.
    goto :frage_admin
)
echo.

REM ---------------------------------------------------------------------------
REM  Schritt 5 von 6: Passwoerter erzeugen und .env schreiben
REM ---------------------------------------------------------------------------
echo  [Schritt 5 von 6] Passwoerter erzeugen und Konfiguration schreiben
echo.

REM Die drei Geheimnisse kommen einzeln aus PowerShell - die Windows-Zufallszahlen
REM aus reinem Batch waeren fuer Schluessel nicht brauchbar.
REM Bewusst je ein eigener Aufruf: eine ueber mehrere Zeilen fortgesetzte
REM PowerShell-Zeile wuerde von cmd zu einem einzigen Argument zusammengezogen.

set "PS=powershell -NoProfile -ExecutionPolicy Bypass -Command"
set "ZUFALL=$b = New-Object byte[] LAENGE; [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b); [Convert]::ToBase64String($b)"

for /f "usebackq delims=" %%A in (`%PS% "!ZUFALL:LAENGE=48! -replace '[+/=]','x'"`) do set "JWT_SECRET=%%A"
for /f "usebackq delims=" %%A in (`%PS% "!ZUFALL:LAENGE=24! -replace '[+/=]','x'"`) do set "DB_PASSWORT=%%A"
for /f "usebackq delims=" %%A in (`%PS% "!ZUFALL:LAENGE=32!"`) do set "AES_KEY=%%A"

if "!JWT_SECRET!"=="" (
    echo    FEHLER: Die Passwoerter konnten nicht erzeugt werden.
    echo    Laeuft PowerShell auf diesem Rechner?
    goto :ende
)

echo    OK  Drei Passwoerter gewuerfelt - du musst sie dir nicht merken

if "!MODUS!"=="1" (
    set "BASIS_URL=http://localhost:8080"
    set "FRONT_URL=http://localhost:4200"
) else (
    set "BASIS_URL=https://!DOMAIN!"
    set "FRONT_URL=https://!DOMAIN!"
)

for /f "delims=" %%D in ('%PS% "Get-Date -Format 'dd.MM.yyyy HH:mm'"') do set "JETZT=%%D"

REM Ab hier wird die .env Zeile fuer Zeile geschrieben. Die Werte stehen in
REM !Variablen! - bei dieser Schreibweise setzt cmd sie erst nach dem Zerlegen
REM der Zeile ein, deshalb stoeren auch & < > | im Secret nicht.
> ".env" echo # Vom Einrichtungsskript erzeugt am !JETZT!
>>".env" echo # Diese Datei enthaelt Passwoerter. Nicht weitergeben, nicht einchecken.
>>".env" echo.
>>".env" echo # --- EVE SSO / ESI ---
>>".env" echo EVE_CLIENT_ID=!EVE_CLIENT!
>>".env" echo EVE_CLIENT_SECRET=!EVE_SECRET!
>>".env" echo EVE_ESI_BASE_URL=https://esi.evetech.net/latest
>>".env" echo EVE_SCOPES="publicData esi-characters.read_corporation_roles.v1 esi-search.search_structures.v1 esi-universe.read_structures.v1 esi-contracts.read_character_contracts.v1 esi-mail.send_mail.v1"
>>".env" echo EVE_ALLOWED_CORP=!CORP_ID!
>>".env" echo.
>>".env" echo # Wer das Admin-Panel oeffnen darf. Weitere durch Komma trennen.
>>".env" echo ADMIN_CHARACTERS=!ADMIN_CHAR!
>>".env" echo.
>>".env" echo # --- Adressen ---
>>".env" echo BASE_URL=!BASIS_URL!
>>".env" echo APP_FRONTEND_URL=!FRONT_URL!
>>".env" echo.
>>".env" echo # --- Automatisch erzeugte Geheimnisse ---
>>".env" echo APP_JWT_SECRET=!JWT_SECRET!
>>".env" echo KEYYY=!AES_KEY!
>>".env" echo POSTGRES_PASSWORD=!DB_PASSWORT!
>>".env" echo.
>>".env" echo # --- Protokoll ---
>>".env" echo AUDIT_RETENTION_DAYS=30
>>".env" echo AUDIT_LOG_READS=false

if not exist ".env" (
    echo    FEHLER: Die Datei .env konnte nicht angelegt werden.
    echo    Liegt dieser Ordner vielleicht schreibgeschuetzt, etwa unter Programme?
    goto :ende
)

echo    OK  Konfiguration liegt in .env
echo.

REM ---------------------------------------------------------------------------
REM  Schritt 6 von 6: Starten
REM ---------------------------------------------------------------------------
echo  [Schritt 6 von 6] Starten
echo.
echo    Der erste Start dauert 10 bis 20 Minuten. Die Anwendung wird gebaut
echo    und die EVE-Item-Datenbank heruntergeladen. Das ist einmalig.
echo.

set "STARTEN="
set /p "STARTEN=   Jetzt starten? [J/n]: "
if /i "!STARTEN!"=="n" (
    echo.
    if "!MODUS!"=="1" (
        echo    Gut. Wenn du soweit bist:  docker compose --profile dev up -d --build
    ) else (
        echo    Gut. Wenn du soweit bist:  docker compose --profile prod up -d --build
    )
    goto :ende
)

echo.
if "!MODUS!"=="1" (
    docker compose --profile dev up -d --build
) else (
    docker compose --profile prod up -d --build
)

if errorlevel 1 (
    echo.
    echo    FEHLER: Der Start ist fehlgeschlagen.
    echo.
    echo    Die haeufigste Ursache ist zu wenig Arbeitsspeicher fuer Docker.
    echo    In Docker Desktop unter Settings ^> Resources auf mindestens
    echo    4 GB stellen und diese Datei erneut ausfuehren.
    goto :ende
)

echo.
echo  ========================================================
echo    FERTIG
echo  ========================================================
echo.

if "!MODUS!"=="1" (
    echo    Die Seite laeuft in ein paar Minuten hier:
    echo      http://localhost:4200
    echo.
    echo    Nachsehen, ob alles laeuft:
    echo      docker compose --profile dev ps
    echo.
    echo    Fehler suchen:
    echo      docker compose --profile dev logs -f backend
    echo.
    echo    Alles wieder anhalten:
    echo      Doppelklick auf stop.cmd
    echo.
    echo    Als Administrator eingetragen: !ADMIN_CHAR!
    echo    Nur dieser Charakter sieht nach dem Login das Admin-Panel.
    echo.
    echo    Naechste Schritte stehen in ANLEITUNG-LOKAL.md, Schritt 4 und 5.
) else (
    echo    Es fehlen noch zwei Dinge, beide in ANLEITUNG-SERVER.md beschrieben:
    echo      1. Die Domain !DOMAIN! auf diesen Rechner zeigen lassen
    echo      2. HTTPS einrichten - Abschnitt "Schloss-Symbol"
    echo.
    echo    In der EVE-Anwendung muss als Callback-URL stehen:
    echo      https://!DOMAIN!/api/auth/callback
    echo.
    echo    Nachsehen, ob alles laeuft:
    echo      docker compose --profile prod ps
)
echo.

:ende
echo.
echo  Fenster kann geschlossen werden.
pause >nul
endlocal
