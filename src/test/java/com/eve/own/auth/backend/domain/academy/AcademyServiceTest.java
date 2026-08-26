package com.eve.own.auth.backend.domain.academy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.eve.own.auth.backend.domain.academy.dto.AcademyDtos;
import com.eve.own.auth.backend.domain.academy.entity.AcademyInterest;
import com.eve.own.auth.backend.domain.academy.entity.AcademyTopic;
import com.eve.own.auth.backend.domain.academy.repository.AcademyInterestRepository;
import com.eve.own.auth.backend.domain.academy.repository.AcademyTopicRepository;
import com.eve.own.auth.backend.domain.academy.service.AcademyService;
import com.eve.own.auth.backend.domain.auth.SystemRoles;
import com.eve.own.auth.backend.domain.character.entity.Character;
import com.eve.own.auth.backend.domain.character.repository.CharacterRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.access.AccessDeniedException;

/**
 * Die Academy sammelt ein Signal ein und macht es ablesbar. Sie genehmigt
 * nichts - und genau deshalb sitzen ihre beiden heiklen Stellen nicht dort, wo
 * man sie bei einem Antragsverfahren suchen wuerde.
 *
 * <p><b>Die erste:</b> eine Bekundung gehoert immer dem Angemeldeten. Es gibt
 * keinen Parameter, mit dem sich eine fremde Account-ID hereinreichen liesse -
 * weder im Pfad noch im Rumpf. Die Tests hier halten diese Eigenschaft fest,
 * damit sie beim naechsten Umbau nicht beilaeufig verlorengeht: wer
 * {@code SaveInterestDto} um ein Feld {@code accountId} erweitert, laesst sie
 * fallen.</p>
 *
 * <p><b>Die zweite:</b> die Namen der Interessenten. Der Sichtkreis ist der
 * feste Autorenkreis <em>plus</em> die am Thema hinterlegten Ausbilderrollen,
 * und damit datengetrieben. Eine leere Ausbilderrollen-Menge laesst sich in zwei
 * Richtungen missdeuten - als "niemand" und als "jeder" -, beide Missdeutungen
 * sind je eine Zeile Code entfernt, und die zweite gaebe die Namensliste an
 * jeden Angemeldeten. Beide stehen deshalb als eigener Test da.</p>
 *
 * <p>Die Attrappen bilden das Verhalten der Datenbank <em>nach</em>, statt stur
 * zurueckzugeben: das Interessen-Repository filtert nach Themen-ID, das
 * Charakter-Repository laesst unbekannte IDs herausfallen. Eine Attrappe, die
 * stur alles zurueckgibt, liesse die Gastregel und die Themenzuordnung
 * stillschweigend bestehen.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Academy: wer Namen sieht, wem eine Bekundung gehoert und was gezaehlt wird")
class AcademyServiceTest {

    private static final Long EWAR_ID = 1L;
    private static final Long OHNE_AUSBILDERROLLE_ID = 2L;
    private static final Long INAKTIV_ID = 3L;
    /** Eine ID, die es nicht gibt - fuer die Frage "unbekannt oder verboten?". */
    private static final Long UNBEKANNTES_THEMA_ID = 999L;

    /** Die Ausbilderrolle genau eines Themas - der datengetriebene Teil des Sichtkreises. */
    private static final String EWAR_LEHRER_ROLLE = "ROLE_EWAR_LEHRER";
    private static final String A38_ROLLE = "ROLE_A38";
    private static final String SECHS_NEUN_ROLLE = "ROLE_69";

    private static final Long NEULING = 1000L;
    private static final Long ZWEITER_NEULING = 1001L;
    /** Traegt eine Ausbilderrolle, aber keine Fuehrungsrolle - an ihm trennen sich die Kreise. */
    private static final Long EWAR_AUSBILDER = 2000L;
    private static final Long A38_AUSBILDERIN = 2100L;
    private static final Long DIREKTORIN = 3000L;
    /** Der Nutzer hat ROLE_69 ausdruecklich in den Autorenkreis genommen. */
    private static final Long SECHS_NEUN_TRAEGER = 3100L;
    /** Hat die Corp verlassen: die Zeile blieb stehen, die Rollen wurden auf Gast gesetzt. */
    private static final Long EX_MITGLIED_GAST = 4000L;

    @Mock private AcademyTopicRepository topicRepo;
    @Mock private AcademyInterestRepository interestRepo;
    @Mock private CharacterRepository characterRepo;

    private AcademyService service;

    private AcademyTopic ewar;
    private AcademyTopic ohneAusbilderrolle;
    private AcademyTopic inaktiv;

    private final Map<Long, AcademyTopic> themen = new HashMap<>();
    private final Map<Long, Character> charaktere = new HashMap<>();
    private final List<AcademyInterest> bekundungen = new ArrayList<>();
    private long naechsteBekundungsId = 500L;

    @BeforeEach
    void setUp() {
        service = new AcademyService(topicRepo, interestRepo, characterRepo);

        ewar = thema(EWAR_ID, "EWar Grundlagen", true, EWAR_LEHRER_ROLLE);
        // Der Normalfall beim Anlegen: niemand hat eine Ausbilderrolle
        // eingetragen. Die Namen sieht dann der feste Autorenkreis - nicht
        // niemand, und schon gar nicht jeder.
        ohneAusbilderrolle = thema(OHNE_AUSBILDERROLLE_ID, "Bergbau fuer Anfaenger", true);
        inaktiv = thema(INAKTIV_ID, "Zeta-Doktrin (wird ueberarbeitet)", false);

        charakter(NEULING, "Neuling", SystemRoles.USER);
        charakter(ZWEITER_NEULING, "Zweiter Neuling", SystemRoles.USER);
        charakter(EWAR_AUSBILDER, "EWar-Ausbilder", SystemRoles.USER, EWAR_LEHRER_ROLLE);
        charakter(A38_AUSBILDERIN, "Ausbilderin", A38_ROLLE);
        charakter(DIREKTORIN, "Direktorin", SystemRoles.DIRECTOR);
        charakter(SECHS_NEUN_TRAEGER, "Sechsneun", SECHS_NEUN_ROLLE);
        charakter(EX_MITGLIED_GAST, "Ehemalige", SystemRoles.GUEST);

        when(topicRepo.findById(any()))
                .thenAnswer(aufruf -> Optional.ofNullable(themen.get(aufruf.getArgument(0))));
        when(topicRepo.findAllByOrderByTitleAsc()).thenAnswer(aufruf -> themen.values().stream()
                .sorted(Comparator.comparing(AcademyTopic::getTitle))
                .toList());
        when(topicRepo.findAllByActiveTrueOrderByTitleAsc())
                .thenAnswer(aufruf -> themen.values().stream()
                        .filter(AcademyTopic::isActive)
                        .sorted(Comparator.comparing(AcademyTopic::getTitle))
                        .toList());
        when(topicRepo.findByTitleIgnoreCase(any())).thenAnswer(aufruf -> {
            String titel = aufruf.getArgument(0);
            return themen.values().stream()
                    .filter(vorhanden -> vorhanden.getTitle().equalsIgnoreCase(titel))
                    .findFirst();
        });
        // Die Datenbank vergibt beim Speichern die ID. Ohne diese Nachbildung
        // ginge ein frisch angelegtes Thema mit id == null aus saveTopic heraus.
        when(topicRepo.save(any())).thenAnswer(aufruf -> {
            AcademyTopic gespeichert = aufruf.getArgument(0);
            if (gespeichert.getId() == null) {
                gespeichert.setId(99L);
            }
            themen.put(gespeichert.getId(), gespeichert);
            return gespeichert;
        });

        // Die Attrappe filtert wie die Datenbank nach der Themen-ID. Gaebe sie
        // stur alle Bekundungen zurueck, zaehlte jede Karte die Nachfrage aller
        // Themen zusammen - und kein Test hier faende es.
        when(interestRepo.findByTopicId(any())).thenAnswer(aufruf -> bekundungen.stream()
                .filter(bekundung -> bekundung.getTopicId().equals(aufruf.getArgument(0)))
                .toList());
        when(interestRepo.findByTopicIdIn(any())).thenAnswer(aufruf -> {
            Collection<Long> themenIds = aufruf.getArgument(0);
            return bekundungen.stream()
                    .filter(bekundung -> themenIds.contains(bekundung.getTopicId()))
                    .toList();
        });
        when(interestRepo.findByTopicIdAndAccountId(any(), any()))
                .thenAnswer(aufruf -> bekundungen.stream()
                        .filter(bekundung -> bekundung.getTopicId().equals(aufruf.getArgument(0))
                                && bekundung.getAccountId().equals(aufruf.getArgument(1)))
                        .findFirst());
        when(interestRepo.save(any())).thenAnswer(aufruf -> {
            AcademyInterest gespeichert = aufruf.getArgument(0);
            if (gespeichert.getId() == null) {
                gespeichert.setId(naechsteBekundungsId++);
                bekundungen.add(gespeichert);
            }
            return gespeichert;
        });
        doAnswer(aufruf -> {
            bekundungen.remove((AcademyInterest) aufruf.getArgument(0));
            // Ausdruecklich null: delete ist void, und ein Antwortwert waere
            // hier nur eine Falle fuer den naechsten Leser.
            return null;
        }).when(interestRepo).delete(any());

        when(characterRepo.findById(any()))
                .thenAnswer(aufruf -> Optional.ofNullable(charaktere.get(aufruf.getArgument(0))));
        // Unbekannte IDs fallen heraus, wie bei findAllById auch - sonst bliebe
        // die Regel "nicht aufloesbar zaehlt nicht" ungeprueft.
        when(characterRepo.findAllById(any())).thenAnswer(aufruf -> {
            Collection<Long> ids = aufruf.getArgument(0);
            return ids.stream().map(charaktere::get).filter(java.util.Objects::nonNull).toList();
        });
    }

    // ==================================================================
    // Regel 1 bis 4: wer die Namen der Interessenten sieht
    // ==================================================================

    @Test
    @DisplayName("Regel 1: Ein Mitglied ohne Ausbilderrolle bekommt eine Ausnahme, keine leere Liste")
    void mitgliedOhneAusbilderrolleBekommtKeineNamensliste() {
        // Eine leere Liste waere eine Falschaussage: sie behauptete "niemand hat
        // Interesse" und liesse sich von "Thema existiert, ist aber unbeachtet"
        // nicht unterscheiden. Die Oberflaeche zeigte dann eine leere
        // Ausbilderansicht statt eines Hinweises auf fehlende Rechte - und
        // niemand suchte den Fehler bei den Rechten.
        bekundung(EWAR_ID, ZWEITER_NEULING, Set.of("TUESDAY"), Set.of("EU_PRIME"));

        assertThatThrownBy(() -> service.interestedIn(NEULING, EWAR_ID))
                .isInstanceOf(AccessDeniedException.class);

        // Nichtwirkung: die Bekundungen werden gar nicht erst gelesen. Ohne
        // diese Zeile bliebe unbemerkt, wenn die Pruefung erst hinter dem Laden
        // stuende - dann ginge die Liste zwar nicht hinaus, waere aber gebaut.
        verify(interestRepo, never()).findByTopicId(any());
    }

    @Test
    @DisplayName("Regel 2: Ein Thema ohne Ausbilderrollen oeffnet den Namenskreis nicht fuer jeden")
    void leereAusbilderrollenOeffnenDenKreisNicht() {
        // Die gefaehrlichste Missdeutung der leeren Menge. Ein "keine
        // Ausbilderrolle eingetragen, also darf jeder" ist eine Zeile Code
        // entfernt (anyMatch weglassen, isEmpty als true werten) - und es
        // betraefe genau den Normalfall, denn beim Anlegen traegt niemand eine
        // Ausbilderrolle ein. Die Namensliste jedes neuen Themas laege dann
        // offen.
        assertThat(ohneAusbilderrolle.getTeacherRoleNames()).isEmpty();
        bekundung(OHNE_AUSBILDERROLLE_ID, NEULING, Set.of("MONDAY"), Set.of("AUTZ"));

        assertThatThrownBy(() -> service.interestedIn(ZWEITER_NEULING, OHNE_AUSBILDERROLLE_ID))
                .isInstanceOf(AccessDeniedException.class);
        // Auch wer anderswo Ausbilder ist, kommt hier nicht hinein: die Rolle
        // gilt fuer sein Thema und nicht fuer alle.
        assertThatThrownBy(() -> service.interestedIn(EWAR_AUSBILDER, OHNE_AUSBILDERROLLE_ID))
                .isInstanceOf(AccessDeniedException.class);

        // Die andere Missdeutung, "leer heisst niemand": der feste Autorenkreis
        // muss die Namen sehen, sonst haette ein Thema ohne eingetragene
        // Ausbilderrolle gar keine Aufsicht mehr.
        assertThat(service.interestedIn(DIREKTORIN, OHNE_AUSBILDERROLLE_ID))
                .extracting(AcademyDtos.InterestDto::characterName)
                .containsExactly("Neuling");
    }

    @Test
    @DisplayName("Regel 3: Wer eine Ausbilderrolle des Themas traegt, sieht die Namen - auch ohne Fuehrungsrolle")
    void ausbilderrolleGenuegtFuerDieNamensliste() {
        // Ohne diesen Weg waere teacherRoleNames ein Feld ohne Wirkung, und der
        // Sichtkreis liesse sich nur noch ueber eine Fuehrungsrolle erweitern -
        // also nur, indem man jemandem viel mehr gibt, als er braucht.
        bekundung(EWAR_ID, NEULING, Set.of("TUESDAY", "THURSDAY"), Set.of("EU_PRIME"));

        List<AcademyDtos.InterestDto> namen = service.interestedIn(EWAR_AUSBILDER, EWAR_ID);

        assertThat(namen).hasSize(1);
        assertThat(namen.getFirst().characterName()).isEqualTo("Neuling");
        assertThat(namen.getFirst().weekdays()).containsExactly("TUESDAY", "THURSDAY");

        // Und das Kennzeichen im Datensatz sagt dasselbe - je Thema getrennt,
        // damit die Oberflaeche den Aufklapp-Knopf nur dort anbietet, wo er
        // auch traegt.
        Map<Long, AcademyDtos.TopicDto> karten = kartenFuer(EWAR_AUSBILDER);
        assertThat(karten.get(EWAR_ID).canViewInterest()).isTrue();
        assertThat(karten.get(OHNE_AUSBILDERROLLE_ID).canViewInterest()).isFalse();
        // Er ist Ausbilder, aber kein Autor: Themen pflegen darf er nicht.
        assertThat(karten.get(EWAR_ID).canEdit()).isFalse();
    }

    @Test
    @DisplayName("Regel 4: Ein unbekanntes Thema liefert dem Unberechtigten dieselbe Meldung wie ein bekanntes")
    void unbekanntesThemaVerraetSeineNichtexistenzNicht() {
        // Sonst waere der Endpunkt ein Existenz-Orakel: wer reihum IDs abfragt,
        // liest an "unbekannt" gegen "verboten" ab, welche Themen es gibt - auch
        // die abgeschalteten, an denen gerade jemand arbeitet.
        Throwable beiBekanntemThema = catchThrowable(() -> service.interestedIn(NEULING, EWAR_ID));
        Throwable beiUnbekanntemThema =
                catchThrowable(() -> service.interestedIn(NEULING, UNBEKANNTES_THEMA_ID));

        assertThat(beiBekanntemThema).isInstanceOf(AccessDeniedException.class);
        assertThat(beiUnbekanntemThema).isInstanceOf(AccessDeniedException.class);
        assertThat(beiUnbekanntemThema.getMessage()).isEqualTo(beiBekanntemThema.getMessage());

        // Wer sehen darf, bekommt dagegen die ehrliche Auskunft: fuer ihn ist
        // "unbekannt" keine geheime Information mehr.
        assertThatThrownBy(() -> service.interestedIn(DIREKTORIN, UNBEKANNTES_THEMA_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unbekannt");
    }

    // ==================================================================
    // Regel 5 und 6: die eigene Bekundung
    // ==================================================================

    @Test
    @DisplayName("Regel 5: Zweimal bekunden erzeugt eine Zeile, nicht zwei - die zweite ueberschreibt")
    void zweimalBekundenErzeugtNurEineZeile() {
        // Ohne den Riegel zaehlte ein Mensch, der seine Auswahl dreimal
        // korrigiert, dreifach - und das Nachfragebild, der einzige Ertrag des
        // ganzen Boards, waere frei erfunden.
        service.saveInterest(NEULING, EWAR_ID, bekundungsWunsch(List.of("TUESDAY"), List.of("EU_PRIME"), null));
        service.saveInterest(NEULING, EWAR_ID,
                bekundungsWunsch(List.of("THURSDAY", "SUNDAY"), List.of("USTZ"), "doch lieber spaet"));

        assertThat(bekundungen).hasSize(1);
        AcademyInterest einzige = bekundungen.getFirst();
        assertThat(einzige.getWeekdays()).containsExactlyInAnyOrder("THURSDAY", "SUNDAY");
        assertThat(einzige.getTimeWindows()).containsExactly("USTZ");
        assertThat(einzige.getNote()).isEqualTo("doch lieber spaet");
        assertThat(kartenFuer(NEULING).get(EWAR_ID).interestCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Regel 6: Ein unbekannter Wochentag wird abgewiesen und nichts gespeichert")
    void unbekannterWochentagWirdAbgewiesen() {
        // Erst pruefen, dann schreiben. Ohne die Reihenfolge stuende nach einem
        // Tippfehler "MONTAG" in der Tabelle: die Verteilung zaehlte ihn nie
        // mit, die Zeile bliebe aber liegen, und der Mensch dahinter waere in
        // der Nachfrage sichtbar, ohne dass jemand saehe, wann er kann.
        assertThatThrownBy(() -> service.saveInterest(NEULING, EWAR_ID,
                bekundungsWunsch(List.of("MONDAY", "MONTAG"), List.of("EU_PRIME"), null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MONTAG");

        verify(interestRepo, never()).save(any());
        assertThat(bekundungen).isEmpty();
    }

    @Test
    @DisplayName("Regel 6b: Ein unbekanntes Zeitfenster wird abgewiesen und nichts gespeichert")
    void unbekanntesZeitfensterWirdAbgewiesen() {
        // Dieselbe Falle eine Spalte weiter. Die fuenf Fenster sind Konstanten
        // und kein Enum - ohne diese Pruefung an der Grenze faende ein
        // Tippfehler ueberhaupt keinen Widerstand mehr.
        assertThatThrownBy(() -> service.saveInterest(NEULING, EWAR_ID,
                bekundungsWunsch(List.of("MONDAY"), List.of("EU_PRIME", "NACHTSCHICHT"), null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("NACHTSCHICHT");

        verify(interestRepo, never()).save(any());
        assertThat(bekundungen).isEmpty();
    }

    @Test
    @DisplayName("Leere Eintraege fallen still heraus, aber ohne Tag oder ohne Fenster gibt es keine Bekundung")
    void bekundungBrauchtTagUndFenster() {
        // Die leere Zeichenkette kommt aus einem noch nicht ausgefuellten
        // Formularfeld und darf keinen Fehler ausloesen - danach ist die Menge
        // aber leer, und eine Bekundung ohne Zeitangabe zaehlt im Zaehler mit,
        // ohne irgendetwas ueber einen moeglichen Termin zu sagen. Genau die
        // Auskunft, um die es geht, waere verwaessert.
        assertThatThrownBy(() -> service.saveInterest(NEULING, EWAR_ID,
                bekundungsWunsch(List.of("", "  "), List.of("EU_PRIME"), null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mindestens einen Tag");

        verify(interestRepo, never()).save(any());
        assertThat(bekundungen).isEmpty();
    }

    @Test
    @DisplayName("Ein abgeschaltetes Thema nimmt keine neue Bekundung an")
    void inaktivesThemaNimmtKeineBekundung() {
        // Ohne die Pruefung waechst die Nachfrage an einem Thema weiter, das in
        // keiner Liste mehr steht - von jedem, der die Seite noch offen hat.
        Throwable abgewiesen = catchThrowable(() -> service.saveInterest(NEULING, INAKTIV_ID,
                bekundungsWunsch(List.of("MONDAY"), List.of("AUTZ"), null)));
        assertThat(abgewiesen).isInstanceOf(IllegalArgumentException.class);

        // Die Meldung darf den Titel NICHT nennen. Sie geht an jeden, der eine
        // ID durchprobiert, und benennte sonst ausgerechnet das Thema, dessen
        // Abschaltung sie durchsetzt - "Zeta-Doktrin (wird ueberarbeitet)"
        // verraet einem Mitglied genau das, was es nicht wissen soll.
        assertThat(abgewiesen.getMessage()).doesNotContain(inaktiv.getTitle());

        verify(interestRepo, never()).save(any());
    }

    // ==================================================================
    // Die gefaehrlichste Stelle: eine Bekundung gehoert immer dem Angemeldeten
    // ==================================================================

    @Test
    @DisplayName("Die Bekundung wird immer auf den Account aus der Sitzung geschrieben")
    void bekundungGehoertImmerDemAngemeldeten() {
        // Der Datensatz SaveInterestDto traegt keine accountId - es gibt also
        // gar keinen Wert, den der Dienst faelschlich uebernehmen koennte. Diese
        // Zusicherung ist der Grund, warum es hier keine "gehoert die Zeile
        // wirklich dir?"-Pruefung gibt. Wer den Datensatz spaeter um ein solches
        // Feld erweitert, hebt sie auf - und dieser Test faellt.
        service.saveInterest(ZWEITER_NEULING, EWAR_ID,
                bekundungsWunsch(List.of("FRIDAY"), List.of("USTZ"), null));

        ArgumentCaptor<AcademyInterest> gespeichert = ArgumentCaptor.forClass(AcademyInterest.class);
        verify(interestRepo).save(gespeichert.capture());
        assertThat(gespeichert.getValue().getAccountId()).isEqualTo(ZWEITER_NEULING);
        assertThat(gespeichert.getValue().getTopicId()).isEqualTo(EWAR_ID);
    }

    @Test
    @DisplayName("Zuruecknehmen loescht die eigene Zeile - und nur die eigene")
    void zuruecknehmenLoeschtNurDieEigeneZeile() {
        // Auch hier gibt es keinen Parameter fuer eine fremde ID. Gaebe es ihn,
        // koennte jeder Angemeldete reihum jede Bekundung der Corp abraeumen und
        // das Nachfragebild leerfegen, ohne dass es jemandem auffiele - es
        // faellt ja nichts aus, es steht nur nichts mehr da.
        bekundung(EWAR_ID, NEULING, Set.of("MONDAY"), Set.of("AUTZ"));
        bekundung(EWAR_ID, ZWEITER_NEULING, Set.of("MONDAY"), Set.of("AUTZ"));

        service.withdrawInterest(NEULING, EWAR_ID);

        assertThat(bekundungen).hasSize(1);
        assertThat(bekundungen.getFirst().getAccountId()).isEqualTo(ZWEITER_NEULING);
    }

    @Test
    @DisplayName("Wer nichts bekundet hat, bekommt beim Zuruecknehmen einen Fehler statt eines stillen Erfolgs")
    void zuruecknehmenOhneBekundungMeldetSich() {
        // Ein stilles "erledigt" verdeckte eine veraltete Anzeige oder einen
        // falsch verdrahteten Knopf: der Aufrufer glaubte, etwas bewirkt zu
        // haben, und die Karte behielte ihren Zaehler.
        assertThatThrownBy(() -> service.withdrawInterest(NEULING, EWAR_ID))
                .isInstanceOf(IllegalArgumentException.class);

        verify(interestRepo, never()).delete(any());
    }

    // ==================================================================
    // Regel 7: Wer die Corp verlaesst, zaehlt nicht mehr mit
    // ==================================================================

    @Test
    @DisplayName("Regel 7: Ein Account mit ROLE_GUEST zaehlt nicht mit und steht nicht in der Namensliste")
    void gastZaehltNichtUndErscheintNichtInDerListe() {
        // Wer die Corp verlaesst, wird nicht geloescht - CharacterRoleService
        // setzt die Rollen auf ROLE_GUEST, die Bekundung bleibt stehen. Ohne
        // diesen Filter meldete die Karte "3 wollen EWar", von denen einer seit
        // Monaten nicht mehr da ist, und ein FC plante eine Schulung fuer eine
        // Nachfrage, die es nicht gibt.
        bekundung(EWAR_ID, NEULING, Set.of("TUESDAY"), Set.of("EU_PRIME"));
        bekundung(EWAR_ID, ZWEITER_NEULING, Set.of("TUESDAY"), Set.of("EU_PRIME"));
        bekundung(EWAR_ID, EX_MITGLIED_GAST, Set.of("SATURDAY"), Set.of("WEEKEND_DAY"));

        AcademyDtos.TopicDto karte = kartenFuer(DIREKTORIN).get(EWAR_ID);

        assertThat(karte.interestCount()).isEqualTo(2);
        // Zaehler und Namensliste entstehen aus derselben gefilterten Menge -
        // eine Karte, die "3" sagt und beim Aufklappen 2 Namen zeigt, waere ein
        // Fehlerbericht.
        assertThat(service.interestedIn(DIREKTORIN, EWAR_ID))
                .extracting(AcademyDtos.InterestDto::characterName)
                .containsExactly("Neuling", "Zweiter Neuling");
        // Und der Samstag des Gastes taucht in der Verteilung nicht auf.
        assertThat(karte.weekdayCounts().get("SATURDAY")).isZero();
        assertThat(karte.weekdayCounts().get("TUESDAY")).isEqualTo(2);

        // Seine eigene Zeile sieht der Gast weiterhin, sonst koennte er sie nie
        // zurueckziehen. Sie zaehlt nur nirgends mit.
        AcademyDtos.TopicDto ausSicherDesGastes = kartenFuer(EX_MITGLIED_GAST).get(EWAR_ID);
        assertThat(ausSicherDesGastes.hasMyInterest()).isTrue();
        assertThat(ausSicherDesGastes.interestCount()).isEqualTo(2);
    }

    // ==================================================================
    // Regel 8: die Bild-Allowlist
    // ==================================================================

    @Test
    @DisplayName("Regel 8: Ein Bild von fremdem Host wird abgewiesen, die Meldung nennt den Host")
    void fremderBildhostWirdAbgewiesen() {
        // Jeder Betrachterbrowser holt das Bild direkt beim fremden Host - der
        // loggt IP und User-Agent der halben Corp-Leitung. Ohne die Meldung mit
        // dem Hostnamen suchte der Autor den Fehler bei sich; mit ihr weiss er
        // sofort, woran es liegt.
        assertThatThrownBy(() -> service.saveTopic(DIREKTORIN, themenWunsch(null,
                "Neues Thema", "Kurzzeile",
                "## Inhalt\n![Bild](https://i.imgur.com/abc.png)")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("i.imgur.com");

        // Nichtwirkung: das Thema entsteht gar nicht erst. Stuende die Pruefung
        // hinter dem Speichern, laege der Lehrplan mit dem fremden Bild bereits
        // in der Tabelle und wuerde ausgeliefert.
        verify(topicRepo, never()).save(any());
    }

    @Test
    @DisplayName("Ein Host wird exakt verglichen: images.evetech.net.boeser-host.example ist nicht erlaubt")
    void aehnlicherHostWirdNichtVerwechselt() {
        // Die Falle bei jeder Allowlist: ein endsWith oder contains liesse
        // images.evetech.net.boeser-host.example durch, und diese Adresse kann
        // jeder registrieren. Sie sieht auf den ersten Blick aus wie die
        // erlaubte - genau darauf setzt sie.
        assertThatThrownBy(() -> service.saveTopic(DIREKTORIN, themenWunsch(null,
                "Getarnt", "Kurzzeile",
                "![x](https://images.evetech.net.boeser-host.example/px.png)")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("images.evetech.net.boeser-host.example");

        // Auch die Benutzerangabe vor dem @ taeuscht den Host nicht vor.
        assertThatThrownBy(() -> service.saveTopic(DIREKTORIN, themenWunsch(null,
                "Getarnt zwei", "Kurzzeile",
                "![x](https://images.evetech.net@boeser-host.example/px.png)")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("boeser-host.example");

        // Und data: kommt in keiner Stufe hinein - Angulars Sanitizer laesst es
        // durch, wir nicht.
        assertThatThrownBy(() -> service.saveTopic(DIREKTORIN, themenWunsch(null,
                "Eingebettet", "Kurzzeile", "![x](data:image/png;base64,AAAA)")))
                .isInstanceOf(IllegalArgumentException.class);

        verify(topicRepo, never()).save(any());
    }

    @Test
    @DisplayName("Bilder von den beiden erlaubten Hosts gehen durch")
    void erlaubteBildhostsGehenDurch() {
        // Die Gegenprobe zur Allowlist: eine Liste, die alles abweist, waere
        // ebenso kaputt wie eine, die alles durchlaesst - nur faellt es
        // niemandem auf, weil die Autoren dann eben keine Bilder benutzen.
        AcademyDtos.TopicDto gespeichert = service.saveTopic(DIREKTORIN, themenWunsch(null,
                "Mit Bildern", "Kurzzeile",
                "![Render](https://images.evetech.net/types/47408/render)\n"
                        + "![Wiki](https://wiki.eveuniversity.org/bild.png)"));

        assertThat(gespeichert.title()).isEqualTo("Mit Bildern");
        verify(topicRepo).save(any());
    }

    // ==================================================================
    // Die Pflege der Themen
    // ==================================================================

    @Test
    @DisplayName("Themen pflegen darf nur der Autorenkreis - ROLE_69 gehoert dazu, ein Neuling nicht")
    void themenpflegeNurFuerDenAutorenkreis() {
        // Die Annotation am AcademyAdminController prueft dasselbe, aber sie
        // haengt an einem Einstiegspunkt. Faellt sie bei einem Umbau weg,
        // koennte jeder Angemeldete Lehrplaene umschreiben - und ein Lehrplan
        // ist genau der Ort, an dem ein Director in Ruhe hineinschaut.
        assertThatThrownBy(() -> service.saveTopic(NEULING,
                themenWunsch(null, "Fremdes Thema", "Kurzzeile", null)))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.deleteTopic(NEULING, EWAR_ID))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.allTopicsFor(NEULING))
                .isInstanceOf(AccessDeniedException.class);
        verify(topicRepo, never()).save(any());
        verify(topicRepo, never()).deleteById(any());

        // Auch die Ausbilderrolle eines Themas macht niemanden zum Autor: sie
        // oeffnet die Namensliste und sonst nichts.
        assertThatThrownBy(() -> service.saveTopic(EWAR_AUSBILDER,
                themenWunsch(null, "Fremdes Thema", "Kurzzeile", null)))
                .isInstanceOf(AccessDeniedException.class);

        // ROLE_69 steht im Autorenkreis, weil der Nutzer es so entschieden hat
        // - im Gruppen-Board ist die Rolle bewusst NICHT dabei. Wer die eine
        // Menge anfasst, aendert damit nicht die andere.
        assertThat(service.saveTopic(SECHS_NEUN_TRAEGER,
                themenWunsch(null, "Von 69 angelegt", "Kurzzeile", null)).canEdit()).isTrue();
        assertThat(service.saveTopic(A38_AUSBILDERIN,
                themenWunsch(null, "Von A38 angelegt", "Kurzzeile", null)).canEdit()).isTrue();
    }

    @Test
    @DisplayName("Loeschen raeumt die Bekundungen mit ab - es gibt keinen Fremdschluessel, der das taete")
    void loeschenRaeumtDieBekundungenMit() {
        // Bleiben sie stehen, zaehlen sie fuer immer auf ein Thema, das es nicht
        // mehr gibt: unsichtbar in der Anwendung, aber in der Tabelle - und beim
        // naechsten Thema mit derselben frisch vergebenen ID plausibel falsch.
        bekundung(EWAR_ID, NEULING, Set.of("MONDAY"), Set.of("AUTZ"));

        service.deleteTopic(DIREKTORIN, EWAR_ID);

        verify(interestRepo).deleteByTopicId(EWAR_ID);
        verify(topicRepo).deleteById(EWAR_ID);
    }

    @Test
    @DisplayName("Ein Titel wird nicht zweimal vergeben, auch nicht in anderer Schreibweise")
    void titelBleibtEindeutig() {
        // Zwei Themen "EWar Grundlagen" und "ewar grundlagen" haelt im Board
        // niemand auseinander - die Bekundungen verteilten sich auf beide, und
        // beide Karten saehen aus, als lohne sich die Schulung nicht.
        assertThatThrownBy(() -> service.saveTopic(DIREKTORIN,
                themenWunsch(null, "ewar grundlagen", "Kurzzeile", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("gibt es bereits");
        verify(topicRepo, never()).save(any());

        // Das Thema unter seinem eigenen Titel zu speichern bleibt moeglich -
        // sonst waere jede Aenderung an der Kurzzeile blockiert.
        assertThat(service.saveTopic(DIREKTORIN,
                themenWunsch(EWAR_ID, "EWar Grundlagen", "Neue Kurzzeile", null)).summary())
                .isEqualTo("Neue Kurzzeile");
    }

    @Test
    @DisplayName("Eine eingebaute Rolle taugt nicht als Ausbilderrolle")
    void eingebauteRolleTaugtNichtAlsAusbilderrolle() {
        // ROLE_USER traegt jeder Angemeldete. Als Ausbilderrolle eingetragen
        // oeffnete sie die Namensliste dieses Themas fuer die gesamte Corp - und
        // weil schon EINE passende Rolle genuegt, half auch kein "wenigstens
        // eine taugt".
        assertThatThrownBy(() -> service.saveTopic(DIREKTORIN, new AcademyDtos.SaveTopicDto(
                null, "Offenes Thema", "Kurzzeile", null, true, List.of(SystemRoles.USER))))
                .isInstanceOf(IllegalArgumentException.class);
        verify(topicRepo, never()).save(any());
    }

    // ==================================================================
    // Das Nachfragebild
    // ==================================================================

    @Test
    @DisplayName("Die Verteilung geht erst ab zwei Bekundungen hinaus - die Zahl immer")
    void verteilungErstAbZweiBekundungen() {
        // Bei genau einer Bekundung sagt "nur Mittwoch, USTZ" in einer Corp, in
        // der sich alle kennen, faktisch den Namen - und der haengt am
        // Sichtkreis, die Verteilung nicht. Ohne die Schwelle waere der ganze
        // Sichtkreis auf die Namen ueber die Verteilung zu umgehen.
        bekundung(EWAR_ID, NEULING, Set.of("WEDNESDAY"), Set.of("USTZ"));

        AcademyDtos.TopicDto beiEiner = kartenFuer(ZWEITER_NEULING).get(EWAR_ID);
        assertThat(beiEiner.interestCount()).isEqualTo(1);
        assertThat(beiEiner.weekdayCounts()).isEmpty();
        assertThat(beiEiner.windowCounts()).isEmpty();

        bekundung(EWAR_ID, ZWEITER_NEULING, Set.of("WEDNESDAY", "FRIDAY"), Set.of("EU_PRIME"));

        // Zwei Bekundungen, aber der Betrachter ist eine davon: er kennt seine
        // eigenen Tage und zieht sie ab. Uebrig bliebe das exakte Profil des
        // anderen - Tage UND Fenster -, obwohl ihm der Name verwehrt ist. Die
        // Schwelle zaehlt deshalb nur FREMDE Bekundungen.
        AcademyDtos.TopicDto ausSichtEinesBeteiligten = kartenFuer(NEULING).get(EWAR_ID);
        assertThat(ausSichtEinesBeteiligten.interestCount()).isEqualTo(2);
        assertThat(ausSichtEinesBeteiligten.weekdayCounts()).isEmpty();
        assertThat(ausSichtEinesBeteiligten.windowCounts()).isEmpty();

        // Derselbe Stand, aber von aussen betrachtet: zwei Fremde tragen die
        // Verteilung, niemand laesst sich herausrechnen.
        AcademyDtos.TopicDto beiZweien = kartenFuer(EWAR_AUSBILDER).get(EWAR_ID);
        assertThat(beiZweien.interestCount()).isEqualTo(2);
        // Alle sieben Tage, auch die mit null - nur so sind die Streifen zweier
        // Karten miteinander vergleichbar.
        assertThat(beiZweien.weekdayCounts()).hasSize(7);
        assertThat(beiZweien.weekdayCounts().keySet())
                .containsExactly("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY",
                        "FRIDAY", "SATURDAY", "SUNDAY");
        assertThat(beiZweien.weekdayCounts().get("WEDNESDAY")).isEqualTo(2);
        assertThat(beiZweien.weekdayCounts().get("FRIDAY")).isEqualTo(1);
        assertThat(beiZweien.windowCounts().keySet())
                .containsExactly("AUTZ", "EU_EARLY", "EU_PRIME", "USTZ", "WEEKEND_DAY");
    }

    @Test
    @DisplayName("Die Bekundungen bleiben bei ihrem Thema und wandern nicht auf die Nachbarkarte")
    void bekundungenBleibenBeiIhremThema() {
        // Der Zaehler entsteht aus EINEM Ladevorgang fuer alle Karten. Wer dabei
        // die Gruppierung nach Themen-ID vergisst, bekommt auf jeder Karte
        // dieselbe Gesamtzahl - und das faellt niemandem auf, solange es nur ein
        // Thema mit Bekundungen gibt.
        bekundung(EWAR_ID, NEULING, Set.of("MONDAY"), Set.of("AUTZ"));
        bekundung(EWAR_ID, ZWEITER_NEULING, Set.of("MONDAY"), Set.of("AUTZ"));
        bekundung(OHNE_AUSBILDERROLLE_ID, NEULING, Set.of("SUNDAY"), Set.of("WEEKEND_DAY"));

        Map<Long, AcademyDtos.TopicDto> karten = kartenFuer(NEULING);

        assertThat(karten.get(EWAR_ID).interestCount()).isEqualTo(2);
        assertThat(karten.get(OHNE_AUSBILDERROLLE_ID).interestCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Die Themenliste zeigt nur angebotene Themen, die Verwaltung auch die abgeschalteten")
    void abgeschalteteThemenNurInDerVerwaltung() {
        // Ein abgeschaltetes Thema ist oft eines, das gerade neu geschrieben
        // wird. Stuende es in der Liste jedes Mitglieds, sammelte es Bekundungen
        // auf einen Halbsatz.
        assertThat(service.topicsFor(NEULING))
                .extracting(AcademyDtos.TopicDto::id)
                .containsExactlyInAnyOrder(EWAR_ID, OHNE_AUSBILDERROLLE_ID);
        assertThat(service.allTopicsFor(DIREKTORIN))
                .extracting(AcademyDtos.TopicDto::id)
                .contains(INAKTIV_ID);
    }

    @Test
    @DisplayName("Ein abgeschaltetes Thema laesst sich auch nicht per ID aufklappen")
    void abgeschaltetesThemaIstAuchEinzelnVerschlossen() {
        // Ohne diese Sperre waere das Abschalten wirkungslos: die IDs laufen
        // fortlaufend, und wer 1..n durchprobiert, bekaeme jeden zurueckgezogenen
        // Lehrplan im Volltext - samt Titel und Kurzzeile. Dass die Liste das
        // Thema verschweigt, nuetzt dann nichts.
        Throwable beiAbgeschaltetem =
                catchThrowable(() -> service.topicDetail(NEULING, INAKTIV_ID));
        Throwable beiUnbekanntem =
                catchThrowable(() -> service.topicDetail(NEULING, UNBEKANNTES_THEMA_ID));

        assertThat(beiAbgeschaltetem).isInstanceOf(IllegalArgumentException.class);
        // Wortgleich mit "gibt es nicht" - bis auf die ID, die der Fragende
        // ohnehin selbst geschickt hat und die deshalb nichts verraet. Ein
        // eigener Text verriete dagegen, dass es das Thema gibt und dass jemand
        // es versteckt hat: dasselbe Orakel, nur eine Tuer weiter.
        assertThat(beiAbgeschaltetem.getMessage().replace(INAKTIV_ID.toString(), "#"))
                .isEqualTo(beiUnbekanntem.getMessage().replace(UNBEKANNTES_THEMA_ID.toString(), "#"));
        assertThat(beiAbgeschaltetem.getMessage()).doesNotContain(inaktiv.getTitle());

        // Der Autorenkreis klappt es weiterhin auf - er schreibt es ja gerade um.
        assertThat(service.topicDetail(DIREKTORIN, INAKTIV_ID).topic().id())
                .isEqualTo(INAKTIV_ID);
    }

    @Test
    @DisplayName("Der Lehrplan kommt erst beim Aufklappen, die eigene Auswahl schon mit der Liste")
    void lehrplanKommtErstBeimAufklappen() {
        // Der Datensatz der Liste hat gar kein Feld fuer den Lehrplan - bei
        // zwoelf Themen gingen sonst zwoelf Lehrplaene ueber die Leitung, bei
        // jedem Laden. Die eigene Bekundung reist dagegen mit, damit die
        // Oberflaeche mit einem Ladevorgang auskommt.
        service.saveTopic(DIREKTORIN, themenWunsch(EWAR_ID, "EWar Grundlagen", "Kurzzeile",
                "## Inhalt\n- Dampener gegen Logi"));
        service.saveInterest(NEULING, EWAR_ID,
                bekundungsWunsch(List.of("SUNDAY", "MONDAY", "WEDNESDAY"), List.of("USTZ", "AUTZ"), "bin gespannt"));

        AcademyDtos.TopicDto karte = kartenFuer(NEULING).get(EWAR_ID);
        assertThat(karte.hasMyInterest()).isTrue();
        assertThat(karte.myNote()).isEqualTo("bin gespannt");
        // In Wochen- und Tagesordnung, nicht alphabetisch: "FRIDAY, MONDAY,
        // SATURDAY" waere fuer das Auge wertlos.
        assertThat(karte.myWeekdays()).containsExactly("MONDAY", "WEDNESDAY", "SUNDAY");
        assertThat(karte.myTimeWindows()).containsExactly("AUTZ", "USTZ");

        AcademyDtos.TopicDetailDto detail = service.topicDetail(NEULING, EWAR_ID);
        assertThat(detail.description()).contains("Dampener gegen Logi");
        assertThat(detail.topic().id()).isEqualTo(EWAR_ID);
    }

    // ==================================================================
    // Aufbau der Testdaten
    // ==================================================================

    /** Ohne Ausbilderrolle aufgerufen entsteht ein Thema mit leerer Menge - kein {@code null}. */
    private AcademyTopic thema(Long id, String titel, boolean aktiv, String... teacherRoleNames) {
        AcademyTopic thema = new AcademyTopic();
        thema.setId(id);
        thema.setTitle(titel);
        thema.setSummary("Kurzzeile von " + titel);
        thema.setActive(aktiv);
        thema.setCreatedAt(Instant.parse("2026-08-01T10:00:00Z"));
        thema.setUpdatedAt(Instant.parse("2026-08-01T10:00:00Z"));
        thema.getTeacherRoleNames().addAll(Set.of(teacherRoleNames));
        themen.put(id, thema);
        return thema;
    }

    /** Das Rollen-Set muss veraenderlich sein - der Rollen-Sync schreibt daran. */
    private void charakter(Long id, String name, String... roles) {
        Character charakter = new Character();
        charakter.setId(id);
        charakter.setName(name);
        charakter.setRoles(new HashSet<>(Set.of(roles)));
        charaktere.put(id, charakter);
    }

    private void bekundung(Long topicId, Long accountId, Set<String> weekdays,
                           Set<String> timeWindows) {
        AcademyInterest bekundung = new AcademyInterest();
        bekundung.setId(naechsteBekundungsId++);
        bekundung.setTopicId(topicId);
        bekundung.setAccountId(accountId);
        bekundung.getWeekdays().addAll(weekdays);
        bekundung.getTimeWindows().addAll(timeWindows);
        bekundung.setCreatedAt(Instant.parse("2026-08-10T18:00:00Z"));
        bekundung.setUpdatedAt(Instant.parse("2026-08-10T18:00:00Z"));
        bekundungen.add(bekundung);
    }

    private static AcademyDtos.SaveInterestDto bekundungsWunsch(List<String> weekdays,
                                                               List<String> timeWindows,
                                                               String note) {
        return new AcademyDtos.SaveInterestDto(weekdays, timeWindows, note);
    }

    private static AcademyDtos.SaveTopicDto themenWunsch(Long id, String titel, String kurzzeile,
                                                         String lehrplan) {
        return new AcademyDtos.SaveTopicDto(id, titel, kurzzeile, lehrplan, true, List.of());
    }

    /**
     * Die Karten, die dieser Betrachter tatsaechlich aufklappen kann.
     *
     * <p>Ein abgeschaltetes Thema faellt fuer jeden ausser dem Autorenkreis
     * heraus - genau wie in der Anwendung, wo es in keiner Liste steht. Der
     * Helfer bildet das nach, statt die Sperre zu umgehen: sonst pruefte jeder
     * Test, der ihn benutzt, einen Zustand, den es gar nicht gibt.</p>
     */
    private Map<Long, AcademyDtos.TopicDto> kartenFuer(Long accountId) {
        Map<Long, AcademyDtos.TopicDto> karten = new HashMap<>();
        for (AcademyTopic thema : themen.values()) {
            try {
                karten.put(thema.getId(), service.topicDetail(accountId, thema.getId()).topic());
            } catch (IllegalArgumentException fuerIhnUnsichtbar) {
                // Keine Karte - und das ist die richtige Antwort, kein Fehler.
            }
        }
        return karten;
    }
}
