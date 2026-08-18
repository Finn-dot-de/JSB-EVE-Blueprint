package com.eve.own.auth.backend.domain.groups;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.eve.own.auth.backend.domain.auth.SystemRoles;
import com.eve.own.auth.backend.domain.auth.entity.SystemRole;
import com.eve.own.auth.backend.domain.auth.repository.SystemRoleRepository;
import com.eve.own.auth.backend.domain.character.entity.Character;
import com.eve.own.auth.backend.domain.character.repository.CharacterRepository;
import com.eve.own.auth.backend.domain.groups.dto.AuthGroupDtos;
import com.eve.own.auth.backend.domain.groups.entity.AuthGroup;
import com.eve.own.auth.backend.domain.groups.entity.AuthGroupRequest;
import com.eve.own.auth.backend.domain.groups.repository.AuthGroupRepository;
import com.eve.own.auth.backend.domain.groups.repository.AuthGroupRequestRepository;
import com.eve.own.auth.backend.domain.groups.service.AuthGroupService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
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
 * Die Gruppen (SIGs) sind ein Rollenautomat: eine angenommene Anfrage haengt dem
 * Charakter einen Rollennamen an, und der Discord-Sync verteilt daraufhin Rechte,
 * ohne noch einmal zu fragen, woher die Rolle kam.
 *
 * <p>Damit ist die Entscheidung ueber eine Anfrage der einzige Punkt, an dem
 * ueberhaupt noch jemand hinsieht. Die Oberflaeche zaehlt dabei nicht: sie blendet
 * den Reiter "Verwaltung" aus, aber {@code POST /api/groups/requests/{id}/approve}
 * steht jedem Angemeldeten offen. Was hier nicht geprueft wird, ist ungeprueft.</p>
 *
 * <p>Deshalb sichert dieser Test die drei Regeln, an denen alles haengt, sowie
 * die Wirkungen, die man ihnen ansieht: dass eine Annahme die Rolle wirklich
 * setzt, dass ein Austritt sie wirklich wieder abnimmt und dass aus einer Person
 * und einer Gruppe nur eine offene Anfrage entstehen kann.</p>
 *
 * <p>Die Zustaendigkeit haengt an Rollen, nicht an einer Person. Das ist kein
 * Namenstausch, sondern der Ausweg aus einer Sackgasse: der einzige Leiter
 * einer Gruppe konnte sich frueher bei ihr bewerben und blieb dann auf seinem
 * eigenen Antrag sitzen, weil niemand sonst zustaendig war. Die Tests spannen
 * deshalb zwei Traeger derselben Leitungsrolle auf - erst daran laesst sich
 * sehen, dass der eine fuer den anderen entscheiden kann.</p>
 *
 * <p>Und es sind mehrere Rollen je Gruppe: ueber "Cap Azubi" entscheiden
 * Direktoren <em>und</em> CEOs, ueber "Blops" die Strat-FCs <em>und</em> die
 * Skirmish-FCs. Die Blops-SIG steht deshalb hier - an einer Gruppe mit nur
 * einer Leitungsrolle liesse sich nicht auseinanderhalten, ob der Dienst die
 * Menge wirklich schneidet oder bloss ihren ersten Eintrag vergleicht.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Gruppen (SIGs): wer entscheiden darf und was dabei passiert")
class AuthGroupServiceTest {

    private static final Long WURMLOCH_ID = 1L;
    private static final Long LOGISTIK_ID = 2L;
    private static final Long HERRENLOS_ID = 3L;
    private static final Long BLOPS_ID = 4L;
    private static final String WURMLOCH_ROLE = "ROLE_WURMLOCH_SIG";
    private static final String LOGISTIK_ROLE = "ROLE_LOGISTIK_SIG";
    private static final String HERRENLOS_ROLE = "ROLE_HERRENLOS_SIG";
    private static final String BLOPS_ROLE = "ROLE_BLOPS_SIG";

    /** Die Leitungsrollen - jede von mehreren Charakteren tragbar, genau das ist der Punkt. */
    private static final String WURMLOCH_LEADER_ROLE = "ROLE_FC_WURMLOCH";
    private static final String LOGISTIK_LEADER_ROLE = "ROLE_FC_LOGISTIK";

    /** Zwei gleichberechtigte Leitungen einer Gruppe - der Fall aus der Praxis. */
    private static final String FC_STRAT = "ROLE_FC_STRAT";
    private static final String FC_SKIRMISH = "ROLE_FC_SKIRMISH";

    private static final Long ANTRAGSTELLER = 1000L;
    private static final Long ZWEITER_ANTRAGSTELLER = 1001L;
    private static final Long WURMLOCH_LEITER = 2000L;
    private static final Long ZWEITER_WURMLOCH_LEITER = 2001L;
    private static final Long LOGISTIK_LEITER = 3000L;
    private static final Long ADMIN = 4000L;
    private static final Long MITGLIED_OHNE_AMT = 5000L;
    private static final Long STRAT_FC = 6000L;
    private static final Long SKIRMISH_FC = 6001L;
    private static final Long ARMOR_FC = 6002L;

    private static final Long ANFRAGE_WURMLOCH = 10L;
    private static final Long ANFRAGE_LOGISTIK = 11L;
    private static final Long ANFRAGE_DES_LEITERS = 12L;
    private static final Long ANFRAGE_BLOPS = 13L;

    @Mock private AuthGroupRepository groupRepo;
    @Mock private AuthGroupRequestRepository requestRepo;
    @Mock private CharacterRepository characterRepo;
    @Mock private SystemRoleRepository systemRoleRepo;

    private AuthGroupService service;

    private AuthGroup wurmloch;
    private AuthGroup logistik;
    private AuthGroup herrenlos;
    private AuthGroup blops;
    /** Ein IT-Admin - die einzige Rolle, die ueber den eigenen Antrag entscheidet. */
    private static final Long IT_ADMIN_ID = 9100L;
    /** Ein CEO - die Gegenprobe dazu: die Ausnahme gilt fuer ihn ausdruecklich nicht. */
    private static final Long CEO_ID = 9200L;

    private final Map<Long, Character> charaktere = new HashMap<>();
    private final List<AuthGroupRequest> offeneAnfragen = new ArrayList<>();

