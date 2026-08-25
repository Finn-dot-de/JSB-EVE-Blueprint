package com.eve.own.auth.backend.domain.groups.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.eve.own.auth.backend.domain.auth.SystemRoles;
import com.eve.own.auth.backend.domain.auth.entity.TitleRoleMapping;
import com.eve.own.auth.backend.domain.auth.repository.TitleRoleMappingRepository;
import com.eve.own.auth.backend.domain.auth.service.AuthService;
import com.eve.own.auth.backend.domain.character.entity.Character;
import com.eve.own.auth.backend.domain.character.entity.Corporation;
import com.eve.own.auth.backend.domain.character.repository.CharacterRepository;
import com.eve.own.auth.backend.domain.character.service.DirectorTokenProvider;
import com.eve.own.auth.backend.esi.EsiAccessDeniedException;
import com.eve.own.auth.backend.esi.EsiResponse;
import com.eve.own.auth.backend.esi.EsiService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Zuordnung von Ingame-Titeln zu Rollen")
class TitleMappingServiceTest {

    private static final Long CORPORATION_ID = 98000001L;
    private static final Long REQUESTER_ID = 1000L;
    private static final Long TITLE_ID = 7L;

    private static final String ROLES_SCOPE = "esi-characters.read_corporation_roles.v1";

    @Mock private EsiService esiService;
    @Mock private AuthService authService;
    @Mock private CharacterRepository characterRepo;
    @Mock private TitleRoleMappingRepository mappingRepo;

    private TitleMappingService service;

    /**
     * Der Anfragende traegt selbst eine Fuehrungsrolle und ein Token - anders
     * kaeme er gar nicht bis hierher, der Endpunkt haengt an
     * {@code AccessRules.LEADERSHIP_OR_IT}. IT_Admin ist bewusst der niedrigste
     * Rang, damit sichtbar bleibt, wann ein Director ihm vorgezogen wird.
     */
    private Character requester;

    @BeforeEach
    void setUp() {
        requester = character(REQUESTER_ID, SystemRoles.IT_ADMIN);
        service = new TitleMappingService(esiService,
                new DirectorTokenProvider(authService, characterRepo, esiService),
                characterRepo, mappingRepo);

        when(characterRepo.findById(REQUESTER_ID)).thenReturn(Optional.of(requester));
        when(characterRepo.findAllWithCorporation()).thenReturn(List.of(requester));
        when(authService.getValidAccessToken(any())).thenReturn("token");
        when(mappingRepo.findByCorporationId(CORPORATION_ID)).thenReturn(List.of());
        when(esiService.getCorporationTitles(anyLong(), anyString()))
                .thenReturn(EsiResponse.changed(new EsiService.EsiCorpTitleResponse[0], null, null));
    }

    private static Character character(Long id, String... roles) {
        Corporation corporation = new Corporation();
        corporation.setId(CORPORATION_ID);

        Character character = new Character();
        character.setId(id);
        character.setName("Pilot " + id);
        character.setCorporation(corporation);
        character.setRefreshToken("refresh");
        character.setRoles(Set.of(roles));
        return character;
    }

    private static TitleRoleMapping mapping(Long titleId, String roleName) {
        TitleRoleMapping mapping = new TitleRoleMapping();
        mapping.setTitleId(titleId);
        mapping.setCorporationId(CORPORATION_ID);
        mapping.setRoleName(roleName);
        return mapping;
    }

