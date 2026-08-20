package com.eve.own.auth.backend.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.eve.own.auth.backend.domain.auth.AuthRoleSource;
import com.eve.own.auth.backend.domain.auth.SystemRoles;
import com.eve.own.auth.backend.domain.auth.dto.RoleAssignmentDtos;
import com.eve.own.auth.backend.domain.auth.entity.RoleAssignmentAudit;
import com.eve.own.auth.backend.domain.auth.entity.SystemRole;
import com.eve.own.auth.backend.domain.auth.entity.TitleRoleMapping;
import com.eve.own.auth.backend.domain.auth.repository.RoleAssignmentAuditRepository;
import com.eve.own.auth.backend.domain.auth.repository.SystemRoleRepository;
import com.eve.own.auth.backend.domain.auth.repository.TitleRoleMappingRepository;
import com.eve.own.auth.backend.domain.character.entity.Character;
import com.eve.own.auth.backend.domain.character.entity.Corporation;
import com.eve.own.auth.backend.domain.character.repository.CharacterRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.access.AccessDeniedException;

/**
 * Rollen von Hand zuweisen und entziehen - der unmittelbarste Weg ins
 * Rechtemodell, den es in dieser Anwendung gibt.
 *
 * <p>Bisher entstand jede Rolle mittelbar: aus der Corp-Zugehoerigkeit, aus
 * einem Ingame-Titel oder aus einer angenommenen Gruppenanfrage. Jeder dieser
 * Wege hat einen Vorgang, an dem jemand hinsieht. Der Weg von Hand hat keinen -
 * er IST der Vorgang. Was hier nicht geprueft wird, ist ungeprueft.</p>
 *
 * <p>Vier Fallen entscheiden ueber Sinn oder Unsinn dieses Weges, und dieser
 * Test haelt fuer jede fest, wie sie geschlossen wurde:</p>
 * <ol>
 *   <li>Eine Rolle ohne {@code is_special} verschwindet beim naechsten Sync
 *       lautlos wieder - das Zuweisen setzt das Kennzeichen deshalb selbst.</li>
 *   <li>Eine Rolle aus einem Ingame-Titel laesst sich nicht entziehen - der
 *       Versuch wird abgewiesen und die Auskunft sagt es vorher.</li>
 *   <li>Jede Aenderung erzeugt einen Nachweis, sonst ist die Herkunft einer
 *       Rolle ab dem ersten Klick unauffindbar.</li>
 *   <li>Die Selbstvergabe bleibt erlaubt, wird aber als solche gekennzeichnet.</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Rollen zuweisen und entziehen")
class RoleAssignmentServiceTest {

    private static final Long CORP = 98000001L;

    private static final Long DIREKTORIN = 100L;
    private static final Long IT_ADMIN = 101L;
    private static final Long MITGLIED_OHNE_AMT = 200L;
    private static final Long PILOT = 300L;

    /** Eine frei angelegte Rolle - der Normalfall einer Handvergabe. */
    private static final String LOGISTIK_ROLE = "ROLE_LOGISTIK_SIG";

    /** Eine Rolle, die in dieser Corp ein Ingame-Titel vergibt. */
    private static final String FC_ROLE = "ROLE_FLEET_COMMANDER";
    private static final String FC_TITEL = "Fleet Commander";

    /**
     * Eine Titel-Rolle, die im Katalog bereits als dauerhaft gefuehrt wird -
     * die eine Ausnahme, in der eine Titel-Rolle vergeben werden darf.
     */
    private static final String AUSBILDER_ROLE = "ROLE_AUSBILDER";
    private static final String AUSBILDER_TITEL = "Ausbilder";

    @Mock private CharacterRepository characterRepo;
    @Mock private SystemRoleRepository systemRoleRepo;
    @Mock private TitleRoleMappingRepository titleRepo;
    @Mock private RoleAssignmentAuditRepository auditRepo;
    @Mock private RoleCatalogService roleCatalogService;

    private RoleAssignmentService service;

    private final Map<Long, Character> charaktere = new HashMap<>();
    private final List<TitleRoleMapping> zuordnungen = new ArrayList<>();
    private final Map<String, SystemRole> katalogZeilen = new HashMap<>();

    @BeforeEach
    void setUp() {
        service = new RoleAssignmentService(
                characterRepo, systemRoleRepo, titleRepo, auditRepo, roleCatalogService);

        charakter(DIREKTORIN, "Direktorin", SystemRoles.DIRECTOR);
        charakter(IT_ADMIN, "Technikerin", SystemRoles.IT_ADMIN);
        charakter(MITGLIED_OHNE_AMT, "Gewoehnliches Mitglied", SystemRoles.MEMBER);
        charakter(PILOT, "Pilot Eins", SystemRoles.USER, SystemRoles.MEMBER);

        // In dieser Corporation vergibt der Titel "Fleet Commander" die
        // FC-Rolle. Genau daran haengt Falle 2.
        zuordnung(FC_ROLE, FC_TITEL);
        zuordnung(AUSBILDER_ROLE, AUSBILDER_TITEL);

        when(characterRepo.findById(anyLong()))
                .thenAnswer(call -> Optional.ofNullable(charaktere.get(call.getArgument(0))));
        when(characterRepo.save(any())).thenAnswer(call -> call.getArgument(0));
        when(characterRepo.findAllById(any())).thenAnswer(call -> {
            List<Character> gefunden = new ArrayList<>();
            for (Long id : (Iterable<Long>) call.getArgument(0)) {
                if (charaktere.containsKey(id)) {
                    gefunden.add(charaktere.get(id));
                }
            }
            return gefunden;
        });

        when(titleRepo.findByCorporationId(CORP)).thenReturn(zuordnungen);
        when(systemRoleRepo.findById(any()))
                .thenAnswer(call -> Optional.ofNullable(katalogZeilen.get(call.getArgument(0))));
        when(systemRoleRepo.save(any())).thenAnswer(call -> call.getArgument(0));
        when(auditRepo.save(any())).thenAnswer(call -> call.getArgument(0));

        // Der Rollenkatalog ist ein eigener Dienst mit eigenen Tests; hier zaehlt
        // nur, was er ueber Herkunft und Dauerhaftigkeit sagt.
        when(roleCatalogService.catalog()).thenAnswer(call -> katalog());
    }

    // ==================================================================
    // Falle 0: Wer darf das ueberhaupt
    // ==================================================================

    @Nested
    @DisplayName("Rechtepruefung im Dienst")
    class Rechte {

        /**
         * Ohne diese Pruefung IM DIENST haengt der gefaehrlichste Endpunkt der
         * Anwendung an einer Annotation am Controller: sie faellt bei einem
         * Umbau lautlos weg und schuetzt einen zweiten Aufrufer gar nicht. Wer
         * durchkaeme, gaebe sich jede Rolle, die er will - und danach ist er der,
         * der die Pruefung besteht.
         */
        @Test
        @DisplayName("weist einen Nicht-Admin beim Zuweisen ab")
        void nichtAdminDarfNichtZuweisen() {
            assertThatThrownBy(() ->
                    service.grant(MITGLIED_OHNE_AMT, PILOT, LOGISTIK_ROLE, null))
                    .isInstanceOf(AccessDeniedException.class);

            assertThat(charaktere.get(PILOT).getRoles()).doesNotContain(LOGISTIK_ROLE);
            verify(auditRepo, never()).save(any());
        }

        /**
         * Dieselbe Ueberlegung von der anderen Seite: ohne die Pruefung koennte
         * jeder Angemeldete den Endpunkt mit einer fremden Charakter-Id aufrufen
         * und reihum jedes Recht der Corporation abraeumen - der Discord-Sync
         * zoege still nach.
         */
        @Test
        @DisplayName("weist einen Nicht-Admin beim Entziehen ab")
        void nichtAdminDarfNichtEntziehen() {
            gibRolle(PILOT, LOGISTIK_ROLE);

            assertThatThrownBy(() ->
                    service.revoke(MITGLIED_OHNE_AMT, PILOT, LOGISTIK_ROLE, null))
                    .isInstanceOf(AccessDeniedException.class);

            assertThat(charaktere.get(PILOT).getRoles()).contains(LOGISTIK_ROLE);
            verify(auditRepo, never()).save(any());
        }

        /** Auch die Auskunft ist die Landkarte des Rechtemodells und nichts fuer jeden. */
        @Test
        @DisplayName("weist einen Nicht-Admin schon bei der Auskunft ab")
        void nichtAdminSiehtDieAuskunftNicht() {
            assertThatThrownBy(() -> service.rolesOf(MITGLIED_OHNE_AMT, PILOT))
                    .isInstanceOf(AccessDeniedException.class);
        }

        @Test
        @DisplayName("laesst den IT-Admin zu - derselbe Kreis wie LEADERSHIP_OR_IT")
        void itAdminDarf() {
            service.grant(IT_ADMIN, PILOT, LOGISTIK_ROLE, null);

            assertThat(charaktere.get(PILOT).getRoles()).contains(LOGISTIK_ROLE);
        }
    }

    // ==================================================================
    // Falle 1: is_special
    // ==================================================================

    @Nested
    @DisplayName("Falle 1: eine Rolle ohne is_special ueberlebt den Sync nicht")
    class Dauerhaftigkeit {

        /**
         * DIE ENTSCHEIDUNG, festgehalten: das Zuweisen SETZT das Kennzeichen,
         * statt zu warnen oder zu verweigern.
         *
         * <p>Ohne diese Zeile liefe folgendes ab: der Admin vergibt die Rolle,
         * die Oberflaeche zeigt sie gesetzt, und spaetestens zehn Minuten spaeter
         * baut {@code CharacterRoleService.applyRoles} den Rollensatz neu auf und
         * behaelt nur, was in {@code system_roles} als speziell gefuehrt ist. Die
         * Rolle waere fort - ohne Fehler, ohne Meldung, und niemand faende den
         * Zusammenhang zwischen "ich habe es doch gesetzt" und einem
         * Hintergrundlauf. Wer eine Rolle von Hand vergibt, sagt damit "sie soll
         * bleiben"; alles andere waere ein Versprechen mit Ablaufdatum.</p>
         */
        @Test
        @DisplayName("markiert eine neue Rolle beim Zuweisen als dauerhaft")
        void zuweisenMarkiertAlsDauerhaft() {
            service.grant(DIREKTORIN, PILOT, LOGISTIK_ROLE, null);

            ArgumentCaptor<SystemRole> gespeichert = ArgumentCaptor.forClass(SystemRole.class);
            verify(systemRoleRepo).save(gespeichert.capture());
            assertThat(gespeichert.getValue().getRoleName()).isEqualTo(LOGISTIK_ROLE);
            assertThat(gespeichert.getValue().isSpecial()).isTrue();
            assertThat(charaktere.get(PILOT).getRoles()).contains(LOGISTIK_ROLE);
        }

        /** Eine vorhandene Zeile ohne Kennzeichen wird nachgezogen statt ersetzt. */
        @Test
        @DisplayName("zieht das Kennzeichen an einer bestehenden Rolle nach")
        void bestehendeRolleWirdNachgezogen() {
            katalogZeile(LOGISTIK_ROLE, "Die Logistik-SIG", false);

            service.grant(DIREKTORIN, PILOT, LOGISTIK_ROLE, null);

            ArgumentCaptor<SystemRole> gespeichert = ArgumentCaptor.forClass(SystemRole.class);
            verify(systemRoleRepo).save(gespeichert.capture());
            assertThat(gespeichert.getValue().isSpecial()).isTrue();
            // Die Beschreibung aus dem Rollenkatalog bleibt stehen - sie ist dort
            // mit Bedacht gesetzt worden.
            assertThat(gespeichert.getValue().getDescription()).isEqualTo("Die Logistik-SIG");
        }

        /** Ist das Kennzeichen schon gesetzt, wird nichts geschrieben. */
        @Test
        @DisplayName("laesst eine bereits dauerhafte Rolle unangetastet")
        void dauerhafteRolleBleibtUnberuehrt() {
            katalogZeile(LOGISTIK_ROLE, "Die Logistik-SIG", true);

            service.grant(DIREKTORIN, PILOT, LOGISTIK_ROLE, null);

            verify(systemRoleRepo, never()).save(any());
            assertThat(charaktere.get(PILOT).getRoles()).contains(LOGISTIK_ROLE);
        }

        /**
         * Die eine Verweigerung, die aus Falle 1 folgt - und der Grund, warum
         * "einfach immer markieren" nicht genuegt.
         *
         * <p>Das Kennzeichen gilt der ROLLE und nicht diesem einen Charakter.
         * Setzte man es an einer Rolle, die ein Ingame-Titel vergibt, bliebe sie
         * kuenftig JEDEM Traeger auch nach dem Verlust des Titels erhalten - aus
         * einer Rolle, die mit dem Titel kommt und geht, wuerde eine, die nur
         * noch kommt. Dieselbe Gefahr wehren {@code RoleCatalogService.save} und
         * {@code AuthGroupService.saveGroup} schon heute ab.</p>
         */
        @Test
        @DisplayName("verweigert eine Titel-Rolle, die dafuer erst dauerhaft werden muesste")
        void titelRolleWirdNichtStillDauerhaftGemacht() {
            assertThatThrownBy(() -> service.grant(DIREKTORIN, PILOT, FC_ROLE, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(FC_TITEL);

            verify(systemRoleRepo, never()).save(any());
            assertThat(charaktere.get(PILOT).getRoles()).doesNotContain(FC_ROLE);
            verify(auditRepo, never()).save(any());
        }

        /**
         * Die Gegenprobe: ist die Titel-Rolle im Katalog bereits als dauerhaft
         * gefuehrt, ist die Entscheidung "sie ueberdauert den Titelverlust"
         * schon getroffen - und zwar von jemandem, der sie dort bewusst gesetzt
         * hat. Dann darf sie auch von Hand vergeben werden.
         */
        @Test
        @DisplayName("laesst eine bereits dauerhafte Titel-Rolle zu")
        void dauerhafteTitelRolleDarfVergebenWerden() {
            katalogZeile(AUSBILDER_ROLE, "Ausbilderin", true);

            service.grant(DIREKTORIN, PILOT, AUSBILDER_ROLE, null);

            assertThat(charaktere.get(PILOT).getRoles()).contains(AUSBILDER_ROLE);
        }
    }

    // ==================================================================
    // Falle 2: Titel-Rollen
    // ==================================================================

    @Nested
    @DisplayName("Falle 2: Titel-Rollen kommen nach dem Entzug zurueck")
    class TitelRollen {

        /**
         * Ohne diese Kennzeichnung sieht der Admin einen Knopf wie jeden anderen.
         * Er klickt, die Rolle verschwindet aus der Liste, und zehn Minuten
         * spaeter traegt {@code CharacterRoleService.titleRoles} sie wieder ein.
         * Er versucht es ein zweites und ein drittes Mal, bevor er begreift, dass
         * es nicht an ihm liegt - und dann weiss er immer noch nicht, woran.
         */
        @Test
        @DisplayName("kennzeichnet den Entzug einer Titel-Rolle als aussichtslos")
        void titelRolleGiltAlsNichtEntziehbar() {
            gibRolle(PILOT, FC_ROLE);

            RoleAssignmentDtos.RoleStateDto fcRolle = rolle(service.rolesOf(DIREKTORIN, PILOT), FC_ROLE);

            assertThat(fcRolle.held()).isTrue();
            assertThat(fcRolle.revocable()).isFalse();
            // Der Titel steht mit Namen drin: der Admin soll ihn ingame
            // wiederfinden, nicht nur erfahren, dass "irgendein Titel" schuld ist.
            assertThat(fcRolle.grantingTitles()).containsExactly(FC_TITEL);
            assertThat(fcRolle.note()).contains(FC_TITEL);
        }

        /**
         * Die Auskunft und die Tat duerfen nie auseinanderfallen. Liesse der
         * Dienst den Entzug zu, meldete der Klick Erfolg - und der naechste Sync
         * machte ihn rueckgaengig. Genau der stille Widerspruch, den die
         * Kennzeichnung oben verhindern soll.
         */
        @Test
        @DisplayName("verweigert den Entzug einer Titel-Rolle")
        void titelRolleLaesstSichNichtEntziehen() {
            gibRolle(PILOT, FC_ROLE);

            assertThatThrownBy(() -> service.revoke(DIREKTORIN, PILOT, FC_ROLE, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(FC_TITEL);

            assertThat(charaktere.get(PILOT).getRoles()).contains(FC_ROLE);
            verify(auditRepo, never()).save(any());
        }

        /** Eine Zuordnung ohne Rollennamen heisst "dieser Titel vergibt nichts". */
        @Test
        @DisplayName("zaehlt eine leere Titel-Zuordnung nicht als Herkunft")
        void leereZuordnungZaehltNicht() {
            zuordnungen.clear();
            zuordnung("", "Ehrentitel");
            katalogZeile(LOGISTIK_ROLE, "Die Logistik-SIG", true);
            gibRolle(PILOT, LOGISTIK_ROLE);

            service.revoke(DIREKTORIN, PILOT, LOGISTIK_ROLE, null);

            assertThat(charaktere.get(PILOT).getRoles()).doesNotContain(LOGISTIK_ROLE);
        }

        /**
         * Entzogen wird genau eine Rolle. Das ganze Set zu leeren sperrte den
         * Charakter aus der halben Anwendung aus, und der Discord-Sync raeumte
         * still hinterher.
         */
        @Test
        @DisplayName("nimmt nur die genannte Rolle und laesst die uebrigen stehen")
        void entziehtNurDieEineRolle() {
            katalogZeile(LOGISTIK_ROLE, "Die Logistik-SIG", true);
            gibRolle(PILOT, LOGISTIK_ROLE);

            service.revoke(DIREKTORIN, PILOT, LOGISTIK_ROLE, null);

            assertThat(charaktere.get(PILOT).getRoles())
                    .containsExactlyInAnyOrder(SystemRoles.USER, SystemRoles.MEMBER);
        }
    }

    // ==================================================================
    // Eingebaute Rollen
    // ==================================================================

    @Nested
    @DisplayName("Eingebaute Rollen bleiben aussen vor")
    class EingebauteRollen {

        /**
         * {@code ROLE_USER}, {@code ROLE_MEMBER} und {@code ROLE_GUEST} berechnet
         * der Sync bei JEDEM Lauf aus der Corp-Zugehoerigkeit neu - von Hand
         * gesetzt sind sie zehn Minuten spaeter wieder so, wie die Corporation es
         * sagt. Das Vergeben ist wirkungslos, das Entziehen ebenso.
         */
        @Test
        @DisplayName("verweigert das Zuweisen von ROLE_MEMBER")
        void mitgliedsrolleLaesstSichNichtVergeben() {
            assertThatThrownBy(() -> service.grant(DIREKTORIN, PILOT, SystemRoles.MEMBER, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(SystemRoles.MEMBER);

            verify(auditRepo, never()).save(any());
        }

        @Test
        @DisplayName("verweigert das Entziehen von ROLE_USER")
        void grundrolleLaesstSichNichtEntziehen() {
            assertThatThrownBy(() -> service.revoke(DIREKTORIN, PILOT, SystemRoles.USER, null))
                    .isInstanceOf(IllegalArgumentException.class);

            assertThat(charaktere.get(PILOT).getRoles()).contains(SystemRoles.USER);
        }

        /**
         * Der wichtigere Teil derselben Regel: eine Fuehrungsrolle von Hand zu
         * vergeben hiesse nach Falle 1, sie als dauerhaft zu markieren - und
         * damit ueberdauerte sie den Verlust des Ingame-Titels. Nebenwirkung mit
         * Gewinn: ueber diesen Weg macht sich niemand zum IT-Admin.
         */
        @Test
        @DisplayName("verweigert das Zuweisen von ROLE_IT_ADMIN - auch an sich selbst")
        void fuehrungsrolleLaesstSichNichtVergeben() {
            assertThatThrownBy(() ->
                    service.grant(DIREKTORIN, DIREKTORIN, SystemRoles.IT_ADMIN, null))
                    .isInstanceOf(IllegalArgumentException.class);

            assertThat(charaktere.get(DIREKTORIN).getRoles())
                    .doesNotContain(SystemRoles.IT_ADMIN);
            verify(auditRepo, never()).save(any());
        }

        @Test
        @DisplayName("weist eingebaute Rollen in der Auskunft als unantastbar aus")
        void auskunftKennzeichnetEingebauteRollen() {
            RoleAssignmentDtos.RoleStateDto grundrolle =
                    rolle(service.rolesOf(DIREKTORIN, PILOT), SystemRoles.USER);

            assertThat(grundrolle.held()).isTrue();
            assertThat(grundrolle.assignable()).isFalse();
            assertThat(grundrolle.revocable()).isFalse();
            assertThat(grundrolle.source()).isEqualTo(AuthRoleSource.BUILT_IN);
        }
    }

    // ==================================================================
    // Falle 3 und 4: der Nachweis
    // ==================================================================

    @Nested
    @DisplayName("Falle 3 und 4: der Nachweis")
    class Nachweis {

        /**
         * Ohne diesen Eintrag steht am Charakter nur der Rollenname. Woher er
         * kommt, sagt der Rollensatz nicht - eine Rolle aus einem Titel, eine aus
         * einer Gruppenaufnahme und eine von Hand vergebene sehen dort
         * zeichengenau gleich aus. Die Frage "wer hat mir das gegeben?" waere ab
         * dem ersten Klick unbeantwortbar.
         */
        @Test
        @DisplayName("schreibt zu jeder Zuweisung einen Eintrag samt Grund")
        void zuweisungWirdProtokolliert() {
            service.grant(DIREKTORIN, PILOT, LOGISTIK_ROLE, "  Uebernimmt die Logistik  ");

            RoleAssignmentAudit eintrag = letzterEintrag();
            assertThat(eintrag.getAction()).isEqualTo(RoleAssignmentAudit.ACTION_GRANT);
            assertThat(eintrag.getCharacterId()).isEqualTo(PILOT);
            assertThat(eintrag.getActorCharacterId()).isEqualTo(DIREKTORIN);
            assertThat(eintrag.getRoleName()).isEqualTo(LOGISTIK_ROLE);
            assertThat(eintrag.getReason()).isEqualTo("Uebernimmt die Logistik");
            assertThat(eintrag.getOccurredAt()).isNotNull();
            assertThat(eintrag.isSelfAssigned()).isFalse();
        }

        /** Der Entzug ist genauso begruendungsbeduerftig wie die Vergabe. */
        @Test
        @DisplayName("schreibt zu jedem Entzug einen Eintrag")
        void entzugWirdProtokolliert() {
            katalogZeile(LOGISTIK_ROLE, "Die Logistik-SIG", true);
            gibRolle(PILOT, LOGISTIK_ROLE);

            service.revoke(DIREKTORIN, PILOT, LOGISTIK_ROLE, null);

            RoleAssignmentAudit eintrag = letzterEintrag();
            assertThat(eintrag.getAction()).isEqualTo(RoleAssignmentAudit.ACTION_REVOKE);
            assertThat(eintrag.getCharacterId()).isEqualTo(PILOT);
            assertThat(eintrag.getActorCharacterId()).isEqualTo(DIREKTORIN);
            // Ohne Angabe bleibt das Feld leer statt "" - ein Protokoll voller
            // Platzhalter waere schlechter als eines mit ehrlichen Luecken.
            assertThat(eintrag.getReason()).isNull();
        }

        /**
         * FALLE 4. Ein Admin kann sich Rollen faktisch ohnehin verschaffen - er
         * legt sonst eine Gruppe an und tritt ihr bei. Eine Sperre waere Symbolik
         * und draengte den Vorgang nur auf einen Weg ohne Nachweis. Sichtbar muss
         * er sein, nicht unmoeglich - genau wie die Selbstannahme eines IT-Admins
         * in {@code AuthGroupService.decide} protokolliert wird.
         *
         * <p>Ohne das Kennzeichen stuenden in der Zeile zwei gleiche IDs, und wer
         * die Selbstvergabe suchen wollte, muesste vorher wissen, dass es sie
         * gibt.</p>
         */
        @Test
        @DisplayName("kennzeichnet es, wenn ein Admin sich selbst eine Rolle gibt")
        void selbstvergabeStehtImNachweis() {
            RoleAssignmentDtos.RoleAuditDto ergebnis =
                    service.grant(DIREKTORIN, DIREKTORIN, LOGISTIK_ROLE, "Ich fahre selbst mit");

            RoleAssignmentAudit eintrag = letzterEintrag();
            assertThat(eintrag.isSelfAssigned()).isTrue();
            assertThat(eintrag.getCharacterId()).isEqualTo(DIREKTORIN);
            assertThat(eintrag.getActorCharacterId()).isEqualTo(DIREKTORIN);
            assertThat(charaktere.get(DIREKTORIN).getRoles()).contains(LOGISTIK_ROLE);

            // Der Rueckgabewert muss den Fall ueberstehen: Handelnder und
            // Betroffener sind derselbe Charakter, und eine unveraenderliche
            // Map.of haette bei dem doppelten Schluessel geworfen - ausgerechnet
            // im Fall, den dieser Nachweis besonders sichtbar machen soll.
            assertThat(ergebnis.selfAssigned()).isTrue();
            assertThat(ergebnis.characterName()).isEqualTo("Direktorin");
            assertThat(ergebnis.actorName()).isEqualTo("Direktorin");
        }

        @Test
        @DisplayName("liefert den Verlauf eines Charakters mit Namen statt IDs")
        void verlaufNenntNamen() {
            RoleAssignmentAudit eintrag = new RoleAssignmentAudit();
            eintrag.setId(1L);
            eintrag.setCharacterId(PILOT);
            eintrag.setActorCharacterId(DIREKTORIN);
            eintrag.setRoleName(LOGISTIK_ROLE);
            eintrag.setAction(RoleAssignmentAudit.ACTION_GRANT);
            when(auditRepo.findByCharacterIdOrderByOccurredAtDesc(PILOT))
                    .thenReturn(List.of(eintrag));

            List<RoleAssignmentDtos.RoleAuditDto> verlauf = service.auditFor(DIREKTORIN, PILOT);

            assertThat(verlauf).singleElement().satisfies(zeile -> {
                assertThat(zeile.characterName()).isEqualTo("Pilot Eins");
                assertThat(zeile.actorName()).isEqualTo("Direktorin");
            });
        }

        /**
         * Ein leerer Verlauf heisst "seit Einfuehrung nichts von Hand geaendert"
         * und nicht "die Rolle war schon immer da" - die Aufzeichnung
         * rekonstruiert die Vergangenheit ausdruecklich nicht.
         */
        @Test
        @DisplayName("liefert fuer einen Charakter ohne Eintraege eine leere Liste")
        void leererVerlauf() {
            when(auditRepo.findByCharacterIdOrderByOccurredAtDesc(PILOT)).thenReturn(List.of());

            assertThat(service.auditFor(DIREKTORIN, PILOT)).isEmpty();
        }

        @Test
        @DisplayName("laesst nur Admins in den Nachweis sehen")
        void nachweisIstAdminSache() {
            assertThatThrownBy(() -> service.auditFor(MITGLIED_OHNE_AMT, PILOT))
                    .isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> service.recentAudit(MITGLIED_OHNE_AMT))
                    .isInstanceOf(AccessDeniedException.class);
        }

        @Test
        @DisplayName("liefert die juengsten Aenderungen ueber alle Charaktere")
        void juengsteAenderungen() {
            RoleAssignmentAudit eintrag = new RoleAssignmentAudit();
            eintrag.setId(1L);
            eintrag.setCharacterId(PILOT);
            eintrag.setActorCharacterId(DIREKTORIN);
            eintrag.setRoleName(LOGISTIK_ROLE);
            eintrag.setAction(RoleAssignmentAudit.ACTION_REVOKE);
            when(auditRepo.findTop200ByOrderByOccurredAtDesc()).thenReturn(List.of(eintrag));

            assertThat(service.recentAudit(DIREKTORIN)).hasSize(1);
        }
    }

    // ==================================================================
    // Der Rest: Eingaben und Auskunft
    // ==================================================================

    @Nested
    @DisplayName("Eingaben und Auskunft")
    class Auskunft {

        /**
         * Rollennamen werden ueberall als Zeichenkette verglichen. Ohne die
         * Normalisierung waere "logistik sig" aus dem Eingabefeld eine ANDERE
         * Rolle als {@code ROLE_LOGISTIK_SIG}: sie griffe nirgends und liesse
         * sich auch nicht wieder entziehen, weil der Katalog sie nicht kennt.
         */
        @Test
        @DisplayName("bringt einen frei eingetippten Namen auf Rollenschreibweise")
        void nameWirdNormalisiert() {
            service.grant(DIREKTORIN, PILOT, "logistik sig", null);

            assertThat(charaktere.get(PILOT).getRoles()).contains(LOGISTIK_ROLE);
            assertThat(letzterEintrag().getRoleName()).isEqualTo(LOGISTIK_ROLE);
        }

        /**
         * Ein stilles "erledigt" verdeckte eine veraltete Anzeige oder einen
         * falsch verdrahteten Knopf - der Aufrufer glaubte dann, etwas bewirkt zu
         * haben. Dieselbe Ueberlegung wie in {@code AuthGroupService}.
         */
        @Test
        @DisplayName("meldet den Entzug einer gar nicht getragenen Rolle als Fehler")
        void entzugOhneRolle() {
            katalogZeile(LOGISTIK_ROLE, "Die Logistik-SIG", true);

            assertThatThrownBy(() -> service.revoke(DIREKTORIN, PILOT, LOGISTIK_ROLE, null))
                    .isInstanceOf(IllegalArgumentException.class);

            verify(auditRepo, never()).save(any());
        }

        @Test
        @DisplayName("meldet die doppelte Zuweisung als Fehler")
        void doppelteZuweisung() {
            katalogZeile(LOGISTIK_ROLE, "Die Logistik-SIG", true);
            gibRolle(PILOT, LOGISTIK_ROLE);

            assertThatThrownBy(() -> service.grant(DIREKTORIN, PILOT, LOGISTIK_ROLE, null))
                    .isInstanceOf(IllegalArgumentException.class);

            verify(auditRepo, never()).save(any());
        }

        @Test
        @DisplayName("meldet einen unbekannten Charakter als Fehler")
        void unbekannterCharakter() {
            assertThatThrownBy(() -> service.grant(DIREKTORIN, 999L, LOGISTIK_ROLE, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("999");
        }

        /**
         * Die Auskunft muss die Zuweisung vorhersagen, sonst zeigt die
         * Oberflaeche einen Knopf, der 400 liefert - oder verbirgt einen, der
         * funktioniert haette.
         */
        @Test
        @DisplayName("sagt vorab, was sich zuweisen laesst und was nicht")
        void auskunftSagtWasGeht() {
            katalogZeile(LOGISTIK_ROLE, "Die Logistik-SIG", true);

            RoleAssignmentDtos.CharacterRolesDto auskunft = service.rolesOf(DIREKTORIN, PILOT);

            assertThat(auskunft.characterName()).isEqualTo("Pilot Eins");
            assertThat(rolle(auskunft, LOGISTIK_ROLE).assignable()).isTrue();
            assertThat(rolle(auskunft, LOGISTIK_ROLE).survivesSync()).isTrue();
            // Nicht getragen, nicht eingebaut, aber ein Titel vergibt sie und sie
            // ist nicht dauerhaft: genau die Bedingung, an der grant abbricht.
            assertThat(rolle(auskunft, FC_ROLE).assignable()).isFalse();
        }

        /**
         * Getragene Rollen stehen oben. Aus einer Streuung kaeme die Liste bei
         * jedem Laden in anderer Reihenfolge, und der Admin suchte jedes Mal neu.
         */
        @Test
        @DisplayName("stellt die getragenen Rollen nach vorn")
        void getrageneRollenZuerst() {
            katalogZeile(LOGISTIK_ROLE, "Die Logistik-SIG", true);

            List<RoleAssignmentDtos.RoleStateDto> rollen =
                    service.rolesOf(DIREKTORIN, PILOT).roles();

            long getragen = rollen.stream().filter(RoleAssignmentDtos.RoleStateDto::held).count();
            assertThat(rollen.subList(0, (int) getragen))
                    .allMatch(RoleAssignmentDtos.RoleStateDto::held);
        }

        /**
         * Eine Rolle, deren Katalogzeile jemand geloescht hat, bliebe sonst
         * unsichtbar am Charakter haengen - und waere damit auch nicht mehr zu
         * entziehen.
         */
        @Test
        @DisplayName("zeigt auch eine Rolle, die im Katalog gar nicht mehr steht")
        void verwaisteRolleBleibtSichtbar() {
            gibRolle(PILOT, "ROLE_VERGESSEN");

            RoleAssignmentDtos.RoleStateDto verwaist =
                    rolle(service.rolesOf(DIREKTORIN, PILOT), "ROLE_VERGESSEN");

            assertThat(verwaist.held()).isTrue();
            assertThat(verwaist.revocable()).isTrue();
        }
    }

    // ==================================================================
    // Aufbau
    // ==================================================================

    private void charakter(Long id, String name, String... rollen) {
        Corporation corporation = new Corporation();
        corporation.setId(CORP);

        Character character = new Character();
        character.setId(id);
        character.setName(name);
        character.setCorporation(corporation);
        character.setRoles(new HashSet<>(Set.of(rollen)));
        charaktere.put(id, character);
    }

    private void gibRolle(Long characterId, String roleName) {
        charaktere.get(characterId).getRoles().add(roleName);
    }

    private void zuordnung(String roleName, String titleName) {
        TitleRoleMapping mapping = new TitleRoleMapping();
        mapping.setId((long) (zuordnungen.size() + 1));
        mapping.setCorporationId(CORP);
        mapping.setTitleId((long) (zuordnungen.size() + 1));
        mapping.setRoleName(roleName);
        mapping.setTitleName(titleName);
        zuordnungen.add(mapping);
    }

    private void katalogZeile(String roleName, String description, boolean special) {
        SystemRole role = new SystemRole();
        role.setRoleName(roleName);
        role.setDescription(description);
        role.setSpecial(special);
        katalogZeilen.put(roleName, role);
    }

    /**
     * Der Rollenkatalog, wie ihn {@code RoleCatalogService} zusammenstellt: die
     * eingebauten Rollen, die in {@code system_roles} hinterlegten und die aus
     * Titel-Zuordnungen entstandenen.
     */
    private List<RoleCatalogService.AuthRoleDto> katalog() {
        List<RoleCatalogService.AuthRoleDto> katalog = new ArrayList<>();
        for (String name : SystemRoles.builtIn()) {
            katalog.add(new RoleCatalogService.AuthRoleDto(
                    name, "Eingebaut", AuthRoleSource.BUILT_IN, false, List.of()));
        }
        katalogZeilen.forEach((name, role) -> katalog.add(new RoleCatalogService.AuthRoleDto(
                name, role.getDescription(), AuthRoleSource.CUSTOM, role.isSpecial(), List.of())));
        for (TitleRoleMapping mapping : zuordnungen) {
            if (mapping.getRoleName() == null || mapping.getRoleName().isBlank()
                    || katalogZeilen.containsKey(mapping.getRoleName())) {
                continue;
            }
            katalog.add(new RoleCatalogService.AuthRoleDto(mapping.getRoleName(),
                    "Aus einem Ingame-Titel", AuthRoleSource.TITLE, false,
                    List.of(mapping.getTitleName())));
        }
        return katalog;
    }

    private static RoleAssignmentDtos.RoleStateDto rolle(
            RoleAssignmentDtos.CharacterRolesDto auskunft, String roleName) {
        return auskunft.roles().stream()
                .filter(rolle -> rolle.roleName().equals(roleName))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        roleName + " fehlt in der Auskunft: " + auskunft.roles()));
    }

    private RoleAssignmentAudit letzterEintrag() {
        ArgumentCaptor<RoleAssignmentAudit> gespeichert =
                ArgumentCaptor.forClass(RoleAssignmentAudit.class);
        verify(auditRepo).save(gespeichert.capture());
        return gespeichert.getValue();
    }
}
