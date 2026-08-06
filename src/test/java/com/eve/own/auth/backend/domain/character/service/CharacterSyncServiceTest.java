package com.eve.own.auth.backend.domain.character.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.eve.own.auth.backend.domain.auth.service.AuthService;
import com.eve.own.auth.backend.domain.auth.service.CharacterRoleService;
import com.eve.own.auth.backend.domain.character.entity.Character;
import com.eve.own.auth.backend.domain.character.entity.CharacterAsset;
import com.eve.own.auth.backend.domain.character.entity.CharacterLp;
import com.eve.own.auth.backend.domain.character.entity.CharacterSkill;
import com.eve.own.auth.backend.domain.character.entity.CharacterStats;
import com.eve.own.auth.backend.domain.character.repository.CharacterAssetRepository;
import com.eve.own.auth.backend.domain.character.repository.CharacterSkillRepository;
import com.eve.own.auth.backend.domain.character.repository.CharacterStatsRepository;
import com.eve.own.auth.backend.esi.EsiResponse;
import com.eve.own.auth.backend.esi.EsiService;
import java.util.List;
import java.util.Map;
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
@DisplayName("Vollstaendiger Sync eines Charakters")
class CharacterSyncServiceTest {

    private static final Long CHARACTER_ID = 1000L;
    private static final String TOKEN = "token";

    @Mock private AuthService authService;
    @Mock private EsiService esiService;
    @Mock private CharacterStatsRepository statsRepo;
    @Mock private CharacterSkillRepository skillRepo;
    @Mock private CharacterAssetRepository assetRepo;
    @Mock private AssetSyncService assetSyncService;
    @Mock private AssetNameResolver assetNameResolver;
    @Mock private AssetMapper assetMapper;
    @Mock private CharacterMembershipService membershipService;
    @Mock private CharacterActivitySyncService activitySyncService;
    @Mock private CharacterRoleService roleService;

    private CharacterSyncService service;
    private Character character;

    @BeforeEach
    void setUp() {
        service = new CharacterSyncService(authService, esiService, statsRepo, skillRepo, assetRepo,
                assetSyncService, assetNameResolver, assetMapper, membershipService,
                activitySyncService, roleService);

        character = new Character();
        character.setId(CHARACTER_ID);
        character.setName("Pilot Eins");

        when(membershipService.verifyMembership(any())).thenReturn(true);
        when(authService.getValidAccessToken(any())).thenReturn(TOKEN);
        when(statsRepo.findById(CHARACTER_ID)).thenReturn(Optional.empty());
        when(statsRepo.save(any())).thenAnswer(call -> call.getArgument(0));
        when(esiService.getWalletBalance(anyLong(), anyString())).thenReturn(EsiResponse.empty());
        when(esiService.getSkills(anyLong(), anyString())).thenReturn(EsiResponse.empty());
        when(esiService.getLoyaltyPoints(anyLong(), anyString())).thenReturn(EsiResponse.empty());
        when(esiService.getAllAssets(anyLong(), anyString())).thenReturn(EsiResponse.empty());
        when(assetNameResolver.resolve(anyList(), anyString(), any())).thenReturn(Map.of());
        when(assetMapper.toCharacterAssets(anyLong(), anyList(), any())).thenReturn(List.of());
    }

    private static EsiService.EsiAssetResponse asset(Long itemId) {
        return new EsiService.EsiAssetResponse(itemId, 587L, 60003760L, 1, true,
                "Hangar", "station", false);
    }

    @Nested
    @DisplayName("Ablauf")
    class Order {

        @Test
        @DisplayName("bricht ab, wenn der Charakter nicht mehr betreut wird")
        void stopsForDepartedCharacter() {
            when(membershipService.verifyMembership(character)).thenReturn(false);

            service.sync(character);

            verifyNoInteractions(authService, esiService, roleService);
        }

        @Test
        @DisplayName("laeuft alle Schritte durch, wenn der Charakter dabei ist")
        void runsEveryStep() {
            service.sync(character);

            verify(membershipService).refreshCorporationFaction(character);
            verify(statsRepo).save(any());
            verify(activitySyncService).sync(character, TOKEN);
            verify(roleService).applyRoles(character, TOKEN);
        }
    }

    @Nested
    @DisplayName("Wallet und Skillpunkte")
    class Stats {