    @BeforeEach
    void setUp() {
        service = new AuthGroupService(groupRepo, requestRepo, characterRepo, systemRoleRepo);

        wurmloch = gruppe(WURMLOCH_ID, "Wurmloch-SIG", WURMLOCH_ROLE, WURMLOCH_LEADER_ROLE);
        logistik = gruppe(LOGISTIK_ID, "Logistik-SIG", LOGISTIK_ROLE, LOGISTIK_LEADER_ROLE);
        // Eine Gruppe ohne hinterlegte Leitung ist der Normalfall beim Anlegen;
        // ueber sie entscheiden dann nur die Admins.
        herrenlos = gruppe(HERRENLOS_ID, "Herrenlos-SIG", HERRENLOS_ROLE);
        // Zwei Leitungsrollen nebeneinander: der Fall, den es ohne die Menge
        // gar nicht gaebe. Beide entscheiden, keiner von beiden hat Vorrang.
        blops = gruppe(BLOPS_ID, "Blops-SIG", BLOPS_ROLE, FC_STRAT, FC_SKIRMISH);

        charakter(ANTRAGSTELLER, "Antragsteller");
        charakter(ZWEITER_ANTRAGSTELLER, "Zweiter Antragsteller");
        charakter(WURMLOCH_LEITER, "Wurmloch-FC", WURMLOCH_LEADER_ROLE);
        charakter(ZWEITER_WURMLOCH_LEITER, "Zweiter Wurmloch-FC", WURMLOCH_LEADER_ROLE);
        charakter(LOGISTIK_LEITER, "Logistik-FC", LOGISTIK_LEADER_ROLE);
        charakter(ADMIN, "Direktorin", SystemRoles.DIRECTOR);
        charakter(MITGLIED_OHNE_AMT, "Gewoehnliches Mitglied", SystemRoles.MEMBER);
        // Je ein Traeger der einen, der anderen und keiner der beiden
        // Blops-Leitungsrollen.
        charakter(STRAT_FC, "Strat-FC", FC_STRAT);
        charakter(SKIRMISH_FC, "Skirmish-FC", FC_SKIRMISH);
        charakter(ARMOR_FC, "Armor-FC", "ROLE_FC_ARMOR");

        // Drei offene Anfragen, absichtlich ueber beide Gruppen verteilt und eine
        // davon vom Wurmloch-Leiter selbst - erst damit laesst sich sehen, wessen
        // Anfragen jemand zu Gesicht bekommt und wessen nicht.
        anfrage(ANFRAGE_WURMLOCH, WURMLOCH_ID, ANTRAGSTELLER, Instant.parse("2026-08-01T10:00:00Z"));
        anfrage(ANFRAGE_LOGISTIK, LOGISTIK_ID, ZWEITER_ANTRAGSTELLER,
                Instant.parse("2026-08-02T10:00:00Z"));
        anfrage(ANFRAGE_DES_LEITERS, WURMLOCH_ID, WURMLOCH_LEITER,
                Instant.parse("2026-08-03T10:00:00Z"));
        anfrage(ANFRAGE_BLOPS, BLOPS_ID, ANTRAGSTELLER, Instant.parse("2026-08-03T12:00:00Z"));

        when(groupRepo.findAllByOrderByNameAsc())
                .thenReturn(List.of(blops, herrenlos, logistik, wurmloch));
        when(groupRepo.findById(WURMLOCH_ID)).thenReturn(Optional.of(wurmloch));
        when(groupRepo.findById(LOGISTIK_ID)).thenReturn(Optional.of(logistik));
        when(groupRepo.findById(HERRENLOS_ID)).thenReturn(Optional.of(herrenlos));
        when(groupRepo.findById(BLOPS_ID)).thenReturn(Optional.of(blops));
        // Die Attrappe bildet die Abfrage nach, mit der die Datenbank die
        // Gruppen zum Rollensatz des Betrachters sucht - den JOIN ueber die
        // Leitungsrollen also, samt DISTINCT: eine Gruppe zaehlt einmal, auch
        // wenn der Betrachter beide ihrer Leitungsrollen traegt. Eine Gruppe
        // ohne Leitungsrolle hat keine Zeile in der Tabelle und faellt beim JOIN
        // heraus - deshalb steht sie hier nie im Ergebnis.
        when(groupRepo.findByLeaderRoleNameIn(any())).thenAnswer(aufruf -> {
            Collection<String> rollen = aufruf.getArgument(0);
            return List.of(wurmloch, logistik, herrenlos, blops).stream()
                    .filter(gruppe -> gruppe.getLeaderRoleNames().stream().anyMatch(rollen::contains))
                    .toList();
        });
        // Die Datenbank vergibt beim Speichern die ID. Ohne diese Nachbildung
        // ginge eine frisch angelegte Gruppe mit id == null aus saveGroup heraus
        // - ein Zustand, den es in der Anwendung nie gibt und an dem der Test
        // aus dem falschen Grund scheitern wuerde.
        when(groupRepo.save(any())).thenAnswer(aufruf -> {
            AuthGroup gespeichert = aufruf.getArgument(0);
            if (gespeichert.getId() == null) {
                gespeichert.setId(99L);
            }
            return gespeichert;
        });

        when(characterRepo.findById(any()))
                .thenAnswer(aufruf -> Optional.ofNullable(charaktere.get(aufruf.getArgument(0))));
        when(characterRepo.findAllById(any())).thenAnswer(aufruf -> {
            Collection<Long> ids = aufruf.getArgument(0);
            return ids.stream().map(charaktere::get).filter(java.util.Objects::nonNull).toList();
        });
        when(characterRepo.findAllWithCorporation())
                .thenAnswer(aufruf -> List.copyOf(charaktere.values()));

        when(requestRepo.findById(any())).thenAnswer(aufruf -> offeneAnfragen.stream()
                .filter(anfrage -> anfrage.getId().equals(aufruf.getArgument(0)))
                .findFirst());
        // Die Attrappe des Repositorys filtert wie die Datenbank nach den
        // uebergebenen Gruppen-IDs. Nur so faellt auf, wenn der Dienst eine zu
        // grosse Menge hineinreicht - eine Attrappe, die stur alles zurueckgibt,
        // wuerde Regel 3 stillschweigend bestehen lassen.
        when(requestRepo.findByStatusAndGroupIdIn(eq(AuthGroupRequest.STATUS_PENDING), any()))
                .thenAnswer(aufruf -> {
                    Collection<Long> gruppenIds = aufruf.getArgument(1);
                    return offeneAnfragen.stream()
                            .filter(anfrage -> gruppenIds.contains(anfrage.getGroupId()))
                            .toList();
                });
        when(requestRepo.findByCharacterIdAndStatus(any(), eq(AuthGroupRequest.STATUS_PENDING)))
                .thenAnswer(aufruf -> offeneAnfragen.stream()
                        .filter(anfrage -> anfrage.getCharacterId().equals(aufruf.getArgument(0)))
                        .toList());
        when(requestRepo.save(any())).thenAnswer(aufruf -> aufruf.getArgument(0));
    }

    // ==================================================================
    // Regel 1: Zustaendigkeit
    // ==================================================================

    @Test
    @DisplayName("Regel 1: Ein Mitglied ohne Zustaendigkeit kann einen Antrag nicht annehmen")
    void mitgliedOhneAmtDarfNichtAnnehmen() {
        // Ohne diese Sperre koennte jeder Angemeldete den Endpunkt mit einer
        // fremden Anfrage-ID aufrufen und sich so jede beliebige Gruppenrolle
        // verteilen lassen - im Zweifel die eines Freundes, der sie danach
        // weiterreicht. Die Oberflaeche zeigt ihm den Reiter zwar nicht, sie
        // haelt ihn aber auch nicht auf.
        assertThatThrownBy(() -> service.decide(MITGLIED_OHNE_AMT, ANFRAGE_WURMLOCH, "approve"))
                .isInstanceOf(AccessDeniedException.class);

        assertThat(anfrage(ANFRAGE_WURMLOCH).getStatus())
                .isEqualTo(AuthGroupRequest.STATUS_PENDING);
        assertThat(charaktere.get(ANTRAGSTELLER).getRoles()).doesNotContain(WURMLOCH_ROLE);
        verify(characterRepo, never()).save(any());
    }

