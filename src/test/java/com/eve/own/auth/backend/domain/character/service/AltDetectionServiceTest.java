package com.eve.own.auth.backend.domain.character.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.eve.own.auth.backend.domain.auth.SystemRoles;
import com.eve.own.auth.backend.domain.character.dto.CharacterDtos;
import com.eve.own.auth.backend.domain.character.entity.AltLinkProposal;
import com.eve.own.auth.backend.domain.character.entity.Character;
import com.eve.own.auth.backend.domain.character.entity.CharacterMining;
import com.eve.own.auth.backend.domain.character.entity.Corporation;
import com.eve.own.auth.backend.domain.character.repository.AltLinkProposalRepository;
import com.eve.own.auth.backend.domain.character.repository.CharacterMiningRepository;
import com.eve.own.auth.backend.domain.character.repository.CharacterRepository;
import com.eve.own.auth.backend.esi.EsiResponse;
import com.eve.own.auth.backend.esi.EsiService;
import java.time.Instant;
import java.util.List;
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

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Alt-Erkennung fuer nicht registrierte Corp-Mitglieder")
class AltDetectionServiceTest {

    private static final Long CORP = 98000001L;
    private static final Long MAIN_ID = 1000L;
    private static final Long UNAUTHED_ID = 5000L;
    private static final Long STRANGER_ID = 7000L;

    private static final String MAIN_NAME = "Comander Video";

    /**
     * Durchnummerierter Zwilling des Mains - das Muster, das auch ALLEIN traegt.
     *
     * <p>Vorher stand hier "Sansha Video", also blosse Nachnamensgleichheit. Die
     * ist als Grundfall untauglich: an echten Daten gemessen ergibt sie 85, und
     * genau dieser Wert faellt seit der Einzelsignal-Schwelle heraus, weil zwei
     * fremde EVE-Spieler voellig gewoehnlich denselben Nachnamen tragen. Ein
     * Testbestand, dessen Normalfall der Fehlalarm ist, prueft die falsche
     * Sache.</p>
     */
    private static final String UNAUTHED_NAME = MAIN_NAME + " 2";

    private static final Instant JOIN = Instant.parse("2026-03-01T12:00:00Z");

    @Mock private CorporationStatsService corporationStatsService;
    @Mock private CharacterRepository characterRepo;
    @Mock private CharacterMiningRepository miningRepo;
    @Mock private AltLinkProposalRepository proposalRepo;
    @Mock private DirectorTokenProvider directorTokenProvider;
    @Mock private EsiService esiService;

    private AltDetectionService service;
    private Character main;

    /**
     * Die Stellschrauben mit ihren Vorgabewerten - also genau den frueheren
     * Konstanten.
     *
     * <p>Je Test ein frisches Objekt. Ein Test, der einen Wert verstellt, um zu
     * pruefen, dass die Konfiguration ueberhaupt wirkt, darf den naechsten Test
     * nicht mitverstellen; genau das waere bei einem statischen Konstantenhalter
     * gar nicht erst moeglich gewesen und ist der Preis der Konfigurierbarkeit.</p>
     */
    private AltDetectionProperties props;

    @BeforeEach
    void setUp() {
        props = new AltDetectionProperties();
        service = new AltDetectionService(corporationStatsService, characterRepo, miningRepo,
                proposalRepo, directorTokenProvider, esiService, props);

        main = character(MAIN_ID, MAIN_ID, MAIN_NAME, SystemRoles.DIRECTOR);

        stats(new CharacterDtos.UnauthedCharDto(UNAUTHED_ID, UNAUTHED_NAME, "portrait"));
        when(characterRepo.findByCorporationId(CORP)).thenReturn(List.of(main));
        when(characterRepo.findAllById(any())).thenReturn(List.of(main));
        when(characterRepo.findById(MAIN_ID)).thenReturn(Optional.of(main));
        when(characterRepo.findById(UNAUTHED_ID)).thenReturn(Optional.empty());
        when(proposalRepo.findByUnauthedCharacterIdIn(anyList())).thenReturn(List.of());
        when(proposalRepo.findByUnauthedCharacterId(anyLong())).thenReturn(Optional.empty());
        when(miningRepo.findByCharacterIdIn(anyList())).thenReturn(List.of());

        // Voreinstellung: KEIN Director-Token, also keine Mitgliederverfolgung.
        // Genau der Zustand, in dem zwei der drei Signale fehlen.
        keinDirectorToken();
    }

    // ==================================================================
    // Hilfen
    // ==================================================================

    private static Character character(Long id, Long mainId, String name, String... roles) {
        Corporation corporation = new Corporation();
        corporation.setId(CORP);

        Character character = new Character();
        character.setId(id);
        character.setMainCharacterId(mainId);
        character.setName(name);
        character.setCorporation(corporation);
        character.setRoles(Set.of(roles));
        return character;
    }

    private void stats(CharacterDtos.UnauthedCharDto... unauthed) {
        when(corporationStatsService.statsForAllCorporations()).thenReturn(List.of(
                new CharacterDtos.CorpStatsDto(CORP, "Corp Eins", 2, 1, 0, 1,
                        List.of(), List.of(unauthed))));
    }

    private void keinDirectorToken() {
        doReturn(new DirectorTokenProvider.DirectorAttempt<>(null, null, List.of()))
                .when(directorTokenProvider).attempt(anyLong(), anyString(), any());
    }

