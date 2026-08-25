# Buybot auf einem Server betreiben

Diese Anleitung stellt den Buybot **im Internet** bereit: eigene Domain, für jeden
erreichbar, mit Schloss-Symbol im Browser.

Sie ist für jemanden geschrieben, der **kein IT-Mensch** ist. Du musst nichts programmieren.
Du tippst ein paar Befehle ab und klickst auf ein paar Knöpfe.

**Zeitaufwand:** etwa eine Stunde, davon 20 Minuten Warten.
**Kosten:** rund 7 € im Monat für den Server, dazu etwa 12 € im Jahr für die Domain.

> **Empfehlung:** Probier den Buybot vorher einmal auf deinem eigenen PC aus —
> **[ANLEITUNG-LOKAL.md](ANLEITUNG-LOKAL.md)**, dauert 30 Minuten und kostet nichts. Dann
> weißt du, ob deine EVE-Zugangsdaten stimmen, bevor du einen Server mietest.

Wenn irgendwo etwas anders aussieht als hier beschrieben: **nicht raten, nachfragen.**

---

## Was am Ende läuft

Eine Internetseite, auf der Spieler ihre Items einfügen und einen Ankaufspreis bekommen. Du
selbst meldest dich an und stellst ein, was zu welchem Preis gekauft wird. Verträge im Spiel
prüft der Bot automatisch und meldet sich, wenn etwas nicht passt.

Dafür besorgst du nacheinander drei Dinge:

1. Einen **Server** — ein Computer, der immer läuft (Teil 1)
2. Eine **Domain** — die Adresse, die man in den Browser tippt (Teil 2)
3. Eine **EVE-Anwendung** — damit der Login funktioniert (Teil 3)

Danach wird alles zusammengesteckt (Teil 4 bis 7).

---

## Teil 1: Server mieten

Wir nehmen **Hetzner**. Andere Anbieter gehen auch, aber dann passt die Anleitung nicht mehr.

1. Auf **console.hetzner.cloud** ein Konto anlegen.
2. Oben links auf **New Project**, Name egal — zum Beispiel `Buybot`.
3. Im Projekt auf **Add Server**.
4. Jetzt kommt eine Seite mit vielen Kacheln. Wähle:

   | Auswahl | Was du anklickst |
   |---|---|
   | Location | **Nürnberg** oder **Falkenstein** |
   | Image | **Ubuntu 24.04** |
   | Type | Reiter **Shared vCPU**, dann **CX22** (2 vCPU, 4 GB RAM) |
   | Networking | **IPv4** muss angehakt sein |
   | SSH Keys | überspringen |

   > **Wichtig:** Nimm nichts Kleineres als CX22. Bei weniger Arbeitsspeicher bricht der
   > erste Start ab, und das ist ärgerlich zu suchen.

5. Ganz unten **Create & Buy now**.
6. Nach etwa einer Minute steht der Server in der Liste. Hetzner zeigt dir jetzt ein
   **Passwort** — **schreib es auf**, es wird nur einmal angezeigt.
7. Notiere dir außerdem die **IPv4-Adresse**. Die sieht aus wie `91.99.12.34`.

---

## Teil 2: Domain kaufen

1. Auf **ionos.de** (oder einem anderen Anbieter) eine Domain suchen, zum Beispiel
   `buybot.net`.
2. Kaufen. `.net` und `.com` sind meist günstiger als `.de`.
3. **Noch nichts einstellen** — das kommt in Teil 5.

---

## Teil 3: EVE-Anwendung anlegen

Damit sich Leute mit ihrem EVE-Account anmelden können, muss CCP wissen, dass es deine Seite
gibt.

> Falls du vorher lokal getestet hast: Du brauchst hier eine **zweite, eigene** Anwendung.
> EVE erlaubt nur eine Callback-Adresse je Anwendung, und die unterscheidet sich.

1. Auf **developers.eveonline.com** mit deinem EVE-Account anmelden.
2. **MANAGE APPLICATIONS** → **CREATE NEW APPLICATION**.
3. Ausfüllen:

   | Feld | Was rein muss |
   |---|---|
   | Name | `Buybot` |
   | Description | `Ankaufsrechner` |
   | Connection Type | **Authentication & API Access** |
   | Permissions | die sieben Einträge aus der Liste unten |
   | Callback URL | `https://DEINE-DOMAIN/api/auth/callback` |

   Bei der Callback-Adresse setzt du deine echte Domain ein, also zum Beispiel
   `https://buybot.net/api/auth/callback`. **Zeichengenau und mit `https`** — sonst schlägt
   der Login später mit einer nichtssagenden EVE-Fehlerseite fehl.

