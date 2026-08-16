# Buybot lokal ausprobieren (Windows)

Diese Anleitung bringt den Buybot **auf deinem eigenen PC** zum Laufen. Ohne Server, ohne
Domain, ohne Kosten. Zum Ausprobieren und um zu prüfen, ob deine EVE-Zugangsdaten stimmen,
bevor du Geld für einen Server ausgibst.

**Zeitaufwand:** etwa 30 Minuten, davon 20 Minuten Warten beim ersten Start.
**Kosten:** keine.

Für den echten Betrieb im Internet gibt es eine eigene Anleitung:
**[ANLEITUNG-SERVER.md](ANLEITUNG-SERVER.md)**.

Du musst nichts programmieren. Wenn irgendwo etwas anders aussieht als hier beschrieben:
**nicht raten, nachfragen.**

---

## Was am Ende läuft

Der Buybot im Browser unter `http://localhost:4200`. Du kannst Item-Listen einfügen, Preise
berechnen lassen und den Admin-Bereich einrichten — genau wie später im echten Betrieb.

Nur eines geht lokal nicht: andere Leute können die Seite nicht aufrufen. Sie läuft
ausschließlich auf deinem Rechner.

Du brauchst dafür zwei Dinge:

1. **Docker Desktop** — das Programm, das den Buybot ausführt (Schritt 1)
2. Eine **EVE-Anwendung** — damit der Login funktioniert (Schritt 2)

---

## Schritt 1: Docker Desktop installieren

1. Herunterladen: **https://www.docker.com/products/docker-desktop/**
   Der Knopf heißt *Download for Windows*.
2. Installieren, dabei alle Vorgaben bestätigen.
3. **Rechner neu starten.** Das ist nicht optional — Docker braucht das.
4. Docker Desktop starten. Unten rechts in der Taskleiste erscheint ein **Wal-Symbol**.
   Warte, bis es **ruhig steht** und nicht mehr blinkt oder sich dreht. Das dauert beim
   ersten Mal ein bis zwei Minuten.

> Falls Windows nach *WSL* fragt oder eine Meldung dazu kommt: zustimmen und installieren
> lassen. Danach noch einmal neu starten.

### Arbeitsspeicher prüfen

Der Buybot braucht beim ersten Start etwa **4 GB** Arbeitsspeicher. Wenn dein Rechner
8 GB oder mehr hat, passt das normalerweise von selbst.

Falls der Start später abbricht: In Docker Desktop auf das **Zahnrad** oben rechts →
**Resources**. Wenn es dort einen Regler für *Memory* gibt, auf mindestens 4 GB stellen und
**Apply & Restart** drücken.

---

## Schritt 2: EVE-Anwendung anlegen

Damit sich jemand mit seinem EVE-Account anmelden kann, muss CCP wissen, dass es deine
Seite gibt.

> **Wichtig für den lokalen Test:** EVE erlaubt nur **eine** Callback-Adresse je Anwendung.
> Wenn du später einen Server aufsetzt, brauchst du dafür eine **zweite** EVE-Anwendung —
> die hier ist nur für den Test auf deinem Rechner.

1. Auf **developers.eveonline.com** mit deinem EVE-Account anmelden.
2. **MANAGE APPLICATIONS** → **CREATE NEW APPLICATION**.
3. Ausfüllen:

   | Feld | Was rein muss |
   |---|---|
   | Name | `Buybot Test` |
   | Description | `Lokaler Test` |
   | Connection Type | **Authentication & API Access** |
   | Permissions | die sechs Einträge aus der Liste unten |
   | Callback URL | `http://localhost:8080/api/auth/callback` |

   Die Callback-Adresse muss **zeichengenau** so lauten. `http`, nicht `https`. Ein
   Tippfehler hier führt später zu einer nichtssagenden EVE-Fehlerseite.

4. Diese sechs Berechtigungen auswählen:

   ```
   publicData
   esi-characters.read_corporation_roles.v1
   esi-search.search_structures.v1
   esi-universe.read_structures.v1
   esi-contracts.read_character_contracts.v1
   esi-mail.send_mail.v1
   ```

5. **CREATE APPLICATION**, dann die Anwendung anklicken und **VIEW APPLICATION**.
6. Dort stehen **Client ID** und **Secret Key**. Beide brauchst du im nächsten Schritt —
   das Fenster offen lassen.

### Corporation-ID besorgen

Nur wer in dieser Corporation ist, darf sich anmelden.