    /** Mitgliederverfolgung mit frei waehlbaren Beitrittszeitpunkten. */
    private void beitrittsdaten(EsiService.EsiMemberTrackingResponse... eintraege) {
        EsiResponse<EsiService.EsiMemberTrackingResponse[]> antwort =
                EsiResponse.changed(eintraege, "etag", null);
        doReturn(new DirectorTokenProvider.DirectorAttempt<>(antwort, main, List.of()))
                .when(directorTokenProvider).attempt(anyLong(), anyString(), any());
    }

    private static EsiService.EsiMemberTrackingResponse beitritt(Long characterId, Instant start) {
        return new EsiService.EsiMemberTrackingResponse(
                characterId, start, null, null, null, null, null);
    }

    private static CharacterMining miningRow(Long characterId, String tag) {
        CharacterMining row = new CharacterMining();
        row.setCharacterId(characterId);
        row.setDate(tag);
        row.setTypeId(1230L);
        row.setQuantity(100L);
        return row;
    }

    private static CharacterDtos.AltSignalDto signal(CharacterDtos.AltSuggestionDto suggestion,
                                                     String name) {
        return suggestion.signals().stream()
                .filter(entry -> entry.signal().equals(name))
                .findFirst()
                .orElseThrow();
    }

    // ==================================================================
    // Die entscheidende Regel
    // ==================================================================

    @Nested
    @DisplayName("Ein fehlendes Signal ist kein Signal mit Wert 0")
    class FehlendeSignale {

        @Test
        @DisplayName("ein fehlendes Signal senkt den Score nicht, es faellt aus der Normierung")
        void fehlendesSignalSenktNicht() {
            // Ohne diese Regel wuerde durch die Summe ALLER Gewichte geteilt:
            // 40*85 / (40+45+15) = 34 statt 85. Aus "ueber Beitritt und Mining
            // ist nichts bekannt" wuerde stillschweigend "geprueft und
            // unauffaellig" - eine niedrige Zahl, die wie ein Freispruch
            // aussieht, obwohl nichts geprueft wurde.
            CharacterDtos.AltSuggestionDto suggestion = service.findProbableAlts().getFirst();

            assertThat(suggestion.probability())
                    .as("nur der Name lag vor, also ist der Score genau der Namenswert")
                    .isEqualTo(props.getNameNumberedTwinScore());
            assertThat(suggestion.probability())
                    .as("und ausdruecklich nicht der ueber alle drei Gewichte verduennte Wert")
                    .isNotEqualTo(34);
        }

        @Test
        @DisplayName("das DTO sagt, welche Signale getragen haben und welche fehlten")
        void aufschluesselungWandertMit() {
            // Ohne die Aufschluesselung stuende dort eine nackte Zahl, und der
            // Director haelt sie fuer geeicht. Eine 85 aus EINEM Signal ist
            // etwas anderes als eine 85 aus dreien.
            CharacterDtos.AltSuggestionDto suggestion = service.findProbableAlts().getFirst();

            assertThat(suggestion.signalsUsed()).isEqualTo(1);
            assertThat(suggestion.signalsTotal()).isEqualTo(AltDetectionService.SIGNAL_COUNT);

            assertThat(signal(suggestion, AltDetectionService.SIGNAL_NAME).available()).isTrue();
            assertThat(signal(suggestion, AltDetectionService.SIGNAL_NAME).score())
                    .isEqualTo(props.getNameNumberedTwinScore());

            CharacterDtos.AltSignalDto mining =
                    signal(suggestion, AltDetectionService.SIGNAL_MINING);
            assertThat(mining.available()).isFalse();
            assertThat(mining.score())
                    .as("nicht gemessen heisst null und nicht 0 - eine 0 waere eine Aussage")
                    .isNull();
            assertThat(mining.detail()).isNotBlank();
        }

        @Test
        @DisplayName("ein gemessener Wert 0 bleibt in der Normierung stehen")
        void gemessenerNullwertBleibt() {
            // Weit auseinander beigetreten IST eine Messung. Fiele sie als
            // "fehlend" heraus, koennte ein Paar, das nachweislich Monate
            // auseinander liegt, allein ueber den Namen die Schwelle reissen.
            beitrittsdaten(beitritt(UNAUTHED_ID, JOIN),
                    beitritt(MAIN_ID, JOIN.plusSeconds(60L * 60 * 24 * 30)));

            List<CharacterDtos.AltSuggestionDto> suggestions = service.findProbableAlts();

            assertThat(suggestions)
                    .as("40*85 + 45*0 geteilt durch 85 ergibt 40 - unter der Schwelle")
                    .isEmpty();
        }
    }

    // ==================================================================
    // Schwelle
    // ==================================================================

    @Nested
    @DisplayName("Schwelle")
    class Schwelle {

        @Test
        @DisplayName("unter der Schwelle faellt der Vorschlag heraus")
        void unterDerSchwelleKeinVorschlag() {
            // Ohne den Filter bekaeme der Director eine Liste mit JEDEM nicht
            // registrierten Mitglied darin - bei 399 Mitgliedern eine Liste, in
            // der die wenigen echten Treffer untergehen.
            stats(new CharacterDtos.UnauthedCharDto(UNAUTHED_ID, "Zzz Qqqq Wwww", "portrait"));

            assertThat(service.findProbableAlts()).isEmpty();
        }