4. Diese sieben Berechtigungen auswählen:

   ```
   publicData
   esi-characters.read_corporation_roles.v1
   esi-search.search_structures.v1
   esi-universe.read_structures.v1
   esi-contracts.read_character_contracts.v1
   esi-mail.send_mail.v1
   esi-assets.read_assets.v1
   ```

5. **CREATE APPLICATION**, dann die Anwendung anklicken und **VIEW APPLICATION**.
6. Dort stehen **Client ID** und **Secret Key**. Beide brauchst du in Teil 4 — Fenster offen
   lassen.

### Corporation-ID besorgen

Nur wer in dieser Corporation ist, darf sich anmelden. Auf **evewho.com** deine Corp suchen
und anklicken; die Nummer steht dann in der Adresszeile, zum Beispiel
`evewho.com/corporation/98378388`.

---

## Teil 4: Buybot auf den Server bringen

Jetzt verbindest du dich mit dem Server. Das geht über ein schwarzes Fenster mit Text — das
ist normal und weniger schlimm, als es aussieht.

**Auf Windows:** Startmenü → `cmd` eintippen → Eingabeaufforderung öffnen.
**Auf Mac:** Programme → Dienstprogramme → Terminal.

Tippe (mit deiner Server-IP von vorhin):

```bash
ssh root@91.99.12.34
```

- Bei der Frage `Are you sure you want to continue connecting?` tippst du **yes**.
- Dann das Passwort von Hetzner. **Beim Tippen passiert auf dem Bildschirm nichts** — kein
  Punkt, kein Sternchen. Das ist Absicht. Einfach tippen und Enter drücken.
- Beim ersten Mal willst du vielleicht ein neues Passwort setzen. Mach das und schreib es auf.

Jetzt tippst du die folgenden vier Blöcke **nacheinander** ab. Nach jedem Enter drücken und
warten, bis die Eingabezeile wieder erscheint.

**1. Server aktualisieren** (1–2 Minuten):

```bash
apt update && apt upgrade -y
```

**2. Docker installieren** (1–2 Minuten):

```bash
apt install -y docker.io docker-compose-plugin git
```

**3. Firewall einschalten:**

```bash
ufw allow OpenSSH && ufw allow 80 && ufw allow 443 && ufw --force enable
```

> Dadurch sind nur noch die drei Türen offen, die gebraucht werden. Datenbank und Anwendung
> sind von außen gar nicht erreichbar — alles läuft über den Proxy aus Teil 6.

**4. Buybot holen und einrichten:**

```bash
git clone REPO-ADRESSE buybot && cd buybot && ./setup.sh
```

> `REPO-ADRESSE` ersetzt du durch die Adresse von Github.

Das Skript stellt dir fünf Fragen — Domain, Client ID, Secret Key, Corporation-ID und den
**Namen deines EVE-Charakters**. Alles davon hast du in Teil 2 und 3 besorgt. Passwörter
denkt es sich selbst aus und legt sie in einer Datei namens `.env` ab; die brauchst du nie
anzufassen.

> Der Charaktername entscheidet, **wer das Admin-Panel öffnen darf**. Schreib ihn genau so,
> wie er im Spiel steht. Ohne diesen Eintrag läuft der Buybot, ist aber von niemandem
> einzurichten.

Dann läuft es **10 bis 20 Minuten**. Es baut die Anwendung und lädt die komplette
EVE-Item-Datenbank herunter. Du kannst zusehen oder Kaffee holen — abbrechen solltest du
nicht.

---

## Teil 5: Domain auf den Server zeigen lassen

1. Bei IONOS einloggen → **Domains & SSL** → deine Domain → **DNS**.
2. Einen Eintrag vom Typ **A** anlegen oder den vorhandenen ändern:

   | Feld | Wert |
   |---|---|
   | Typ | A |
   | Host / Name | `@` |
   | Wert / Zeigt auf | deine Server-IP, z. B. `91.99.12.34` |
   | TTL | Standard lassen |