    @Test
    @DisplayName("Regel 1: Der Leiter einer anderen Gruppe entscheidet hier ebenfalls nicht")
    void fremderLeiterDarfNichtAnnehmen() {
        // "Leiter sein" reicht nicht, es muss die Leitungsrolle genau dieser
        // Gruppe sein. Wuerde nur auf irgendeine Leitung geprueft, genuegte eine
        // beliebige eigene Gruppe als Eintrittskarte in alle uebrigen.
        assertThatThrownBy(() -> service.decide(LOGISTIK_LEITER, ANFRAGE_WURMLOCH, "approve"))
                .isInstanceOf(AccessDeniedException.class);

        assertThat(charaktere.get(ANTRAGSTELLER).getRoles()).doesNotContain(WURMLOCH_ROLE);
        verify(characterRepo, never()).save(any());
    }

    @Test
    @DisplayName("Regel 1: Der Leiter der Gruppe und ein Admin duerfen entscheiden")
    void leiterUndAdminDuerfenEntscheiden() {
        // Die Gegenprobe zu den beiden Tests darueber: waere die Pruefung zu
        // streng, blieben alle Anfragen fuer immer liegen - ein Fehler, den man
        // erst bemerkt, wenn sich jemand beschwert.
        service.decide(WURMLOCH_LEITER, ANFRAGE_WURMLOCH, "approve");
        assertThat(anfrage(ANFRAGE_WURMLOCH).getStatus())
                .isEqualTo(AuthGroupRequest.STATUS_APPROVED);

        service.decide(ADMIN, ANFRAGE_LOGISTIK, "reject");
        assertThat(anfrage(ANFRAGE_LOGISTIK).getStatus())
                .isEqualTo(AuthGroupRequest.STATUS_REJECTED);
    }

    // ==================================================================
    // Regel 1, neu: Die Leitung ist eine Rolle, kein Charakter
    // ==================================================================

    @Test
    @DisplayName("Leitung als Rolle: Wer sie traegt, entscheidet - wer sie ablegt, nicht mehr")
    void dieLeitungsrolleEntscheidet() {
        // Der eigentliche Gewinn der Umstellung: ein zweiter Traeger derselben
        // Rolle kann einspringen. Frueher hing die Zustaendigkeit an genau einer
        // Charakter-Id; war die Person im Urlaub oder selbst der Antragsteller,
        // blieb der Antrag liegen und niemand konnte ihn aufloesen.
        service.decide(ZWEITER_WURMLOCH_LEITER, ANFRAGE_WURMLOCH, "approve");
        assertThat(anfrage(ANFRAGE_WURMLOCH).getStatus())
                .isEqualTo(AuthGroupRequest.STATUS_APPROVED);
        assertThat(charaktere.get(ANTRAGSTELLER).getRoles()).contains(WURMLOCH_ROLE);

        // Und die Kehrseite: die Zustaendigkeit haengt wirklich am Rollensatz und
        // nicht an der Person. Wer die Rolle verliert (abgegebener Titel,
        // Rollen-Sync), verliert damit auch die Entscheidungsbefugnis - sonst
        // entschiede ein ehemaliger FC weiter ueber Aufnahmen.
        charaktere.get(ZWEITER_WURMLOCH_LEITER).getRoles().remove(WURMLOCH_LEADER_ROLE);

        // Dieselbe Person, dieselbe Gruppe, nur ohne die Rolle - mehr aendert
        // sich zwischen den beiden Haelften dieses Tests nicht.
        assertThatThrownBy(
                () -> service.decide(ZWEITER_WURMLOCH_LEITER, ANFRAGE_DES_LEITERS, "approve"))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(anfrage(ANFRAGE_DES_LEITERS).getStatus())
                .isEqualTo(AuthGroupRequest.STATUS_PENDING);
    }

    @Test
    @DisplayName("Leitung als Rolle: Ohne hinterlegte Rolle entscheiden nur die Admins")
    void ohneLeitungsrolleEntscheidetNurDerAdmin() {
        anfrage(30L, HERRENLOS_ID, ANTRAGSTELLER, Instant.parse("2026-08-05T10:00:00Z"));

        // Eine Gruppe ohne Leitungsrolle darf nicht versehentlich zur Gruppe
        // ohne Tuerschloss werden: fiele die Null-Pruefung weg, verglichen sich
        // im Zweifel zwei leere Werte und jeder Angemeldete waere ihr Leiter.
        assertThatThrownBy(() -> service.decide(MITGLIED_OHNE_AMT, 30L, "approve"))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.decide(WURMLOCH_LEITER, 30L, "approve"))
                .isInstanceOf(AccessDeniedException.class);