        @Test
        @DisplayName("Ein blosser Namensvetter kommt nicht durch, ein Zwilling schon")
        void einzelnesSignalMussHoeherSpringen() {
            // An echten Daten gemessen: traegt nur der Name, IST der Gesamtwert
            // der Namenswert. Ein gemeinsamer Nachname ergibt 85 und laege ueber
            // der normalen Schwelle von 80 - in EVE haben zwei fremde Spieler
            // aber voellig gewoehnlich denselben Nachnamen. Ohne die hoehere
            // Einzelsignal-Schwelle bestuende die Liste bei mehreren hundert
            // unregistrierten Mitgliedern ueberwiegend aus solchen Zufaellen,
            // jeder mit einem Knopf daneben, der einen fremden Menschen einem
            // fremden Konto zuschlaegt.
            stats(new CharacterDtos.UnauthedCharDto(UNAUTHED_ID, "Zaphod Video", "portrait"));
            assertThat(service.findProbableAlts())
                    .as("gemeinsamer Nachname allein reicht nicht")
                    .isEmpty();

            // Der durchnummerierte Zwilling ist dagegen ein ernstzunehmender
            // Hinweis und muss weiterhin durchkommen - sonst haette die
            // Verschaerfung das Merkmal gleich mit abgeschaltet.
            stats(new CharacterDtos.UnauthedCharDto(UNAUTHED_ID, MAIN_NAME + " 2", "portrait"));
            assertThat(service.findProbableAlts())
                    .as("durchnummerierter Zwilling traegt auch allein")
                    .singleElement()
                    .satisfies(v -> assertThat(v.signalsUsed()).isEqualTo(1));
        }

        @Test
        @DisplayName("zwei starke Signale heben den Vorschlag ueber die Schwelle")
        void zweiSignaleTragen() {
            beitrittsdaten(beitritt(UNAUTHED_ID, JOIN), beitritt(MAIN_ID, JOIN.plusSeconds(120)));

            CharacterDtos.AltSuggestionDto suggestion = service.findProbableAlts().getFirst();

            assertThat(suggestion.signalsUsed()).isEqualTo(2);
            assertThat(suggestion.probability())
                    .isGreaterThanOrEqualTo(props.getMinProbability());
            assertThat(signal(suggestion, AltDetectionService.SIGNAL_JOIN).score()).isEqualTo(100);
        }

        @Test
        @DisplayName("eine Rekrutierungswelle daempft den gemeinsamen Beitritt")
        void rekrutierungswelleWirdGedaempft() {
            // Ohne die Daempfung waere der Beitritt derselbe Fehler wie der rohe
            // Mining-Tag: ein Gruppenereignis, das fuer einen Fingerabdruck
            // gehalten wird. Zehn gleichzeitig aufgenommene Bewerber wuerden
            // dann alle zehn den vollen Punktwert bekommen.
            EsiService.EsiMemberTrackingResponse[] welle =
                    new EsiService.EsiMemberTrackingResponse[12];
            welle[0] = beitritt(UNAUTHED_ID, JOIN);
            welle[1] = beitritt(MAIN_ID, JOIN.plusSeconds(120));
            for (int i = 2; i < welle.length; i++) {
                welle[i] = beitritt(60000L + i, JOIN.plusSeconds(60L * i));
            }
            beitrittsdaten(welle);

            assertThat(service.findProbableAlts())
                    .as("ohne Daempfung waere es 93 und der Vorschlag stuende in der Liste; "
                            + "zwoelf Beitritte im selben Fenster sagen ueber ein Paar nichts mehr")
                    .isEmpty();
        }
    }

    // ==================================================================
    // Mining
    // ==================================================================

    @Nested
    @DisplayName("Mining")
    class Mining {

        @Test
        @DisplayName("ohne Mining-Zeilen des Kandidaten gilt das Signal als nicht verfuegbar")
        void ohneZeilenNichtVerfuegbar() {
            // Ein nicht registrierter Charakter hat kein eigenes Token und
            // deshalb keine einzige Zeile im Ledger. Ohne diese Unterscheidung
            // flosse er mit 0 ein und saehe aus wie geprueft.
            when(miningRepo.findByCharacterIdIn(anyList()))
                    .thenReturn(List.of(miningRow(MAIN_ID, "2026-05-01")));

            CharacterDtos.AltSignalDto mining =
                    signal(service.findProbableAlts().getFirst(), AltDetectionService.SIGNAL_MINING);

            assertThat(mining.available()).isFalse();
            assertThat(mining.score()).isNull();
        }

        @Test
        @DisplayName("ein einziger gemeinsamer Tag ist noch keine Aussage")
        void einTagReichtNicht() {
            // Bei 16 bekannten Mining-Tagen im ganzen Bestand ist ein einzelner
            // gemeinsamer Tag Rauschen. Ohne die Mindestzahl entstuende daraus
            // ein Wert, der genauso aussieht wie ein belegter.
            when(miningRepo.findByCharacterIdIn(anyList())).thenReturn(List.of(
                    miningRow(UNAUTHED_ID, "2026-05-01"),
                    miningRow(MAIN_ID, "2026-05-01")));

            CharacterDtos.AltSignalDto mining =
                    signal(service.findProbableAlts().getFirst(), AltDetectionService.SIGNAL_MINING);

            assertThat(mining.available()).isFalse();
            assertThat(mining.detail()).contains("gemeinsame Mining-Tage");
        }

        @Test
        @DisplayName("gemeinsame Mining-Tage zaehlen, sobald genug davon vorliegen")
        void genugTageErgebenEinenWert() {
            when(miningRepo.findByCharacterIdIn(anyList())).thenReturn(List.of(
                    miningRow(UNAUTHED_ID, "2026-05-01"), miningRow(MAIN_ID, "2026-05-01"),
                    miningRow(UNAUTHED_ID, "2026-05-02"), miningRow(MAIN_ID, "2026-05-02")));

            CharacterDtos.AltSuggestionDto suggestion = service.findProbableAlts().getFirst();
            CharacterDtos.AltSignalDto mining =
                    signal(suggestion, AltDetectionService.SIGNAL_MINING);

            assertThat(mining.available()).isTrue();
            assertThat(mining.score()).isNotNull().isPositive();
            assertThat(suggestion.signalsUsed()).isEqualTo(2);
        }
    }