3. Speichern.

Das braucht **bis zu einer Stunde**, bis es überall im Internet angekommen ist. Meistens geht
es schneller. Ob es schon klappt, prüfst du im schwarzen Fenster mit:

```bash
ping DEINE-DOMAIN
```

Wenn dort deine Server-IP auftaucht, ist es soweit. Weiter geht es erst dann — das
Zertifikat in Teil 6 lässt sich sonst nicht ausstellen.

---

## Teil 6: Das Schloss-Symbol (HTTPS)

Ohne diesen Schritt zeigt der Browser deinen Besuchern eine Warnung an. Außerdem funktioniert
der Login erst mit HTTPS richtig.

Die Einstellung dafür läuft über eine eigene Oberfläche, die aus Sicherheitsgründen nicht
öffentlich erreichbar ist. Du holst sie dir über einen Tunnel auf deinen eigenen Rechner.

**Öffne ein zweites schwarzes Fenster** (das erste bleibt offen) und tippe:

```bash
ssh -L 81:localhost:81 root@91.99.12.34
```

Passwort eingeben. Dieses Fenster bleibt jetzt einfach offen — es hält den Tunnel.

Dann im Browser aufrufen: **http://localhost:81**

- Anmelden mit `admin@example.com` und `changeme`
- Es verlangt sofort neue Zugangsdaten — **setze sie und schreib sie auf**
- **Hosts** → **Proxy Hosts** → **Add Proxy Host**

Ausfüllen:

| Feld | Wert |
|---|---|
| Domain Names | deine Domain, z. B. `buybot.net` |
| Scheme | `http` |
| Forward Hostname | `frontend-prod` |
| Forward Port | `80` |
| Block Common Exploits | anhaken |
| Websockets Support | anhaken |

Dann **oben auf den Reiter SSL** wechseln:

| Feld | Wert |
|---|---|
| SSL Certificate | **Request a new SSL Certificate** |
| Force SSL | anhaken |
| HTTP/2 Support | anhaken |
| I Agree to the Let's Encrypt Terms | anhaken |

**Save**. Nach ein paar Sekunden ist das Zertifikat da und erneuert sich künftig von selbst.

Jetzt rufst du deine Domain im Browser auf. Der Buybot sollte erscheinen, mit Schloss-Symbol
in der Adresszeile.

> Klappt das Zertifikat nicht, zeigt der Domain-Eintrag aus Teil 5 noch nicht auf den Server.
> Eine halbe Stunde warten und erneut versuchen.

---

## Teil 7: Einrichten

1. Auf deiner Seite unten rechts auf **EVE Login** und mit deinem Charakter anmelden.

   > Falls dort kein *EVE Login* steht, ist der Link im Frontend auskommentiert. Dann rufst
   > du direkt `https://DEINE-DOMAIN/api/auth/login` auf.

2. Wenn du der in Teil 4 eingetragene Charakter bist, erscheint unten **Admin-Panel**.
3. Dort stellst du der Reihe nach ein:

   | Bereich | Was du tust |
   |---|---|
   | Betriebszustand | auf AKTIV lassen |
   | Preisbasis | Jita Buy oder Sell, dazu dein Standard-Prozentsatz |
   | Abgabeorte | Ort anlegen, Gebühren eintragen, **Lupe drücken** für die Station-ID |
   | Kategorien-Whitelist | was du überhaupt ankaufst, zum Beispiel `Asteroid` |
   | Einzelitem-Regeln | Ausnahmen und Sperren |
   | Vertragserstellung | auf welchen Charakter die Verträge laufen sollen |
   | Vertragsprüfung | Prüf-Charakter wählen, Toleranz auf **1** setzen, Meldeweg wählen |

> Die **Station-ID** ist wichtig. Ohne sie kann der Bot nicht prüfen, ob ein Vertrag am
> richtigen Ort liegt, und lehnt dann jeden ab. Die Lupe holt sie automatisch, sobald der
> Ortsname eingetippt ist.

> Die **Toleranz** nicht auf 0 setzen. Dann fliegt jeder Vertrag raus, der auch nur eine ISK
> abweicht — und das passiert durch Rundung ständig.