    private static HttpClientErrorException forbidden(String body) {
        return HttpClientErrorException.create(HttpStatus.FORBIDDEN, "Forbidden", HttpHeaders.EMPTY,
                body == null ? null : body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }

    /** Ein Token in JWT-Form, dessen Nutzlast genau die genannten Scopes traegt. */
    private static String tokenWithScopes(String... scopes) {
        String scopeList = Stream.of(scopes)
                .map(scope -> "\"" + scope + "\"")
                .collect(Collectors.joining(","));
        String payload = "{\"sub\":\"CHARACTER:EVE:1000\",\"scp\":[" + scopeList + "]}";
        return "kopf." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + ".signatur";
    }

    private static EsiService.EsiCharacterRolesResponse ingameRoles(String... roles) {
        return new EsiService.EsiCharacterRolesResponse(roles, null, null, null);
    }

    @Nested
    @DisplayName("Titel lesen")
    class ReadingTitles {

        @Test
        @DisplayName("listet die Titel samt zugeordneter Rolle")
        void listsTitlesWithRoles() {
            when(esiService.getCorporationTitles(anyLong(), anyString())).thenReturn(
                    EsiResponse.changed(new EsiService.EsiCorpTitleResponse[]{
                            new EsiService.EsiCorpTitleResponse(TITLE_ID, "Director")}, null, null));
            when(mappingRepo.findByCorporationId(CORPORATION_ID))
                    .thenReturn(List.of(mapping(TITLE_ID, SystemRoles.DIRECTOR)));

            assertThat(service.corporationTitles(REQUESTER_ID)).singleElement().satisfies(title -> {
                assertThat(title.name()).isEqualTo("Director");
                assertThat(title.mappedRole()).isEqualTo(SystemRoles.DIRECTOR);
            });
        }

        @Test
        @DisplayName("entfernt die HTML-Auszeichnung aus dem Titelnamen")
        void stripsHtml() {
            when(esiService.getCorporationTitles(anyLong(), anyString())).thenReturn(
                    EsiResponse.changed(new EsiService.EsiCorpTitleResponse[]{
                            new EsiService.EsiCorpTitleResponse(TITLE_ID,
                                    "<color=0xff00ff00>A38</color>")}, null, null));

            assertThat(service.corporationTitles(REQUESTER_ID)).singleElement()
                    .satisfies(title -> assertThat(title.name()).isEqualTo("A38"));
        }

        @Test
        @DisplayName("laesst einen Titel ohne Zuordnung leer")
        void leavesUnmappedTitleEmpty() {
            when(esiService.getCorporationTitles(anyLong(), anyString())).thenReturn(
                    EsiResponse.changed(new EsiService.EsiCorpTitleResponse[]{
                            new EsiService.EsiCorpTitleResponse(TITLE_ID, "Neu")}, null, null));

            assertThat(service.corporationTitles(REQUESTER_ID)).singleElement()
                    .satisfies(title -> assertThat(title.mappedRole()).isNull());
        }

        @Test
        @DisplayName("nimmt bevorzugt ein Token der Fuehrungsebene")
        void prefersLeadershipToken() {
            Character director = character(2000L, SystemRoles.DIRECTOR);
            when(characterRepo.findAllWithCorporation()).thenReturn(List.of(requester, director));

            service.corporationTitles(REQUESTER_ID);

            verify(authService).getValidAccessToken(director);
        }

        @Test
        @DisplayName("erklaert eine Absage von ESI im Klartext")
        void explainsForbidden() {
            // Ohne Ingame-Director laesst CCP diesen Endpunkt nicht zu.
            when(esiService.getCorporationTitles(anyLong(), anyString())).thenThrow(forbidden(null));

            assertThatThrownBy(() -> service.corporationTitles(REQUESTER_ID))
                    .isInstanceOf(EsiAccessDeniedException.class)
                    .hasMessageContaining("Ingame-Director-Rechten");
        }

        @Test
        @DisplayName("liefert eine leere Liste, wenn ESI keine Titel meldet")
        void handlesMissingTitles() {
            when(esiService.getCorporationTitles(anyLong(), anyString()))
                    .thenReturn(EsiResponse.empty());

            assertThat(service.corporationTitles(REQUESTER_ID)).isEmpty();
        }

        @Test
        @DisplayName("weist einen unbekannten Anfragenden ab")
        void rejectsUnknownRequester() {
            when(characterRepo.findById(404L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.corporationTitles(404L))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("Auswahl des Token-Gebers")
    class ChoosingTheTokenProvider {

        @Test
        @DisplayName("geht bei einer Absage zum naechsten Kandidaten weiter")
        void movesToNextCandidateOnForbidden() {
            // Ohne diese Zeile bleibt es beim ersten Versuch - und genau das war
            // der Fehler: ein per findFirst() zufaellig gegriffener Kandidat ohne
            // Rechte, und die Titel galten als nicht abrufbar, obwohl daneben ein
            // Director mit gueltigem Token stand.
            Character ceo = character(2000L, SystemRoles.CEO);
            Character director = character(3000L, SystemRoles.DIRECTOR);
            when(characterRepo.findAllWithCorporation())
                    .thenReturn(List.of(requester, ceo, director));
            when(esiService.getCorporationTitles(anyLong(), anyString()))
                    .thenThrow(forbidden(null))
                    .thenReturn(EsiResponse.changed(new EsiService.EsiCorpTitleResponse[]{
                            new EsiService.EsiCorpTitleResponse(TITLE_ID, "Logistik")}, null, null));

            assertThat(service.corporationTitles(REQUESTER_ID)).singleElement()
                    .satisfies(title -> assertThat(title.name()).isEqualTo("Logistik"));

            verify(authService).getValidAccessToken(ceo);
            verify(authService).getValidAccessToken(director);
        }

        @Test
        @DisplayName("probiert Gleichrangige in einer festen Reihenfolge")
        void ordersEquallyRankedDeterministically() {
            // Ohne das zweite Sortierkriterium gibt die Reihenfolge der Datenbank
            // den Ton an, und die sichert ohne ORDER BY nichts zu: dieselbe
            // Anfrage traegt dann heute und faellt morgen um.
            Character later = character(9000L, SystemRoles.DIRECTOR);
            Character earlier = character(4000L, SystemRoles.DIRECTOR);
            when(characterRepo.findAllWithCorporation()).thenReturn(List.of(later, earlier));

            service.corporationTitles(REQUESTER_ID);

            verify(authService).getValidAccessToken(earlier);
            verify(authService, never()).getValidAccessToken(later);
        }

        @Test
        @DisplayName("uebergeht Kandidaten ohne hinterlegtes Token")
        void skipsCandidatesWithoutRefreshToken() {
            // Ohne diesen Filter kostet jeder tokenlose Director einen
            // SSO-Rundlauf, der nur scheitern kann.
            Character tokenless = character(2000L, SystemRoles.CEO);
            tokenless.setRefreshToken(null);
            when(characterRepo.findAllWithCorporation())
                    .thenReturn(List.of(tokenless, requester));

            service.corporationTitles(REQUESTER_ID);

            verify(authService, never()).getValidAccessToken(tokenless);
            verify(authService).getValidAccessToken(requester);
        }

        @Test
        @DisplayName("uebergeht Kandidaten mit dauerhaft ungueltigem Token")
        void skipsCandidatesWithBrokenToken() {
            // Der Vermerk haelt bereits fest, dass sich dieser Token nicht mehr
            // erneuern laesst. Ohne diesen Filter wird er trotzdem jedes Mal
            // erneut probiert - eine Leiche pro Lauf.
            Character broken = character(2000L, SystemRoles.CEO);
            broken.setTokenInvalidSince(Instant.now());
            when(characterRepo.findAllWithCorporation())
                    .thenReturn(List.of(broken, requester));

            service.corporationTitles(REQUESTER_ID);

            verify(authService, never()).getValidAccessToken(broken);
        }
    }

    @Nested
    @DisplayName("Ehrliche Begruendung eines Fehlschlags")
    class ExplainingFailure {

        @Test
        @DisplayName("nennt Scope und Namen, wenn das Token zu alt ist")
        void namesCharactersWithOutdatedToken() {
            // Der Fall aus dem Betrieb: der Charakter IST Director, sein Token
            // stammt nur aus der Zeit vor der Scope-Erweiterung. Ohne diese
            // Unterscheidung schickt die Meldung ihn Ingame-Rollen suchen, die
            // er laengst hat.
            when(authService.getValidAccessToken(any())).thenReturn(tokenWithScopes(ROLES_SCOPE));
            when(esiService.getCorporationTitles(anyLong(), anyString())).thenThrow(forbidden(null));

            assertThatThrownBy(() -> service.corporationTitles(REQUESTER_ID))
                    .isInstanceOf(EsiAccessDeniedException.class)
                    .hasMessageContaining(TitleMappingService.TITLES_SCOPE)
                    .hasMessageContaining("Pilot 1000")
                    .hasMessageContaining("Neuanmeldung");

            // Und keine Rollenabfrage: der Grund steht schon fest, jeder weitere
            // ESI-Aufruf waere verbranntes Fehler-Budget.
            verify(esiService, never()).getCharacterRoles(anyLong(), anyString());
        }

        @Test
        @DisplayName("nennt die fehlende Ingame-Rolle, wenn der Scope vorhanden ist")
        void namesMissingIngameRole() {
            // Scope da, ESI sagt trotzdem nein - erst jetzt darf die Meldung
            // behaupten, es fehle an der Ingame-Rolle. Und nur, weil ESI selbst
            // das bestaetigt: /characters/{id}/roles/ kann nicht an einer
            // fehlenden Rolle scheitern, seine Antwort ist also belastbar.
            when(authService.getValidAccessToken(any()))
                    .thenReturn(tokenWithScopes(TitleMappingService.TITLES_SCOPE, ROLES_SCOPE));
            when(esiService.getCorporationTitles(anyLong(), anyString())).thenThrow(forbidden(null));
            when(esiService.getCharacterRoles(anyLong(), anyString())).thenReturn(
                    EsiResponse.changed(ingameRoles("Factory_Manager", "Station_Manager"), null, null));

            assertThatThrownBy(() -> service.corporationTitles(REQUESTER_ID))
                    .isInstanceOf(EsiAccessDeniedException.class)
                    .hasMessageContaining("Pilot 1000")
                    .hasMessageContaining("Director")
                    .hasMessageContaining("Ingame-Director-Rechten");
        }

        @Test
        @DisplayName("reicht den Wortlaut von CCP und die Ausnahme als Ursache weiter")
        void keepsCcpTextAndCause() {
            // Genau dieser Satz wurde frueher ersatzlos verworfen - er ist die
            // einzige Auskunft, die "Scope fehlt" von "Rolle fehlt" trennt.
            HttpClientErrorException absage = forbidden(
                    "{\"error\":\"The given character doesn't have the required role(s)\"}");
            when(authService.getValidAccessToken(any()))
                    .thenReturn(tokenWithScopes(TitleMappingService.TITLES_SCOPE, ROLES_SCOPE));
            when(esiService.getCorporationTitles(anyLong(), anyString())).thenThrow(absage);
            when(esiService.getCharacterRoles(anyLong(), anyString()))
                    .thenReturn(EsiResponse.changed(ingameRoles(), null, null));

            assertThatThrownBy(() -> service.corporationTitles(REQUESTER_ID))
                    .isInstanceOf(EsiAccessDeniedException.class)
                    .hasMessageContaining("The given character doesn't have the required role(s)")
                    .hasCause(absage);
        }

        @Test
        @DisplayName("sagt es, wenn es gar keinen Kandidaten gibt")
        void reportsMissingCandidates() {
            // Niemand angemeldet ist etwas anderes als niemand berechtigt. Ohne
            // diese Unterscheidung sucht der Nutzer Ingame-Rollen, obwohl sich
            // schlicht kein Director je hier angemeldet hat.
            when(characterRepo.findAllWithCorporation())
                    .thenReturn(List.of(character(3000L, SystemRoles.MEMBER)));

            assertThatThrownBy(() -> service.corporationTitles(REQUESTER_ID))
                    .isInstanceOf(EsiAccessDeniedException.class)
                    .hasMessageContaining("kein Charakter dieser Corporation")
                    .hasMessageNotContaining("Ingame-Director-Rechten");
        }

        @Test
        @DisplayName("verkleidet einen anderen Fehler nicht als fehlende Rechte")
        void doesNotDisguiseOtherFailures() {
            // Ein 500 von ESI sagt ueber Director-Rechte gar nichts. Die alte
            // Meldung behauptete trotzdem, es fehle ein Director.
            when(esiService.getCorporationTitles(anyLong(), anyString())).thenThrow(
                    HttpServerErrorException.create(HttpStatus.INTERNAL_SERVER_ERROR, "Boom",
                            HttpHeaders.EMPTY, null, null));

            assertThatThrownBy(() -> service.corporationTitles(REQUESTER_ID))
                    .isInstanceOf(EsiAccessDeniedException.class)
                    .hasMessageContaining("nicht, weil Rechte fehlen")
                    .hasMessageNotContaining("Ingame-Director-Rechten");
        }
    }

    @Nested
    @DisplayName("Zuordnung speichern")
    class SavingMappings {

        @Test
        @DisplayName("legt eine neue Zuordnung an")
        void createsMapping() {
            service.saveMapping(REQUESTER_ID, TITLE_ID, SystemRoles.DIRECTOR);

            ArgumentCaptor<TitleRoleMapping> saved = ArgumentCaptor.forClass(TitleRoleMapping.class);
            verify(mappingRepo).save(saved.capture());
            assertThat(saved.getValue().getTitleId()).isEqualTo(TITLE_ID);
            assertThat(saved.getValue().getRoleName()).isEqualTo(SystemRoles.DIRECTOR);
            assertThat(saved.getValue().getCorporationId()).isEqualTo(CORPORATION_ID);
        }

        @Test
        @DisplayName("ueberschreibt eine vorhandene Zuordnung")
        void updatesMapping() {
            TitleRoleMapping existing = mapping(TITLE_ID, SystemRoles.MEMBER);
            when(mappingRepo.findByCorporationId(CORPORATION_ID)).thenReturn(List.of(existing));

            service.saveMapping(REQUESTER_ID, TITLE_ID, SystemRoles.DIRECTOR);

            assertThat(existing.getRoleName()).isEqualTo(SystemRoles.DIRECTOR);
            verify(mappingRepo).save(existing);
        }

        @Test
        @DisplayName("loescht die Zuordnung bei leerem Rollennamen")
        void deletesMappingWhenCleared() {
            // Ein leerer Wert wuerde vom naechsten Sync automatisch neu gefuellt.
            TitleRoleMapping existing = mapping(TITLE_ID, SystemRoles.DIRECTOR);
            when(mappingRepo.findByCorporationId(CORPORATION_ID)).thenReturn(List.of(existing));

            service.saveMapping(REQUESTER_ID, TITLE_ID, "   ");

            verify(mappingRepo).delete(existing);
            verify(mappingRepo, never()).save(any());
        }

        @Test
        @DisplayName("legt fuer einen leeren Rollennamen nichts Neues an")
        void createsNothingForEmptyRole() {
            service.saveMapping(REQUESTER_ID, TITLE_ID, null);

            verify(mappingRepo, never()).save(any());
            verify(mappingRepo, never()).delete(any());
        }
    }
}