    // ==================================================================
    // Bestaetigung
    // ==================================================================

    @Nested
    @DisplayName("Bestaetigung")
    class Bestaetigung {

        @Test
        @DisplayName("ein Fremder darf nicht bestaetigen")
        void fremderDarfNicht() {
            // Die Pruefung steht IM DIENST und nicht nur am Controller: die
            // Annotation dort deckt genau einen Einstiegspunkt ab und faellt bei
            // einem Umbau lautlos weg. Ohne diese Zeile koennte jeder
            // angemeldete Spieler einen fremden Charakter einem fremden Konto
            // zuschlagen.
            Character fremder = character(STRANGER_ID, STRANGER_ID, "Random Guy", SystemRoles.MEMBER);
            when(characterRepo.findById(STRANGER_ID)).thenReturn(Optional.of(fremder));

            assertThatThrownBy(() ->
                    service.confirmAltSuggestion(STRANGER_ID, UNAUTHED_ID, MAIN_ID))
                    .isInstanceOf(AccessDeniedException.class);

            verify(proposalRepo, never()).save(any());
        }

        @Test
        @DisplayName("eine bestehende Zuordnung wird nicht ueberschrieben")
        void bestehendeZuordnungBleibt() {
            // Der gefaehrlichste Fall des ganzen Merkmals. Wuerde hier
            // geschrieben, haenge ein Charakter an einem fremden Konto - mit
            // dessen Steuerakte, dessen Bestaenden und dessen Rollen. Und es
            // gaebe keinen Weg zurueck: im ganzen Produktivcode loest nichts
            // einen Charakter wieder aus einem Konto heraus.
            Character bereitsZugeordnet = character(UNAUTHED_ID, 9999L, UNAUTHED_NAME);
            when(characterRepo.findById(UNAUTHED_ID)).thenReturn(Optional.of(bereitsZugeordnet));

            assertThatThrownBy(() -> service.confirmAltSuggestion(MAIN_ID, UNAUTHED_ID, MAIN_ID))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("bereits registriert");

            verify(proposalRepo, never()).save(any());
        }

        @Test
        @DisplayName("eine bestehende Vormerkung wird nicht stillschweigend ersetzt")
        void bestehendeVormerkungBleibt() {
            // Sonst koennte ein zweiter Director denselben Charakter einem
            // anderen Konto zuschlagen, und der erste Nachweis waere weg -
            // ausgerechnet der, der die Frage "wer hat das behauptet?"
            // beantworten soll.
            AltLinkProposal vorhanden = new AltLinkProposal();
            vorhanden.setUnauthedCharacterId(UNAUTHED_ID);
            vorhanden.setMainCharacterId(4242L);
            vorhanden.setDecidedAt(Instant.parse("2026-04-01T00:00:00Z"));
            when(proposalRepo.findByUnauthedCharacterId(UNAUTHED_ID))
                    .thenReturn(Optional.of(vorhanden));

            assertThatThrownBy(() -> service.confirmAltSuggestion(MAIN_ID, UNAUTHED_ID, MAIN_ID))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Vormerkung");

            verify(proposalRepo, never()).save(any());
        }

        @Test
        @DisplayName("ein Paar ohne aktuellen Vorschlag wird abgelehnt")
        void nurVorgeschlagenePaare() {
            // Ohne den Neuabgleich koennte ein Director ein beliebiges Paar
            // bestaetigen, das die Erkennung nie vorgeschlagen hat - und der
            // Nachweis truege eine Wahrscheinlichkeit, die niemand errechnet hat.
            stats(new CharacterDtos.UnauthedCharDto(UNAUTHED_ID, "Zzz Qqqq Wwww", "portrait"));

            assertThatThrownBy(() -> service.confirmAltSuggestion(MAIN_ID, UNAUTHED_ID, MAIN_ID))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("keinen Vorschlag");
        }

        @Test
        @DisplayName("die Bestaetigung schreibt eine Vormerkung mit vollem Nachweis - und nichts sonst")
        void vormerkungMitNachweis() {
            // Ohne actorCharacterId waere die Frage "wer hat behauptet, dass
            // dieser Charakter mir gehoert?" ab dem ersten Klick unbeantwortbar.
            CharacterDtos.AltLinkResultDto result =
                    service.confirmAltSuggestion(MAIN_ID, UNAUTHED_ID, MAIN_ID);

            ArgumentCaptor<AltLinkProposal> captor = ArgumentCaptor.forClass(AltLinkProposal.class);
            verify(proposalRepo).save(captor.capture());
            AltLinkProposal saved = captor.getValue();

            assertThat(saved.getUnauthedCharacterId()).isEqualTo(UNAUTHED_ID);
            assertThat(saved.getUnauthedCharacterName()).isEqualTo(UNAUTHED_NAME);
            assertThat(saved.getMainCharacterId()).isEqualTo(MAIN_ID);
            assertThat(saved.getCorporationId()).isEqualTo(CORP);
            assertThat(saved.getActorCharacterId()).isEqualTo(MAIN_ID);
            assertThat(saved.getProbability())
                    .isEqualTo(props.getNameNumberedTwinScore());
            assertThat(saved.getSignalSummary())
                    .as("die Aufschluesselung gehoert in den Nachweis, nicht nur in die Antwort")
                    .contains(AltDetectionService.SIGNAL_NAME)
                    .contains("nicht verfuegbar");
            assertThat(saved.isSelfAssigned())
                    .as("der Director hat sich den Charakter selbst vorgemerkt")
                    .isTrue();
            assertThat(saved.getDecidedAt()).isNotNull();

            assertThat(result.linked())
                    .as("es wurde NICHTS nach characters.main_character_id geschrieben")
                    .isFalse();
            assertThat(result.message()).contains("NICHT");
        }

