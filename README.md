# Buybot 3000

Ankaufsrechner für EVE Online. Ein Spieler fügt seine Item-Liste ein, bekommt einen Preis,
erstellt den Vertrag im Spiel — und der Bot prüft den Vertrag automatisch gegen dieselbe
Preismatrix und meldet sich, wenn etwas nicht passt.

## Was es kann

**Für Spieler** — ohne Anmeldung nutzbar
- Item-Liste aus dem EVE-Client einfügen: Inventar, Hangar, Vertrags- oder Fitting-Fenster.
  Doppelte Zeilen werden zusammengezählt, Mengen vor oder hinter dem Namen erkannt.
- Preis je Abgabeort, inklusive Transport- und Sicherheitsgebühr.
- Anleitung zur Vertragserstellung direkt im Anschluss an das Ergebnis.
- Deutsch und Englisch, umschaltbar; `?lang=en` verlinkt direkt die englische Fassung.

**Für den Betreiber** — Admin-Panel, Zugang über EVE-Corp-Titel
- Preisbasis (Jita Buy oder Sell) und Standard-Modifikator.
- Whitelist nach Kategorie, Regeln für einzelne Items, Sperrliste.
  Einzelitem schlägt Kategorie, Sperre schlägt alles.
- Wahlweise Bewertung über den Reprocessing-Wert der Ausbeute statt über den Marktpreis.
- Abgabeorte mit Gebühr je m³ und Sicherheitsgebühr in Prozent.
- Automatische Vertragsprüfung über ESI: falscher Ort, Preisabweichung über Toleranz,
  gesperrte Items, zurückgeforderte Items. Meldung per Discord-Webhook oder EVE-Mail.
- Wartungsmodus, Bot-Sprüche, Protokoll.

## Aufbau

| Teil | Technik |
|---|---|
| Backend | Java 26, Spring Boot 4, PostgreSQL |
| Frontend | Angular 21, Standalone Components |
| Marktdaten | Fuzzwork (gebündelte Jita-Preise) |
| Spieldaten | EVE SDE, beim Start nach PostgreSQL importiert |
| Anmeldung | EVE SSO, Sitzungscookie, Rollen aus Corp-Titeln |

```
src/main/java/com/eve/buy/bot/backend/
  audit/          Protokoll: Filter, Ereignisse, asynchrone Persistenz, Aufbewahrung
  config/         Sicherheit, Fehlerbehandlung, Scheduler, Async
  domain/auth/    EVE-SSO-Login, Tokens, Rollen
  domain/buybot/  Preis-Engine, Parser, Vertragsprüfung, Benachrichtigungen
  domain/character/ Charakter, Corporation, Allianz
  domain/eve/     Zugriff auf die Statikdatenbank
  esi/            ESI-Anbindung
```

## Starten

Entwicklung (Backend, Frontend mit Hot Reload, Datenbank):

```bash
docker compose --profile dev up -d --build
```

Frontend auf http://localhost:4200, Backend auf http://localhost:8080.
Der erste Start dauert länger, weil die EVE-Statikdatenbank importiert wird.

Produktion:

- **[ANLEITUNG-LOKAL.md](ANLEITUNG-LOKAL.md)** — auf dem eigenen Windows-Rechner
  ausprobieren. 30 Minuten, keine Kosten, keine Domain.
- **[ANLEITUNG-SERVER.md](ANLEITUNG-SERVER.md)** — echter Betrieb mit Domain und HTTPS.
  Am Ende ein Abschnitt mit den technischen Einzelheiten.

Beide sind für Leute ohne IT-Hintergrund geschrieben und in sich abgeschlossen.

Kurzform auf einem frischen Linux-Server:

```bash
git clone <REPO> buybot && cd buybot && ./setup.sh
```

Auf Windows (Docker Desktop) stattdessen **Doppelklick auf `setup.cmd`**. Dort gibt es
zusätzlich einen Testlauf-Modus, der alles nur lokal auf `localhost` startet — praktisch,
um vor dem Servermieten zu prüfen, ob die EVE-Zugangsdaten stimmen. Anhalten mit
`stop.cmd`.

Beide Skripte fragen Domain, EVE-Zugangsdaten und Corp-ID ab, erzeugen alle Passwörter
selbst und starten den Stack.

## Konfiguration

Alle Geheimnisse kommen aus der `.env`, Vorlage in [.env.example](.env.example).
Fachliche Einstellungen — Preise, Whitelist, Abgabeorte, Vertragsprüfung — werden zur
Laufzeit im Admin-Panel gepflegt und liegen in der Datenbank.

## Tests

```bash
mvn test                          # Backend, 76 Tests
npm test --prefix frontend        # Frontend, 20 Tests
```

Abgedeckt sind vor allem die Stellen, an denen ISK bewegt wird: Parser, Preis-Engine und
Vertragsprüfung. Dazu Sitzungstoken, Verschlüsselung, Protokollierung und Fehlerbehandlung.

## Protokoll

Jeder schreibende Zugriff, jede Preisanfrage, jede Admin-Änderung und jeder Fehler landet
mit Zeitpunkt, IP-Adresse, Auslöser und Aufruf-ID in der Tabelle `audit_entries` und ist im
Admin-Panel einsehbar. Da die meisten Nutzer nicht angemeldet sind, bekommt jeder Fehler
eine Fehler-ID, die dem Spieler angezeigt wird — damit lässt sich ein gemeldeter Fehler
eindeutig wiederfinden. Einträge enthalten IP-Adressen und werden nach der eingestellten
Frist automatisch gelöscht (Standard 30 Tage).