        @Test
        @DisplayName("uebernimmt Kontostand und Skillpunkte")
        void storesWalletAndSkillPoints() {
            when(esiService.getWalletBalance(anyLong(), anyString()))
                    .thenReturn(EsiResponse.changed(1_500_000_000.0, null, null));
            when(esiService.getSkills(anyLong(), anyString())).thenReturn(EsiResponse.changed(
                    new EsiService.SkillResponse(85_000_000L, 0, new EsiService.EsiSkillEntry[0]),
                    null, null));

            service.sync(character);

            ArgumentCaptor<CharacterStats> saved = ArgumentCaptor.forClass(CharacterStats.class);
            verify(statsRepo).save(saved.capture());
            assertThat(saved.getValue().getWalletBalance()).isEqualTo(1_500_000_000.0);
            assertThat(saved.getValue().getSkillPoints()).isEqualTo(85_000_000L);
            assertThat(saved.getValue().getLastUpdated()).isNotNull();
        }

        @Test
        @DisplayName("laesst vorhandene Werte stehen, wenn ESI nichts liefert")
        void keepsExistingValues() {
            CharacterStats existing = new CharacterStats();
            existing.setCharacterId(CHARACTER_ID);
            existing.setWalletBalance(42.0);
            when(statsRepo.findById(CHARACTER_ID)).thenReturn(Optional.of(existing));

            service.sync(character);

            assertThat(existing.getWalletBalance()).isEqualTo(42.0);
        }
    }

    @Nested
    @DisplayName("Skills")
    class Skills {

        @Test
        @DisplayName("spiegelt die Einzel-Skills aus derselben Antwort")
        void storesIndividualSkills() {
            when(esiService.getSkills(anyLong(), anyString())).thenReturn(EsiResponse.changed(
                    new EsiService.SkillResponse(1L, 0, new EsiService.EsiSkillEntry[]{
                            new EsiService.EsiSkillEntry(3327L, 5, 5, 256_000L)}),
                    null, null));

            service.sync(character);

            ArgumentCaptor<List<CharacterSkill>> saved = ArgumentCaptor.captor();
            verify(assetSyncService).replaceCharacterSkills(anyLong(), saved.capture());
            assertThat(saved.getValue()).singleElement().satisfies(skill -> {
                assertThat(skill.getSkillTypeId()).isEqualTo(3327L);
                assertThat(skill.getActiveLevel()).isEqualTo(5);
                assertThat(skill.getTrainedLevel()).isEqualTo(5);
            });
        }

        @Test
        @DisplayName("wertet ein fehlendes aktives Level als nicht trainiert")
        void treatsMissingActiveLevelAsUntrained() {
            // Alpha-Accounts liefern hier gelegentlich nichts.
            when(esiService.getSkills(anyLong(), anyString())).thenReturn(EsiResponse.changed(
                    new EsiService.SkillResponse(1L, 0, new EsiService.EsiSkillEntry[]{
                            new EsiService.EsiSkillEntry(3327L, null, 5, 256_000L)}),
                    null, null));

            service.sync(character);

            ArgumentCaptor<List<CharacterSkill>> saved = ArgumentCaptor.captor();
            verify(assetSyncService).replaceCharacterSkills(anyLong(), saved.capture());
            assertThat(saved.getValue().getFirst().getActiveLevel()).isZero();
        }

        @Test
        @DisplayName("ueberspringt das Neuschreiben bei unveraenderten Skills")
        void skipsRewriteWhenUnchanged() {
            when(esiService.getSkills(anyLong(), anyString())).thenReturn(EsiResponse.unchanged(
                    new EsiService.SkillResponse(1L, 0, new EsiService.EsiSkillEntry[]{
                            new EsiService.EsiSkillEntry(3327L, 5, 5, 1L)}),
                    null, null));
            when(skillRepo.existsByCharacterId(CHARACTER_ID)).thenReturn(true);

            service.sync(character);

            verify(assetSyncService, never()).replaceCharacterSkills(anyLong(), anyList());
        }

        @Test
        @DisplayName("schreibt trotz 304 neu, solange noch keine Skills gespeichert sind")
        void writesDespite304WhenTableIsEmpty() {
            // Nach einem Deployment ist der ETag-Cache gefuellt, die Tabelle aber leer.
            when(esiService.getSkills(anyLong(), anyString())).thenReturn(EsiResponse.unchanged(
                    new EsiService.SkillResponse(1L, 0, new EsiService.EsiSkillEntry[]{
                            new EsiService.EsiSkillEntry(3327L, 5, 5, 1L)}),
                    null, null));
            when(skillRepo.existsByCharacterId(CHARACTER_ID)).thenReturn(false);

            service.sync(character);

            verify(assetSyncService).replaceCharacterSkills(anyLong(), anyList());
        }