        service.decide(ADMIN, 30L, "approve");
        assertThat(charaktere.get(ANTRAGSTELLER).getRoles()).contains(HERRENLOS_ROLE);
    }

    // ==================================================================
    // Regel 2: Kein eigener Antrag
    // ==================================================================

    @Test
    @DisplayName("Regel 2: Auch ein Admin nimmt seinen eigenen Antrag nicht an")
    void adminEntscheidetNichtUeberDenEigenenAntrag() {
        anfrage(20L, LOGISTIK_ID, ADMIN, Instant.parse("2026-08-04T10:00:00Z"));

        // Ohne diese Regel waere der Antragsweg fuer genau die Personen eine
        // Formalie, die ihn ueberwachen sollen: beantragen, selbst annehmen,
        // Rolle sitzt - und im Nachweis stuende der Admin als sein eigener
        // Entscheider. Wer wirklich in die Gruppe soll, laesst sich von jemand
        // anderem aufnehmen oder traegt die Rolle im Rollenkatalog ein.
        assertThatThrownBy(() -> service.decide(ADMIN, 20L, "approve"))
                .isInstanceOf(AccessDeniedException.class);

        assertThat(anfrage(20L).getStatus()).isEqualTo(AuthGroupRequest.STATUS_PENDING);
        assertThat(charaktere.get(ADMIN).getRoles()).doesNotContain(LOGISTIK_ROLE);
        verify(characterRepo, never()).save(any());
    }

    @Test
    @DisplayName("Ausnahme: Der IT-Admin nimmt auch den eigenen Antrag an")
    void itAdminEntscheidetUeberDenEigenenAntrag() {
        // Bewusste Lockerung, und nur fuer ihn. Sie kostet nichts, weil ein
        // IT-Admin dieselbe Rolle unmittelbar im Rollenkatalog eintragen kann -
        // die Sperre war ihm gegenueber Symbolik und hinderte ihn daran, den
        // Antragsweg ueberhaupt zu erproben. Der Test grenzt sie ab: Er muss
        // fehlschlagen, sobald jemand die Ausnahme auf DIRECTOR oder CEO
        // ausweitet, denn dafuer gibt es die beiden Tests darueber und darunter.
        charakter(IT_ADMIN_ID, "Finn", SystemRoles.IT_ADMIN);
        anfrage(21L, LOGISTIK_ID, IT_ADMIN_ID, Instant.parse("2026-08-04T11:00:00Z"));

        service.decide(IT_ADMIN_ID, 21L, "approve");

        assertThat(anfrage(21L).getStatus()).isEqualTo(AuthGroupRequest.STATUS_APPROVED);
        assertThat(charaktere.get(IT_ADMIN_ID).getRoles()).contains(LOGISTIK_ROLE);
    }

    @Test
    @DisplayName("Regel 2: Auch ein CEO nimmt seinen eigenen Antrag nicht an")
    void ceoEntscheidetNichtUeberDenEigenenAntrag() {
        // Die zweite Haelfte der Abgrenzung. Der Test darueber pruefte die
        // Ausnahme bisher nur gegen den Direktor - wer sie auf den CEO
        // ausweitete, haette die ganze Suite gruen gelassen, obwohl die
        // Ausnahme ausdruecklich nur fuer den IT-Admin gilt: er kann sich
        // dieselbe Rolle ohnehin im Rollenkatalog eintragen, der CEO tut das
        // ueber den Antragsweg oder gar nicht.
        charakter(CEO_ID, "Bossfrau", SystemRoles.CEO);
        anfrage(23L, LOGISTIK_ID, CEO_ID, Instant.parse("2026-08-04T12:00:00Z"));

        assertThatThrownBy(() -> service.decide(CEO_ID, 23L, "approve"))
                .isInstanceOf(AccessDeniedException.class);

        assertThat(anfrage(23L).getStatus()).isEqualTo(AuthGroupRequest.STATUS_PENDING);
        assertThat(charaktere.get(CEO_ID).getRoles()).doesNotContain(LOGISTIK_ROLE);
        verify(characterRepo, never()).save(any());

        // Und der eigene Antrag steht ihm auch nicht im Posteingang - sonst
        // gaebe es dort einen Knopf, der zuverlaessig 403 liefert.
        assertThat(service.openRequestsFor(CEO_ID))
                .extracting(AuthGroupDtos.GroupRequestDto::requestId)
                .doesNotContain(23L);
    }

    @Test
    @DisplayName("Ausnahme: Der eigene Antrag erscheint dem IT-Admin auch in der Liste")
    void itAdminSiehtDenEigenenAntrag() {
        // Sonst haette er das Recht zu entscheiden, aber der Eintrag stuende
        // nicht in seiner Liste - genau der Widerspruch, den die Verwaltung
        // vorher zeigte: "keine offenen Anfragen" neben "Anfrage ausstehend".
        charakter(IT_ADMIN_ID, "Finn", SystemRoles.IT_ADMIN);
        anfrage(22L, LOGISTIK_ID, IT_ADMIN_ID, Instant.parse("2026-08-04T11:00:00Z"));

        assertThat(service.openRequestsFor(IT_ADMIN_ID))
                .extracting(r -> r.requestId())
                .contains(22L);
    }

    @Test
    @DisplayName("Regel 2: Auch die Leitung nimmt ihren eigenen Antrag nicht an - ein Kollege aber schon")
    void leiterEntscheidetNichtUeberDenEigenenAntrag() {
        // Derselbe Fall eine Stufe tiefer: der Leiter waere in seiner eigenen
        // Gruppe zustaendig (Regel 1 haette nichts einzuwenden), und ohne Regel 2
        // reichte ihm ein Klick, um sich selbst aufzunehmen.
        assertThatThrownBy(() -> service.decide(WURMLOCH_LEITER, ANFRAGE_DES_LEITERS, "approve"))
                .isInstanceOf(AccessDeniedException.class);

        assertThat(anfrage(ANFRAGE_DES_LEITERS).getStatus())
                .isEqualTo(AuthGroupRequest.STATUS_PENDING);
        verify(characterRepo, never()).save(any());

        // Regel 2 bleibt also unangetastet - sie ist nur keine Sackgasse mehr:
        // der zweite Traeger derselben Leitungsrolle loest den Antrag auf, der
        // frueher fuer immer liegen geblieben waere.
        service.decide(ZWEITER_WURMLOCH_LEITER, ANFRAGE_DES_LEITERS, "approve");
        assertThat(anfrage(ANFRAGE_DES_LEITERS).getStatus())
                .isEqualTo(AuthGroupRequest.STATUS_APPROVED);
        assertThat(charaktere.get(WURMLOCH_LEITER).getRoles()).contains(WURMLOCH_ROLE);
    }

    // ==================================================================
    // Regel 3: Wer welche Anfragen sieht
    // ==================================================================

    @Test
    @DisplayName("Regel 3: Ein Leiter sieht nur die Anfragen seiner eigenen Gruppen")
    void leiterSiehtNurSeineGruppen() {
        List<AuthGroupDtos.GroupRequestDto> sicht = service.openRequestsFor(WURMLOCH_LEITER);

        // Ohne diese Einschraenkung laege der komplette Antragsverkehr aller SIGs
        // offen: wer sich wo bewirbt, geht einen fremden Leiter nichts an, und
        // entscheiden koennte er darueber ohnehin nicht (Regel 1) - er saehe also
        // nur Knoepfe, die zuverlaessig 403 liefern.
        assertThat(sicht).extracting(AuthGroupDtos.GroupRequestDto::requestId)
                .containsExactly(ANFRAGE_WURMLOCH);
        assertThat(sicht).extracting(AuthGroupDtos.GroupRequestDto::groupName)
                .containsExactly("Wurmloch-SIG");
    }

    @Test
    @DisplayName("Regel 3: Ein Admin sieht die Anfragen aller Gruppen, nach Datum sortiert")
    void adminSiehtAlleGruppen() {
        List<AuthGroupDtos.GroupRequestDto> sicht = service.openRequestsFor(ADMIN);

        assertThat(sicht).extracting(AuthGroupDtos.GroupRequestDto::requestId)
                .containsExactly(ANFRAGE_WURMLOCH, ANFRAGE_LOGISTIK, ANFRAGE_DES_LEITERS,
                        ANFRAGE_BLOPS);
    }

    @Test
    @DisplayName("Regel 3: Der eigene Antrag steht nicht im eigenen Posteingang")
    void dereigeneAntragTauchtInDerVerwaltungNichtAuf() {
        // Die Anzeigeseite von Regel 2: der Wurmloch-Leiter hat selbst einen
        // Antrag gestellt, sieht ihn aber nicht unter "Verwaltung". Ein Knopf,
        // der immer 403 liefert, ist schlimmer als kein Knopf.
        assertThat(service.openRequestsFor(WURMLOCH_LEITER))
                .extracting(AuthGroupDtos.GroupRequestDto::requestId)
                .doesNotContain(ANFRAGE_DES_LEITERS);

        // Sein Kollege sieht ihn dagegen sehr wohl - sonst waere die Anfrage
        // zwar entscheidbar, aber fuer niemanden sichtbar.
        assertThat(service.openRequestsFor(ZWEITER_WURMLOCH_LEITER))
                .extracting(AuthGroupDtos.GroupRequestDto::requestId)
                .contains(ANFRAGE_DES_LEITERS);
    }

    @Test
    @DisplayName("Regel 3: Wer weder Leiter noch Admin ist, sieht eine leere Liste")
    void mitgliedOhneAmtSiehtNichts() {
        // Kein Fehler, sondern eine leere Liste: die Oberflaeche blendet den
        // Reiter "Verwaltung" daraufhin selbst aus.
        assertThat(service.openRequestsFor(MITGLIED_OHNE_AMT)).isEmpty();
        verify(requestRepo, never()).findByStatus(any());
    }

    // ==================================================================
    // Was eine Annahme bewirkt - und was ein zweiter Antrag nicht bewirkt
    // ==================================================================

    @Test
    @DisplayName("Bei Annahme haengt die Rolle wirklich am Charakter und ist gespeichert")
    void annahmeHaengtDieRolleAnDenCharakter() {
        service.decide(WURMLOCH_LEITER, ANFRAGE_WURMLOCH, "approve");

        Character antragsteller = charaktere.get(ANTRAGSTELLER);
        // Hier entsteht die Mitgliedschaft - es gibt keine zweite Stelle. Bliebe
        // das Rollen-Set unberuehrt, waere die Anfrage angenommen, der Charakter
        // aber weder in der Anwendung noch auf Discord Mitglied, und niemand
        // saehe der APPROVED-Zeile an, dass die Wirkung fehlt.
        assertThat(antragsteller.getRoles()).contains(WURMLOCH_ROLE);
        verify(characterRepo).save(antragsteller);

        AuthGroupRequest anfrage = anfrage(ANFRAGE_WURMLOCH);
        assertThat(anfrage.getStatus()).isEqualTo(AuthGroupRequest.STATUS_APPROVED);
        assertThat(anfrage.getDecidedByCharacterId()).isEqualTo(WURMLOCH_LEITER);
        assertThat(anfrage.getDecidedAt()).isNotNull();
    }

    @Test
    @DisplayName("Bei Ablehnung bekommt der Charakter die Rolle nicht")
    void ablehnungVergibtKeineRolle() {
        service.decide(WURMLOCH_LEITER, ANFRAGE_WURMLOCH, "reject");

        // Die Ablehnung darf nicht versehentlich denselben Zweig nehmen wie die
        // Annahme; ein vertauschtes Vorzeichen faellt sonst nirgends auf.
        assertThat(charaktere.get(ANTRAGSTELLER).getRoles()).doesNotContain(WURMLOCH_ROLE);
        verify(characterRepo, never()).save(any());
        assertThat(anfrage(ANFRAGE_WURMLOCH).getStatus())
                .isEqualTo(AuthGroupRequest.STATUS_REJECTED);
    }

    @Test
    @DisplayName("Ein zweiter Antrag derselben Person auf dieselbe Gruppe entsteht nicht")
    void keinZweiterAntragAufDieselbeGruppe() {
        when(requestRepo.existsByGroupIdAndCharacterIdAndStatus(
                WURMLOCH_ID, ANTRAGSTELLER, AuthGroupRequest.STATUS_PENDING)).thenReturn(true);

        // Ohne den Riegel haeuft ein Klickfreudiger beliebig viele offene
        // Anfragen an. Die Verwaltung muesste sie einzeln wegklicken, und nach
        // der ersten Annahme blieben die uebrigen als Karteileichen stehen -
        // jede von ihnen weiterhin annehmbar, obwohl die Rolle laengst haengt.
        assertThatThrownBy(() -> service.apply(ANTRAGSTELLER, WURMLOCH_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Wurmloch-SIG");

        verify(requestRepo, never()).save(any());
    }

    @Test
    @DisplayName("Wer die Rolle schon traegt, stellt keinen Antrag mehr")
    void keinAntragAlsBereitsMitglied() {
        charaktere.get(ANTRAGSTELLER).getRoles().add(WURMLOCH_ROLE);

        assertThatThrownBy(() -> service.apply(ANTRAGSTELLER, WURMLOCH_ID))
                .isInstanceOf(IllegalArgumentException.class);

        verify(requestRepo, never()).save(any());
    }

    @Test
    @DisplayName("Der erste Antrag entsteht mit Status PENDING und Zeitstempel")
    void ersterAntragEntstehtOffen() {
        AuthGroupDtos.GroupRequestDto neu = service.apply(ANTRAGSTELLER, WURMLOCH_ID);

        assertThat(neu.status()).isEqualTo(AuthGroupRequest.STATUS_PENDING);
        assertThat(neu.groupName()).isEqualTo("Wurmloch-SIG");
        assertThat(neu.characterName()).isEqualTo("Antragsteller");
        // Ohne Zeitstempel haette die Verwaltungsliste keine Sortierung und
        // keine Datumsspalte; die Spalte ist im Frontend fest eingeplant.
        assertThat(neu.requestedAt()).isNotNull();
    }

    // ==================================================================
    // Austreten
    // ==================================================================

    @Test
    @DisplayName("Austreten nimmt die Rolle ab, und danach geht ein neuer Antrag")
    void austrittNimmtDieRolleUndOeffnetDenWegZurueck() {
        // Erst wirklich Mitglied werden, statt die Rolle von Hand zu setzen:
        // sonst prueft der Test den Austritt gegen einen Zustand, den der Dienst
        // so nie erzeugt.
        service.decide(WURMLOCH_LEITER, ANFRAGE_WURMLOCH, "approve");
        Character aussteiger = charaktere.get(ANTRAGSTELLER);
        assertThat(aussteiger.getRoles()).contains(WURMLOCH_ROLE);

        // Das Speichern der Aufnahme zaehlt fuer die Pruefung unten nicht mit -
        // sonst faende sie zwei Aufrufe und liesse offen, welcher davon der
        // Austritt war.
        clearInvocations(characterRepo);

        service.leave(ANTRAGSTELLER, WURMLOCH_ID);

        // Der Austritt muss die Rolle wirklich abnehmen und den Charakter
        // speichern. Bliebe sie haengen, waere der Nutzer laut Oberflaeche
        // draussen, auf Discord aber weiter drin - der schlimmste der moeglichen
        // Ausgaenge, weil niemand ihn bemerkt.
        assertThat(aussteiger.getRoles()).doesNotContain(WURMLOCH_ROLE);
        verify(characterRepo).save(aussteiger);

        // Und der Weg zurueck steht offen: der Doppelantrag-Riegel sieht nur
        // OFFENE Anfragen, die abgeschlossene von eben blockiert nicht.
        AuthGroupDtos.GroupRequestDto erneut = service.apply(ANTRAGSTELLER, WURMLOCH_ID);
        assertThat(erneut.status()).isEqualTo(AuthGroupRequest.STATUS_PENDING);
        assertThat(erneut.groupName()).isEqualTo("Wurmloch-SIG");
    }

    @Test
    @DisplayName("Austreten fragt niemanden - es entsteht keine Anfrage")
    void austrittBrauchtKeineZustimmung() {
        charaktere.get(ANTRAGSTELLER).getRoles().add(LOGISTIK_ROLE);

        service.leave(ANTRAGSTELLER, LOGISTIK_ID);

        // Wer raus will, ist raus: kein Antrag, keine Entscheidung, keine
        // Wartezeit. Entstuende hier eine Anfrage, haenge der Austritt an der
        // Laune der Leitung.
        assertThat(charaktere.get(ANTRAGSTELLER).getRoles()).doesNotContain(LOGISTIK_ROLE);
        verify(requestRepo, never()).save(any());
    }

    @Test
    @DisplayName("Wer gar kein Mitglied ist, kann auch nicht austreten")
    void austrittOhneMitgliedschaftScheitert() {
        // Ein stilles OK verdeckte eine veraltete Anzeige oder einen falsch
        // verdrahteten Knopf - und der Aufrufer glaubte, etwas bewirkt zu haben.
        assertThatThrownBy(() -> service.leave(ANTRAGSTELLER, WURMLOCH_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Wurmloch-SIG");

        verify(characterRepo, never()).save(any());
    }

    @Test
    @DisplayName("Ein Austritt trifft nur den Austretenden - kein Mitglied verliert nebenbei die Rolle")
    void austrittTrifftNiemandSonst() {
        // Der Austritt ist die einzige Stelle, an der ein gewoehnlicher Nutzer
        // eine Rolle wieder abnimmt. Griffe er dabei ueber den eigenen Charakter
        // hinaus, waere aus dem Selbstbedienungsknopf ein Rauswurf-Knopf fuer
        // jeden Angemeldeten geworden - und weil der Discord-Sync anschliessend
        // still hinterherraeumt, faellt der Verlust erst dem Betroffenen auf.
        charaktere.get(ANTRAGSTELLER).getRoles().add(WURMLOCH_ROLE);
        charaktere.get(ZWEITER_ANTRAGSTELLER).getRoles().add(WURMLOCH_ROLE);
        Character mitbewohner = charaktere.get(ZWEITER_ANTRAGSTELLER);

        service.leave(ANTRAGSTELLER, WURMLOCH_ID);

        assertThat(charaktere.get(ANTRAGSTELLER).getRoles()).doesNotContain(WURMLOCH_ROLE);
        assertThat(mitbewohner.getRoles()).contains(WURMLOCH_ROLE);
        // Nicht nur unveraendert, sondern gar nicht erst angefasst: geschrieben
        // wird ausschliesslich der Charakter aus dem Sicherheitskontext.
        verify(characterRepo, never()).save(mitbewohner);
        verify(characterRepo).save(charaktere.get(ANTRAGSTELLER));
    }

    // ==================================================================
    // Die Sicht des Aufrufers auf die Gruppenliste
    // ==================================================================

    @Test
    @DisplayName("Die Gruppenliste traegt Leitungsrolle, Mitgliedschaft und offene Anfrage")
    void gruppenlisteZeigtDenStandDesAufrufers() {
        charaktere.get(ANTRAGSTELLER).getRoles().add(LOGISTIK_ROLE);

        Map<String, AuthGroupDtos.GroupDto> sicht = service.groupsFor(ANTRAGSTELLER).stream()
                .collect(java.util.stream.Collectors.toMap(
                        AuthGroupDtos.GroupDto::name, gruppe -> gruppe));

        // Die Leitung geht als blanke Rollennamen hinaus; die Tabelle zeigt sie
        // als Etiketten und faellt ohne Rolle auf "Ohne Leitung" zurueck. Ein
        // Personenname stuende hier nicht mehr zur Verfuegung.
        assertThat(sicht.get("Wurmloch-SIG").leaderRoleNames())
                .containsExactly(WURMLOCH_LEADER_ROLE);
        // Leer und nicht null: die Oberflaeche zaehlt Etiketten, sie soll sich
        // nicht zusaetzlich gegen einen fehlenden Wert absichern muessen.
        assertThat(sicht.get("Herrenlos-SIG").leaderRoleNames()).isEmpty();
        // Sortiert - die Sammlung ist eine Streuung, ohne Ordnung tauschten die
        // beiden Etiketten bei jedem Laden die Plaetze.
        assertThat(sicht.get("Blops-SIG").leaderRoleNames())
                .containsExactly(FC_SKIRMISH, FC_STRAT);

        // Mitglied ist, wer die Gruppenrolle traegt - eine offene Anfrage macht
        // noch kein Mitglied, und die Mitgliederzahl zaehlt deshalb auch keine
        // Bewerber mit.
        assertThat(sicht.get("Logistik-SIG").isMember()).isTrue();
        assertThat(sicht.get("Logistik-SIG").memberCount()).isEqualTo(1L);
        assertThat(sicht.get("Wurmloch-SIG").isMember()).isFalse();
        assertThat(sicht.get("Wurmloch-SIG").hasPendingRequest()).isTrue();
        assertThat(sicht.get("Wurmloch-SIG").memberCount()).isZero();

        // Und der Leiter erkennt seine eigene Gruppe am Kennzeichen, ohne dass
        // die Oberflaeche Rollennamen vergleichen muss.
        Map<String, AuthGroupDtos.GroupDto> leitersicht =
                service.groupsFor(WURMLOCH_LEITER).stream()
                        .collect(java.util.stream.Collectors.toMap(
                                AuthGroupDtos.GroupDto::name, gruppe -> gruppe));
        assertThat(leitersicht.get("Wurmloch-SIG").isLeader()).isTrue();
        assertThat(leitersicht.get("Logistik-SIG").isLeader()).isFalse();
        assertThat(leitersicht.get("Herrenlos-SIG").isLeader()).isFalse();

        // Und eine von zwei Leitungsrollen genuegt fuer das Kennzeichen - sonst
        // saehe der Skirmish-FC seine eigene Gruppe nicht als seine an.
        Map<String, AuthGroupDtos.GroupDto> skirmishsicht =
                service.groupsFor(SKIRMISH_FC).stream()
                        .collect(java.util.stream.Collectors.toMap(
                                AuthGroupDtos.GroupDto::name, gruppe -> gruppe));
        assertThat(skirmishsicht.get("Blops-SIG").isLeader()).isTrue();
    }

    // ==================================================================
    // Pflege: welche Rolle als Leitung taugt
    // ==================================================================

    @Test
    @DisplayName("Eine eingebaute Rolle taugt auch als zweite Leitung nicht")
    void eingebauteRolleTaugtNichtAlsLeitung() {
        // ROLE_MEMBER traegt praktisch jeder. Als Leitungsrolle eingetragen
        // duerfte damit jedes Mitglied ueber die Aufnahmen dieser Gruppe
        // entscheiden - der Antragsweg waere reine Zierde.
        assertThatThrownBy(() -> service.saveGroup(ADMIN, new AuthGroupDtos.SaveGroupDto(
                null, "Neue SIG", null, "ROLE_NEUE_SIG", List.of(SystemRoles.MEMBER))))
                .isInstanceOf(IllegalArgumentException.class);

        // Und in Gesellschaft einer tauglichen Rolle ebenso wenig: es genuegt
        // EINE passende Leitungsrolle, um zu entscheiden. Wer hier nur pruefte,
        // ob wenigstens eine Rolle taugt, haette die Sperre abgeschafft.
        assertThatThrownBy(() -> service.saveGroup(ADMIN, new AuthGroupDtos.SaveGroupDto(
                null, "Neue SIG", null, "ROLE_NEUE_SIG", List.of(FC_STRAT, SystemRoles.MEMBER))))
                .isInstanceOf(IllegalArgumentException.class);

        verify(groupRepo, never()).save(any());
    }

    @Test
    @DisplayName("Eine leere Leitung bleibt leer, eine eingetippte wird normalisiert")
    void leitungWirdNormalisiert() {
        AuthGroupDtos.GroupDto ohne = service.saveGroup(ADMIN, new AuthGroupDtos.SaveGroupDto(
                null, "Neue SIG", null, "ROLE_NEUE_SIG", List.of("   ")));
        // Leer heisst nicht "Rolle mit leerem Namen", sondern "keine Leitung" -
        // sonst entstuende eine Gruppe, deren Leitungsrolle niemand traegt und
        // die trotzdem nicht als fuehrungslos gilt.
        assertThat(ohne.leaderRoleNames()).isEmpty();

        // Auch die fehlende Liste ist "keine Leitung" und kein Fehler: eine
        // Gruppe ohne Leitung ist der Normalfall beim Anlegen.
        AuthGroupDtos.GroupDto garkeine = service.saveGroup(ADMIN, new AuthGroupDtos.SaveGroupDto(
                null, "Dritte SIG", null, "ROLE_DRITTE_SIG", null));
        assertThat(garkeine.leaderRoleNames()).isEmpty();

        AuthGroupDtos.GroupDto mit = service.saveGroup(ADMIN, new AuthGroupDtos.SaveGroupDto(
                null, "Andere SIG", null, "ROLE_ANDERE_SIG", List.of("fc strat", "FC Skirmish")));
        // Rollennamen werden ueberall als Zeichenkette verglichen. Eine
        // abweichende Schreibweise waere eine andere Rolle und griffe schlicht
        // nie - die Gruppe haette dann eine Leitung, die niemand traegt.
        assertThat(mit.leaderRoleNames()).containsExactly(FC_SKIRMISH, FC_STRAT);
    }

    // ==================================================================
    // Mehrere Leitungsrollen je Gruppe
    // ==================================================================

    @Test
    @DisplayName("Mehrere Leitungen: Eine der hinterlegten Rollen genuegt zum Entscheiden")
    void eineVonMehrerenLeitungsrollenGenuegt() {
        // Der Fall, fuer den die Menge da ist: ueber die Blops-SIG entscheiden
        // Strat-FCs UND Skirmish-FCs. Wuerde nur der erste Eintrag verglichen,
        // stuende einer der beiden Kreise ohne Zustaendigkeit da - und zwar je
        // nach Reihenfolge in der Datenbank mal der eine, mal der andere.
        service.decide(SKIRMISH_FC, ANFRAGE_BLOPS, "approve");

        assertThat(anfrage(ANFRAGE_BLOPS).getStatus())
                .isEqualTo(AuthGroupRequest.STATUS_APPROVED);
        assertThat(charaktere.get(ANTRAGSTELLER).getRoles()).contains(BLOPS_ROLE);
    }

    @Test
    @DisplayName("Mehrere Leitungen: Wer keine davon traegt, entscheidet nicht")
    void wederNochDarfNichtEntscheiden() {
        // Die Gegenprobe. Der Armor-FC ist Leitung genug, um in der Oberflaeche
        // wie ein FC auszusehen - nur eben nicht bei dieser Gruppe. Ohne diese
        // Haelfte wuerde ein "traegt irgendeine Rolle"-Fehler nie auffallen.
        assertThatThrownBy(() -> service.decide(ARMOR_FC, ANFRAGE_BLOPS, "approve"))
                .isInstanceOf(AccessDeniedException.class);

        assertThat(anfrage(ANFRAGE_BLOPS).getStatus())
                .isEqualTo(AuthGroupRequest.STATUS_PENDING);
        assertThat(charaktere.get(ANTRAGSTELLER).getRoles()).doesNotContain(BLOPS_ROLE);
        verify(characterRepo, never()).save(any());
    }

    @Test
    @DisplayName("Mehrere Leitungen: Beide Kreise sehen die Anfragen ihrer Gruppe, Fremde nicht")
    void beideLeitungskreiseSehenDieAnfrage() {
        assertThat(service.openRequestsFor(STRAT_FC))
                .extracting(AuthGroupDtos.GroupRequestDto::requestId)
                .containsExactly(ANFRAGE_BLOPS);
        assertThat(service.openRequestsFor(SKIRMISH_FC))
                .extracting(AuthGroupDtos.GroupRequestDto::requestId)
                .containsExactly(ANFRAGE_BLOPS);
        // Kein Fehler, sondern eine leere Liste - der Reiter blendet sich aus.
        assertThat(service.openRequestsFor(ARMOR_FC)).isEmpty();
    }

    @Test
    @DisplayName("Mehrere Leitungen: Ein Charakter mit beiden Rollen bekommt die Gruppe nur einmal")
    void beideRollenLiefernDieGruppeNurEinmal() {
        // Der Grund fuer das DISTINCT in der Abfrage: der JOIN ueber die
        // Leitungsrollen trifft bei diesem Charakter zwei Zeilen derselben
        // Gruppe. Ohne DISTINCT stuende jede Anfrage doppelt im Posteingang -
        // und die zweite liefe beim Klick ins Leere, weil die erste sie schon
        // entschieden hat.
        charaktere.get(STRAT_FC).getRoles().add(FC_SKIRMISH);

        assertThat(service.openRequestsFor(STRAT_FC))
                .extracting(AuthGroupDtos.GroupRequestDto::requestId)
                .containsExactly(ANFRAGE_BLOPS);
    }

    // ==================================================================
    // Beim Anlegen entsteht die Rolle - und zwar als besondere
    // ==================================================================

    @Test
    @DisplayName("Beim Anlegen entsteht die Rolle, und sie traegt isSpecial = true")
    void neueGruppeLegtIhreRolleAlsBesondereAn() {
        when(systemRoleRepo.findById(any())).thenReturn(Optional.empty());

        service.saveGroup(ADMIN, new AuthGroupDtos.SaveGroupDto(
                null, "Kapital-Ausbildung", "Cap Azubi", "ROLE_CAP_AZUBI", List.of()));

        ArgumentCaptor<SystemRole> gespeichert = ArgumentCaptor.forClass(SystemRole.class);
        verify(systemRoleRepo).save(gespeichert.capture());
        assertThat(gespeichert.getValue().getRoleName()).isEqualTo("ROLE_CAP_AZUBI");

        // Der wichtigste Haken dieses Tests. CharacterRoleService.applyRoles baut
        // das Rollen-Set alle zehn Minuten aus Corp-Zugehoerigkeit und Titeln neu
        // auf und rettet daraus nur die Rollen mit isSpecial == true
        // (retainedSpecialRoles ueber findByIsSpecialTrue). Fehlt das Kennzeichen,
        // ist jede Mitgliedschaft dieser Gruppe beim naechsten Lauf weg - samt
        // Discord-Rolle, waehrend die Anfrage weiter auf APPROVED steht und
        // niemand einen Fehler sieht.
        assertThat(gespeichert.getValue().isSpecial()).isTrue();
    }

    @Test
    @DisplayName("Ohne eingetippten Rollennamen entsteht er aus dem Gruppennamen")
    void rollennameEntstehtAusDemGruppennamen() {
        when(systemRoleRepo.findById(any())).thenReturn(Optional.empty());

        // Der Admin musste die Rolle bis hierher vorher von Hand im
        // Rollenkatalog anlegen. Wurde das vergessen, stand die Gruppe da und
        // ihre Rolle existierte nicht - der Vorschlag nimmt den Schritt ab.
        AuthGroupDtos.GroupDto neu = service.saveGroup(ADMIN, new AuthGroupDtos.SaveGroupDto(
                null, "Wurmloch SIG", null, "  ", List.of()));

        assertThat(neu.roleName()).isEqualTo("ROLE_WURMLOCH_SIG");

        // Und er bleibt ein Vorschlag: ein mitgeschickter Name sticht ihn.
        AuthGroupDtos.GroupDto eigen = service.saveGroup(ADMIN, new AuthGroupDtos.SaveGroupDto(
                null, "Wurmloch SIG", null, "wh gang", List.of()));
        assertThat(eigen.roleName()).isEqualTo("ROLE_WH_GANG");
    }

    @Test
    @DisplayName("Eine vorhandene Rolle wird wiederverwendet und nicht ueberschrieben")
    void vorhandeneRolleWirdWiederverwendet() {
        SystemRole vorhanden = new SystemRole();
        vorhanden.setRoleName("ROLE_CAP_AZUBI");
        vorhanden.setDescription("Von Hand gepflegt, haengt an einem Ingame-Titel");
        vorhanden.setSpecial(true);
        when(systemRoleRepo.findById("ROLE_CAP_AZUBI")).thenReturn(Optional.of(vorhanden));

        service.saveGroup(ADMIN, new AuthGroupDtos.SaveGroupDto(
                null, "Kapital-Ausbildung", null, "ROLE_CAP_AZUBI", List.of()));

        ArgumentCaptor<SystemRole> gespeichert = ArgumentCaptor.forClass(SystemRole.class);
        verify(systemRoleRepo).save(gespeichert.capture());
        // Dieselbe Zeile, keine zweite: die Rolle kann laengst an einem
        // Ingame-Titel und an Charakteren haengen.
        assertThat(gespeichert.getValue()).isSameAs(vorhanden);
        // Ihre Beschreibung stammt aus dem Rollenkatalog und ist dort mit
        // Bedacht gesetzt worden - die Gruppe ueberschreibt sie nicht mit ihrem
        // eigenen Baustein.
        assertThat(gespeichert.getValue().getDescription())
                .isEqualTo("Von Hand gepflegt, haengt an einem Ingame-Titel");
        assertThat(gespeichert.getValue().isSpecial()).isTrue();
    }

    // ==================================================================
    // Pflegen darf nur, wer Admin ist
    // ==================================================================

    @Test
    @DisplayName("Wer kein Admin ist, legt keine Gruppe an, aendert und loescht keine")
    void nurAdminsPflegenGruppen() {
        // Das gefaehrlichste Loch des ganzen Features, wenn es fehlte: wer eine
        // Gruppe anlegen kann, traegt sich selbst als Leitung ein und nimmt
        // danach seinen eigenen Antrag an - oder legt gleich eine Gruppe auf
        // eine bestehende, maechtige Rolle. Der Controller haelt zwar ein
        // @PreAuthorize, doch das haengt an einem Einstiegspunkt und faellt bei
        // einem Umbau lautlos weg; die Regel gehoert an die Sache.
        AuthGroupDtos.SaveGroupDto neu = new AuthGroupDtos.SaveGroupDto(
                null, "Selbstbedienung", null, "ROLE_SELBSTBEDIENUNG", List.of(FC_STRAT));

        assertThatThrownBy(() -> service.saveGroup(MITGLIED_OHNE_AMT, neu))
                .isInstanceOf(AccessDeniedException.class);
        // Auch eine Leitung ist kein Admin: sie entscheidet ueber Anfragen ihrer
        // Gruppe, aber sie schafft keine neuen Gruppen und keine neuen Rollen.
        assertThatThrownBy(() -> service.saveGroup(WURMLOCH_LEITER, neu))
                .isInstanceOf(AccessDeniedException.class);

        AuthGroupDtos.SaveGroupDto aenderung = new AuthGroupDtos.SaveGroupDto(
                WURMLOCH_ID, "Wurmloch-SIG", null, WURMLOCH_ROLE, List.of(FC_STRAT));
        assertThatThrownBy(() -> service.saveGroup(WURMLOCH_LEITER, aenderung))
                .isInstanceOf(AccessDeniedException.class);

        assertThatThrownBy(() -> service.deleteGroup(MITGLIED_OHNE_AMT, WURMLOCH_ID))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.deleteGroup(WURMLOCH_LEITER, WURMLOCH_ID))
                .isInstanceOf(AccessDeniedException.class);

        // Nichts davon darf halb durchgekommen sein - weder die Gruppe noch die
        // Rolle, die beim Anlegen nebenbei entstuende.
        verify(groupRepo, never()).save(any());
        verify(groupRepo, never()).deleteById(any());
        verify(requestRepo, never()).deleteByGroupId(any());
        verify(systemRoleRepo, never()).save(any());
        assertThat(wurmloch.getLeaderRoleNames()).containsExactly(WURMLOCH_LEADER_ROLE);
    }

    @Test
    @DisplayName("Der Admin loescht die Gruppe samt ihrer Anfragen")
    void adminLoeschtGruppeUndAnfragen() {
        when(groupRepo.existsById(WURMLOCH_ID)).thenReturn(true);

        service.deleteGroup(ADMIN, WURMLOCH_ID);

        // Zuerst die Anfragen: eine verwaiste Anfrage zeigte auf eine Gruppe,
        // die es nicht mehr gibt, und liesse sich weder anzeigen noch entscheiden.
        verify(requestRepo).deleteByGroupId(WURMLOCH_ID);
        verify(groupRepo).deleteById(WURMLOCH_ID);
    }

    // ==================================================================
    // Aufbau der Testdaten
    // ==================================================================

    /** Ohne Leitungsrolle aufgerufen entsteht eine Gruppe mit leerer Menge - kein {@code null}. */
    private static AuthGroup gruppe(Long id, String name, String roleName,
                                    String... leaderRoleNames) {
        AuthGroup gruppe = new AuthGroup();
        gruppe.setId(id);
        gruppe.setName(name);
        gruppe.setDescription("Beschreibung von " + name);
        gruppe.setRoleName(roleName);
        gruppe.getLeaderRoleNames().addAll(Set.of(leaderRoleNames));
        return gruppe;
    }

    /** Das Rollen-Set muss veraenderlich sein - Aufnahme und Austritt haengen daran an. */
    private void charakter(Long id, String name, String... roles) {
        Character charakter = new Character();
        charakter.setId(id);
        charakter.setName(name);
        charakter.setRoles(new HashSet<>(Set.of(roles)));
        charaktere.put(id, charakter);
    }

    private void anfrage(Long id, Long groupId, Long characterId, Instant requestedAt) {
        AuthGroupRequest anfrage = new AuthGroupRequest();
        anfrage.setId(id);
        anfrage.setGroupId(groupId);
        anfrage.setCharacterId(characterId);
        anfrage.setStatus(AuthGroupRequest.STATUS_PENDING);
        anfrage.setRequestedAt(requestedAt);
        offeneAnfragen.add(anfrage);
    }

    private AuthGroupRequest anfrage(Long id) {
        return offeneAnfragen.stream()
                .filter(vorhanden -> vorhanden.getId().equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Anfrage " + id + " gibt es im Test nicht."));
    }
}