        @Test
        @DisplayName("die Bestaetigung fasst characters nicht an")
        void keinSchreibzugriffAufCharacters() {
            // Der Kern der Entscheidung: fuer einen nicht registrierten
            // Charakter gibt es dort keine Zeile, und eine anzulegen hiesse, den
            // Eigentumsnachweis durch eine Wahrscheinlichkeit zu ersetzen.
            service.confirmAltSuggestion(MAIN_ID, UNAUTHED_ID, MAIN_ID);

            verify(characterRepo, never()).save(any());
            verify(characterRepo, never()).saveAll(any());
        }
    }

    // ==================================================================
    // Vorschlagsliste
    // ==================================================================

    @Nested
    @DisplayName("Vorschlagsliste")
    class Vorschlagsliste {

        @Test
        @DisplayName("ein bereits zugeordneter Charakter erscheint nicht mehr als Vorschlag")
        void bereitsZugeordneterVerschwindet() {
            // Ohne diesen Filter bekaeme der Director denselben Vorschlag beim
            // naechsten Aufruf erneut und wuesste nicht, ob sein Klick
            // angekommen ist - und beim zweiten Klick antwortete der Endpunkt
            // mit einem Konflikt, den er sich nicht erklaeren kann.
            AltLinkProposal vorhanden = new AltLinkProposal();
            vorhanden.setUnauthedCharacterId(UNAUTHED_ID);
            vorhanden.setMainCharacterId(MAIN_ID);
            when(proposalRepo.findByUnauthedCharacterIdIn(anyList()))
                    .thenReturn(List.of(vorhanden));

            assertThat(service.findProbableAlts()).isEmpty();
        }

        @Test
        @DisplayName("je Charakter bleibt nur der beste Vorschlag stehen")
        void nurDerBesteVorschlag() {
            // Drei konkurrierende Konten fuer denselben Charakter waeren keine
            // Hilfe, sondern eine Einladung zum Raten.
            Character zweiterMain = character(2000L, 2000L, "Sansha Videoo");
            when(characterRepo.findByCorporationId(CORP)).thenReturn(List.of(main, zweiterMain));
            when(characterRepo.findAllById(any())).thenReturn(List.of(main, zweiterMain));

            List<CharacterDtos.AltSuggestionDto> suggestions = service.findProbableAlts();

            assertThat(suggestions).hasSize(1);
            assertThat(suggestions.getFirst().mainId())
                    .as("der durchnummerierte Zwilling schlaegt den blossen Namensnachbarn")
                    .isEqualTo(MAIN_ID);
        }

        @Test
        @DisplayName("ohne registrierte Konten in der Corporation gibt es nichts zu vergleichen")
        void ohneKontenKeineVorschlaege() {
            when(characterRepo.findByCorporationId(CORP)).thenReturn(List.of());

            assertThat(service.findProbableAlts()).isEmpty();
        }
    }

    // ==================================================================
    // Gruppen unregistrierter Charaktere untereinander
    // ==================================================================

    @Nested
    @DisplayName("Gruppen unregistrierter Charaktere")
    class UnregistrierteGruppen {

        private static final Long A = 5001L;
        private static final Long B = 5002L;
        private static final Long C = 5003L;

        @Test
        @DisplayName("Drei Fremde mit demselben Nachnamen sind keine Gruppe")
        void gleicherNachnameIstKeineGruppe() {
            // Der Fall, an dem die transitive Huelle scheitert: "A Video",
            // "B Video" und "C Video" sind paarweise aehnlich, und blindes
            // Vereinigen macht daraus EINEN Menschen. Ohne die
            // Einzelsignal-Schwelle entstuende hier gar keine Kante erst gar
            // nicht - 85 fuer den geteilten Nachnamen liegt unter den
            // geforderten 90 -, und in einer Corp mit mehreren hundert
            // Mitgliedern waere das Merkmal danach eine Liste aus lauter
            // Namensvettern.
            stats(new CharacterDtos.UnauthedCharDto(A, "Zaphod Video", "portrait"),
                    new CharacterDtos.UnauthedCharDto(B, "Sansha Video", "portrait"),
                    new CharacterDtos.UnauthedCharDto(C, "Random Video", "portrait"));

            assertThat(service.findUnregisteredGroups(MAIN_ID)).isEmpty();
        }

        @Test
        @DisplayName("Zwei durchnummerierte Zwillinge sind eine Gruppe")
        void zwillingeSindEineGruppe() {
            // Die Gegenprobe zur vorigen Zeile: waere die Schwelle so streng,
            // dass auch der Zwilling herausfaellt, haette die Verschaerfung das
            // Merkmal gleich mit abgeschaltet.
            stats(new CharacterDtos.UnauthedCharDto(A, "Miner Guy", "portrait"),
                    new CharacterDtos.UnauthedCharDto(B, "Miner Guy 2", "portrait"));

            List<CharacterDtos.AltGroupDto> gruppen = service.findUnregisteredGroups(MAIN_ID);

            assertThat(gruppen).singleElement().satisfies(gruppe -> {
                assertThat(gruppe.members()).extracting(CharacterDtos.UnauthedCharDto::id)
                        .containsExactlyInAnyOrder(A, B);
                assertThat(gruppe.probability())
                        .isEqualTo(props.getNameNumberedTwinScore());
                assertThat(gruppe.signalsUsed())
                        .as("ohne Director-Token traegt auch hier nur der Name")
                        .isEqualTo(1);
                assertThat(gruppe.note())
                        .as("eine Gruppe ohne Konto ist eine Beobachtung, keine Handlung")
                        .contains("Beobachtung");
            });
        }

