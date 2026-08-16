@echo off
setlocal enabledelayedexpansion
cd /d "%~dp0"
title Buybot anhalten

REM ===========================================================================
REM  Haelt den Buybot an.
REM
REM  Die Daten bleiben erhalten - Preise, Regeln und das Protokoll liegen in
REM  der Datenbank und sind nach dem naechsten Start wieder da.
REM ===========================================================================

echo.
echo  ========================================================
echo    BUYBOT ANHALTEN
echo  ========================================================
echo.

docker info >nul 2>&1
if errorlevel 1 (
    echo    Docker Desktop laeuft gar nicht - dann laeuft auch der Buybot nicht.
    echo    Es gibt nichts anzuhalten.
    goto :ende
)

echo    Halte alle Container an...
echo.

docker compose --profile dev down
docker compose --profile prod down

echo.
echo    Fertig. Der Buybot laeuft nicht mehr.
echo.
echo    Deine Daten sind nicht weg: Preise, Regeln und Protokoll liegen in der
echo    Datenbank und sind beim naechsten Start wieder da.
echo.
echo    Wieder starten:
echo      Doppelklick auf setup.cmd, oder
echo      docker compose --profile dev up -d

:ende
echo.
echo  Fenster kann geschlossen werden.
pause >nul
endlocal
