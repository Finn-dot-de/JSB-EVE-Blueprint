package com.eve.own.auth.backend.domain.mining.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.eve.own.auth.backend.domain.character.entity.ActivityType;
import com.eve.own.auth.backend.domain.character.entity.Character;
import com.eve.own.auth.backend.domain.character.entity.CharacterActivity;
import com.eve.own.auth.backend.domain.character.entity.CharacterMining;
import com.eve.own.auth.backend.domain.character.repository.CharacterActivityRepository;
import com.eve.own.auth.backend.domain.character.repository.CharacterMiningRepository;
import com.eve.own.auth.backend.domain.character.repository.CharacterRepository;
import com.eve.own.auth.backend.domain.eve.entity.InvType;
import com.eve.own.auth.backend.domain.eve.repository.InvTypeRepository;
import com.eve.own.auth.backend.domain.mining.dto.MiningDtos;
import com.eve.own.auth.backend.domain.mining.entity.MiningTaxInvoice;
import com.eve.own.auth.backend.domain.mining.entity.MiningTaxRate;
import com.eve.own.auth.backend.domain.mining.repository.MiningTaxInvoiceRepository;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
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
import tools.jackson.databind.ObjectMapper;

/**
 * Die Steuerbilanz ist die Rechnung, die Mitglieder tatsaechlich bezahlen -
 * hier zaehlt jeder Sonderfall.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Mining-Steuerbilanz")
class MiningLedgerServiceTest {

    private static final Long MAIN_ID = 1000L;
    private static final Long ALT_ID = 1001L;
    private static final Long VELDSPAR = 1230L;

    /** Ein sicher abgeschlossener Monat - er darf eingefroren werden. */
    private static final String PAST_MONTH = "2020-01";

    private static final String CURRENT_MONTH = YearMonth.now(ZoneOffset.UTC).toString();

    @Mock private CharacterRepository characterRepo;
    @Mock private CharacterMiningRepository miningRepo;
    @Mock private CharacterActivityRepository activityRepo;
    @Mock private MiningTaxInvoiceRepository invoiceRepo;
    @Mock private MiningTaxRateService taxRateService;
    @Mock private InvTypeRepository invTypeRepo;

    private MiningLedgerService service;

    @BeforeEach
    void setUp() {
        service = new MiningLedgerService(characterRepo, miningRepo, activityRepo,
                invoiceRepo, taxRateService, invTypeRepo, new ObjectMapper());

        Character main = character(MAIN_ID, MAIN_ID);
        when(characterRepo.findById(MAIN_ID)).thenReturn(Optional.of(main));
        when(characterRepo.findByMainCharacterId(MAIN_ID)).thenReturn(List.of(main, character(ALT_ID, MAIN_ID)));
        when(activityRepo.findByCharacterIdIn(anyList())).thenReturn(List.of());
        when(miningRepo.findByCharacterIdIn(anyList())).thenReturn(List.of());
        when(invoiceRepo.findByMainCharacterId(MAIN_ID)).thenReturn(List.of());
        when(taxRateService.findAllByTypeId()).thenReturn(Map.of());
        when(invTypeRepo.findAllById(any())).thenReturn(List.of());
    }

    private static Character character(Long id, Long mainId) {
        Character character = new Character();
        character.setId(id);
        character.setName("Pilot " + id);
        character.setMainCharacterId(mainId);
        return character;
    }

    private static CharacterMining mined(Long characterId, String date, Long typeId, long quantity) {
        CharacterMining entry = new CharacterMining();
        entry.setCharacterId(characterId);
        entry.setDate(date);
        entry.setTypeId(typeId);
        entry.setQuantity(quantity);
        return entry;
    }

    private static MiningTaxRate rate(Long typeId, double jitaBuy, double taxPercentage) {
        MiningTaxRate rate = new MiningTaxRate();
        rate.setTypeId(typeId);
        rate.setTypeName("Veldspar");
        rate.setCategory("ORE");
        rate.setCurrentJitaBuy(jitaBuy);
        rate.setTaxPercentage(taxPercentage);
        return rate;
    }

    private static CharacterActivity payment(Long characterId, double amount) {
        return CharacterActivity.of(characterId, ActivityType.TAX_PAYMENT, amount, Instant.now());
    }

    @Nested
    @DisplayName("Berechnung eines Monats")
    class MonthlyCalculation {

        @Test
        @DisplayName("liefert eine leere Bilanz, wenn nichts abgebaut und nichts gezahlt wurde")
        void emptyLedger() {
            MiningDtos.UserLedgerResponse ledger = service.ledgerOf(MAIN_ID);

            assertThat(ledger.months()).isEmpty();
            assertThat(ledger.totalDebt()).isZero();
            assertThat(ledger.totalPaid()).isZero();
            assertThat(ledger.currentBalance()).isZero();
        }

        @Test
        @DisplayName("rechnet Menge mal Jita-Preis mal Steuersatz")
        void appliesTaxFormula() {
            // 1.000 Einheiten zu 10 ISK bei 10 % ergeben 1.000 ISK Steuer.
            when(miningRepo.findByCharacterIdIn(anyList()))
                    .thenReturn(List.of(mined(MAIN_ID, PAST_MONTH + "-15", VELDSPAR, 1_000)));
            when(taxRateService.findAllByTypeId()).thenReturn(Map.of(VELDSPAR, rate(VELDSPAR, 10.0, 10.0)));

            MiningDtos.UserLedgerResponse ledger = service.ledgerOf(MAIN_ID);

            assertThat(ledger.months()).hasSize(1);
            assertThat(ledger.months().getFirst().totalTax()).isEqualTo(1_000.0);
            assertThat(ledger.totalDebt()).isEqualTo(1_000.0);
        }

        @Test
        @DisplayName("summiert die Mengen aller Charaktere eines Accounts")
        void sumsAcrossAccountCharacters() {
            when(miningRepo.findByCharacterIdIn(anyList())).thenReturn(List.of(
                    mined(MAIN_ID, PAST_MONTH + "-15", VELDSPAR, 600),
                    mined(ALT_ID, PAST_MONTH + "-16", VELDSPAR, 400)));
            when(taxRateService.findAllByTypeId()).thenReturn(Map.of(VELDSPAR, rate(VELDSPAR, 10.0, 10.0)));

            MiningDtos.UserLedgerResponse ledger = service.ledgerOf(MAIN_ID);

            assertThat(ledger.months().getFirst().details()).hasSize(1);
            assertThat(ledger.months().getFirst().details().getFirst().quantity()).isEqualTo(1_000);
        }

        @Test
        @DisplayName("rechnet ein Erz ohne Preis oder Satz mit null")
        void treatsMissingValuesAsZero() {
            when(miningRepo.findByCharacterIdIn(anyList()))
                    .thenReturn(List.of(mined(MAIN_ID, PAST_MONTH + "-15", VELDSPAR, 1_000)));
            MiningTaxRate incomplete = rate(VELDSPAR, 10.0, 10.0);
            incomplete.setCurrentJitaBuy(null);
            incomplete.setTaxPercentage(null);
            when(taxRateService.findAllByTypeId()).thenReturn(Map.of(VELDSPAR, incomplete));

            MiningDtos.UserLedgerResponse ledger = service.ledgerOf(MAIN_ID);

            assertThat(ledger.months().getFirst().totalTax()).isZero();
        }

        @Test
        @DisplayName("legt fuer ein unbekanntes Erz einen Steuersatz an")
        void createsMissingTaxRate() {
            when(miningRepo.findByCharacterIdIn(anyList()))
                    .thenReturn(List.of(mined(MAIN_ID, PAST_MONTH + "-15", 9999L, 10)));
            when(taxRateService.findAllByTypeId()).thenReturn(Map.of());
            when(taxRateService.createMissingRate(9999L)).thenReturn(rate(9999L, 5.0, 20.0));

            MiningDtos.UserLedgerResponse ledger = service.ledgerOf(MAIN_ID);

            verify(taxRateService).createMissingRate(9999L);
            assertThat(ledger.months().getFirst().totalTax()).isEqualTo(10 * 5.0 * 0.20);
        }

        @Test
        @DisplayName("weist das Volumen anhand des SDE-Stueckvolumens aus")
        void reportsVolumeFromSde() {
            when(miningRepo.findByCharacterIdIn(anyList()))
                    .thenReturn(List.of(mined(MAIN_ID, PAST_MONTH + "-15", VELDSPAR, 1_000)));
            when(taxRateService.findAllByTypeId()).thenReturn(Map.of(VELDSPAR, rate(VELDSPAR, 10.0, 10.0)));
            InvType veldspar = new InvType();
            veldspar.setTypeId(VELDSPAR);
            veldspar.setVolume(0.1);
            when(invTypeRepo.findAllById(any())).thenReturn(List.of(veldspar));

            MiningDtos.UserLedgerResponse ledger = service.ledgerOf(MAIN_ID);

            assertThat(ledger.months().getFirst().details().getFirst().volume()).isEqualTo(100.0);
        }

        @Test
        @DisplayName("sortiert die Posten nach Steueranteil absteigend")
        void sortsDetailsByTaxDescending() {
            when(miningRepo.findByCharacterIdIn(anyList())).thenReturn(List.of(
                    mined(MAIN_ID, PAST_MONTH + "-15", 1L, 10),
                    mined(MAIN_ID, PAST_MONTH + "-15", 2L, 1_000)));
            when(taxRateService.findAllByTypeId()).thenReturn(Map.of(
                    1L, rate(1L, 10.0, 10.0),
                    2L, rate(2L, 10.0, 10.0)));

            MiningDtos.UserLedgerResponse ledger = service.ledgerOf(MAIN_ID);

            List<MiningDtos.LedgerItemDto> details = ledger.months().getFirst().details();
            assertThat(details).hasSize(2);
            assertThat(details.getFirst().taxToPay()).isGreaterThan(details.get(1).taxToPay());
        }

        @Test
        @DisplayName("ueberspringt Ledger-Zeilen ohne brauchbares Datum")
        void skipsEntriesWithoutDate() {
            when(miningRepo.findByCharacterIdIn(anyList())).thenReturn(List.of(
                    mined(MAIN_ID, null, VELDSPAR, 1_000),
                    mined(MAIN_ID, "2020", VELDSPAR, 1_000)));
            when(taxRateService.findAllByTypeId()).thenReturn(Map.of(VELDSPAR, rate(VELDSPAR, 10.0, 10.0)));

            assertThat(service.ledgerOf(MAIN_ID).months()).isEmpty();
        }
    }

    @Nested
    @DisplayName("Einfrieren abgeschlossener Monate")
    class Freezing {

        @Test
        @DisplayName("friert einen vergangenen Monat als Snapshot ein")
        void freezesPastMonth() {
            when(miningRepo.findByCharacterIdIn(anyList()))
                    .thenReturn(List.of(mined(MAIN_ID, PAST_MONTH + "-15", VELDSPAR, 1_000)));
            when(taxRateService.findAllByTypeId()).thenReturn(Map.of(VELDSPAR, rate(VELDSPAR, 10.0, 10.0)));

            service.ledgerOf(MAIN_ID);

            ArgumentCaptor<MiningTaxInvoice> saved = ArgumentCaptor.forClass(MiningTaxInvoice.class);
            verify(invoiceRepo).save(saved.capture());
            assertThat(saved.getValue().getMonth()).isEqualTo(PAST_MONTH);
            assertThat(saved.getValue().getMainCharacterId()).isEqualTo(MAIN_ID);
            assertThat(saved.getValue().getTotalTax()).isEqualTo(1_000.0);
            assertThat(saved.getValue().getDetailsJson()).contains("Veldspar");
        }

        @Test
        @DisplayName("laesst den laufenden Monat offen")
        void leavesCurrentMonthOpen() {
            when(miningRepo.findByCharacterIdIn(anyList()))
                    .thenReturn(List.of(mined(MAIN_ID, CURRENT_MONTH + "-01", VELDSPAR, 1_000)));
            when(taxRateService.findAllByTypeId()).thenReturn(Map.of(VELDSPAR, rate(VELDSPAR, 10.0, 10.0)));

            service.ledgerOf(MAIN_ID);

            verify(invoiceRepo, never()).save(any());
        }

        @Test
        @DisplayName("nimmt einen vorhandenen Snapshot unveraendert und rechnet nicht neu")
        void usesExistingSnapshot() {
            MiningTaxInvoice invoice = new MiningTaxInvoice();
            invoice.setMainCharacterId(MAIN_ID);
            invoice.setMonth(PAST_MONTH);
            invoice.setTotalTax(777.0);
            invoice.setDetailsJson("[]");
            when(invoiceRepo.findByMainCharacterId(MAIN_ID)).thenReturn(List.of(invoice));
            // Rohdaten mit einem voellig anderen Betrag - der Snapshot gewinnt.
            when(miningRepo.findByCharacterIdIn(anyList()))
                    .thenReturn(List.of(mined(MAIN_ID, PAST_MONTH + "-15", VELDSPAR, 1_000_000)));
            when(taxRateService.findAllByTypeId()).thenReturn(Map.of(VELDSPAR, rate(VELDSPAR, 10.0, 10.0)));

            MiningDtos.UserLedgerResponse ledger = service.ledgerOf(MAIN_ID);

            assertThat(ledger.months().getFirst().totalTax()).isEqualTo(777.0);
            verify(invoiceRepo, never()).save(any());
        }

        @Test
        @DisplayName("zeigt einen Monat auch dann, wenn nur der Snapshot existiert")
        void snapshotWithoutRawData() {
            MiningTaxInvoice invoice = new MiningTaxInvoice();
            invoice.setMainCharacterId(MAIN_ID);
            invoice.setMonth("2019-05");
            invoice.setTotalTax(500.0);
            invoice.setDetailsJson("[]");
            when(invoiceRepo.findByMainCharacterId(MAIN_ID)).thenReturn(List.of(invoice));

            MiningDtos.UserLedgerResponse ledger = service.ledgerOf(MAIN_ID);

            assertThat(ledger.months()).hasSize(1);
            assertThat(ledger.totalDebt()).isEqualTo(500.0);
        }

        @Test
        @DisplayName("zeigt den Monat auch, wenn die Aufschluesselung unlesbar ist")
        void survivesBrokenSnapshotDetails() {
            MiningTaxInvoice invoice = new MiningTaxInvoice();
            invoice.setMainCharacterId(MAIN_ID);
            invoice.setMonth(PAST_MONTH);
            invoice.setTotalTax(500.0);
            invoice.setDetailsJson("das ist kein JSON");
            when(invoiceRepo.findByMainCharacterId(MAIN_ID)).thenReturn(List.of(invoice));

            MiningDtos.UserLedgerResponse ledger = service.ledgerOf(MAIN_ID);

            assertThat(ledger.months().getFirst().totalTax()).isEqualTo(500.0);
            assertThat(ledger.months().getFirst().details()).isEmpty();
        }
    }

    @Nested
    @DisplayName("Wasserfall-Verrechnung der Zahlungen")
    class Waterfall {

        private void twoPastMonthsOf1000Each() {
            when(miningRepo.findByCharacterIdIn(anyList())).thenReturn(List.of(
                    mined(MAIN_ID, "2020-01-15", VELDSPAR, 1_000),
                    mined(MAIN_ID, "2020-02-15", VELDSPAR, 1_000)));
            when(taxRateService.findAllByTypeId()).thenReturn(Map.of(VELDSPAR, rate(VELDSPAR, 10.0, 10.0)));
        }

        @Test
        @DisplayName("verrechnet vom aeltesten Monat an")
        void allocatesOldestFirst() {
            twoPastMonthsOf1000Each();
            when(activityRepo.findByCharacterIdIn(anyList())).thenReturn(List.of(payment(MAIN_ID, 1_000)));

            MiningDtos.UserLedgerResponse ledger = service.ledgerOf(MAIN_ID);

            // Die Ausgabe steht neueste zuerst.
            MiningDtos.MonthlyLedgerDto january = ledger.months().get(1);
            MiningDtos.MonthlyLedgerDto february = ledger.months().getFirst();

            assertThat(january.month()).isEqualTo("2020-01");
            assertThat(january.taxPaid()).isEqualTo(1_000.0);
            assertThat(january.isPaid()).isTrue();

            assertThat(february.month()).isEqualTo("2020-02");
            assertThat(february.taxPaid()).isZero();
            assertThat(february.isPaid()).isFalse();
        }

        @Test
        @DisplayName("deckt mit einer Vorauszahlung auch spaetere Monate")
        void advancePaymentCoversLaterMonths() {
            twoPastMonthsOf1000Each();
            when(activityRepo.findByCharacterIdIn(anyList())).thenReturn(List.of(payment(MAIN_ID, 5_000)));

            MiningDtos.UserLedgerResponse ledger = service.ledgerOf(MAIN_ID);

            assertThat(ledger.months()).allSatisfy(month -> assertThat(month.isPaid()).isTrue());
            assertThat(ledger.currentBalance()).isEqualTo(3_000.0);
        }

        @Test
        @DisplayName("teilt eine zu kleine Zahlung anteilig zu")
        void partialPayment() {
            twoPastMonthsOf1000Each();
            when(activityRepo.findByCharacterIdIn(anyList())).thenReturn(List.of(payment(MAIN_ID, 1_200)));

            MiningDtos.UserLedgerResponse ledger = service.ledgerOf(MAIN_ID);

            assertThat(ledger.months().get(1).taxPaid()).isEqualTo(1_000.0);
            assertThat(ledger.months().getFirst().taxPaid()).isEqualTo(200.0);
            assertThat(ledger.months().getFirst().isPaid()).isFalse();
        }

        @Test
        @DisplayName("wertet einen Monat ab 95 Prozent Deckung als bezahlt")
        void toleratesRoundingBelowFullAmount() {
            when(miningRepo.findByCharacterIdIn(anyList()))
                    .thenReturn(List.of(mined(MAIN_ID, PAST_MONTH + "-15", VELDSPAR, 1_000)));
            when(taxRateService.findAllByTypeId()).thenReturn(Map.of(VELDSPAR, rate(VELDSPAR, 10.0, 10.0)));
            when(activityRepo.findByCharacterIdIn(anyList())).thenReturn(List.of(payment(MAIN_ID, 950)));

            assertThat(service.ledgerOf(MAIN_ID).months().getFirst().isPaid()).isTrue();
        }

        @Test
        @DisplayName("wertet knapp unter 95 Prozent noch als offen")
        void staysOpenJustBelowThreshold() {
            when(miningRepo.findByCharacterIdIn(anyList()))
                    .thenReturn(List.of(mined(MAIN_ID, PAST_MONTH + "-15", VELDSPAR, 1_000)));
            when(taxRateService.findAllByTypeId()).thenReturn(Map.of(VELDSPAR, rate(VELDSPAR, 10.0, 10.0)));
            when(activityRepo.findByCharacterIdIn(anyList())).thenReturn(List.of(payment(MAIN_ID, 949)));

            assertThat(service.ledgerOf(MAIN_ID).months().getFirst().isPaid()).isFalse();
        }

        @Test
        @DisplayName("zaehlt nur Steuerzahlungen, keine anderen Kennzahlen")
        void countsOnlyTaxPayments() {
            twoPastMonthsOf1000Each();
            when(activityRepo.findByCharacterIdIn(anyList())).thenReturn(List.of(
                    payment(MAIN_ID, 1_000),
                    CharacterActivity.of(MAIN_ID, ActivityType.PVE_ISK, 999_999, Instant.now())));

            assertThat(service.ledgerOf(MAIN_ID).totalPaid()).isEqualTo(1_000.0);
        }

        @Test
        @DisplayName("summiert die Zahlungen aller Charaktere des Accounts")
        void sumsPaymentsAcrossAccount() {
            twoPastMonthsOf1000Each();
            when(activityRepo.findByCharacterIdIn(anyList()))
                    .thenReturn(List.of(payment(MAIN_ID, 600), payment(ALT_ID, 400)));

            assertThat(service.ledgerOf(MAIN_ID).totalPaid()).isEqualTo(1_000.0);
        }
    }

    @Nested
    @DisplayName("Zugriff")
    class Access {

        @Test
        @DisplayName("weist einen unbekannten Charakter ab")
        void rejectsUnknownCharacter() {
            when(characterRepo.findById(404L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.ledgerOf(404L))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("404");
        }

        @Test
        @DisplayName("rechnet fuer einen Alt die Bilanz seines Accounts")
        void resolvesAccountOfAnAlt() {
            when(characterRepo.findById(ALT_ID)).thenReturn(Optional.of(character(ALT_ID, MAIN_ID)));

            service.ledgerOf(ALT_ID);

            verify(characterRepo).findByMainCharacterId(MAIN_ID);
        }
    }

    @Nested
    @DisplayName("Admin-Uebersicht")
    class AdminSummaries {

        @BeforeEach
        void adminData() {
            when(characterRepo.findAll())
                    .thenReturn(new ArrayList<>(List.of(character(MAIN_ID, MAIN_ID), character(ALT_ID, MAIN_ID))));
            when(activityRepo.findAll()).thenReturn(List.of());
            when(invoiceRepo.findAll()).thenReturn(List.of());
            when(miningRepo.findAll()).thenReturn(List.of());
            when(taxRateService.findAllByTypeId()).thenReturn(Map.of());
        }

        @Test
        @DisplayName("fasst Main und Alts zu einer Zeile zusammen")
        void groupsByAccount() {
            List<MiningDtos.AdminLedgerSummaryDto> summaries = service.allAccountSummaries();

            assertThat(summaries).hasSize(1);
            assertThat(summaries.getFirst().mainId()).isEqualTo(MAIN_ID);
            assertThat(summaries.getFirst().mainName()).isEqualTo("Pilot 1000");
            assertThat(summaries.getFirst().portraitUrl()).contains("/characters/1000/portrait");
        }

        @Test
        @DisplayName("addiert eingefrorene Monate und den laufenden Monat")
        void addsFrozenAndLiveTax() {
            MiningTaxInvoice invoice = new MiningTaxInvoice();
            invoice.setMainCharacterId(MAIN_ID);
            invoice.setMonth(PAST_MONTH);
            invoice.setTotalTax(500.0);
            when(invoiceRepo.findAll()).thenReturn(List.of(invoice));
            when(miningRepo.findAll())
                    .thenReturn(List.of(mined(ALT_ID, CURRENT_MONTH + "-01", VELDSPAR, 1_000)));
            when(taxRateService.findAllByTypeId()).thenReturn(Map.of(VELDSPAR, rate(VELDSPAR, 10.0, 10.0)));

            MiningDtos.AdminLedgerSummaryDto summary = service.allAccountSummaries().getFirst();

            assertThat(summary.totalTax()).isEqualTo(1_500.0);
        }

        @Test
        @DisplayName("laesst den laufenden Monat ohne Steuersatz aussen vor")
        void ignoresLiveMiningWithoutRate() {
            when(miningRepo.findAll())
                    .thenReturn(List.of(mined(MAIN_ID, CURRENT_MONTH + "-01", 9999L, 1_000)));

            assertThat(service.allAccountSummaries().getFirst().totalTax()).isZero();
        }

        @Test
        @DisplayName("zieht die Zahlungen vom Soll ab")
        void computesBalance() {
            MiningTaxInvoice invoice = new MiningTaxInvoice();
            invoice.setMainCharacterId(MAIN_ID);
            invoice.setMonth(PAST_MONTH);
            invoice.setTotalTax(1_000.0);
            when(invoiceRepo.findAll()).thenReturn(List.of(invoice));
            when(activityRepo.findAll()).thenReturn(List.of(payment(ALT_ID, 400)));

            MiningDtos.AdminLedgerSummaryDto summary = service.allAccountSummaries().getFirst();

            assertThat(summary.totalPaid()).isEqualTo(400.0);
            assertThat(summary.currentBalance()).isEqualTo(-600.0);
        }

        @Test
        @DisplayName("stellt das groesste Minus nach oben")
        void sortsByBalanceAscending() {
            Character other = character(2000L, 2000L);
            when(characterRepo.findAll())
                    .thenReturn(new ArrayList<>(List.of(character(MAIN_ID, MAIN_ID), other)));

            MiningTaxInvoice debt = new MiningTaxInvoice();
            debt.setMainCharacterId(2000L);
            debt.setMonth(PAST_MONTH);
            debt.setTotalTax(5_000.0);
            when(invoiceRepo.findAll()).thenReturn(List.of(debt));

            List<MiningDtos.AdminLedgerSummaryDto> summaries = service.allAccountSummaries();

            assertThat(summaries).hasSize(2);
            assertThat(summaries.getFirst().mainId()).isEqualTo(2000L);
            assertThat(summaries.getFirst().currentBalance()).isEqualTo(-5_000.0);
        }

        @Test
        @DisplayName("nimmt den Namen eines Alts, wenn der Main-Datensatz fehlt")
        void fallsBackToFirstCharacterName() {
            when(characterRepo.findAll())
                    .thenReturn(new ArrayList<>(List.of(character(ALT_ID, MAIN_ID))));

            assertThat(service.allAccountSummaries().getFirst().mainName()).isEqualTo("Pilot 1001");
        }

        @Test
        @DisplayName("ignoriert Zahlungen von Charakteren ausserhalb der Accounts")
        void ignoresForeignPayments() {
            when(activityRepo.findAll()).thenReturn(List.of(payment(999_999L, 1_000)));

            assertThat(service.allAccountSummaries().getFirst().totalPaid()).isZero();
        }
    }
}