> Beim **Meldeweg** hast du die Wahl zwischen Discord-Webhook und EVE-Mail. Mit dem Knopf
> **TESTNACHRICHT** prüfst du sofort, ob es funktioniert, ohne auf einen echten Vertrag zu
> warten.

---

## Wenn etwas nicht klappt

**Die Seite lädt nicht.**
Im schwarzen Fenster nachsehen:

```bash
cd buybot && docker compose --profile prod ps
```

Bei allen Zeilen muss `Up` stehen, beim Backend zusätzlich `(healthy)`. Steht irgendwo
`Exited`:

```bash
docker compose --profile prod up -d
```

**Der Browser warnt vor der Seite.**
Das Zertifikat aus Teil 6 fehlt oder ist nicht ausgestellt worden. Zurück zu Teil 6.

**Der Login landet auf einer EVE-Fehlerseite.**
Die Callback-Adresse in der EVE-Anwendung stimmt nicht mit deiner Domain überein. Sie muss
exakt `https://DEINE-DOMAIN/api/auth/callback` lauten.

**Der Login sagt „Zugriff verweigert".**
Dein Charakter ist nicht in der angegebenen Corporation. Eingetragene ID prüfen:

```bash
grep EVE_ALLOWED_CORP .env
```

**Ich bin angemeldet, sehe aber kein Admin-Panel.**
Dein Charaktername steht nicht in der Admin-Liste. Nachsehen:

```bash
grep ADMIN_CHARACTERS .env
```

Dort muss der Name genau so stehen wie im Spiel. Ändern kannst du ihn mit `nano .env`
(Speichern mit `Strg+O`, Enter, Schließen mit `Strg+X`), danach:

```bash
docker compose --profile prod restart backend
```

Dann einmal ab- und wieder anmelden. Weitere Administratoren trennst du mit Komma:
`ADMIN_CHARACTERS=Konsti Miner,Zweiter Pilot`

**Der erste Start bricht ab.**
Meist zu wenig Arbeitsspeicher. Bei Hetzner den Server auf ein größeres Modell umstellen
(Server anklicken → **Rescale**), dann:

```bash
cd buybot && docker compose --profile prod up -d --build
```

**Irgendwas anderes.**
Die letzten Meldungen holen:

```bash
cd buybot && docker compose --profile prod logs --tail 50 backend
```

Diese Ausgabe kannst du kopieren und weitergeben — darin steht meistens genau, was fehlt.

---

## Der laufende Betrieb

**Kurz pausieren**, zum Beispiel wenn du die Preise umstellst: im Admin-Panel unter
*Betriebszustand* auf **PAUSIERT**. Die Seite zeigt dann einen Hinweis statt des Rechners.
Dafür musst du nichts abschalten und nichts neu starten.

**Sichern** — einmal im Monat ist eine gute Gewohnheit:

```bash
cd buybot && docker compose exec postgres pg_dump -U eve_user eve_own_auth > sicherung_$(date +%F).sql
```

Die Datei liegt dann im Buybot-Ordner. Wer es ernst nimmt, kopiert sie noch weg vom Server.

**Aktualisieren**, wenn es eine neue Version gibt:

```bash
cd buybot && git pull && docker compose --profile prod up -d --build
```

**Nachsehen, wer was gemacht hat:** im Admin-Panel unter *Protokoll*. Dort steht jede
Preisanfrage, jede Änderung und jeder Fehler — mit Zeitpunkt und IP-Adresse. Meldet dir
jemand einen Fehler, kann er die **Fehler-ID** nennen, die ihm angezeigt wurde; danach kannst
du dort direkt suchen.

**Läuft die Vertragsprüfung?** Im Admin-Panel im Bereich *Vertragsprüfung* steht ein
Statuskasten mit letztem und nächstem Lauf. Er aktualisiert sich alle 15 Sekunden — du kannst
also zusehen, wie der Zeitstempel weiterläuft.

---

## Technische Einzelheiten

Dieser Abschnitt ist für den Fall, dass jemand mit IT-Hintergrund nachsehen will. Für den
normalen Betrieb brauchst du ihn nicht.

### Werte in der `.env`

Vom Einrichtungsskript geschrieben, Vorlage in `.env.example`.

