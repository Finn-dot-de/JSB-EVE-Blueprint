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

    @BeforeEach
    void setUp() {
        service = new AltDetectionService(corporationStatsService, characterRepo, miningRepo,
                proposalRepo, directorTokenProvider, esiService);

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
                    .isEqualTo(AltDetectionTuning.NAME_NUMBERED_TWIN_SCORE);
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
                    .isEqualTo(AltDetectionTuning.NAME_NUMBERED_TWIN_SCORE);

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
                    .isGreaterThanOrEqualTo(AltDetectionTuning.MIN_PROBABILITY);
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
                    .isEqualTo(AltDetectionTuning.NAME_NUMBERED_TWIN_SCORE);
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
}