        @Test
        @DisplayName("Die Kette A-B-C ohne Kante A-C wird nicht zu einer Gruppe verschmolzen")
        void keineTransitiveHuelle() {
            // Genau der Fehler, den union-find ALLEIN macht: A und B sind
            // aehnlich, B und C sind aehnlich, A und C nicht - die
            // Zusammenhangskomponente umfasst trotzdem alle drei und erklaert
            // drei Menschen zu einem. Ohne die vollstaendige Verkettung
            // ("Kante zu JEDEM Mitglied, nicht bloss zu einem") stuende hier
            // eine Dreiergruppe.
            stats(new CharacterDtos.UnauthedCharDto(A, "Comander Video", "portrait"),
                    new CharacterDtos.UnauthedCharDto(B, "Comander Video 2", "portrait"),
                    new CharacterDtos.UnauthedCharDto(C, "Comander Videq 2", "portrait"));

            List<CharacterDtos.AltGroupDto> gruppen = service.findUnregisteredGroups(MAIN_ID);

            assertThat(gruppen).allSatisfy(gruppe -> assertThat(gruppe.members())
                    .as("keine Gruppe darf alle drei enthalten")
                    .hasSizeLessThan(3));
            assertThat(gruppen).singleElement().satisfies(gruppe ->
                    assertThat(gruppe.members()).extracting(CharacterDtos.UnauthedCharDto::id)
                            .containsExactlyInAnyOrder(A, B));
        }

        @Test
        @DisplayName("Der gemeinsame Beitritt traegt auch zwischen zwei Unregistrierten")
        void beitrittTraegtBeideSeiten() {
            // Der Grund, warum diese Gruppen ueberhaupt zwei Signale haben
            // koennen: die Mitgliederverfolgung kommt mit dem Director-Token und
            // deckt die GANZE Corporation ab, registriert oder nicht. Ohne
            // dieses zweite Signal bliebe hier nur der Name, und ein geteilter
            // Nachname allein ist ausdruecklich keine Gruppe.
            stats(new CharacterDtos.UnauthedCharDto(A, "Zaphod Video", "portrait"),
                    new CharacterDtos.UnauthedCharDto(B, "Sansha Video", "portrait"));
            beitrittsdaten(beitritt(A, JOIN), beitritt(B, JOIN.plusSeconds(120)));

            assertThat(service.findUnregisteredGroups(MAIN_ID)).singleElement()
                    .satisfies(gruppe -> {
                        assertThat(gruppe.signalsUsed()).isEqualTo(2);
                        assertThat(gruppe.probability())
                                .isGreaterThanOrEqualTo(props.getMinProbability());
                    });
        }

        @Test
        @DisplayName("Eine Rekrutierungswelle loest die Namensvettern wieder auf")
        void welleLoestGruppeAuf() {
            // Dieselbe Bremse wie bei den Kontovorschlaegen, und hier ist sie
            // die wichtigste ueberhaupt: drei gleichzeitig aufgenommene
            // Namensvettern wuerden ohne die Cluster-Daempfung ueber den
            // Beitritt genau die Gruppe bilden, die der Nachname allein nicht
            // bilden darf - ein Gruppenereignis, gehalten fuer einen
            // Fingerabdruck.
            stats(new CharacterDtos.UnauthedCharDto(A, "Zaphod Video", "portrait"),
                    new CharacterDtos.UnauthedCharDto(B, "Sansha Video", "portrait"),
                    new CharacterDtos.UnauthedCharDto(C, "Random Video", "portrait"));
            beitrittsdaten(beitritt(A, JOIN), beitritt(B, JOIN.plusSeconds(120)),
                    beitritt(C, JOIN.plusSeconds(240)));

            assertThat(service.findUnregisteredGroups(MAIN_ID)).isEmpty();
        }

        @Test
        @DisplayName("Die Gruppenansicht ist fuer Unberechtigte gesperrt")
        void gruppenNurFuerDieFuehrung() {
            // Dass hier nichts geschrieben wird, macht die Ansicht nicht
            // harmlos: es sind zusammengetragene Vermutungen darueber, welche
            // Menschen hinter welchen Charakteren stecken. Ohne diese Zeile
            // koennte jedes Corp-Mitglied sie abrufen, sobald der Controller
            // einmal umgebaut wird.
            Character fremder = character(STRANGER_ID, STRANGER_ID, "Random Guy", SystemRoles.MEMBER);
            when(characterRepo.findById(STRANGER_ID)).thenReturn(Optional.of(fremder));

            assertThatThrownBy(() -> service.findUnregisteredGroups(STRANGER_ID))
                    .isInstanceOf(AccessDeniedException.class);
        }

        @Test
        @DisplayName("Die Gruppenansicht schreibt nichts")
        void gruppenSchreibenNichts() {
            // Es gibt kein Konto, dem sich eine solche Gruppe zuordnen liesse -
            // also darf hier auch nichts entstehen, was spaeter wie eine
            // Zuordnung aussieht.
            stats(new CharacterDtos.UnauthedCharDto(A, "Miner Guy", "portrait"),
                    new CharacterDtos.UnauthedCharDto(B, "Miner Guy 2", "portrait"));

            service.findUnregisteredGroups(MAIN_ID);

            verify(proposalRepo, never()).save(any());
            verify(characterRepo, never()).save(any());
        }
    }