Auf **evewho.com** deine Corp suchen und anklicken. Die Nummer steht dann oben in der
Adresszeile des Browsers, zum Beispiel `evewho.com/corporation/98378388` — dann ist
`98378388` deine ID.

---

## Schritt 3: Einrichten und starten

1. Den Buybot-Ordner auf deinen Rechner holen.
   Wenn es eine ZIP ist: **entpacken**, nicht einfach hineinschauen.
2. In den Ordner gehen und **Doppelklick auf `setup.cmd`**.

Ein schwarzes Fenster öffnet sich und führt dich durch fünf Fragen:

| Frage | Deine Antwort |
|---|---|
| Was soll eingerichtet werden? | **1** für Testlauf |
| EVE Client ID | aus Schritt 2 kopieren |
| EVE Secret Key | aus Schritt 2 kopieren |
| ID deiner Corporation | die Nummer von evewho |
| Name deines EVE-Charakters | genau wie im Spiel geschrieben |
| Jetzt starten? | **J** (oder einfach Enter) |

> Der Charaktername entscheidet, **wer das Admin-Panel öffnen darf**. Schreib ihn genau so,
> wie er im Spiel steht — auf Groß- und Kleinschreibung kommt es nicht an, auf Leerzeichen
> und Bindestriche schon.

> **Einfügen im schwarzen Fenster:** Rechtsklick fügt ein. `Strg+V` funktioniert dort je
> nach Windows-Version nicht.

Passwörter musst du dir keine überlegen — das Skript würfelt sie selbst aus und legt sie in
einer Datei namens `.env` ab. Die brauchst du nie anzufassen.

Dann läuft es **10 bis 20 Minuten**. In der Zeit wird die Anwendung gebaut und die komplette
EVE-Item-Datenbank heruntergeladen. Das passiert nur beim ersten Mal.

Solange es läuft, siehst du viele Textzeilen durchlaufen. Das ist normal. **Nicht abbrechen.**

Wenn am Ende `FERTIG` erscheint, ist es geschafft.

---

## Schritt 4: Aufrufen und ausprobieren

Im Browser aufrufen: **http://localhost:4200**

> Beim ersten Aufruf kann es noch eine Minute dauern, bis die Seite erscheint — im
> Hintergrund startet die Anwendung noch. Einmal neu laden hilft.

Du siehst den Buybot in grüner Terminal-Optik. Probier es aus:

1. In das große Textfeld eine Liste tippen, zum Beispiel:

   ```
   Tritanium 1000
   Pyerite 500
   ```

2. **BERECHNEN** drücken.

Beim ersten Versuch steht in der Spalte *Bedarf* wahrscheinlich überall **NICHT GELISTET**.
Das ist richtig so: der Buybot kauft nichts an, was nicht ausdrücklich freigegeben ist. Das
stellst du im nächsten Schritt ein.

---

## Schritt 5: Admin-Bereich einrichten

1. Unten rechts auf **EVE Login** und mit deinem Charakter anmelden.

   > Falls dort kein *EVE Login* steht: Der Link ist im Frontend möglicherweise
   > auskommentiert. Dann rufst du direkt **http://localhost:8080/api/auth/login** auf.

2. Nach der Anmeldung erscheint unten **Admin-Panel** — vorausgesetzt, du hast in Schritt 3
   den Namen dieses Charakters als Administrator eingetragen.
3. Dort stellst du der Reihe nach ein:

   | Bereich | Was du tust |
   |---|---|
   | Betriebszustand | auf AKTIV lassen |
   | Preisbasis | Jita Buy oder Sell, dazu dein Standard-Prozentsatz |
   | Abgabeorte | Ort anlegen, Gebühren eintragen, **Lupe drücken** für die Station-ID |
   | Kategorien-Whitelist | was du überhaupt ankaufst, zum Beispiel `Asteroid` |
   | Einzelitem-Regeln | Ausnahmen und Sperren |
   | Vertragserstellung | auf welchen Charakter die Verträge laufen sollen |
   | Vertragsprüfung | Prüf-Charakter wählen, Toleranz auf **1** setzen |

4. Danach noch einmal berechnen. Jetzt sollte bei den freigegebenen Items **OK** und ein
   Preis stehen.

> Die **Toleranz** nicht auf 0 setzen. Dann fliegt jeder Vertrag raus, der auch nur eine ISK
> abweicht — und das passiert durch Rundung ständig.

