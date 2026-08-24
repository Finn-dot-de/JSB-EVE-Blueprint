package com.eve.own.auth.backend.domain.character.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.eve.own.auth.backend.domain.character.CorporationScope;
import com.eve.own.auth.backend.domain.character.entity.ActivityType;
import com.eve.own.auth.backend.domain.character.entity.Character;
import com.eve.own.auth.backend.domain.character.entity.CharacterActivity;
import com.eve.own.auth.backend.domain.character.entity.CharacterMining;
import com.eve.own.auth.backend.domain.eve.entity.InvType;
import com.eve.own.auth.backend.domain.eve.repository.InvTypeRepository;
import com.eve.own.auth.backend.esi.EsiResponse;
import com.eve.own.auth.backend.esi.EsiService;
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

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Kennzahlen aus Mining-Ledger und Wallet-Journal")
class CharacterActivitySyncServiceTest {

    private static final Long CHARACTER_ID = 1000L;
    private static final Long MAIN_CORP = 98000001L;
    private static final Long VELDSPAR = 1230L;
    private static final String TOKEN = "token";

    @Mock private EsiService esiService;
    @Mock private InvTypeRepository invTypeRepo;
    @Mock private AssetSyncService assetSyncService;

    private CharacterActivitySyncService service;
    private Character character;

    @BeforeEach
    void setUp() {
        service = new CharacterActivitySyncService(esiService, invTypeRepo, assetSyncService,
                new CorporationScope(MAIN_CORP, ""));

        character = new Character();
        character.setId(CHARACTER_ID);
        character.setName("Pilot Eins");

        when(esiService.getMiningLedger(anyLong(), anyString())).thenReturn(EsiResponse.empty());
        when(esiService.getWalletJournal(anyLong(), anyString())).thenReturn(EsiResponse.empty());
        when(invTypeRepo.findAllById(any())).thenReturn(List.of());
    }

    private static EsiService.EsiMiningResponse mined(String date, Long typeId, long quantity) {
        return new EsiService.EsiMiningResponse(date, quantity, 30000142L, typeId);
    }

    private static EsiService.EsiJournalResponse journal(String refType, Double amount,
                                                        Long secondParty, String reason) {
        return new EsiService.EsiJournalResponse(1L, "2026-08-05T12:00:00Z", refType, amount,
                secondParty, reason);
    }

    private List<CharacterActivity> capturedActivities() {
        ArgumentCaptor<List<CharacterActivity>> captor = ArgumentCaptor.captor();
        verify(assetSyncService).mergeCharacterActivities(anyLong(), captor.capture());
        return captor.getValue();
    }

    @Nested
    @DisplayName("Mining")
    class Mining {

        @Test
        @DisplayName("spiegelt das Ledger und meldet das abgebaute Volumen")
        void reportsMinedVolume() {
            when(esiService.getMiningLedger(anyLong(), anyString())).thenReturn(
                    EsiResponse.changed(new EsiService.EsiMiningResponse[]{
                            mined("2026-08-05", VELDSPAR, 1_000)}, null, null));
            InvType veldspar = new InvType();
            veldspar.setTypeId(VELDSPAR);
            veldspar.setVolume(0.1);
            when(invTypeRepo.findAllById(any())).thenReturn(List.of(veldspar));

            service.sync(character, TOKEN);

            ArgumentCaptor<List<CharacterMining>> ledger = ArgumentCaptor.captor();
            verify(assetSyncService).mergeCharacterMining(anyLong(), ledger.capture());
            assertThat(ledger.getValue()).singleElement()
                    .satisfies(entry -> assertThat(entry.getQuantity()).isEqualTo(1_000));

            assertThat(capturedActivities())
                    .filteredOn(activity -> activity.isOfType(ActivityType.MINING_VOLUME))
                    .singleElement()
                    .satisfies(activity -> assertThat(activity.getValue()).isEqualByComparingTo("100.00"));
        }

        @Test
        @DisplayName("rechnet Typen ohne bekanntes Volumen mit null")
        void treatsUnknownVolumeAsZero() {
            when(esiService.getMiningLedger(anyLong(), anyString())).thenReturn(
                    EsiResponse.changed(new EsiService.EsiMiningResponse[]{
                            mined("2026-08-05", 9999L, 1_000)}, null, null));

            service.sync(character, TOKEN);

            assertThat(capturedActivities())
                    .filteredOn(activity -> activity.isOfType(ActivityType.MINING_VOLUME))
                    .singleElement()
                    .satisfies(activity -> assertThat(activity.getValue()).isZero());
        }

        @Test
        @DisplayName("meldet ohne Mining-Daten gar keine Volumen-Kennzahl")
        void reportsNothingWithoutMining() {
            service.sync(character, TOKEN);

            verify(assetSyncService, never()).mergeCharacterMining(anyLong(), any());
            verify(assetSyncService, never()).mergeCharacterActivities(anyLong(), any());
        }
    }

    @Nested
    @DisplayName("Wallet-Journal")
    class WalletJournal {