    // ==================================================================
    // Kalibrieransicht
    // ==================================================================

    @Nested
    @DisplayName("Kalibrieransicht")
    class Kalibrierung {

        @Test
        @DisplayName("Die Kalibrieransicht liefert auch unter der Schwelle, bestaetigt aber nichts")
        void liefertUnterDerSchwelle() {
            // Der eigentliche Mangel, den diese Ansicht behebt: eine leere
            // Vorschlagsliste sieht genauso aus, ob der Scorer nichts findet
            // oder gar nicht laeuft. Ohne diese Zeile bliebe die Schwelle eine
            // Zahl, die man nur raten kann.
            stats(new CharacterDtos.UnauthedCharDto(UNAUTHED_ID, "Zaphod Video", "portrait"));

            assertThat(service.findProbableAlts())
                    .as("der blosse Namensvetter faellt aus der Handlungsliste heraus")
                    .isEmpty();

            CharacterDtos.AltCalibrationDto ansicht = service.calibrationSample(MAIN_ID, null);

            assertThat(ansicht.accountPairs()).singleElement().satisfies(zeile -> {
                assertThat(zeile.suggestion().probability())
                        .isEqualTo(props.getNameFamilyMatchScore());
                assertThat(zeile.requiredThreshold())
                        .as("ein einzelnes Signal muss hoeher springen - und das steht dabei")
                        .isEqualTo(props.getMinProbabilitySingleSignal());
                assertThat(zeile.aboveThreshold()).isFalse();
                assertThat(zeile.suggestion().signals())
                        .as("die volle Aufschluesselung, sonst ist die Zahl wieder nackt")
                        .hasSize(AltDetectionService.SIGNAL_COUNT);
            });
            assertThat(ansicht.minProbability()).isEqualTo(props.getMinProbability());
            assertThat(ansicht.examinedAccountPairs()).isEqualTo(1);

            // Hier wird NICHTS bestaetigt: keine Zuordnung, keine Vormerkung.
            // Waere das anders, liesse sich ueber die Kalibrierung genau die
            // Vorsicht aushebeln, die die Schwelle darstellt.
            verify(proposalRepo, never()).save(any());
            verify(characterRepo, never()).save(any());
        }

        @Test
        @DisplayName("Die Kalibrieransicht ist fuer Unberechtigte gesperrt")
        void nurFuerDieFuehrung() {
            // Die Pruefung steht IM DIENST und nicht nur am Controller. Ohne sie
            // waere ausgerechnet die ungefilterte Ansicht - die ueber JEDES
            // Corp-Mitglied eine Vermutung enthaelt - der am wenigsten
            // geschuetzte Weg des ganzen Merkmals.
            Character fremder = character(STRANGER_ID, STRANGER_ID, "Random Guy", SystemRoles.MEMBER);
            when(characterRepo.findById(STRANGER_ID)).thenReturn(Optional.of(fremder));

            assertThatThrownBy(() -> service.calibrationSample(STRANGER_ID, 10))
                    .isInstanceOf(AccessDeniedException.class);
        }

        @Test
        @DisplayName("Ein zu grosses Limit wird auf die Obergrenze gekuerzt")
        void limitWirdGekuerzt() {
            // Ohne die Grenze waere die Ansicht ein Vollabzug der
            // Namens-Kreuztabelle ueber mehrere hundert Menschen - sie soll
            // zeigen, WIE gerechnet wird, nicht alles ausliefern, WAS gerechnet
            // wurde.
            assertThat(service.calibrationSample(MAIN_ID, 100_000).limit())
                    .isEqualTo(props.getCalibrationMaxLimit());
            assertThat(service.calibrationSample(MAIN_ID, null).limit())
                    .isEqualTo(props.getCalibrationDefaultLimit());
        }

        @Test
        @DisplayName("Die Kalibrieransicht zeigt auch die Paare unregistrierter untereinander")
        void auchUnregistriertePaare() {
            // Sonst waere die Gruppenansicht genauso unkalibrierbar wie vorher
            // die Vorschlagsliste: leer, ohne Auskunft darueber, wie knapp es
            // darunter zugeht.
            stats(new CharacterDtos.UnauthedCharDto(5001L, "Zaphod Video", "portrait"),
                    new CharacterDtos.UnauthedCharDto(5002L, "Sansha Video", "portrait"));

            CharacterDtos.AltCalibrationDto ansicht = service.calibrationSample(MAIN_ID, null);

            assertThat(ansicht.examinedUnregisteredPairs()).isEqualTo(1);
            assertThat(ansicht.unregisteredPairs()).singleElement().satisfies(paar -> {
                assertThat(paar.probability()).isEqualTo(props.getNameFamilyMatchScore());
                assertThat(paar.aboveThreshold())
                        .as("ein geteilter Nachname allein begruendet keine Gruppenkante")
                        .isFalse();
            });
        }
    }

    // ==================================================================
    // Konfiguration
    // ==================================================================

    @Nested
    @DisplayName("Die Stellschrauben wirken zur Laufzeit")
    class Stellschrauben {

