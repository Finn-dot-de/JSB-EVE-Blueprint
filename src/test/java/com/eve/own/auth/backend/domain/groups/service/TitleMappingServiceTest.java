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
import com.eve.own.auth.backend.esi.EsiAccessDeniedException;
import com.eve.own.auth.backend.esi.EsiResponse;
import com.eve.own.auth.backend.esi.EsiService;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Zuordnung von Ingame-Titeln zu Rollen")
class TitleMappingServiceTest {

    private static final Long CORPORATION_ID = 98000001L;
    private static final Long REQUESTER_ID = 1000L;
    private static final Long TITLE_ID = 7L;

    @Mock private EsiService esiService;
    @Mock private AuthService authService;
    @Mock private CharacterRepository characterRepo;
    @Mock private TitleRoleMappingRepository mappingRepo;

    private TitleMappingService service;

    @BeforeEach
    void setUp() {
        service = new TitleMappingService(esiService, authService, characterRepo, mappingRepo);

        when(characterRepo.findById(REQUESTER_ID)).thenReturn(Optional.of(character(REQUESTER_ID)));
        when(characterRepo.findByCorporationId(CORPORATION_ID)).thenReturn(List.of());
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
            when(characterRepo.findByCorporationId(CORPORATION_ID)).thenReturn(List.of(director));

            service.corporationTitles(REQUESTER_ID);

            verify(authService).getValidAccessToken(director);
        }

        @Test
        @DisplayName("erklaert eine Absage von ESI im Klartext")
        void explainsForbidden() {
            // Ohne Ingame-Director laesst CCP diesen Endpunkt nicht zu.
            when(esiService.getCorporationTitles(anyLong(), anyString())).thenThrow(
                    HttpClientErrorException.create(HttpStatus.FORBIDDEN, "Forbidden",
                            HttpHeaders.EMPTY, null, null));

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
