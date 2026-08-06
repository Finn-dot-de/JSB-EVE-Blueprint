package com.eve.own.auth.backend.domain.character.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.eve.own.auth.backend.domain.character.entity.ActivityType;
import com.eve.own.auth.backend.domain.character.entity.CharacterActivity;
import com.eve.own.auth.backend.domain.character.entity.CharacterAsset;
import com.eve.own.auth.backend.domain.character.entity.CharacterLp;
import com.eve.own.auth.backend.domain.character.entity.CharacterMining;
import com.eve.own.auth.backend.domain.character.entity.CharacterSkill;
import com.eve.own.auth.backend.domain.character.entity.CorporationAsset;
import com.eve.own.auth.backend.domain.character.repository.CharacterActivityRepository;
import com.eve.own.auth.backend.domain.character.repository.CharacterAssetRepository;
import com.eve.own.auth.backend.domain.character.repository.CharacterLpRepository;
import com.eve.own.auth.backend.domain.character.repository.CharacterMiningRepository;
import com.eve.own.auth.backend.domain.character.repository.CharacterSkillRepository;
import com.eve.own.auth.backend.domain.character.repository.CorporationAssetRepository;
import java.time.Instant;
import java.util.List;
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

/**
 * Das Schreiben der Sync-Ergebnisse. Zwei Verfahren stehen sich gegenueber:
 * vollstaendig ersetzen fuer Momentaufnahmen, zusammenfuehren fuer alles, was
 * eine Historie hat.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Speichern der Sync-Ergebnisse")
class AssetSyncServiceTest {

    private static final Long CHARACTER_ID = 1000L;
    private static final Long CORPORATION_ID = 98000001L;

    @Mock private CharacterAssetRepository assetRepo;
    @Mock private CharacterLpRepository lpRepo;
    @Mock private CharacterActivityRepository activityRepo;
    @Mock private CharacterMiningRepository miningRepo;
    @Mock private CharacterSkillRepository skillRepo;
    @Mock private CorporationAssetRepository corpAssetRepo;

    private AssetSyncService service;

    @BeforeEach
    void setUp() {
        service = new AssetSyncService(assetRepo, lpRepo, activityRepo, miningRepo, skillRepo,
                corpAssetRepo);
        when(miningRepo.findByCharacterId(anyLong())).thenReturn(List.of());
        when(activityRepo.findByCharacterId(anyLong())).thenReturn(List.of());
    }

    private static CharacterMining mined(String date, Long typeId, long quantity) {
        CharacterMining entry = new CharacterMining();
        entry.setCharacterId(CHARACTER_ID);
        entry.setDate(date);
        entry.setTypeId(typeId);
        entry.setQuantity(quantity);
        return entry;
    }

    @Nested
    @DisplayName("Momentaufnahmen ersetzen")
    class Snapshots {

        @Test
        @DisplayName("ersetzt Bestaende, Skills und Punkte vollstaendig")
        void replacesSnapshots() {
            service.replaceCharacterAssets(CHARACTER_ID, List.of(new CharacterAsset()));
            verify(assetRepo).deleteByCharacterId(CHARACTER_ID);
            verify(assetRepo).saveAll(anyList());

            service.replaceCharacterSkills(CHARACTER_ID, List.of(new CharacterSkill()));
            verify(skillRepo).deleteByCharacterId(CHARACTER_ID);

            service.replaceCharacterLp(CHARACTER_ID, List.of(new CharacterLp()));
            verify(lpRepo).deleteByCharacterId(CHARACTER_ID);

            service.replaceCorporationAssets(CORPORATION_ID, List.of(new CorporationAsset()));
            verify(corpAssetRepo).deleteByCorporationId(CORPORATION_ID);
        }
    }

    @Nested
    @DisplayName("Mining-Ledger zusammenfuehren")
    class MiningMerge {

        @Test
        @DisplayName("legt neue Tage an")
        void addsNewEntries() {
            service.mergeCharacterMining(CHARACTER_ID, List.of(mined("2026-08-05", 1230L, 100)));

            ArgumentCaptor<List<CharacterMining>> saved = ArgumentCaptor.captor();
            verify(miningRepo).saveAll(saved.capture());
            assertThat(saved.getValue()).hasSize(1);
        }

        @Test
        @DisplayName("schreibt die Menge eines bekannten Tages fort")
        void updatesExistingDay() {
            // Am laufenden Tag waechst die Menge, solange weiter abgebaut wird.
            CharacterMining existing = mined("2026-08-05", 1230L, 100);
            when(miningRepo.findByCharacterId(CHARACTER_ID)).thenReturn(List.of(existing));

            service.mergeCharacterMining(CHARACTER_ID, List.of(mined("2026-08-05", 1230L, 250)));

            assertThat(existing.getQuantity()).isEqualTo(250);
        }

        @Test
        @DisplayName("haelt verschiedene Erze desselben Tages auseinander")
        void keepsDifferentTypesApart() {
            when(miningRepo.findByCharacterId(CHARACTER_ID))
                    .thenReturn(List.of(mined("2026-08-05", 1230L, 100)));

            service.mergeCharacterMining(CHARACTER_ID, List.of(mined("2026-08-05", 9999L, 50)));

            ArgumentCaptor<List<CharacterMining>> saved = ArgumentCaptor.captor();
            verify(miningRepo).saveAll(saved.capture());
            assertThat(saved.getValue()).singleElement()
                    .satisfies(entry -> assertThat(entry.getTypeId()).isEqualTo(9999L));
        }

        @Test
        @DisplayName("macht bei leerer Lieferung nichts")
        void ignoresEmptyLedger() {
            service.mergeCharacterMining(CHARACTER_ID, List.of());
            service.mergeCharacterMining(CHARACTER_ID, null);

            verify(miningRepo, never()).saveAll(anyList());
        }
    }

    @Nested
    @DisplayName("Kennzahlen zusammenfuehren")
    class ActivityMerge {

        @Test
        @DisplayName("raeumt die fortlaufenden Kennzahlen vor dem Schreiben ab")
        void clearsRollingActivities() {
            service.mergeCharacterActivities(CHARACTER_ID, List.of(
                    CharacterActivity.of(CHARACTER_ID, ActivityType.PVE_ISK, 1.0, Instant.now())));

            verify(activityRepo).deleteRollingActivitiesByCharacterId(CHARACTER_ID);
            verify(activityRepo).saveAll(anyList());
        }

        @Test
        @DisplayName("legt eine noch unbekannte Steuerzahlung an")
        void addsNewTaxPayment() {
            service.mergeCharacterActivities(CHARACTER_ID, List.of(
                    CharacterActivity.of(CHARACTER_ID, ActivityType.TAX_PAYMENT, 1_000_000,
                            Instant.parse("2026-08-05T12:00:00Z"))));

            ArgumentCaptor<List<CharacterActivity>> saved = ArgumentCaptor.captor();
            verify(activityRepo).saveAll(saved.capture());
            assertThat(saved.getValue()).hasSize(1);
        }

        @Test
        @DisplayName("legt eine bereits erfasste Steuerzahlung nicht erneut an")
        void skipsDuplicateTaxPayment() {
            // Das Wallet-Journal liefert dieselbe Zahlung bei jedem Lauf erneut.
            Instant paidAt = Instant.parse("2026-08-05T12:00:00Z");
            CharacterActivity existing =
                    CharacterActivity.of(CHARACTER_ID, ActivityType.TAX_PAYMENT, 1_000_000, paidAt);
            when(activityRepo.findByCharacterId(CHARACTER_ID)).thenReturn(List.of(existing));

            service.mergeCharacterActivities(CHARACTER_ID, List.of(
                    CharacterActivity.of(CHARACTER_ID, ActivityType.TAX_PAYMENT, 1_000_000, paidAt)));

            verify(activityRepo, never()).saveAll(anyList());
        }

        @Test
        @DisplayName("erkennt zwei Zahlungen zum selben Zeitpunkt ueber den Betrag")
        void distinguishesByAmount() {
            Instant paidAt = Instant.parse("2026-08-05T12:00:00Z");
            when(activityRepo.findByCharacterId(CHARACTER_ID)).thenReturn(List.of(
                    CharacterActivity.of(CHARACTER_ID, ActivityType.TAX_PAYMENT, 1_000_000, paidAt)));

            service.mergeCharacterActivities(CHARACTER_ID, List.of(
                    CharacterActivity.of(CHARACTER_ID, ActivityType.TAX_PAYMENT, 2_000_000, paidAt)));

            verify(activityRepo).saveAll(anyList());
        }

        @Test
        @DisplayName("raeumt auch bei leerer Lieferung die fortlaufenden Kennzahlen ab")
        void clearsEvenWithoutNewData() {
            service.mergeCharacterActivities(CHARACTER_ID, List.of());

            verify(activityRepo).deleteRollingActivitiesByCharacterId(CHARACTER_ID);
            verify(activityRepo, never()).saveAll(anyList());
        }
    }
}
