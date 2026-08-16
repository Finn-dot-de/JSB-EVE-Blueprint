package com.eve.own.auth.backend.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.eve.own.auth.backend.domain.auth.security.AesEncryptionService;
import com.eve.own.auth.backend.domain.auth.security.EveSsoClient;
import com.eve.own.auth.backend.domain.character.entity.Character;
import com.eve.own.auth.backend.domain.character.entity.Corporation;
import com.eve.own.auth.backend.domain.character.repository.AllianceRepository;
import com.eve.own.auth.backend.domain.character.repository.CharacterRepository;
import com.eve.own.auth.backend.domain.character.repository.CorporationRepository;
import com.eve.own.auth.backend.esi.EsiResponse;
import com.eve.own.auth.backend.esi.EsiService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
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

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Anmeldung und Token-Pflege")
class AuthServiceTest {

    private static final Long CHARACTER_ID = 95465499L;
    private static final Long CORPORATION_ID = 98000001L;
    private static final Long ALLIANCE_ID = 99005338L;
    private static final String ACCESS_TOKEN = "access";
    private static final String REFRESH_TOKEN = "refresh";

    @Mock private EveSsoClient ssoClient;
    @Mock private EsiService esiService;
    @Mock private CharacterRepository characterRepo;
    @Mock private CorporationRepository corpRepo;
    @Mock private AllianceRepository allianceRepo;
    @Mock private AesEncryptionService encryptionService;
    @Mock private CharacterRoleService roleService;

    private TokenHealthService tokenHealth;
    private AuthService service;

    @BeforeEach
    void setUp() {
        tokenHealth = mock(TokenHealthService.class);
        service = new AuthService(ssoClient, esiService, characterRepo, corpRepo, allianceRepo,
                encryptionService, roleService, tokenHealth);

        when(ssoClient.exchangeCode(anyString())).thenReturn(tokens());
        when(ssoClient.readIdentity(anyString()))
                .thenReturn(new EveSsoClient.EveIdentity(CHARACTER_ID, "Pilot Eins"));
        when(esiService.getCharacter(anyLong())).thenReturn(EsiResponse.changed(
                new EsiService.EsiCharacterResponse("Pilot Eins", CORPORATION_ID), null, null));
        when(esiService.getCorporation(anyLong())).thenReturn(EsiResponse.changed(
                new EsiService.EsiCorporationResponse("Corp Eins", "CORP", null, null), null, null));
        when(characterRepo.findById(CHARACTER_ID)).thenReturn(Optional.empty());
        when(characterRepo.save(any())).thenAnswer(call -> call.getArgument(0));
        when(corpRepo.save(any())).thenAnswer(call -> call.getArgument(0));
        when(allianceRepo.save(any())).thenAnswer(call -> call.getArgument(0));
        when(encryptionService.encrypt(anyString())).thenAnswer(call -> "enc:" + call.getArgument(0));
        when(encryptionService.decrypt(anyString()))
                .thenAnswer(call -> call.getArgument(0, String.class).replace("enc:", ""));
        when(roleService.applyRoles(any(), any())).thenAnswer(call -> call.getArgument(0));
    }

    private static EveSsoClient.TokenResponse tokens() {
        return new EveSsoClient.TokenResponse(ACCESS_TOKEN, REFRESH_TOKEN, 1200);
    }

    private static Character storedCharacter(Instant expiry) {
        Character character = new Character();
        character.setId(CHARACTER_ID);
        character.setName("Pilot Eins");
        character.setAccessToken("enc:" + ACCESS_TOKEN);
        character.setRefreshToken("enc:" + REFRESH_TOKEN);
        character.setTokenExpiry(expiry);
        return character;
    }

    @Nested
    @DisplayName("Login")
    class Login {

        @Test
        @DisplayName("legt Charakter, Corporation und Rollen an")
        void createsCharacterOnFirstLogin() {
            Character character = service.processEveLogin("code", null);

            assertThat(character.getId()).isEqualTo(CHARACTER_ID);
            assertThat(character.getName()).isEqualTo("Pilot Eins");
            assertThat(character.getCorporation().getName()).isEqualTo("Corp Eins");
            verify(roleService).applyRoles(any(), org.mockito.ArgumentMatchers.eq(ACCESS_TOKEN));
        }

        @Test
        @DisplayName("macht einen erstmalig angemeldeten Charakter zu seinem eigenen Main")
        void firstLoginBecomesItsOwnMain() {
            assertThat(service.processEveLogin("code", null).getMainCharacterId())
                    .isEqualTo(CHARACTER_ID);
        }

        @Test
        @DisplayName("haengt einen Charakter an den bereits angemeldeten Account")
        void linksCharacterToLoggedInAccount() {
            assertThat(service.processEveLogin("code", 777L).getMainCharacterId()).isEqualTo(777L);
        }

        @Test
        @DisplayName("laesst die bestehende Account-Zuordnung unangetastet")
        void keepsExistingAccountAssignment() {
            Character existing = storedCharacter(Instant.now());
            existing.setMainCharacterId(555L);
            when(characterRepo.findById(CHARACTER_ID)).thenReturn(Optional.of(existing));

            assertThat(service.processEveLogin("code", null).getMainCharacterId()).isEqualTo(555L);
        }