        @Test
        @DisplayName("summiert Kopfgelder und zaehlt die Gutschriften")
        void sumsBounties() {
            when(esiService.getWalletJournal(anyLong(), anyString())).thenReturn(
                    EsiResponse.changed(new EsiService.EsiJournalResponse[]{
                            journal("bounty_prizes", 100_000.0, null, null),
                            journal("bounty_prizes", 50_000.0, null, null)}, null, null));

            service.sync(character, TOKEN);

            List<CharacterActivity> activities = capturedActivities();
            assertThat(activities).filteredOn(a -> a.isOfType(ActivityType.PVE_ISK))
                    .singleElement()
                    .satisfies(a -> assertThat(a.getValue()).isEqualByComparingTo("150000.00"));
            assertThat(activities).filteredOn(a -> a.isOfType(ActivityType.RAT_KILLS))
                    .singleElement()
                    .satisfies(a -> assertThat(a.getValue()).isEqualByComparingTo("2.00"));
        }

        @Test
        @DisplayName("erkennt eine Ueberweisung an die Corp mit Steuer-Stichwort")
        void detectsTaxPayment() {
            when(esiService.getWalletJournal(anyLong(), anyString())).thenReturn(
                    EsiResponse.changed(new EsiService.EsiJournalResponse[]{
                            journal("player_donation", -5_000_000.0, MAIN_CORP, "Mining Steuer Juli")},
                            null, null));

            service.sync(character, TOKEN);

            assertThat(capturedActivities())
                    .filteredOn(a -> a.isOfType(ActivityType.TAX_PAYMENT))
                    .singleElement()
                    .satisfies(a -> assertThat(a.getValue()).isEqualByComparingTo("5000000.00"));
        }

        @Test
        @DisplayName("erkennt die Stichworte unabhaengig von der Schreibweise")
        void detectsKeywordsCaseInsensitively() {
            when(esiService.getWalletJournal(anyLong(), anyString())).thenReturn(
                    EsiResponse.changed(new EsiService.EsiJournalResponse[]{
                            journal("player_donation", -1.0, MAIN_CORP, "TAX")}, null, null));

            service.sync(character, TOKEN);

            assertThat(capturedActivities()).anyMatch(a -> a.isOfType(ActivityType.TAX_PAYMENT));
        }

        @Test
        @DisplayName("wertet eine eingehende Zahlung nicht als Steuer")
        void ignoresIncomingTransfers() {
            when(esiService.getWalletJournal(anyLong(), anyString())).thenReturn(
                    EsiResponse.changed(new EsiService.EsiJournalResponse[]{
                            journal("player_donation", 5_000_000.0, MAIN_CORP, "Steuer")}, null, null));

            service.sync(character, TOKEN);

            assertThat(capturedActivities()).noneMatch(a -> a.isOfType(ActivityType.TAX_PAYMENT));
        }

        @Test
        @DisplayName("wertet eine Zahlung an eine fremde Corporation nicht als Steuer")
        void ignoresPaymentsToOtherCorporations() {
            when(esiService.getWalletJournal(anyLong(), anyString())).thenReturn(
                    EsiResponse.changed(new EsiService.EsiJournalResponse[]{
                            journal("player_donation", -1.0, 99999999L, "Steuer")}, null, null));

            service.sync(character, TOKEN);

            assertThat(capturedActivities()).noneMatch(a -> a.isOfType(ActivityType.TAX_PAYMENT));
        }

        @Test
        @DisplayName("wertet eine Ueberweisung ohne passendes Stichwort nicht als Steuer")
        void ignoresUnrelatedTransfers() {
            when(esiService.getWalletJournal(anyLong(), anyString())).thenReturn(
                    EsiResponse.changed(new EsiService.EsiJournalResponse[]{
                            journal("player_donation", -1.0, MAIN_CORP, "Danke fuer das Schiff"),
                            journal("player_donation", -1.0, MAIN_CORP, null)}, null, null));

            service.sync(character, TOKEN);

            assertThat(capturedActivities()).noneMatch(a -> a.isOfType(ActivityType.TAX_PAYMENT));
        }

        @Test
        @DisplayName("ueberspringt Journalzeilen ohne Betrag")
        void skipsEntriesWithoutAmount() {
            when(esiService.getWalletJournal(anyLong(), anyString())).thenReturn(
                    EsiResponse.changed(new EsiService.EsiJournalResponse[]{
                            journal("bounty_prizes", null, null, null)}, null, null));

            service.sync(character, TOKEN);

            assertThat(capturedActivities())
                    .filteredOn(a -> a.isOfType(ActivityType.PVE_ISK))
                    .singleElement()
                    .satisfies(a -> assertThat(a.getValue()).isZero());
        }

        @Test
        @DisplayName("meldet auch bei leerem Journal die Kopfgeld-Kennzahlen mit null")
        void reportsZeroBountiesForEmptyJournal() {
            when(esiService.getWalletJournal(anyLong(), anyString())).thenReturn(
                    EsiResponse.changed(new EsiService.EsiJournalResponse[0], null, null));

            service.sync(character, TOKEN);

            assertThat(capturedActivities()).hasSize(2);
        }
    }
}
