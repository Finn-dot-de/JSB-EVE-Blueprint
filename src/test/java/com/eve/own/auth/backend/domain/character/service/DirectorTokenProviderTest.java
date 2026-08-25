package com.eve.own.auth.backend.domain.character.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.eve.own.auth.backend.domain.auth.SystemRoles;
import com.eve.own.auth.backend.domain.auth.service.AuthService;
import com.eve.own.auth.backend.domain.character.entity.Character;
import com.eve.own.auth.backend.domain.character.entity.Corporation;
import com.eve.own.auth.backend.domain.character.repository.CharacterRepository;
import com.eve.own.auth.backend.esi.EsiHttpStatus;
import com.eve.own.auth.backend.esi.EsiResponse;
import com.eve.own.auth.backend.esi.EsiService;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClientResponseException;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Auswahl eines Tokens mit Director-Rechten")
class DirectorTokenProviderTest {

    private static final Long CORPORATION_ID = 98000001L;

    @Mock private AuthService authService;
    @Mock private CharacterRepository characterRepo;
    @Mock private EsiService esiService;

    private DirectorTokenProvider provider;
    private Character ceo;

    @BeforeEach
    void setUp() {
        provider = new DirectorTokenProvider(authService, characterRepo, esiService);
        ceo = candidate(2000L, SystemRoles.CEO);

        when(characterRepo.findAllWithCorporation()).thenReturn(List.of(ceo));
        when(authService.getValidAccessToken(any())).thenReturn("token");
    }

    private static Character candidate(Long id, String... roles) {
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

    private static RestClientResponseException httpError(int status) {
        return new RestClientResponseException("Fehler", status, "", HttpHeaders.EMPTY, null, null);
    }

    @Test
    @DisplayName("reicht das aufgebrauchte Fehler-Budget nach oben durch")
    void propagatesErrorLimit() {
        // Ohne dieses Durchreichen probiert die Schleife munter weiter und
        // verlaengert damit das Zeitfenster, in dem CCP uns aussperrt.
        assertThatThrownBy(() -> provider.attempt(CORPORATION_ID, null, token -> {
            throw httpError(EsiHttpStatus.ERROR_LIMITED);
        })).isInstanceOf(RestClientResponseException.class);
    }

    @Test
    @DisplayName("haelt einen Fehlschlag samt Ursache fest, statt ihn zu verschlucken")
    void keepsFailureAndCause() {
        RestClientResponseException absage = httpError(403);

        DirectorTokenProvider.DirectorAttempt<String> attempt =
                provider.attempt(CORPORATION_ID, null, token -> {
                    throw absage;
                });

        assertThat(attempt.succeeded()).isFalse();
        assertThat(attempt.noCandidateTried()).isFalse();
        assertThat(attempt.failures()).singleElement().satisfies(failure -> {
            assertThat(failure.reason()).isEqualTo(DirectorTokenProvider.FailureReason.FORBIDDEN);
            assertThat(failure.characterName()).isEqualTo("Pilot 2000");
        });
        assertThat(attempt.firstCause()).isSameAs(absage);
    }

    @Test
    @DisplayName("meldet \"kein Kandidat\" getrennt von \"alle abgelehnt\"")
    void reportsAbsenceOfCandidatesSeparately() {
        // Beides gleich zu melden schickt den Nutzer in die falsche Richtung:
        // einmal fehlt eine Anmeldung, einmal fehlen Rechte.
        when(characterRepo.findAllWithCorporation())
                .thenReturn(List.of(candidate(3000L, SystemRoles.MEMBER)));

        DirectorTokenProvider.DirectorAttempt<String> attempt =
                provider.attempt(CORPORATION_ID, null, token -> "egal");

        assertThat(attempt.noCandidateTried()).isTrue();
    }

    @Test
    @DisplayName("bestaetigt die echte Ingame-Rolle ueber das Token des Kandidaten")
    void confirmsIngameRoleWithOwnToken() {
        when(esiService.getCharacterRoles(anyLong(), anyString())).thenReturn(EsiResponse.changed(
                new EsiService.EsiCharacterRolesResponse(
                        new String[]{"Director", "Personnel_Manager"}, null, null, null),
                null, null));

        DirectorTokenProvider.DirectorAttempt<String> attempt =
                provider.attempt(CORPORATION_ID, null, token -> {
                    throw httpError(403);
                });

        assertThat(provider.confirmDirectorRole(attempt.failures()))
                .containsEntry(ceo.getId(), Boolean.TRUE);
    }

    @Test
    @DisplayName("wertet eine Antwort ohne Director als belastbares Nein")
    void treatsAnswerWithoutDirectorAsNo() {
        // Der Endpunkt kann nicht an einer fehlenden Rolle scheitern - eine
        // 200-Antwort ohne "Director" ist deshalb eine Aussage, kein Fehler.
        when(esiService.getCharacterRoles(anyLong(), anyString())).thenReturn(EsiResponse.changed(
                new EsiService.EsiCharacterRolesResponse(
                        new String[]{"Factory_Manager"}, null, null, null),
                null, null));

        DirectorTokenProvider.DirectorAttempt<String> attempt =
                provider.attempt(CORPORATION_ID, null, token -> {
                    throw httpError(403);
                });

        assertThat(provider.confirmDirectorRole(attempt.failures()))
                .containsEntry(ceo.getId(), Boolean.FALSE);
    }

    @Test
    @DisplayName("sagt \"unbekannt\", wenn die Rollenabfrage selbst scheitert")
    void reportsUnknownWhenRoleLookupFails() {
        // Ohne diesen Zweig wuerde ein Ausfall der Rollenabfrage als "kein
        // Director" gedeutet - wieder eine Behauptung ohne Beleg.
        when(esiService.getCharacterRoles(anyLong(), anyString())).thenThrow(httpError(403));

        DirectorTokenProvider.DirectorAttempt<String> attempt =
                provider.attempt(CORPORATION_ID, null, token -> {
                    throw httpError(403);
                });

        assertThat(provider.confirmDirectorRole(attempt.failures()))
                .containsEntry(ceo.getId(), null);
    }

    @Test
    @DisplayName("liest eine Rollenantwort ohne jedes Feld als \"keine Rolle\"")
    void handlesEmptyRolesPayload() {
        // ESI schickt einem Charakter ohne Rollen ein leeres Objekt; alle vier
        // Felder sind laut Definition optional. Ohne die Null-Pruefung faellt
        // hier die Diagnose mit einer NullPointerException aus.
        EsiService.EsiCharacterRolesResponse leer =
                new EsiService.EsiCharacterRolesResponse(null, null, null, null);

        assertThat(leer.hasCorporationRole(DirectorTokenProvider.INGAME_ROLE_DIRECTOR)).isFalse();
    }
}