        @Test
        @DisplayName("legt die Token nur verschluesselt ab")
        void storesTokensEncrypted() {
            Character character = service.processEveLogin("code", null);

            assertThat(character.getAccessToken()).isEqualTo("enc:" + ACCESS_TOKEN);
            assertThat(character.getRefreshToken()).isEqualTo("enc:" + REFRESH_TOKEN);
            assertThat(character.getTokenExpiry()).isAfter(Instant.now());
        }

        @Test
        @DisplayName("speichert die Allianz der Corporation mit")
        void storesAlliance() {
            when(esiService.getCorporation(anyLong())).thenReturn(EsiResponse.changed(
                    new EsiService.EsiCorporationResponse("Corp", "C", ALLIANCE_ID, null), null, null));
            when(esiService.getAlliance(ALLIANCE_ID)).thenReturn(EsiResponse.changed(
                    new EsiService.EsiAllianceResponse("Die Allianz", "ALLY"), null, null));

            Character character = service.processEveLogin("code", null);

            assertThat(character.getCorporation().getAlliance().getName()).isEqualTo("Die Allianz");
        }

        @Test
        @DisplayName("speichert die Corporation auch ohne abrufbare Allianz")
        void survivesUnavailableAlliance() {
            when(esiService.getCorporation(anyLong())).thenReturn(EsiResponse.changed(
                    new EsiService.EsiCorporationResponse("Corp", "C", ALLIANCE_ID, null), null, null));
            when(esiService.getAlliance(ALLIANCE_ID)).thenReturn(EsiResponse.empty());

            assertThat(service.processEveLogin("code", null).getCorporation().getAlliance()).isNull();
        }

        @Test
        @DisplayName("bricht ab, wenn ESI keine Charakterdaten liefert")
        void failsWithoutCharacterData() {
            when(esiService.getCharacter(anyLong())).thenReturn(EsiResponse.empty());

            assertThatThrownBy(() -> service.processEveLogin("code", null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Charakterdaten");
        }

        @Test
        @DisplayName("bricht ab, wenn ESI keine Corp-Daten liefert")
        void failsWithoutCorporationData() {
            when(esiService.getCorporation(anyLong())).thenReturn(EsiResponse.empty());

            assertThatThrownBy(() -> service.processEveLogin("code", null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Corp-Daten");
        }
    }

    @Nested
    @DisplayName("Token-Erneuerung")
    class TokenRefresh {

        @Test
        @DisplayName("gibt ein noch gueltiges Token unveraendert zurueck")
        void reusesValidToken() {
            Character character = storedCharacter(Instant.now().plus(1, ChronoUnit.HOURS));

            assertThat(service.getValidAccessToken(character)).isEqualTo(ACCESS_TOKEN);
            verify(ssoClient, never()).refresh(anyString());
        }

        @Test
        @DisplayName("erneuert ein bald ablaufendes Token vorsorglich")
        void refreshesTokenAboutToExpire() {
            // Sonst koennte es zwischen Pruefung und Aufruf ungueltig werden.
            Character character = storedCharacter(Instant.now().plusSeconds(30));
            when(ssoClient.refresh(anyString())).thenReturn(tokens());

            assertThat(service.getValidAccessToken(character)).isEqualTo(ACCESS_TOKEN);
            verify(ssoClient).refresh(REFRESH_TOKEN);
        }

        @Test
        @DisplayName("erneuert ein abgelaufenes Token")
        void refreshesExpiredToken() {
            Character character = storedCharacter(Instant.now().minus(1, ChronoUnit.HOURS));
            when(ssoClient.refresh(anyString())).thenReturn(tokens());

            service.getValidAccessToken(character);

            verify(characterRepo).save(character);
            assertThat(character.getTokenExpiry()).isAfter(Instant.now());
        }

        @Test
        @DisplayName("erneuert auch ohne hinterlegtes Ablaufdatum")
        void refreshesWithoutExpiry() {
            Character character = storedCharacter(null);
            when(ssoClient.refresh(anyString())).thenReturn(tokens());

            assertThat(service.getValidAccessToken(character)).isEqualTo(ACCESS_TOKEN);
        }

        @Test
        @DisplayName("behaelt den alten Refresh-Token, wenn CCP keinen neuen schickt")
        void keepsRefreshTokenWhenNoneReturned() {
            Character character = storedCharacter(null);
            when(ssoClient.refresh(anyString()))
                    .thenReturn(new EveSsoClient.TokenResponse("neu", null, 1200));

            service.getValidAccessToken(character);

            assertThat(character.getRefreshToken()).isEqualTo("enc:" + REFRESH_TOKEN);
            assertThat(character.getAccessToken()).isEqualTo("enc:neu");
        }

        @Test
        @DisplayName("meldet eine gescheiterte Erneuerung deutlich")
        void reportsFailedRefresh() {
            Character character = storedCharacter(null);
            when(ssoClient.refresh(anyString())).thenReturn(null);

            assertThatThrownBy(() -> service.getValidAccessToken(character))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("erneuern");
        }

        @Test
        @DisplayName("meldet auch eine Antwort ohne Token als Fehlschlag")
        void reportsEmptyRefreshResponse() {
            Character character = storedCharacter(null);
            when(ssoClient.refresh(anyString()))
                    .thenReturn(new EveSsoClient.TokenResponse(null, null, 0));

            assertThatThrownBy(() -> service.getValidAccessToken(character))
                    .isInstanceOf(IllegalStateException.class);
        }
    }
}