| Schlüssel | Bedeutung |
|---|---|
| `EVE_CLIENT_ID` / `EVE_CLIENT_SECRET` | aus der EVE-Anwendung |
| `EVE_SCOPES` | die sechs Berechtigungen aus Teil 3 |
| `EVE_ESI_BASE_URL` | `https://esi.evetech.net/latest` |
| `EVE_ALLOWED_CORP` | Corporation, die sich anmelden darf |
| `ADMIN_CHARACTERS` | wer das Admin-Panel öffnen darf; Namen oder IDs, per Komma getrennt |
| `BASE_URL` | Adresse der Anwendung; entscheidet auch, ob das Sitzungscookie als `Secure` gesetzt wird |
| `APP_FRONTEND_URL` | Ziel nach dem Login, steuert außerdem CORS |
| `APP_JWT_SECRET` | Signaturgeheimnis der Sitzungstokens |
| `KEYYY` | AES-Schlüssel für die ESI-Tokens in der Datenbank |
| `POSTGRES_PASSWORD` | Datenbankpasswort |
| `AUDIT_RETENTION_DAYS` | Aufbewahrung des Protokolls in Tagen, Standard 30 |
| `AUDIT_LOG_READS` | auch erfolgreiche Lesezugriffe protokollieren, Standard `false` |

Die **Discord-Webhook-Adresse** gehört nicht hierher, sondern ins Admin-Panel.

### Zugriffsrechte

Wer sich anmelden darf, steuert `EVE_ALLOWED_CORP`. Wer **verwalten** darf, steuert
`ADMIN_CHARACTERS` — diese Charaktere erhalten bei jeder Anmeldung und bei jedem
Rollen-Sync die Rolle `ROLE_IT_ADMIN`.

Zusätzlich werden EVE-Corp-Titel automatisch in Rollen übersetzt: ein Titel *Director* wird
zu `ROLE_DIRECTOR`. Auch damit kommt man an das Admin-Panel, weil die Rangfolge
`ROLE_IT_ADMIN > ROLE_CEO > ROLE_DIRECTOR` gilt. Verlässlich ist aber nur die Liste in der
`.env` — Titel hat nicht jede Corporation.

Ein von Hand in der Datenbank vergebenes `ROLE_IT_ADMIN` bleibt ebenfalls erhalten: die
Rolle ist in `system_roles` als besonders markiert und übersteht damit die Neuberechnung.
Diesen Eintrag legt die Anwendung beim Start selbst an.

### Netzwerk

Nach außen sind nur die Ports 80 und 443 offen; dahinter sitzt der Nginx Proxy Manager.
Datenbank (5434) und Backend (8080) sind an `127.0.0.1` gebunden und damit nur vom Server
selbst erreichbar. Port 81 — die Proxy-Oberfläche — ist absichtlich nicht in der Firewall
freigegeben und nur über den SSH-Tunnel aus Teil 6 zu erreichen.

### Zustand prüfen

`GET /actuator/health` ist ohne Anmeldung erreichbar und wird vom Docker-Healthcheck des
Backends abgefragt. Deshalb zeigt `docker compose ps` beim Backend `(healthy)`, wenn die
Anwendung wirklich bereit ist — und nicht bloß, dass der Prozess läuft.

### Logausgabe

Geht nach stdout und wird von Docker gesammelt. Jede Zeile trägt Aufruf-ID, IP-Adresse und
Auslöser:

```bash
docker compose --profile prod logs -f backend
```

### Datenschutz

Das Protokoll enthält IP-Adressen und damit personenbezogene Daten. Ein täglicher Lauf löscht
Einträge, die älter sind als `AUDIT_RETENTION_DAYS` (Standard 30 Tage). Wer den Dienst
öffentlich betreibt, sollte das in seiner Datenschutzerklärung erwähnen.

### Altlasten aus früheren Versionen

Die Anwendung war ursprünglich ein größeres Corp-Werkzeug. Bei einer **frisch aufgesetzten**
Datenbank entstehen nur noch die Tabellen, die der Buybot benutzt — hier ist nichts zu tun.

Zieht dagegen eine gewachsene Datenbank mit um, liegen dort noch ungenutzte Tabellen. Nach
einer Sicherung räumt das folgende Skript sie weg, inklusive der nicht gebrauchten Teile der
EVE-Statikdatenbank:

```bash
docker compose exec -T postgres psql -U eve_user -d eve_own_auth -f - < SQL/cleanup_legacy_tables.sql
```