> Die **Station-ID** ist wichtig. Ohne sie kann der Bot nicht prüfen, ob ein Vertrag am
> richtigen Ort liegt, und lehnt dann jeden ab. Die Lupe holt sie automatisch, sobald du den
> Ortsnamen eingetippt hast.

---

## Anhalten und wieder starten

**Anhalten:** Doppelklick auf **`stop.cmd`**.

Deine Einstellungen bleiben erhalten — Preise, Regeln und Protokoll liegen in der Datenbank
und sind beim nächsten Start wieder da.

**Wieder starten:** Doppelklick auf `setup.cmd` und bei der Frage nach dem Neu-Einrichten
mit **N** antworten. Oder direkt in der Eingabeaufforderung im Buybot-Ordner:

```
docker compose --profile dev up -d
```

Das geht dann in Sekunden, weil nichts mehr gebaut werden muss.

---

## Wenn etwas nicht klappt

**Das Fenster schließt sich sofort wieder.**
Dann ist Docker Desktop nicht gestartet. Starte es, warte auf das ruhige Wal-Symbol und
versuche es erneut.

**„Docker Desktop laeuft nicht" — obwohl es läuft.**
Das Wal-Symbol dreht sich noch. Eine Minute warten, dann erneut.

**Der Start bricht mit einer Fehlermeldung ab.**
Fast immer zu wenig Arbeitsspeicher. In Docker Desktop → Zahnrad → **Resources** den
Memory-Regler auf mindestens 4 GB, **Apply & Restart**, dann `setup.cmd` erneut.

**„port is already allocated" oder „address already in use".**
Ein anderes Programm belegt einen der Ports 4200, 8080 oder 5434. Meist ein noch laufender
Entwicklungsserver. Beende ihn, oder starte den Rechner neu.

**Die Seite lädt nicht.**
Eingabeaufforderung im Buybot-Ordner öffnen und nachsehen:

```
docker compose --profile dev ps
```

Bei allen Zeilen muss `Up` stehen. Steht irgendwo `Exited`:

```
docker compose --profile dev up -d
```

**Der Login landet auf einer EVE-Fehlerseite.**
Die Callback-Adresse in deiner EVE-Anwendung stimmt nicht. Sie muss für den lokalen Test
exakt `http://localhost:8080/api/auth/callback` lauten — mit `http`, nicht `https`.

**Der Login sagt „Zugriff verweigert".**
Dein Charakter ist nicht in der Corporation, deren ID du eingetippt hast. Prüfen kannst du
die eingetragene ID mit:

```
findstr EVE_ALLOWED_CORP .env
```

**Ich bin angemeldet, sehe aber kein Admin-Panel.**
Dein Charaktername steht nicht in der Admin-Liste. Nachsehen mit:

```
findstr ADMIN_CHARACTERS .env
```

Dort muss der Name genau so stehen wie im Spiel. Korrigieren kannst du ihn direkt in der
Datei `.env` (mit dem Editor öffnen), danach:

```
docker compose --profile dev restart backend
```

Dann einmal ab- und wieder anmelden.

**Irgendwas anderes.**
Die letzten Meldungen der Anwendung holen:

```
docker compose --profile dev logs --tail 50 backend
```

Diesen Text kannst du kopieren (markieren, Enter drücken) und weitergeben — darin steht
meistens genau, was fehlt.

---

## Nützliche Befehle

Alle in der Eingabeaufforderung, im Buybot-Ordner:

| Was du willst | Befehl |
|---|---|
| Nachsehen, was läuft | `docker compose --profile dev ps` |
| Meldungen mitlesen | `docker compose --profile dev logs -f backend` |
| Anhalten | `docker compose --profile dev down` |
| Starten | `docker compose --profile dev up -d` |
| Nach Code-Änderung neu bauen | `docker compose --profile dev up -d --build` |

Mitlesen beendest du mit `Strg+C`.

---

## Wenn der Test geklappt hat

Dann weißt du: deine EVE-Zugangsdaten stimmen, du kennst die Bedienung, und der Buybot
rechnet wie er soll.

Für den echten Betrieb — eigene Domain, aus dem Internet erreichbar, mit Schloss-Symbol —
geht es weiter mit **[ANLEITUNG-SERVER.md](ANLEITUNG-SERVER.md)**.

Denk daran: dort brauchst du eine **zweite EVE-Anwendung** mit der Callback-Adresse deiner
echten Domain. Die aus dieser Anleitung bleibt für lokale Tests bestehen.