        @Test
        @DisplayName("ueberspringt Skills ohne Typ-ID")
        void skipsSkillsWithoutTypeId() {
            when(esiService.getSkills(anyLong(), anyString())).thenReturn(EsiResponse.changed(
                    new EsiService.SkillResponse(1L, 0, new EsiService.EsiSkillEntry[]{
                            new EsiService.EsiSkillEntry(null, 5, 5, 1L)}),
                    null, null));

            service.sync(character);

            ArgumentCaptor<List<CharacterSkill>> saved = ArgumentCaptor.captor();
            verify(assetSyncService).replaceCharacterSkills(anyLong(), saved.capture());
            assertThat(saved.getValue()).isEmpty();
        }
    }

    @Nested
    @DisplayName("Loyalitaetspunkte")
    class LoyaltyPoints {

        @Test
        @DisplayName("spiegelt die Punkte je Corporation")
        void storesLoyaltyPoints() {
            when(esiService.getLoyaltyPoints(anyLong(), anyString())).thenReturn(EsiResponse.changed(
                    new EsiService.EsiLpResponse[]{new EsiService.EsiLpResponse(1000125L, 5000)},
                    null, null));

            service.sync(character);

            ArgumentCaptor<List<CharacterLp>> saved = ArgumentCaptor.captor();
            verify(assetSyncService).replaceCharacterLp(anyLong(), saved.capture());
            assertThat(saved.getValue()).singleElement().satisfies(lp -> {
                assertThat(lp.getCorporationId()).isEqualTo(1000125L);
                assertThat(lp.getLoyaltyPoints()).isEqualTo(5000);
            });
        }

        @Test
        @DisplayName("ruehrt die Punkte nicht an, wenn ESI nichts liefert")
        void keepsLoyaltyPointsWithoutData() {
            service.sync(character);

            verify(assetSyncService, never()).replaceCharacterLp(anyLong(), anyList());
        }
    }

    @Nested
    @DisplayName("Bestaende")
    class Assets {

        @Test
        @DisplayName("spiegelt den Hangar samt Custom-Namen")
        void storesAssets() {
            List<EsiService.EsiAssetResponse> esiAssets = List.of(asset(1L));
            when(esiService.getAllAssets(anyLong(), anyString()))
                    .thenReturn(EsiResponse.changed(esiAssets, null, null));
            when(assetMapper.toCharacterAssets(anyLong(), anyList(), any()))
                    .thenReturn(List.of(new CharacterAsset()));

            service.sync(character);

            verify(assetNameResolver).resolve(anyList(), anyString(), any());
            verify(assetSyncService).replaceCharacterAssets(anyLong(), anyList());
        }

        @Test
        @DisplayName("ueberspringt das Neuschreiben bei unveraendertem Hangar")
        void skipsRewriteWhenUnchanged() {
            when(esiService.getAllAssets(anyLong(), anyString()))
                    .thenReturn(EsiResponse.unchanged(List.of(asset(1L)), null, null));
            when(assetRepo.hasPendingCustomNames(CHARACTER_ID)).thenReturn(false);

            service.sync(character);

            verify(assetSyncService, never()).replaceCharacterAssets(anyLong(), anyList());
        }

        @Test
        @DisplayName("laeuft trotz 304 durch, solange Custom-Namen ausstehen")
        void writesDespite304WhenNamesArePending() {
            when(esiService.getAllAssets(anyLong(), anyString()))
                    .thenReturn(EsiResponse.unchanged(List.of(asset(1L)), null, null));
            when(assetRepo.hasPendingCustomNames(CHARACTER_ID)).thenReturn(true);
            when(assetMapper.toCharacterAssets(anyLong(), anyList(), any()))
                    .thenReturn(List.of(new CharacterAsset()));

            service.sync(character);

            verify(assetSyncService).replaceCharacterAssets(anyLong(), anyList());
        }

        @Test
        @DisplayName("schreibt bei leerem Hangar nichts")
        void skipsEmptyHangar() {
            when(esiService.getAllAssets(anyLong(), anyString()))
                    .thenReturn(EsiResponse.changed(List.of(), null, null));

            service.sync(character);

            verify(assetSyncService, never()).replaceCharacterAssets(anyLong(), anyList());
        }
    }
}
