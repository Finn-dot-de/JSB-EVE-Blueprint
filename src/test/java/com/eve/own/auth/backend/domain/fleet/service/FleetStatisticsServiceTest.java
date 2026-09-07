package com.eve.own.auth.backend.domain.fleet.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.eve.own.auth.backend.domain.auth.SystemRoles;
import com.eve.own.auth.backend.domain.character.entity.Character;
import com.eve.own.auth.backend.domain.character.repository.CharacterRepository;
import com.eve.own.auth.backend.domain.fleet.TrackingType;
import com.eve.own.auth.backend.domain.fleet.dto.FleetStatisticsDtos;
import com.eve.own.auth.backend.domain.fleet.dto.FleetStatisticsDtos.Anteil;
import com.eve.own.auth.backend.domain.fleet.dto.FleetStatisticsDtos.FatStatistik;
import com.eve.own.auth.backend.domain.fleet.repository.FleetStatisticsQueryRepository;
import com.eve.own.auth.backend.testsupport.FakeTuple;
import jakarta.persistence.Tuple;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TimeZone;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.access.AccessDeniedException;

/**
 * Die FAT-Statistik - und vor allem das, was sie <em>nicht</em> sagt.
 *
 * <p>Die Falle, die dieses Projekt zweimal getroffen hat, ist die duenne
 * Datenlage: "100 % Teilnahme" bei einer einzigen Flotte sieht aus wie eine
 * Messung und ist keine. Ein guter Teil dieser Tests prueft deshalb nicht, ob
 * eine Zahl stimmt, sondern ob sie bei zu wenigen Faellen ausbleibt und
 * stattdessen ein Satz dasteht, der die Fallzahl nennt.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("FAT-Statistik")
class FleetStatisticsServiceTest {

    private static final Long DIREKTOR = 1L;
    private static final Long FC_EINS = 1001L;
    private static final Long FC_ZWEI = 1002L;
    private static final Long MITGLIED = 9L;

    private static final Long SELTEN = 2001L;
    private static final Long TREU = 2002L;

    private static final Instant JETZT = Instant.now();

    @Mock private FleetStatisticsQueryRepository queryRepo;
    @Mock private CharacterRepository characterRepo;

    private FleetStatisticsService dienst;
    private TimeZone urspruenglicheZone;

    /** Kopfdaten je Flotte, in Einfuegereihenfolge. */
    private final Map<Long, Kopf> flotten = new LinkedHashMap<>();
    /** Teilnahmen je Flotte. */
    private final Map<Long, List<Teiln>> teilnahmen = new LinkedHashMap<>();
    /**
     * Was in {@code characters} steht - der Rest der Welt steht dort nicht.
     *
     * <p>Wer hier fehlt, ist der Gast ueber den Link: kein Main, keine
     * Verknuepfung, eigene Zeile.</p>
     */
    private final Map<Long, Registriert> auth = new LinkedHashMap<>();

    private record Kopf(Instant start, Long fcId, String fcName, String typ, String doktrin) {}

    private record Teiln(long attendanceId, Long charId, String name) {}

    /** @param mainId wie in der Spalte: die eigene ID, eine fremde oder gar keine */
    private record Registriert(String name, Long mainId) {}

    @BeforeEach
    void setUp() {
        urspruenglicheZone = TimeZone.getDefault();
        dienst = new FleetStatisticsService(queryRepo, characterRepo);

        charakter(DIREKTOR, "Der Direktor", SystemRoles.DIRECTOR);
        charakter(FC_EINS, "Erster FC", "ROLE_1337");
        charakter(FC_ZWEI, "Zweiter FC", "ROLE_A38");
        charakter(MITGLIED, "Gewoehnliches Mitglied", SystemRoles.USER, SystemRoles.MEMBER);

        when(queryRepo.skelett(any())).thenAnswer(a -> baueSkelett());
        when(queryRepo.vorab(any())).thenAnswer(a -> new FleetStatisticsQueryRepository.Vorab(
                baueSkelett().size(),
                flotten.values().stream().map(Kopf::start).min(Instant::compareTo).orElse(null)));
        when(queryRepo.accounts(any())).thenAnswer(a -> baueAccounts(a.getArgument(0)));
    }

    @AfterEach
    void tearDown() {
        TimeZone.setDefault(urspruenglicheZone);
    }

    // ==================================================================
    // Aufbau
    // ==================================================================

    private void charakter(Long id, String name, String... rollen) {
        Character c = new Character();
        c.setId(id);
        c.setName(name);
        c.setRoles(Set.of(rollen));
        when(characterRepo.findById(id)).thenReturn(Optional.of(c));
    }

    /** Genau n Tage her - ohne Verschiebung auf eine Uhrzeit. */
    private static Instant vorTagen(int tage) {
        return JETZT.minus(tage, ChronoUnit.DAYS);
    }

    /** Ein Zeitpunkt vor n Tagen, auf eine feste Uhrzeit in EVE-Zeit gesetzt. */
    private static Instant umUtc(int vorTagen, int stunde) {
        return JETZT.minus(vorTagen, ChronoUnit.DAYS).atZone(ZoneOffset.UTC)
                .withHour(stunde).withMinute(30).withSecond(0).withNano(0).toInstant();
    }

    private void flotte(long id, Instant start, Long fcId, String fcName) {
        flotte(id, start, fcId, fcName, TrackingType.LIVE, "Ferox");
    }

    private void flotte(long id, Instant start, Long fcId, String fcName,
                        TrackingType typ, String doktrin) {
        flotten.put(id, new Kopf(start, fcId, fcName, typ.dbValue(), doktrin));
        teilnahmen.computeIfAbsent(id, k -> new ArrayList<>());
    }

    private void dabei(long fleetId, Long charId, String name) {
        List<Teiln> liste = teilnahmen.computeIfAbsent(fleetId, k -> new ArrayList<>());
        liste.add(new Teiln(fleetId * 1000 + liste.size(), charId, name));
    }

    /**
     * Baut die Zeilenmenge so, wie der LEFT JOIN sie liefert: eine Zeile je
     * (Flotte, Teilnehmer) - und fuer eine Flotte ohne Teilnehmer genau eine
     * Zeile mit leeren Teilnahmespalten.
     */
    private List<Tuple> baueSkelett() {
        List<Tuple> zeilen = new ArrayList<>();
        flotten.forEach((id, kopf) -> {
            List<Teiln> liste = teilnahmen.getOrDefault(id, List.of());
            if (liste.isEmpty()) {
                zeilen.add(zeile(id, kopf, null));
                return;
            }
            liste.forEach(t -> zeilen.add(zeile(id, kopf, t)));
        });
        return zeilen;
    }

    private Tuple zeile(long fleetId, Kopf kopf, Teiln t) {
        return FakeTuple.of(
                "fleetId", fleetId,
                "startTime", kopf.start(),
                "fcCharacterId", kopf.fcId(),
                "fcCharacterName", kopf.fcName(),
                "trackingType", kopf.typ(),
                "doctrine", kopf.doktrin(),
                "attendanceId", t == null ? null : t.attendanceId(),
                "characterId", t == null ? null : t.charId(),
                "characterName", t == null ? null : t.name(),
                // Genau das, was der COALESCE der Abfrage liefert.
                "accountId", t == null ? null : accountVon(t.charId()));
    }

    /** Ein Charakter, den das Auth kennt - mit oder ohne Verweis auf seinen Main. */
    private void registriert(Long charId, String name, Long mainId) {
        auth.put(charId, new Registriert(name, mainId));
    }

    /** {@code COALESCE(characters.main_character_id, fleet_attendance.character_id)}. */
    private Long accountVon(Long charId) {
        Registriert r = auth.get(charId);
        return r != null && r.mainId() != null ? r.mainId() : charId;
    }

    /**
     * Die Nachschlage-Abfrage: je Account der Name des Mains und wie viele
     * Charaktere das Auth ihm zuordnet.
     *
     * <p>Zaehlt ueber <em>alle</em> registrierten Charaktere und nicht nur die
     * geflogenen - genau darauf beruht die Aussage, ob eine Verknuepfung
     * existiert.</p>
     */
    private List<Tuple> baueAccounts(Collection<Long> ids) {
        Map<Long, Long> anzahl = new LinkedHashMap<>();
        auth.keySet().forEach(charId -> anzahl.merge(accountVon(charId), 1L, Long::sum));

        List<Tuple> zeilen = new ArrayList<>();
        for (Long id : ids) {
            Long charaktere = anzahl.get(id);
            if (charaktere == null) {
                // Kein Eintrag in characters - die Abfrage liefert fuer diesen
                // Account gar keine Zeile.
                continue;
            }
            Registriert main = auth.get(id);
            zeilen.add(FakeTuple.of(
                    "accountId", id,
                    "charaktere", charaktere,
                    "mainName", main == null ? null : main.name()));
        }
        return zeilen;
    }

    /** Eine Zeile der Tafel, gleich ob sie unter den Einmaligen steht. */
    private static FleetStatisticsDtos.AccountZeile zeileVon(FatStatistik s, Long accountId) {
        return Stream.concat(s.teilnahme().zeilen().stream(), s.teilnahme().einmalige().stream())
                .filter(z -> accountId.equals(z.accountId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Keine Zeile fuer Account " + accountId));
    }

    /**
     * Ein Quartal, wie es aussieht, wenn die Statistik ueberhaupt etwas sagen
     * kann: fuenfzehn Flotten, zwei FCs, zwei Piloten mit unterschiedlich
     * vielen Teilnahmen.
     */
    private void quartalMitFuenfzehnFlotten() {
        int[] tage = {85, 80, 75, 70, 65, 60, 50, 48, 46, 40, 35, 30, 25, 20, 15};
        long id = 1;
        for (int t : tage) {
            Long fc = id <= 12 ? FC_EINS : FC_ZWEI;
            flotte(id, umUtc(t, 19), fc, fc.equals(FC_EINS) ? "Erster FC" : "Zweiter FC");
            id++;
        }

        for (long f : List.of(1L, 2L, 3L, 4L, 5L)) {
            dabei(f, SELTEN, "Selten dabei");
        }
        for (long f : List.of(1L, 2L, 3L, 4L, 5L, 10L, 11L, 12L, 13L, 14L)) {
            dabei(f, TREU, "Immer dabei");
        }
    }

    // ==================================================================
    // Wer darf
    // ==================================================================

    @Nested
    @DisplayName("Zugang")
    class Zugang {

        @Test
        @DisplayName("ohne FC-Rolle gibt es die Statistik nicht")
        void ohneFleetStaffKeineStatistik() {
            // Ohne diese Zeile faellt die Pruefung auf die Annotation am
            // Controller zurueck - und die gehoert zu genau einem
            // Einstiegspunkt. Hier haengen Namen von Menschen dran.
            assertThatThrownBy(() -> dienst.statistik(MITGLIED, null))
                    .isInstanceOf(AccessDeniedException.class)
                    .hasMessageContaining("FCs und Direktoren");
        }

        @Test
        @DisplayName("Director und die beiden FC-Rollen sehen sie")
        void fleetStaffDarf() {
            // Genau die drei Namen aus AccessRules.FLEET_STAFF. Wer sie dort
            // aendert, muss sie hier mitaendern - sonst kommt jemand am
            // Endpunkt vorbei und im Dienst nicht, oder schlimmer, umgekehrt.
            for (Long id : List.of(DIREKTOR, FC_EINS, FC_ZWEI)) {
                assertThat(dienst.statistik(id, null)).isNotNull();
            }
        }

        @Test
        @DisplayName("nur 30, 90 und 180 Tage sind waehlbar")
        void nurErlaubteZeitraeume() {
            // Ohne diese Zeile wuerde ein unbekannter Wert still durch 90
            // ersetzt: In der Kopfzeile stuende dann "90 Tage", waehrend der
            // Leser 45 angefragt hat.
            assertThatThrownBy(() -> dienst.statistik(DIREKTOR, 45))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("45");
            assertThat(dienst.statistik(DIREKTOR, 180).zeitraum().tageGewaehlt()).isEqualTo(180);
            assertThat(dienst.statistik(DIREKTOR, null).zeitraum().tageGewaehlt()).isEqualTo(90);
        }
    }

    // ==================================================================
    // Duenne Datenlage
    // ==================================================================

    @Nested
    @DisplayName("Duenne Datenlage")
    class DuenneDatenlage {

        @Test
        @DisplayName("ein leerer Zeitraum liefert eine Leerauskunft und keine Nullen, die wie Messwerte aussehen")
        void leererZeitraum() {
            FatStatistik ergebnis = dienst.statistik(DIREKTOR, null);

            assertThat(ergebnis.auswertbar()).isFalse();
            assertThat(ergebnis.hinweis()).contains("keine Flotte");
            // Ohne diese Zeile stuende "0 FCs, 0 Piloten" wie ein Befund da.
            // Der Satz daneben sagt, dass es eine Aussage ueber die Daten ist
            // und nicht ueber die Corporation.
            assertThat(ergebnis.hinweis()).contains("ueber die Daten");
            assertThat(ergebnis.kopf().flotten()).isZero();
            assertThat(ergebnis.kopf().liveAnteil()).isEqualTo(new Anteil(0, 0));
            assertThat(ergebnis.kopf().doktrin().gereiht()).isFalse();

            assertThat(ergebnis.teilnahme().zeilen()).isEmpty();
            assertThat(ergebnis.teilnahme().einmalige()).isEmpty();
            assertThat(ergebnis.zeitraum().ersteFlotteInsgesamt()).isNull();
            assertThat(ergebnis.zeitraum().tageMitDaten()).isNull();
        }

        @Test
        @DisplayName("eine einzige Flotte traegt die Tafel nicht - und die Seite sagt das")
        void einzelneFlotteWirdNichtGereiht() {
            flotte(1, umUtc(3, 19), FC_EINS, "Erster FC");
            dabei(1, 3001L, "Pilot A");
            dabei(1, 3002L, "Pilot B");

            FatStatistik ergebnis = dienst.statistik(DIREKTOR, null);

            // Ohne dieses Kennzeichen stuende "Pilot A: 1 Flotte" da, als
            // waere das eine Auskunft ueber ihn.
            assertThat(ergebnis.auswertbar()).isFalse();
            assertThat(ergebnis.hinweis()).contains("zu wenig fuer eine Auswertung");
            assertThat(ergebnis.hinweis())
                    .contains(String.valueOf(FleetStatisticsService.MIN_FLOTTEN_FUER_AUSWERTUNG));

            // Die absoluten Zahlen bleiben - sie behaupten nichts.
            assertThat(ergebnis.kopf().flotten()).isEqualTo(1);
            assertThat(ergebnis.kopf().accounts()).isEqualTo(2);
            assertThat(ergebnis.teilnahme().einmalige()).hasSize(2);
        }

        @Test
        @DisplayName("die Spanne nennt, wie weit die Daten wirklich zurueckreichen")
        void ehrlicheSpanne() {
            for (int i = 0; i < 6; i++) {
                flotte(i + 1L, vorTagen(22 - i), FC_EINS, "Erster FC");
                dabei(i + 1L, TREU, "Immer dabei");
            }
            FatStatistik ergebnis = dienst.statistik(DIREKTOR, null);

            // Ohne diesen Satz behauptet die Seite einen Nenner von 90 Tagen,
            // von denen 68 leer sind.
            assertThat(ergebnis.zeitraum().tageGewaehlt()).isEqualTo(90);
            assertThat(ergebnis.zeitraum().tageMitDaten()).isEqualTo(22);
            assertThat(ergebnis.zeitraum().hinweis()).contains("Daten reichen 22 Tage zurueck");
        }
    }

    // ==================================================================
    // Fallzahlen
    // ==================================================================

    @Nested
    @DisplayName("Fallzahlen")
    class Fallzahlen {

        @Test
        @DisplayName("kein Datensatz gibt eine Prozentzahl heraus - nur Zaehler und Nenner")
        void keineProzentwerteImDto() {
            // Der strukturelle Schutz: Solange kein Feld ein Gleitkommawert ist
            // und keines "prozent" heisst, kann keine Quote das Backend ohne
            // ihre Fallzahl verlassen. Ohne diesen Test genuegt ein
            // "double auslastung", und die Lehre aus zwei Fehlschlaegen ist
            // wieder weg.
            for (Class<?> typ : FleetStatisticsDtos.class.getDeclaredClasses()) {
                if (!typ.isRecord()) {
                    continue;
                }
                for (RecordComponent bestandteil : typ.getRecordComponents()) {
                    String wo = typ.getSimpleName() + "." + bestandteil.getName();
                    assertThat(bestandteil.getType())
                            .as(wo + " darf kein Gleitkommawert sein")
                            .isNotIn(double.class, float.class, Double.class, Float.class,
                                    BigDecimal.class);
                    assertThat(bestandteil.getName().toLowerCase(Locale.ROOT))
                            .as(wo + " darf keine fertige Quote sein")
                            .doesNotContain("prozent").doesNotContain("percent");
                }
            }
            // Und der Anteil selbst rechnet nicht: Es gibt keinen Weg von hier
            // zu einer nackten Zahl.
            assertThat(Anteil.class.getDeclaredMethods())
                    .noneMatch(m -> m.getReturnType() == double.class);
        }

        @Test
        @DisplayName("jede Quote traegt ihren Nenner mit, und keiner ist null")
        void jedeQuoteMitNenner() {
            quartalMitFuenfzehnFlotten();
            FatStatistik ergebnis = dienst.statistik(DIREKTOR, null);

            List<String> gefunden = new ArrayList<>();
            sammleAnteile("statistik", ergebnis, gefunden);

            // Ohne diese Zeile koennte irgendwo ein "4 von 0" stehen - eine
            // Quote, deren Grundlage nicht existiert.
            assertThat(gefunden).isNotEmpty();
            assertThat(gefunden).allSatisfy(eintrag ->
                    assertThat(eintrag).doesNotContain("von 0)"));
        }

        @Test
        @DisplayName("die Teilnahmetafel traegt keine Quote, sondern absolute Zahlen")
        void teilnahmeOhneQuote() {
            quartalMitFuenfzehnFlotten();
            FatStatistik ergebnis = dienst.statistik(DIREKTOR, null);

            // Der Grund, warum AccountZeile keinen Anteil hat: Ein Pilot, der
            // vor drei Wochen beigetreten ist, kann keine 15 Flotten haben.
            // Jeder Nenner waere entweder unfair oder zirkulaer - also steht
            // keiner da, sondern die erste und die letzte Flotte.
            assertThat(FleetStatisticsDtos.AccountZeile.class.getRecordComponents())
                    .noneMatch(bestandteil -> bestandteil.getType() == Anteil.class);
            assertThat(ergebnis.teilnahme().zeilen())
                    .extracting(FleetStatisticsDtos.AccountZeile::accountId,
                            FleetStatisticsDtos.AccountZeile::flotten)
                    .containsExactly(
                            org.assertj.core.api.Assertions.tuple(TREU, 10L),
                            org.assertj.core.api.Assertions.tuple(SELTEN, 5L));
            assertThat(ergebnis.teilnahme().zeilen())
                    .allSatisfy(z -> assertThat(z.ersteFlotte()).isBefore(z.letzteFlotte()));
        }
    }

    // ==================================================================
    // EVE-Zeit
    // ==================================================================

    @Nested
    @DisplayName("EVE-Zeit")
    class EveZeit {

        @Test
        @DisplayName("das Datum im Hinweis steht in EVE-Zeit, nicht in der Serverzeit")
        void datumInUtc() {
            // Eine Zone, die weit genug von UTC entfernt ist, um den Tag zu
            // verschieben: +14 Stunden.
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Kiritimati"));
            Instant erste = umUtc(30, 23);
            flotte(1, erste, FC_EINS, "Erster FC");
            flotte(2, umUtc(20, 23), FC_EINS, "Erster FC");
            flotte(3, umUtc(10, 23), FC_EINS, "Erster FC");
            dabei(1, TREU, "Immer dabei");

            String hinweis = dienst.statistik(DIREKTOR, null).hinweis();

            // In +14 ist 23:30 UTC bereits der Folgetag. Ohne EVE_ZEIT stuende
            // im Satz ein Datum, das es fuer den FC nie gegeben hat.
            DateTimeFormatter utc =
                    DateTimeFormatter.ofPattern("dd.MM.yyyy").withZone(ZoneOffset.UTC);
            DateTimeFormatter lokal = DateTimeFormatter.ofPattern("dd.MM.yyyy")
                    .withZone(TimeZone.getDefault().toZoneId());
            assertThat(hinweis).contains(utc.format(erste));
            assertThat(lokal.format(erste)).isNotEqualTo(utc.format(erste));
        }
    }

    // ==================================================================
    // Wer gar nicht dabei war
    // ==================================================================

    @Nested
    @DisplayName("Nichtteilnehmer")
    class Nichtteilnehmer {

        @Test
        @DisplayName("ein Mitglied ohne jede Teilnahme taucht nirgends mit einer Zeile auf")
        void keineNullZeilen() {
            quartalMitFuenfzehnFlotten();
            // MITGLIED ist registriert, in der Corp - und war auf keiner Flotte.
            FatStatistik ergebnis = dienst.statistik(DIREKTOR, null);

            // Die Auswertung geht bewusst von den Teilnahmen aus und nicht vom
            // Mitgliederverzeichnis. Anders herum stuende neben jedem Namen
            // eine 0, und niemand koennte sie von "war im Urlaub", "ist neu"
            // oder "war damals gar nicht in der Corp" unterscheiden.
            assertThat(ergebnis.teilnahme().zeilen())
                    .extracting(FleetStatisticsDtos.AccountZeile::accountId)
                    .doesNotContain(MITGLIED);
            assertThat(ergebnis.teilnahme().einmalige())
                    .extracting(FleetStatisticsDtos.AccountZeile::accountId)
                    .doesNotContain(MITGLIED);
        }
    }

    // ==================================================================
    // Erfassungsluecken
    // ==================================================================

    @Nested
    @DisplayName("Erfassung")
    class Erfassung {

        @Test
        @DisplayName("ein doppelt erfasster Teilnehmer zaehlt einmal")
        void doppelteZeilenZaehlenEinmal() {
            for (int i = 1; i <= 6; i++) {
                flotte(i, umUtc(40 - i, 19), FC_EINS, "Erster FC");
                dabei(i, TREU, "Immer dabei");
            }
            // Der 60-Sekunden-Scheduler und ein manueller Abgleich koennen sich
            // ueberschneiden; einen Eindeutigkeitsindex gibt es nicht.
            dabei(1, TREU, "Immer dabei");

            FatStatistik ergebnis = dienst.statistik(DIREKTOR, null);

            // Ohne das Entdoppeln stuende in der Tafel eine Teilnahmezahl, die
            // groesser ist als die Zahl der Flotten, auf denen der Pilot war.
            assertThat(ergebnis.kopf().accounts()).isEqualTo(1);
            assertThat(ergebnis.teilnahme().zeilen()).hasSize(1);
            assertThat(ergebnis.teilnahme().zeilen().getFirst().flotten()).isEqualTo(6);
        }

        @Test
        @DisplayName("eine Flotte ohne Teilnehmer bleibt eine Flotte")
        void leereFlotteZaehltMit() {
            for (int i = 1; i <= 5; i++) {
                flotte(i, umUtc(40 - i, 19), FC_EINS, "Erster FC");
                dabei(i, TREU, "Immer dabei");
            }
            flotte(6, umUtc(34, 19), FC_ZWEI, "Zweiter FC");

            FatStatistik ergebnis = dienst.statistik(DIREKTOR, null);

            // Ohne den LEFT JOIN verschwaende die Flotte - und "5 von 5" saehe
            // aus wie lueckenlose Teilnahme, obwohl eine Flotte fehlt.
            assertThat(ergebnis.kopf().flotten()).isEqualTo(6);
            assertThat(ergebnis.kopf().fcs()).isEqualTo(2);
            assertThat(ergebnis.teilnahme().zeilen().getFirst().flotten()).isEqualTo(5);
        }

        @Test
        @DisplayName("die Doktrin-Angabe wird nicht gereiht, wenn zu viele Flotten keine tragen")
        void doktrinOhneAngabeWirdNichtGereiht() {
            for (int i = 1; i <= 10; i++) {
                flotte(i, umUtc(40 - i, 19), FC_EINS, "Erster FC",
                        TrackingType.LIVE, i <= 5 ? null : "Ferox");
                dabei(i, TREU, "Immer dabei");
            }
            var doktrin = dienst.statistik(DIREKTOR, null).kopf().doktrin();

            // Eine "haeufigste Doktrin" aus einer Minderheit ausgefuellter
            // Felder ist eine Behauptung ueber die Mehrheit, ueber die nichts
            // bekannt ist.
            assertThat(doktrin.gereiht()).isFalse();
            assertThat(doktrin.haeufigste()).isNull();
            assertThat(doktrin.ohneAngabe()).isEqualTo(5);
            assertThat(doktrin.hinweis()).contains("5 von 10");
        }

        @Test
        @DisplayName("Schreibweisen derselben Doktrin werden zusammengefasst")
        void doktrinSchreibweisen() {
            String[] angaben = {"Ferox", " ferox ", "FEROX", "Ferox", "Eagle", "Eagle"};
            for (int i = 0; i < angaben.length; i++) {
                flotte(i + 1L, umUtc(40 - i, 19), FC_EINS, "Erster FC",
                        TrackingType.LIVE, angaben[i]);
                dabei(i + 1L, TREU, "Immer dabei");
            }
            var doktrin = dienst.statistik(DIREKTOR, null).kopf().doktrin();

            // Ohne das Zusammenfassen waeren "Ferox", "ferox" und "FEROX" drei
            // Doktrinen, und "Eagle" stuende mit 2 von 6 an der Spitze.
            assertThat(doktrin.gereiht()).isTrue();
            assertThat(doktrin.haeufigste()).isEqualTo("Ferox");
            assertThat(doktrin.anteil()).isEqualTo(new Anteil(4, 6));
        }

        @Test
        @DisplayName("der LIVE-Anteil der Kopfzeile nennt seinen Nenner")
        void liveAnteilMitNenner() {
            for (int i = 1; i <= 6; i++) {
                flotte(i, umUtc(40 - i, 19), FC_EINS, "Erster FC",
                        i <= 4 ? TrackingType.LIVE : TrackingType.LINK, "Ferox");
                dabei(i, TREU, "Immer dabei");
            }
            var kopf = dienst.statistik(DIREKTOR, null).kopf();

            // Der Vorbehalt zur Tafel: Bei einer LINK-Flotte steht nur drin,
            // wer den Link geklickt hat. Ohne den Nenner daneben liesse sich
            // nicht sehen, wie gross dieser Vorbehalt ist.
            assertThat(kopf.liveAnteil()).isEqualTo(new Anteil(4, 6));
        }

        @Test
        @DisplayName("ein zu grosses Fenster wird verkuerzt - und das steht dabei")
        void zuGrossesFensterWirdVerkuerzt() {
            for (int i = 1; i <= 6; i++) {
                flotte(i, umUtc(20 - i, 19), FC_EINS, "Erster FC");
                dabei(i, TREU, "Immer dabei");
            }
            when(queryRepo.vorab(any())).thenReturn(
                    new FleetStatisticsQueryRepository.Vorab(
                            FleetStatisticsService.MAX_SKELETT_ZEILEN + 1, umUtc(300, 19)));

            var zeitraum = dienst.statistik(DIREKTOR, null).zeitraum();

            // Ein stilles Abschneiden waere die schlechtere Luege: Die Seite
            // haette dann eine Aussage ueber 30 Tage und eine Ueberschrift
            // ueber 90.
            assertThat(zeitraum.gekuerzt()).isTrue();
            assertThat(zeitraum.tageGewaehlt()).isEqualTo(90);
            assertThat(zeitraum.tageAusgewertet()).isEqualTo(30);
            assertThat(zeitraum.hinweis()).contains("verkuerzt");
        }

        @Test
        @DisplayName("Piloten mit genau einer Flotte stehen getrennt")
        void einmaligeGetrennt() {
            for (int i = 1; i <= 6; i++) {
                flotte(i, umUtc(40 - i, 19), FC_EINS, "Erster FC");
                dabei(i, TREU, "Immer dabei");
                dabei(i, 4000L + i, "Gast " + i);
            }
            var teilnahme = dienst.statistik(DIREKTOR, null).teilnahme();

            // Sonst besteht die Tafel zur Haelfte aus Gaesten, die einmal
            // mitgeflogen sind - und das Nachschlagewerk wird unbrauchbar.
            assertThat(teilnahme.zeilen()).hasSize(1);
            assertThat(teilnahme.einmalige()).hasSize(6);
        }

        @Test
        @DisplayName("die Tafel steht nach Namen, nicht nach Flottenzahl")
        void tafelNachNamen() {
            for (int i = 1; i <= 6; i++) {
                flotte(i, umUtc(40 - i, 19), FC_EINS, "Erster FC");
                dabei(i, TREU, "Zeta Vielflieger");
                if (i <= 3) {
                    dabei(i, SELTEN, "Alpha Gelegentlich");
                }
            }
            var zeilen = dienst.statistik(DIREKTOR, null).teilnahme().zeilen();

            // Die Vorgabesortierung entscheidet, ob die Tafel ein
            // Nachschlagewerk ist oder eine Bestenliste - und eine Bestenliste
            // hat immer ein unteres Ende.
            assertThat(zeilen).extracting(FleetStatisticsDtos.AccountZeile::name)
                    .containsExactly("Alpha Gelegentlich", "Zeta Vielflieger");
        }
    }

    // ==================================================================
    // Je Account statt je Charakter
    // ==================================================================

    /**
     * Der Kern des Umbaus: Wer drei Alts mitbringt, hat einmal teilgenommen.
     *
     * <p>Sonst misst die Tafel Multiboxing statt Beteiligung - und in einer
     * Corporation, in der beim Mining ohnehin multiboxt wird, waere die
     * Rangfolge schlicht die Anzahl der Bildschirme.</p>
     */
    @Nested
    @DisplayName("Je Account")
    class JeAccount {

        private static final Long MAIN = 5000L;
        private static final Long ALT_EINS = 5001L;
        private static final Long ALT_ZWEI = 5002L;
        private static final Long FREMDER = 7777L;

        /** Ein Main mit zwei Alts, so wie CharLink ihn ablegt. */
        private void accountMitZweiAlts() {
            // Der Main traegt seine eigene ID - der Datenbestand kennt diese
            // Schreibweise ebenso wie main_character_id IS NULL.
            registriert(MAIN, "Haupt Charakter", MAIN);
            registriert(ALT_EINS, "Erster Alt", MAIN);
            registriert(ALT_ZWEI, "Zweiter Alt", MAIN);
        }

        @Test
        @DisplayName("Derselbe Charakter unter zwei Namen ist EIN Charakter")
        void nachgetragenerNameZaehltNichtDoppelt() {
            // Am echten Bestand gefunden: der ESI-Abgleich legt einen
            // Teilnehmer als "Unknown Pilot <id>" an und traegt den richtigen
            // Namen erst beim naechsten Lauf nach. Zwei von vier Charakteren
            // standen dadurch unter je zwei Namen.
            accountMitZweiAlts();
            for (int i = 1; i <= 5; i++) {
                flotte(i, umUtc(40 - i, 19), FC_EINS, "Erster FC");
            }
            dabei(1, MAIN, "Haupt Charakter");
            dabei(1, ALT_EINS, "Unknown Pilot " + ALT_EINS);
            dabei(2, MAIN, "Haupt Charakter");
            dabei(2, ALT_EINS, "Erster Alt");

            var zeile = zeileVon(dienst.statistik(DIREKTOR, null), MAIN);

            // OHNE die Sammlung nach KENNUNG statt nach Namen stuenden hier
            // drei Charaktere - und ein Direktor, der die Zuordnung pruefen
            // will, saehe eine Person, die er nicht kennt, und zweifelte an
            // der Gruppierung statt an der Beschriftung.
            assertThat(zeile.charaktere())
                    .containsExactlyInAnyOrder("Haupt Charakter", "Erster Alt");
        }

        @Test
        @DisplayName("drei Charaktere desselben Mains in EINER Flotte ergeben EINEN FAT")
        void dreiAltsEineFlotteEinFat() {
            accountMitZweiAlts();
            for (int i = 1; i <= 5; i++) {
                flotte(i, umUtc(40 - i, 19), FC_EINS, "Erster FC");
            }
            dabei(1, MAIN, "Haupt Charakter");
            dabei(1, ALT_EINS, "Erster Alt");
            dabei(1, ALT_ZWEI, "Zweiter Alt");

            FatStatistik ergebnis = dienst.statistik(DIREKTOR, null);

            // Ohne die Gruppierung stuenden hier drei Zeilen mit je einem FAT,
            // und wer die meisten Bildschirme hat, stuende oben.
            assertThat(zeileVon(ergebnis, MAIN).flotten()).isEqualTo(1);
            assertThat(ergebnis.teilnahme().zeilen()).isEmpty();
            assertThat(ergebnis.teilnahme().einmalige()).hasSize(1);
        }

        @Test
        @DisplayName("dieselben drei in DREI verschiedenen Flotten ergeben DREI")
        void dreiAltsDreiFlottenDreiFats() {
            accountMitZweiAlts();
            for (int i = 1; i <= 5; i++) {
                flotte(i, umUtc(40 - i, 19), FC_EINS, "Erster FC");
            }
            dabei(1, MAIN, "Haupt Charakter");
            dabei(2, ALT_EINS, "Erster Alt");
            dabei(3, ALT_ZWEI, "Zweiter Alt");

            FatStatistik ergebnis = dienst.statistik(DIREKTOR, null);

            // Ohne diese Zeile waere die Gruppierung zu scharf: Sie soll
            // Bildschirme zusammenfassen, nicht Abende. Drei Flotten sind drei
            // Teilnahmen, gleich mit welchem Charakter.
            assertThat(zeileVon(ergebnis, MAIN).flotten()).isEqualTo(3);
            assertThat(ergebnis.teilnahme().zeilen()).hasSize(1);
        }

        @Test
        @DisplayName("die Entdopplung wirkt weiterhin - auch innerhalb eines Accounts")
        void doppelteZeileZaehltAuchJeAccountEinmal() {
            accountMitZweiAlts();
            for (int i = 1; i <= 5; i++) {
                flotte(i, umUtc(40 - i, 19), FC_EINS, "Erster FC");
                dabei(i, MAIN, "Haupt Charakter");
            }
            // fleet_attendance hat keinen Eindeutigkeitsindex auf (Flotte,
            // Charakter); der 60-Sekunden-Scheduler kann sich mit einem
            // manuellen Abgleich ueberschneiden.
            dabei(1, MAIN, "Haupt Charakter");
            dabei(1, ALT_EINS, "Erster Alt");

            FatStatistik ergebnis = dienst.statistik(DIREKTOR, null);

            // Zwei Sicherungen hintereinander: erst je (Flotte, Charakter)
            // entdoppeln, dann je Account nur verschiedene Flotten zaehlen.
            // Faellt eine davon weg, stuende hier 6 oder 7 - mehr Flotten, als
            // es ueberhaupt gab.
            assertThat(zeileVon(ergebnis, MAIN).flotten()).isEqualTo(5);
            assertThat(ergebnis.kopf().flotten()).isEqualTo(5);
        }

        @Test
        @DisplayName("ein Charakter ohne Eintrag in characters bleibt seine eigene Zeile")
        void fremderBleibtEigeneZeile() {
            accountMitZweiAlts();
            for (int i = 1; i <= 5; i++) {
                flotte(i, umUtc(40 - i, 19), FC_EINS, "Erster FC");
                dabei(i, MAIN, "Haupt Charakter");
                dabei(i, FREMDER, "Unknown Pilot 123");
            }

            FatStatistik ergebnis = dienst.statistik(DIREKTOR, null);

            // Blues und Gaeste ueber den Link haben keinen Main. Ohne den
            // Rueckfall auf die eigene Kennung fielen sie entweder ganz aus
            // der Tafel oder landeten unter einem gemeinsamen "null"-Account -
            // beides waere schlimmer, als sie einzeln zu zaehlen.
            assertThat(zeileVon(ergebnis, FREMDER).flotten()).isEqualTo(5);
            assertThat(zeileVon(ergebnis, FREMDER).name()).isEqualTo("Unknown Pilot 123");
            assertThat(zeileVon(ergebnis, FREMDER).verbunden()).isFalse();
            assertThat(ergebnis.kopf().accounts()).isEqualTo(2);
        }

        @Test
        @DisplayName("angezeigt wird der Name des Mains, auch wenn der Main selbst nie mitflog")
        void nameDesMainsAuchOhneEigeneTeilnahme() {
            accountMitZweiAlts();
            for (int i = 1; i <= 5; i++) {
                flotte(i, umUtc(40 - i, 19), FC_EINS, "Erster FC");
                dabei(i, ALT_ZWEI, "Zweiter Alt");
            }
            dabei(1, ALT_EINS, "Erster Alt");

            FatStatistik ergebnis = dienst.statistik(DIREKTOR, null);

            // Ohne das Nachschlagen in characters stuende hier der Name des
            // Alts, der zufaellig zuerst beigetreten ist - und der Direktor
            // suchte einen Piloten, den er unter diesem Namen nicht kennt.
            var zeile = zeileVon(ergebnis, MAIN);
            assertThat(zeile.name()).isEqualTo("Haupt Charakter");
            // Und daneben, wer tatsaechlich geflogen ist: Ohne diese Liste
            // laesst sich die Zuordnung nicht pruefen, man muesste sie glauben.
            // Sortiert und nicht in Beitrittsreihenfolge, sonst sieht dieselbe
            // Zeile bei jedem Laden anders aus.
            assertThat(zeile.charaktere()).containsExactly("Erster Alt", "Zweiter Alt");
        }

        @Test
        @DisplayName("die Zahl der Accounts ohne Verknuepfung steht im Ergebnis und nennt ihren Nenner")
        void unverbundeneAccountsWerdenGezaehlt() {
            accountMitZweiAlts();
            // Ein Einzelchar-Spieler, den das Auth kennt - ohne jeden Verweis.
            registriert(SELTEN, "Selten dabei", null);
            for (int i = 1; i <= 5; i++) {
                flotte(i, umUtc(40 - i, 19), FC_EINS, "Erster FC");
                dabei(i, ALT_EINS, "Erster Alt");
                dabei(i, SELTEN, "Selten dabei");
                dabei(i, FREMDER, "Unknown Pilot 123");
            }

            FatStatistik ergebnis = dienst.statistik(DIREKTOR, null);

            // Drei Accounts, zwei davon kennt das Auth als einzelnen Charakter:
            // der Einzelchar-Spieler und der Gast ueber den Link. Ohne diese
            // Zahl sieht die Tafel genauer aus, als sie ist - ein Alt, der
            // nicht verknuepft ist, steckt in genau dieser Zahl mit drin.
            assertThat(ergebnis.teilnahme().ohneVerbindung()).isEqualTo(new Anteil(2, 3));
            assertThat(zeileVon(ergebnis, MAIN).verbunden()).isTrue();
            assertThat(zeileVon(ergebnis, SELTEN).verbunden()).isFalse();
            // Und der Satz dazu gehoert auf die Seite, nicht in einen
            // Kommentar - samt dem Weg, der das Problem behebt.
            assertThat(ergebnis.teilnahme().hinweis())
                    .contains("2 von 3")
                    .contains("Alt-Erkennung");
        }

        @Test
        @DisplayName("die Einmaligen gelten je Account: zwei Alts an einem Abend sind ein Gast")
        void einmaligeGeltenJeAccount() {
            accountMitZweiAlts();
            for (int i = 1; i <= 5; i++) {
                flotte(i, umUtc(40 - i, 19), FC_EINS, "Erster FC");
                dabei(i, SELTEN, "Selten dabei");
            }
            dabei(3, ALT_EINS, "Erster Alt");
            dabei(3, ALT_ZWEI, "Zweiter Alt");

            var teilnahme = dienst.statistik(DIREKTOR, null).teilnahme();

            // Ohne die Gruppierung stuenden hier zwei Gaeste, wo einer einmal
            // mit zwei Fenstern vorbeigeschaut hat.
            assertThat(teilnahme.einmalige()).hasSize(1);
            assertThat(teilnahme.einmalige().getFirst().accountId()).isEqualTo(MAIN);
            assertThat(teilnahme.einmalige().getFirst().charaktere())
                    .containsExactly("Erster Alt", "Zweiter Alt");
        }

        @Test
        @DisplayName("die Kopfzeile nennt Accounts und Charaktere, weil beides verschieden ist")
        void kopfzeileNenntBeideEinheiten() {
            accountMitZweiAlts();
            for (int i = 1; i <= 5; i++) {
                flotte(i, umUtc(40 - i, 19), FC_EINS, "Erster FC");
                dabei(i, MAIN, "Haupt Charakter");
                dabei(i, ALT_EINS, "Erster Alt");
                dabei(i, ALT_ZWEI, "Zweiter Alt");
                dabei(i, FREMDER, "Unknown Pilot 123");
            }

            var kopf = dienst.statistik(DIREKTOR, null).kopf();

            // Eine Zahl, die ihre Einheit wechselt, ohne dass die Beschriftung
            // mitgeht, ist eine stille Falschaussage: "4 Piloten" waere nach
            // dem Umbau falsch, "2 Accounts" allein verschwiege, dass acht
            // Fenster offen waren.
            assertThat(kopf.accounts()).isEqualTo(2);
            assertThat(kopf.charaktere()).isEqualTo(4);
        }

        @Test
        @DisplayName("ein Main ohne eigenen Eintrag in main_character_id ist trotzdem sein Account")
        void mainOhneEigenverweis() {
            // Die zweite Schreibweise des Datenbestands: main_character_id ist
            // leer, der Charakter ist trotzdem der Main seines Accounts.
            registriert(MAIN, "Haupt Charakter", null);
            registriert(ALT_EINS, "Erster Alt", MAIN);
            for (int i = 1; i <= 5; i++) {
                flotte(i, umUtc(40 - i, 19), FC_EINS, "Erster FC");
                dabei(i, MAIN, "Haupt Charakter");
                dabei(i, ALT_EINS, "Erster Alt");
            }

            FatStatistik ergebnis = dienst.statistik(DIREKTOR, null);

            // Ohne den COALESCE stuende der Main mit NULL als Schluessel da -
            // und alle Mains dieser Schreibweise waeren ein einziger Account.
            assertThat(ergebnis.kopf().accounts()).isEqualTo(1);
            assertThat(zeileVon(ergebnis, MAIN).flotten()).isEqualTo(5);
            assertThat(zeileVon(ergebnis, MAIN).verbunden()).isTrue();
        }
    }

    // ==================================================================
    // Hilfsmittel
    // ==================================================================

    /**
     * Sammelt alle {@link Anteil} aus dem Ergebnisbaum als "Pfad (x von y)".
     *
     * <p>Reflektiv, damit ein neu hinzugefuegter Datensatz nicht an der
     * Pruefung vorbeikommt, ohne dass jemand diesen Test anfasst.</p>
     */
    private static void sammleAnteile(String pfad, Object wert, List<String> ziel) {
        switch (wert) {
            case null -> {
                return;
            }
            case Anteil anteil -> {
                ziel.add(pfad + " (" + anteil.zaehler() + " von " + anteil.nenner() + ")");
                return;
            }
            case Collection<?> sammlung -> {
                int i = 0;
                for (Object element : sammlung) {
                    sammleAnteile(pfad + "[" + i++ + "]", element, ziel);
                }
                return;
            }
            default -> { }
        }
        if (!wert.getClass().isRecord()) {
            return;
        }
        for (RecordComponent bestandteil : wert.getClass().getRecordComponents()) {
            try {
                sammleAnteile(pfad + "." + bestandteil.getName(),
                        bestandteil.getAccessor().invoke(wert), ziel);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Datensatz nicht lesbar: " + pfad, e);
            }
        }
    }
}
