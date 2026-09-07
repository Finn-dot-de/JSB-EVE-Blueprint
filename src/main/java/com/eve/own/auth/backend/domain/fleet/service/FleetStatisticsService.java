package com.eve.own.auth.backend.domain.fleet.service;

import com.eve.own.auth.backend.common.AccessRules;
import com.eve.own.auth.backend.domain.auth.SystemRoles;
import com.eve.own.auth.backend.domain.character.entity.Character;
import com.eve.own.auth.backend.domain.character.repository.CharacterRepository;
import com.eve.own.auth.backend.domain.fleet.TrackingType;
import com.eve.own.auth.backend.domain.fleet.dto.FleetStatisticsDtos.AccountZeile;
import com.eve.own.auth.backend.domain.fleet.dto.FleetStatisticsDtos.Anteil;
import com.eve.own.auth.backend.domain.fleet.dto.FleetStatisticsDtos.DoktrinAngabe;
import com.eve.own.auth.backend.domain.fleet.dto.FleetStatisticsDtos.FatStatistik;
import com.eve.own.auth.backend.domain.fleet.dto.FleetStatisticsDtos.Kopfzeile;
import com.eve.own.auth.backend.domain.fleet.dto.FleetStatisticsDtos.Teilnahme;
import com.eve.own.auth.backend.domain.fleet.dto.FleetStatisticsDtos.Zeitraum;
import com.eve.own.auth.backend.domain.fleet.repository.FleetStatisticsQueryRepository;
import jakarta.persistence.Tuple;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Die FAT-Statistik: wer im gewaehlten Zeitraum bei wie vielen Flotten dabei
 * war.
 *
 * <h2>Wofuer diese Seite da ist</h2>
 * <p>Fuer genau eine Frage - die Teilnahme. Sie zaehlt, und sie bewertet
 * nicht: keine Rangfolge, keine Aussage darueber, wer zu viel traegt oder wer
 * wegrutscht. Die Kopfzahlen (Flotten, Accounts, Charaktere, FCs, Spanne)
 * gehoeren dazu, weil sie der Nenner sind, gegen den eine Teilnahmezahl
 * ueberhaupt erst lesbar wird: "4 Flotten" heisst etwas anderes bei 6 als bei
 * 60.</p>
 *
 * <h2>Gezaehlt wird je Account, nicht je Charakter</h2>
 * <p>Wer drei Alts in dieselbe Flotte bringt, hat <em>einmal</em>
 * teilgenommen. Sonst misst die Tafel Multiboxing statt Beteiligung, und in
 * einer Corporation, in der beim Mining ohnehin multiboxt wird, waere die
 * Rangfolge schlicht die Anzahl der Bildschirme. Der Schluessel ist der Main
 * aus {@code characters.main_character_id}, mit Rueckfall auf den Charakter
 * selbst - siehe {@link FleetStatisticsQueryRepository#skelett}.</p>
 *
 * <p>Der Preis dieser Gruppierung steht mit auf der Seite: Sie ist nur so gut
 * wie die CharLink-Daten. {@link Teilnahme#ohneVerbindung()} sagt, wie viele
 * Accounts das Auth als einzelnen Charakter kennt - die Obergrenze dessen, was
 * hier noch auseinanderfallen kann.</p>
 *
 * <h2>Die Falle, die dieses Projekt zweimal getroffen hat</h2>
 * <p>Bei duenner Datenlage luegen Prozentzahlen: "100 % Teilnahme" bei einer
 * einzigen Flotte ist keine Aussage. Deshalb traegt jede Quote ihre Fallzahl
 * als {@link Anteil} mit sich, und unterhalb von
 * {@link #MIN_FLOTTEN_FUER_AUSWERTUNG} Flotten <em>reiht</em> die Seite nicht,
 * sondern sagt, dass die Grundlage zu duenn ist. Die Schwelle ist kein
 * Anzeigefilter, sondern ein Textwechsel - eine leere Seite saehe aus wie ein
 * Fehler.</p>
 *
 * <h2>Warum die Rechtepruefung hier steht und nicht nur am Endpunkt</h2>
 * <p>Am Controller haengt {@code @PreAuthorize(AccessRules.FLEET_STAFF)}, und
 * das bleibt. Die Annotation gehoert aber zu <em>einem</em> Einstiegspunkt:
 * sie faellt bei einem Umbau lautlos weg und greift gar nicht, wenn ein
 * anderer Dienst diese Methode direkt ruft. Dieselbe Ueberlegung wie im
 * {@link FleetPingService} - und hier haengt dran, dass eine Liste mit den
 * Namen aller Piloten samt ihrer Teilnahmezahl herausgeht.</p>
 */
@Service
public class FleetStatisticsService {

    /**
     * Wer die Statistik sehen darf - dieselben drei Namen wie in
     * {@link AccessRules#FLEET_STAFF} und im {@link FleetPingService}.
     *
     * <p>{@code ROLE_1337} und {@code ROLE_A38} stehen als Zeichenkette da und
     * nicht als Konstante aus {@link SystemRoles}: die beiden entstehen aus
     * Ingame-Titeln, und {@link SystemRoles} fuehrt nur die Rollen, die die
     * Anwendung selbst vergibt.</p>
     *
     * <p>Wer den Kreis in {@link AccessRules#FLEET_STAFF} aendert, muss ihn
     * hier mitaendern. <b>Nicht</b> uebernommen ist die gleichnamige Liste im
     * Frontend-Router ({@code app.routes.ts}), die fuenf Rollen fuehrt: Ein
     * CEO kaeme damit auf die Seite und bekaeme hier ein 403. Massgeblich ist
     * der Server.</p>
     */
    private static final Set<String> FLEET_STAFF_ROLLEN =
            Set.of(SystemRoles.DIRECTOR, "ROLE_1337", "ROLE_A38");

    // ==================================================================
    // Zeitraum
    // ==================================================================

    /**
     * EVE-Zeit. <b>Jedes</b> ausgegebene Datum geht ueber diesen Versatz.
     *
     * <p>Die Serverzeit zu nehmen waere der lautloseste Fehler dieser Seite:
     * Ein Server in Berlin verschoebe im Sommer jede Flotte um zwei Stunden,
     * und eine Flotte um 23:30 UTC am Donnerstag traege als Datum bereits den
     * Freitag. Der FC plant aber in EVE-Zeit.</p>
     */
    private static final ZoneOffset EVE_ZEIT = ZoneOffset.UTC;

    /**
     * Vorgabe-Zeitraum in Tagen.
     *
     * <p>90 Tage sind ein Quartal - der Takt, in dem sich Doktrin,
     * Heimatsystem und Prime-Time tatsaechlich aendern. Runtergedreht stehen
     * in der Tafel Teilnahmezahlen aus so wenigen Flotten, dass ein einziger
     * Urlaub sie halbiert; hochgedreht steht ein Pilot mit Zahlen aus einer
     * Zeit oben, in der die Corporation eine andere war.</p>
     */
    public static final int TAGE_VORGABE = 90;

    /**
     * Waehlbare Zeitraeume.
     *
     * <p>Ein "seit Anbeginn" fehlt bewusst: Die Zahl wuerde mit jedem Monat
     * traeger - ein Pilot, der vor zwei Jahren 40 Flotten flog, stuende ewig
     * oben - und die Abfrage wuechse unbegrenzt. Wer die Gesamthistorie will,
     * will einen Export und nicht diese Seite.</p>
     */
    public static final List<Integer> TAGE_ERLAUBT = List.of(30, 90, 180);

    // ==================================================================
    // Schwellen
    // ==================================================================

    /**
     * Unter so vielen Flotten im Fenster zeigt die Seite nur die Kopfzeile mit
     * absoluten Zahlen und einen Satz, warum sonst nichts dasteht.
     *
     * <p>Runtergedreht steht die Tafel ohne Vorbehalt da, obwohl ihre Zahlen
     * aus zwei oder drei Flotten stammen - genau die erfundene Genauigkeit,
     * die schlimmer ist als eine ehrliche Luecke. Hochgedreht bleibt die Seite
     * bei einer jungen Corporation laenger vorsichtig, sagt dabei aber
     * weiterhin, ab wann sie es nicht mehr ist.</p>
     */
    static final int MIN_FLOTTEN_FUER_AUSWERTUNG = 5;

    /**
     * Ab so viel fehlender Doktrin-Angabe wird gar keine genannt.
     *
     * <p>Eine "haeufigste Doktrin" aus einer Minderheit ausgefuellter Felder
     * ist eine Behauptung ueber die Mehrheit, ueber die nichts bekannt ist.</p>
     */
    static final int MAX_OHNE_DOKTRIN_PROZENT = 40;

    /**
     * Ab so vielen Zeilen im Skelett wird das Fenster auf
     * {@link #NOTFENSTER_TAGE} verkuerzt - und das im Ergebnis gesagt.
     *
     * <p>Die erwartete Groessenordnung liegt bei drei Flotten die Woche und
     * zehn bis dreissig Teilnehmern bei 400 bis 1200 Zeilen, bei einer sehr
     * aktiven Corporation mit 200 Flotten im Quartal bei rund 10 000. Diese
     * Grenze greift also nur, wenn etwas grundlegend anders ist als
     * angenommen. Ein stilles Abschneiden waere die schlechtere Luege.</p>
     */
    static final long MAX_SKELETT_ZEILEN = 100_000L;

    /** Auf so viele Tage wird verkuerzt, wenn {@link #MAX_SKELETT_ZEILEN} reisst. */
    static final int NOTFENSTER_TAGE = 30;

    /** Fuer die Saetze im Ergebnis - Datum in EVE-Zeit, ohne Uhrzeit. */
    private static final DateTimeFormatter DATUM =
            DateTimeFormatter.ofPattern("dd.MM.yyyy").withZone(EVE_ZEIT);

    private final FleetStatisticsQueryRepository queryRepo;
    private final CharacterRepository characterRepo;

    public FleetStatisticsService(FleetStatisticsQueryRepository queryRepo,
                                  CharacterRepository characterRepo) {
        this.queryRepo = queryRepo;
        this.characterRepo = characterRepo;
    }

    // ==================================================================
    // Einstieg
    // ==================================================================

    /**
     * Die ganze Auswertung.
     *
     * @param actorId der anfragende Charakter - er muss zur Flottenfuehrung gehoeren
     * @param tage 30, 90 oder 180; {@code null} bedeutet {@link #TAGE_VORGABE}
     * @throws AccessDeniedException wenn der Anfragende keine FC-Rolle traegt
     * @throws IllegalArgumentException bei einem nicht waehlbaren Zeitraum
     */
    @Transactional(readOnly = true)
    public FatStatistik statistik(Long actorId, Integer tage) {
        requireFleetStaff(actorId);

        int gewaehlt = geprueftesFenster(tage);
        Instant bis = Instant.now();
        Instant angefragtesVon = bis.minus(gewaehlt, ChronoUnit.DAYS);

        FleetStatisticsQueryRepository.Vorab vorab = queryRepo.vorab(angefragtesVon);

        // Die Sicherung greift vor dem Laden, nicht danach: Sonst faellt die
        // Menge erst beim Materialisieren auf - dann ist sie schon im Speicher.
        boolean gekuerzt = vorab.skelettZeilen() > MAX_SKELETT_ZEILEN;
        int ausgewertet = gekuerzt ? NOTFENSTER_TAGE : gewaehlt;
        Instant von = gekuerzt ? bis.minus(NOTFENSTER_TAGE, ChronoUnit.DAYS) : angefragtesVon;

        List<Flotte> flotten = ladeFlotten(von);
        Zeitraum zeitraum = zeitraum(gewaehlt, ausgewertet, von, bis,
                vorab.ersteFlotteInsgesamt(), gekuerzt);

        return zusammensetzen(flotten, zeitraum);
    }

    // ==================================================================
    // Rechtepruefung
    // ==================================================================

    /**
     * Stellt sicher, dass der Anfragende zur Flottenfuehrung gehoert.
     *
     * <p>Geprueft wird am Rollensatz der Entitaet und nicht am
     * Sicherheitskontext - dasselbe Vorgehen wie in {@link FleetPingService},
     * {@code RoleAssignmentService} und {@code MiningAdminGuard}.</p>
     */
    private void requireFleetStaff(Long actorId) {
        Character actor = characterRepo.findById(actorId).orElseThrow(
                () -> new IllegalArgumentException("Charakter " + actorId + " ist unbekannt."));

        if (FLEET_STAFF_ROLLEN.stream().noneMatch(actor::hasRole)) {
            // Dieselbe Ausnahme wie bei einer abgewiesenen @PreAuthorize, damit
            // ApiExceptionHandler daraus ein 403 macht und kein 500.
            throw new AccessDeniedException(
                    "Die FAT-Statistik sehen nur FCs und Direktoren.");
        }
    }

    /**
     * Nimmt nur die waehlbaren Zeitraeume an.
     *
     * <p>Ein unbekannter Wert wird abgewiesen und nicht still durch die
     * Vorgabe ersetzt: Sonst stuende in der Kopfzeile "90 Tage", waehrend der
     * Leser 45 angefragt hat.</p>
     */
    private static int geprueftesFenster(Integer tage) {
        if (tage == null) {
            return TAGE_VORGABE;
        }
        if (!TAGE_ERLAUBT.contains(tage)) {
            throw new IllegalArgumentException("Waehlbar sind " + TAGE_ERLAUBT
                    + " Tage, nicht " + tage + ".");
        }
        return tage;
    }

    // ==================================================================
    // Laden
    // ==================================================================

    /**
     * Eine Teilnahme, wie sie in die Auswertung eingeht.
     *
     * @param attendanceId nur zum Entdoppeln - siehe {@link #ladeFlotten}
     * @param accountId der Main dieses Charakters, sonst er selbst. Der
     *     Rueckfall ist gewollt: Wer nicht in {@code characters} steht - Blues
     *     und Fremde ueber den Link - hat keinen Main und bleibt seine eigene
     *     Zeile.
     */
    private record TeilnahmeZeile(long attendanceId, Long characterId, String characterName,
                                  Long accountId) {}

    private record Flotte(long id, Instant start, Long fcId, String fcName,
                          boolean live, String doktrin, List<TeilnahmeZeile> teilnehmer) {}

    /**
     * Baut aus dem Skelett die Flotten samt Teilnehmern.
     *
     * <p>Entdoppelt dabei je (Flotte, Charakter). {@code fleet_attendance} hat
     * keinen Eindeutigkeitsindex auf dieses Paar - die Eindeutigkeit haengt
     * allein daran, dass vor dem Schreiben gesucht wird, und der
     * 60-Sekunden-Scheduler kann sich mit einem manuellen Abgleich
     * ueberschneiden. Ohne diese Zeilen zaehlte ein doppelt erfasster Pilot
     * doppelt, und die Teilnehmerzahl einer Flotte waere groesser als ihre
     * Teilnehmerliste.</p>
     *
     * <p>Gewinnt die hoehere {@code attendanceId}: Das ist die zuletzt
     * angelegte Zeile und traegt damit denselben Namen, den auch die
     * Teilnehmerliste des FLEETS-Reiters zeigt.</p>
     */
    private List<Flotte> ladeFlotten(Instant von) {
        Map<Long, Instant> start = new LinkedHashMap<>();
        Map<Long, Object[]> kopf = new LinkedHashMap<>();
        Map<Long, Map<Long, TeilnahmeZeile>> teilnehmer = new LinkedHashMap<>();

        for (Tuple zeile : queryRepo.skelett(von)) {
            long fleetId = FleetStatisticsQueryRepository.zahl(zeile.get("fleetId"));
            Instant beginn = FleetStatisticsQueryRepository.zeitpunkt(zeile.get("startTime"));
            if (beginn == null) {
                // Kann nur aus einem Altbestand kommen. Eine Flotte ohne
                // Startzeit hat keinen Platz in der Spalte "erste"/"letzte" -
                // sie hier durchzulassen hiesse, sie spaeter an mehreren
                // Stellen abzufangen und an einer davon zu vergessen.
                continue;
            }
            start.putIfAbsent(fleetId, beginn);
            kopf.putIfAbsent(fleetId, new Object[]{
                    FleetStatisticsQueryRepository.id(zeile.get("fcCharacterId")),
                    FleetStatisticsQueryRepository.text(zeile.get("fcCharacterName")),
                    FleetStatisticsQueryRepository.text(zeile.get("trackingType")),
                    FleetStatisticsQueryRepository.text(zeile.get("doctrine"))});
            teilnehmer.computeIfAbsent(fleetId, k -> new LinkedHashMap<>());

            Long characterId = FleetStatisticsQueryRepository.id(zeile.get("characterId"));
            if (characterId == null) {
                // Eine Flotte ganz ohne Teilnehmer. Sie ist trotzdem gefahren
                // worden und zaehlt in die Flottenzahl der Kopfzeile; ohne den
                // LEFT JOIN waere sie unsichtbar, und der Nenner, gegen den
                // jede Teilnahmezahl gelesen wird, waere zu klein.
                continue;
            }
            Long accountId = FleetStatisticsQueryRepository.id(zeile.get("accountId"));
            TeilnahmeZeile neu = new TeilnahmeZeile(
                    FleetStatisticsQueryRepository.zahl(zeile.get("attendanceId")),
                    characterId,
                    FleetStatisticsQueryRepository.text(zeile.get("characterName")),
                    // Der Rueckfall noch einmal in Java: Der COALESCE der
                    // Abfrage kann nur greifen, wenn a.character_id gesetzt
                    // ist. Faellt die Spalte aus irgendeinem Grund leer aus,
                    // ist ein Charakter unter seiner eigenen Kennung immer
                    // noch richtig gezaehlt - unter "null" waeren alle
                    // Unbekannten ein einziger Account.
                    accountId != null ? accountId : characterId);
            teilnehmer.get(fleetId).merge(characterId, neu,
                    (alt, jung) -> jung.attendanceId() >= alt.attendanceId() ? jung : alt);
        }

        List<Flotte> flotten = new ArrayList<>();
        for (Map.Entry<Long, Object[]> eintrag : kopf.entrySet()) {
            long fleetId = eintrag.getKey();
            Object[] werte = eintrag.getValue();
            flotten.add(new Flotte(fleetId, start.get(fleetId),
                    (Long) werte[0], (String) werte[1],
                    TrackingType.LIVE.matches((String) werte[2]), (String) werte[3],
                    List.copyOf(teilnehmer.get(fleetId).values())));
        }
        flotten.sort(Comparator.comparing(Flotte::start,
                Comparator.nullsLast(Comparator.naturalOrder())));
        return flotten;
    }

    // ==================================================================
    // Zusammensetzen
    // ==================================================================

    private FatStatistik zusammensetzen(List<Flotte> flotten, Zeitraum zeitraum) {
        long anzahl = flotten.size();
        Kopfzeile kopf = kopfzeile(flotten);
        // Einmal gebaut und in beide Zweige gereicht: Die Tafel schlaegt Namen
        // in der Datenbank nach, und zweimal gerufen liefe diese Abfrage
        // zweimal - fuer dasselbe Ergebnis.
        Teilnahme teilnahme = teilnahme(flotten);

        if (anzahl < MIN_FLOTTEN_FUER_AUSWERTUNG) {
            // Die Tafel wird trotzdem gefuellt - sie zaehlt nur. Was fehlt,
            // ist die Zusage, dass ihre Zahlen etwas bedeuten; genau das sagt
            // dieser Satz. Die Kopfzeile bleibt ebenfalls: Sie besteht aus
            // absoluten Zahlen und behauptet nichts.
            String satz = anzahl == 0
                    ? "Im gewaehlten Zeitraum liegt keine Flotte. Es gibt nichts auszuwerten - "
                            + "das ist keine Aussage ueber die Corporation, sondern ueber die Daten."
                    : anzahl + " Flotten seit dem " + DATUM.format(flotten.getFirst().start())
                            + " - zu wenig fuer eine Auswertung. Ab "
                            + MIN_FLOTTEN_FUER_AUSWERTUNG
                            + " Flotten sagt eine Teilnahmezahl etwas ueber den Piloten "
                            + "und nicht ueber den Zufall.";
            return new FatStatistik(zeitraum, kopf, false, satz, teilnahme);
        }

        return new FatStatistik(zeitraum, kopf, true, null, teilnahme);
    }

    private Zeitraum zeitraum(int gewaehlt, int ausgewertet, Instant von, Instant bis,
                              Instant ersteInsgesamt, boolean gekuerzt) {
        Integer tageMitDaten = null;
        String hinweis = null;

        if (ersteInsgesamt == null) {
            hinweis = "Es ist noch nie eine Flotte erfasst worden.";
        } else if (ersteInsgesamt.isAfter(von)) {
            // Die ehrliche Klammer: Ein Nenner, den es nicht gibt, wird nicht
            // behauptet. "90 Tage" sind sonst 90 Tage, von denen 67 leer sind.
            long tage = Duration.between(ersteInsgesamt, bis).toDays();
            tageMitDaten = (int) tage;
            hinweis = ausgewertet + " Tage gewaehlt - Daten reichen " + tage
                    + " Tage zurueck (erste Flotte " + DATUM.format(ersteInsgesamt) + ").";
        } else {
            tageMitDaten = ausgewertet;
        }

        if (gekuerzt) {
            String kuerzung = "Der Zeitraum wurde auf " + NOTFENSTER_TAGE
                    + " Tage verkuerzt: Im gewaehlten Fenster liegen mehr als "
                    + MAX_SKELETT_ZEILEN + " Teilnahmezeilen.";
            hinweis = hinweis == null ? kuerzung : kuerzung + " " + hinweis;
        }
        return new Zeitraum(gewaehlt, ausgewertet, von, bis, ersteInsgesamt,
                tageMitDaten, gekuerzt, hinweis);
    }

    // ==================================================================
    // Kopfzeile
    // ==================================================================

    /**
     * Die Kopfzahlen - und beide Einheiten nebeneinander.
     *
     * <p>Accounts <em>und</em> Charaktere, weil die Tafel darunter je Account
     * zaehlt: Stuende hier weiterhin nur eine Zahl mit der Beschriftung
     * "Piloten", waere sie nach dem Umbau schlicht falsch. Der Abstand
     * zwischen den beiden ist ausserdem die einzige Stelle, an der ueberhaupt
     * sichtbar wird, wie stark hier multiboxt wird.</p>
     */
    private Kopfzeile kopfzeile(List<Flotte> flotten) {
        Set<Long> accounts = new HashSet<>();
        Set<Long> charaktere = new HashSet<>();
        Set<Long> fcs = new HashSet<>();
        long live = 0;
        for (Flotte f : flotten) {
            if (f.fcId() != null) {
                fcs.add(f.fcId());
            }
            if (f.live()) {
                live++;
            }
            f.teilnehmer().forEach(t -> {
                accounts.add(t.accountId());
                charaktere.add(t.characterId());
            });
        }
        return new Kopfzeile(flotten.size(), accounts.size(), charaktere.size(), fcs.size(),
                new Anteil(live, flotten.size()), doktrin(flotten));
    }

    /**
     * Die haeufigste Doktrin-<em>Angabe</em> - nicht die haeufigste Doktrin.
     *
     * <p>Der Unterschied ist der Grund, warum das eine Zeile in der Kopfzeile
     * ist und kein eigener Abschnitt: {@code doctrine} sagt, wie der FC die
     * Flotte <em>genannt</em> hat, nicht was geflogen wurde. Und es ist ein
     * ungepruefter Freitext aus dem Anlege-Formular - "Ferox", "ferox" und
     * "Ferox/Eagle" waeren darin drei Doktrinen.</p>
     */
    private static DoktrinAngabe doktrin(List<Flotte> flotten) {
        Map<String, String> schreibweise = new LinkedHashMap<>();
        Map<String, Long> zaehlung = new LinkedHashMap<>();
        long ohneAngabe = 0;

        for (Flotte f : flotten) {
            String angabe = f.doktrin();
            if (angabe == null || angabe.isBlank()) {
                ohneAngabe++;
                continue;
            }
            String schluessel = angabe.trim().toLowerCase(Locale.ROOT);
            schreibweise.putIfAbsent(schluessel, angabe.trim());
            zaehlung.merge(schluessel, 1L, Long::sum);
        }

        if (flotten.isEmpty()) {
            return new DoktrinAngabe(false, null, Anteil.LEER, 0,
                    "Keine Flotte im Zeitraum.");
        }
        if (ohneAngabe * 100 > (long) MAX_OHNE_DOKTRIN_PROZENT * flotten.size()) {
            return new DoktrinAngabe(false, null, Anteil.LEER, ohneAngabe,
                    ohneAngabe + " von " + flotten.size()
                            + " Flotten tragen keine Doktrin-Angabe - eine Rangfolge "
                            + "waere hier geraten.");
        }
        if (zaehlung.isEmpty()) {
            return new DoktrinAngabe(false, null, Anteil.LEER, ohneAngabe,
                    "Keine Flotte traegt eine Doktrin-Angabe.");
        }
        Map.Entry<String, Long> haeufigste = zaehlung.entrySet().stream()
                .max(Comparator.comparingLong(Map.Entry<String, Long>::getValue)
                        .thenComparing(Map.Entry::getKey, Comparator.reverseOrder()))
                .orElseThrow();
        return new DoktrinAngabe(true, schreibweise.get(haeufigste.getKey()),
                new Anteil(haeufigste.getValue(), flotten.size()), ohneAngabe,
                "Freitext aus dem Anlege-Formular, nur zusammengefasst nach "
                        + "Gross- und Kleinschreibung.");
    }

    // ==================================================================
    // Teilnahme je Account
    // ==================================================================

    /**
     * Was das Auth ueber einen Account weiss.
     *
     * @param charaktere wie viele Charaktere ihm zugeordnet sind - auch die,
     *     die nie geflogen sind. Weniger als zwei heisst: keine Verknuepfung.
     */
    private record AccountInfo(String mainName, long charaktere) {}

    /**
     * Die Tafel: welcher <b>Account</b> war bei wie vielen Flotten dabei.
     *
     * <p>Ohne Quote. Ein Pilot, der vor drei Wochen beigetreten ist, kann
     * keine 40 Flotten haben; jeder Nenner waere entweder unfair (die
     * Gesamtzahl des Fensters) oder zirkulaer (ab seiner ersten Flotte).
     * Absolute Zahl plus erste und letzte Flotte - dann sieht der Leser in
     * einer Sekunde selbst, ob "4" wenig ist oder alles, was in zwei Wochen
     * ging.</p>
     */
    private Teilnahme teilnahme(List<Flotte> flotten) {
        // Die Charaktere haengen an ihrer KENNUNG, nicht am Namen. Der
        // ESI-Abgleich traegt zuerst "Unknown Pilot <id>" ein und schiebt den
        // echten Namen spaeter nach; derselbe Charakter steht dadurch in
        // verschiedenen Flotten unter verschiedenen Namen. Eine Menge von
        // NAMEN listete ihn deshalb zweimal - im echten Bestand betrifft das
        // zwei von vier Charakteren. Gezaehlt wird die Kennung, angezeigt der
        // beste bekannte Name.
        record Bau(Set<Long> flotten, Map<Long, String> charaktere,
                   Instant erste, Instant letzte) {}
        Map<Long, Bau> jeAccount = new LinkedHashMap<>();

        for (Flotte f : flotten) {
            for (TeilnahmeZeile t : f.teilnehmer()) {
                jeAccount.compute(t.accountId(), (id, alt) -> {
                    Bau bau = alt != null ? alt
                            : new Bau(new LinkedHashSet<>(), new LinkedHashMap<>(),
                                    f.start(), f.start());
                    // ***Der ganze Punkt dieser Seite***: die Flotten-IDs
                    // liegen in einer MENGE. Drei Alts desselben Mains in
                    // derselben Flotte legen dreimal dieselbe ID hinein und
                    // ergeben EINEN FAT. Wer das zu einem Zaehler
                    // "optimiert", misst wieder die Zahl der Bildschirme -
                    // und in einer Corporation, die beim Mining ohnehin
                    // multiboxt, waere die Rangfolge genau das.
                    bau.flotten().add(f.id());
                    bau.charaktere().merge(t.characterId(), t.characterName(),
                            FleetStatisticsService::bessererName);
                    return alt == null ? bau
                            : new Bau(bau.flotten(), bau.charaktere(),
                                    frueher(bau.erste(), f.start()),
                                    spaeter(bau.letzte(), f.start()));
                });
            }
        }

        Map<Long, AccountInfo> bekannt = accountInfos(jeAccount.keySet());

        List<AccountZeile> alle = jeAccount.entrySet().stream()
                .map(e -> zeile(e.getKey(), e.getValue().flotten().size(),
                        new LinkedHashSet<>(e.getValue().charaktere().values()),
                        e.getValue().erste(), e.getValue().letzte(),
                        bekannt.get(e.getKey())))
                // Nach Namen: Die Vorgabesortierung entscheidet, ob die Tabelle
                // ein Nachschlagewerk ist oder eine Bestenliste - und eine
                // Bestenliste hat immer ein unteres Ende.
                .sorted(Comparator.comparing(z -> nameOderLeer(z.name())))
                .toList();

        long ohneVerbindung = alle.stream().filter(z -> !z.verbunden()).count();
        return new Teilnahme(
                alle.stream().filter(z -> z.flotten() > 1).toList(),
                alle.stream().filter(z -> z.flotten() == 1).toList(),
                new Anteil(ohneVerbindung, alle.size()),
                verbindungsHinweis(ohneVerbindung, alle.size()));
    }

    /**
     * Eine fertige Zeile - und dabei die Frage, welcher Name dasteht.
     *
     * <p>Der des Mains, und der kommt aus {@code characters}. Ihn aus den
     * Teilnahmezeilen zu nehmen waere der naheliegende Fehler: Der Main muss
     * ueberhaupt nicht mitgeflogen sein - dann stuende in der Tafel der Name
     * des Alts, der zufaellig zuerst beigetreten ist, und der Direktor sucht
     * einen Piloten, den er unter diesem Namen nicht kennt.</p>
     *
     * <p>Kennt das Auth den Account gar nicht, bleibt nur der geflogene Name.
     * Das ist der Gast ueber den Link, und "Unknown Pilot 123" ist immer noch
     * eine bessere Auskunft als eine leere Zelle.</p>
     */
    /**
     * Welcher von zwei Namen desselben Charakters angezeigt wird.
     *
     * <p>Der ESI-Abgleich legt einen Teilnehmer zunaechst als
     * {@code "Unknown Pilot <id>"} an und traegt den echten Namen erst beim
     * naechsten Lauf nach. Beide Schreibweisen stehen dann in verschiedenen
     * Flotten derselben Kennung. Angezeigt wird der echte Name, sobald es ihn
     * gibt - sonst saehe ein Direktor unter einem Account zwei Charaktere, die
     * dieselbe Person sind, und zweifelte an der Zuordnung statt an der
     * Beschriftung.</p>
     */
    static String bessererName(String bisher, String neu) {
        if (istPlatzhalter(bisher) && !istPlatzhalter(neu)) {
            return neu;
        }
        return bisher != null ? bisher : neu;
    }

    /** Der Notname, den der ESI-Abgleich vergibt, bevor er den echten kennt. */
    private static boolean istPlatzhalter(String name) {
        return name == null || name.startsWith("Unknown Pilot");
    }

    private static AccountZeile zeile(Long accountId, long flotten, Set<String> charaktere,
                                      Instant erste, Instant letzte, AccountInfo info) {
        // Sortiert und nicht in Beitrittsreihenfolge: Sonst haengt die
        // Reihenfolge der Namen daran, wer zuerst in die Flotte gewarpt ist,
        // und dieselbe Zeile sieht bei jedem Laden anders aus.
        List<String> namen = charaktere.stream().sorted().toList();
        String name = info != null && info.mainName() != null
                ? info.mainName()
                : (namen.isEmpty() ? null : namen.getFirst());
        return new AccountZeile(accountId, name, namen, flotten, erste, letzte,
                info != null && info.charaktere() > 1);
    }

    /** Namen und Charakterzahl zu den gezaehlten Accounts, aus dem Auth. */
    private Map<Long, AccountInfo> accountInfos(Set<Long> accountIds) {
        Map<Long, AccountInfo> infos = new LinkedHashMap<>();
        for (Tuple zeile : queryRepo.accounts(accountIds)) {
            Long id = FleetStatisticsQueryRepository.id(zeile.get("accountId"));
            if (id == null) {
                continue;
            }
            infos.put(id, new AccountInfo(
                    FleetStatisticsQueryRepository.text(zeile.get("mainName")),
                    FleetStatisticsQueryRepository.zahl(zeile.get("charaktere"))));
        }
        return infos;
    }

    /**
     * Der Satz, ohne den die Tafel genauer aussieht, als sie ist.
     *
     * <p>Die Gruppierung ist nur so gut wie die CharLink-Daten. Ein Alt, der
     * im Auth nicht mit seinem Main verbunden ist, bekommt hier weiterhin eine
     * eigene Zeile - und dann stimmt die Tafel genau fuer die Leute nicht, um
     * deretwillen sie umgebaut wurde. Diese Zahl steht deshalb auf der Seite
     * und nicht nur in einem Kommentar.</p>
     *
     * <p>Was sie nicht kann: Einzelchar-Spieler von nicht verknuepften Alts
     * unterscheiden - beide sehen in der Datenbank gleich aus. Sie ist die
     * Obergrenze des moeglichen Fehlers, und genau so ist sie formuliert.</p>
     */
    private static String verbindungsHinweis(long ohneVerbindung, long accounts) {
        if (accounts == 0) {
            return null;
        }
        if (ohneVerbindung == 0) {
            return "Zu jedem der " + accounts + " gezaehlten Accounts kennt das Auth "
                    + "mindestens zwei Charaktere - die Gruppierung steht auf gepflegten "
                    + "CharLink-Daten.";
        }
        return "Bei " + ohneVerbindung + " von " + accounts + " Accounts kennt das Auth nur "
                + "einen einzigen Charakter. Darunter sind Einzelchar-Spieler und Gaeste "
                + "ueber den Link, die zu Recht einzeln stehen - aber auch jeder Alt, der "
                + "nicht mit seinem Main verknuepft ist: Der bekommt hier eine eigene Zeile, "
                + "und sein Account zaehlt doppelt. Die Alt-Erkennung unter CharLink schlaegt "
                + "solche Verknuepfungen vor.";
    }

    // ==================================================================
    // Kleinkram
    // ==================================================================

    private static Instant spaeter(Instant a, Instant b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.isAfter(b) ? a : b;
    }

    private static Instant frueher(Instant a, Instant b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.isBefore(b) ? a : b;
    }

    /** Sortieren nach Namen darf an einem fehlenden Namen nicht scheitern. */
    private static String nameOderLeer(String name) {
        return name == null ? "" : name;
    }
}