        @Test
        @DisplayName("Die rechnenden Stellschrauben sind wirklich verdrahtet")
        void rechnendeStellschraubenWirken() {
            // Eine Bindung, die niemand prueft, ist keine Bindung. Der
            // Falsifikationslauf hat es gezeigt: ersetzt man einen props-Getter
            // durch sein Vorgabe-Literal, bleiben alle Tests gruen - die
            // Schraube bewegt sich dann stumm nicht mehr, und der Nutzer sucht
            // den Fehler bei sich. Geprueft werden hier die fuenf, die
            // tatsaechlich in die Zahl eingehen; die uebrigen sind Grenzen und
            // Schalter, keine Summanden. weightMining fehlt hier bewusst: das
            // Signal ist in der Praxis nie verfuegbar (fuer Unregistrierte gibt
            // es keine Mining-Zeilen), ein Test darauf pruefte einen Pfad, der
            // nicht laeuft, und waere entsprechend zerbrechlich.
            beitrittsdaten(beitritt(UNAUTHED_ID, JOIN), beitritt(MAIN_ID, JOIN.plusSeconds(120)));
            int mitVorgabe = service.findProbableAlts().getFirst().probability();

            // Beitritt entwertet: der Wert MUSS sich bewegen, sonst wird das
            // Gewicht nicht gelesen.
            props.setWeightJoin(0);
            assertThat(service.findProbableAlts().getFirst().probability())
                    .as("weightJoin geht in die Rechnung ein")
                    .isNotEqualTo(mitVorgabe);

            props.setWeightJoin(new AltDetectionProperties().getWeightJoin());
            props.setWeightName(0);
            assertThat(service.findProbableAlts().getFirst().probability())
                    .as("weightName geht in die Rechnung ein")
                    .isNotEqualTo(mitVorgabe);

            // Die Schwelle: hochgedreht ueber den erreichten Wert faellt der
            // Vorschlag heraus.
            props.setWeightName(new AltDetectionProperties().getWeightName());
            props.setMinProbability(mitVorgabe + 1);
            assertThat(service.findProbableAlts())
                    .as("minProbability wird gelesen")
                    .isEmpty();

            // Die Mindestzahl an Signalen: verlangt man drei, traegt das Paar
            // mit zweien nicht mehr.
            props.setMinProbability(new AltDetectionProperties().getMinProbability());
            props.setMinAvailableSignals(3);
            assertThat(service.findProbableAlts())
                    .as("minAvailableSignals wird gelesen")
                    .isEmpty();
        }

        @Test
        @DisplayName("Eine gesenkte Einzelsignal-Schwelle laesst den Namensvetter durch")
        void geaenderteSchwelleWirkt() {
            // Der ganze Zweck der Umstellung von static final auf Konfiguration:
            // ohne diese Zeile koennte die Bindung ins Leere gehen und der
            // Dienst weiter mit den einkompilierten Werten rechnen - der Nutzer
            // wuerde eine Eigenschaft setzen, an der Liste aendert sich nichts,
            // und niemand koennte sagen warum.
            stats(new CharacterDtos.UnauthedCharDto(UNAUTHED_ID, "Zaphod Video", "portrait"));
            assertThat(service.findProbableAlts())
                    .as("mit der Vorgabe von 90 faellt der Namensvetter heraus")
                    .isEmpty();

            props.setMinProbabilitySingleSignal(80);

            assertThat(service.findProbableAlts())
                    .as("mit 80 kommt genau derselbe Vorschlag durch")
                    .singleElement()
                    .satisfies(vorschlag -> assertThat(vorschlag.probability())
                            .isEqualTo(props.getNameFamilyMatchScore()));
        }

        @Test
        @DisplayName("Ein geaenderter Namenspunktwert wirkt bis in die Namensaehnlichkeit")
        void geaenderterPunktwertWirkt() {
            // Die Punktwerte liegen in derselben Konfiguration, werden aber von
            // NameSimilarity gelesen. Ohne diese Zeile bliebe unbemerkt, dass
            // der Dienst sich seine Namensaehnlichkeit mit einer anderen
            // Konfiguration gebaut hat als der, die er selbst benutzt.
            stats(new CharacterDtos.UnauthedCharDto(UNAUTHED_ID, MAIN_NAME + " 2", "portrait"));
            props.setNameNumberedTwinScore(99);

            assertThat(service.findProbableAlts()).singleElement()
                    .satisfies(vorschlag -> assertThat(vorschlag.probability()).isEqualTo(99));
        }

        @Test
        @DisplayName("Abgeschaltete Gruppierung liefert keine Gruppen")
        void gruppierungAbschaltbar() {
            // Eine Beobachtung, die niemand braucht, muss sich abstellen lassen,
            // ohne dass jemand neu baut - sonst steht sie fuer immer in der
            // Oberflaeche.
            stats(new CharacterDtos.UnauthedCharDto(5001L, "Miner Guy", "portrait"),
                    new CharacterDtos.UnauthedCharDto(5002L, "Miner Guy 2", "portrait"));
            assertThat(service.findUnregisteredGroups(MAIN_ID)).isNotEmpty();

            props.setGroupUnregistered(false);

            assertThat(service.findUnregisteredGroups(MAIN_ID)).isEmpty();
        }

        @Test
        @DisplayName("Ein gesenktes Paarbudget bricht die Corporation ehrlich ab")
        void budgetWirkt() {
            // Die Reissleine gegen eine Corporation, die um Groessenordnungen
            // waechst. Ohne sie rechnete der Endpunkt minutenlang an einem
            // Seitenaufruf, der laengst niemanden mehr interessiert.
            stats(new CharacterDtos.UnauthedCharDto(UNAUTHED_ID, UNAUTHED_NAME, "portrait"));
            assertThat(service.findProbableAlts()).isNotEmpty();

            props.setMaxPairsPerCorporation(0);

            assertThat(service.findProbableAlts()).isEmpty();
        }
    }
}
