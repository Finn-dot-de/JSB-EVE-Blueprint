package com.eve.own.auth.backend.domain.mining.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
import com.eve.own.auth.backend.domain.mining.entity.MiningTaxCredit;
import com.eve.own.auth.backend.domain.mining.entity.MiningTaxInvoice;
import com.eve.own.auth.backend.domain.mining.entity.MiningTaxRate;
import com.eve.own.auth.backend.domain.mining.repository.MiningTaxInvoiceRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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

    /** Die Fuehrung - fuer die Admin-Sichten, die den Handelnden verlangen. */
    private static final Long DIREKTORIN = 100L;

    /** Ein Mitglied ohne Amt - es darf keine der Admin-Sichten sehen. */
    private static final Long MITGLIED_OHNE_AMT = 200L;

    /** Ein laengst abgeschlossener Monat - er ist auch aus der Karenzzeit heraus. */
    private static final String PAST_MONTH = "2020-01";

    private static final String CURRENT_MONTH = YearMonth.now(ZoneOffset.UTC).toString();

    /**
     * Der Vormonat: vorbei, aber noch in der Karenzzeit.
     *
     * <p>Genau das Fenster, in dem der ESI-Ledger noch Zeilen nachliefert.</p>
     */
    private static final String GRACE_MONTH = YearMonth.now(ZoneOffset.UTC).minusMonths(1).toString();

    /** Weit genug zurueck, dass die Karenzzeit an jedem Tag des Jahres abgelaufen ist. */
    private static final String SETTLED_MONTH = YearMonth.now(ZoneOffset.UTC).minusMonths(3).toString();

    @Mock private CharacterRepository characterRepo;
    @Mock private CharacterMiningRepository miningRepo;
    @Mock private CharacterActivityRepository activityRepo;
    @Mock private MiningTaxInvoiceRepository invoiceRepo;
    @Mock private MiningTaxRateService taxRateService;
    @Mock private InvTypeRepository invTypeRepo;
    @Mock private MiningTaxCreditService creditService;
    @Mock private MiningAdminGuard guard;

    private MiningLedgerService service;

    @BeforeEach
    void setUp() {
        service = new MiningLedgerService(characterRepo, miningRepo, activityRepo,
                invoiceRepo, taxRateService, invTypeRepo, new ObjectMapper(), creditService, guard);

        Character main = character(MAIN_ID, MAIN_ID);
        when(characterRepo.findById(MAIN_ID)).thenReturn(Optional.of(main));
        when(characterRepo.findByMainCharacterId(MAIN_ID)).thenReturn(List.of(main, character(ALT_ID, MAIN_ID)));
        when(activityRepo.findByCharacterIdIn(anyList())).thenReturn(List.of());
        when(miningRepo.findByCharacterIdIn(anyList())).thenReturn(List.of());
        when(invoiceRepo.findByMainCharacterId(anyLong())).thenReturn(List.of());
        when(taxRateService.findAllByTypeId()).thenReturn(Map.of());
        when(invTypeRepo.findAllById(any())).thenReturn(List.of());

        // Der Regelfall des Einfrierens: die Rechnung ist neu.
        when(invoiceRepo.insertIfAbsent(anyLong(), anyString(), any(), any(), any())).thenReturn(1);

        // Ohne Gutschriften, solange ein Test nichts anderes sagt - die
        // Gutschriften haben ihren eigenen Dienst und ihren eigenen Test.
        when(creditService.applicableFor(anyLong())).thenReturn(List.of());
        when(creditService.applicableByAccount()).thenReturn(Map.of());
        when(creditService.historyForChecked(anyLong())).thenReturn(List.of());

        // Der Waechter laesst die Direktorin durch und weist alle anderen ab -
        // genau so, wie es die echte Pruefung am Rollensatz tut.
        when(guard.requireLeadership(DIREKTORIN)).thenReturn(character(DIREKTORIN, DIREKTORIN));
        when(guard.requireLeadership(MITGLIED_OHNE_AMT))
                .thenThrow(new AccessDeniedException("kein Amt"));
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

    private static MiningTaxRate rate(Long typeId, String jitaBuy, String taxPercentage) {
        MiningTaxRate rate = new MiningTaxRate();
        rate.setTypeId(typeId);
        rate.setTypeName("Veldspar");
        rate.setCategory("ORE");
        rate.setCurrentJitaBuy(new BigDecimal(jitaBuy));
        rate.setTaxPercentage(new BigDecimal(taxPercentage));
        return rate;
    }

    private static CharacterActivity payment(Long characterId, String amount) {
        return CharacterActivity.of(characterId, ActivityType.TAX_PAYMENT,
                new BigDecimal(amount), Instant.now());
    }

    /**
     * Eine nachgetragene Gutschrift, wie sie {@code MiningTaxCreditService}
     * herausgibt - mit Begruender, Zeitpunkt und Grund, denn genau diese drei
     * Angaben muessen bis in die Monatszeile durchkommen.
     */
    private static MiningDtos.TaxCreditDto gutschrift(Long id, String amount, String reason) {
        return new MiningDtos.TaxCreditDto(id, MAIN_ID, "Pilot 1000", "portrait",
                new BigDecimal(amount), MiningTaxCredit.STATUS_ACTIVE, null,
                DIREKTORIN, "Pilot 100", false, reason, Instant.now());
    }

    private static MiningTaxInvoice invoice(Long accountId, String month, String totalTax) {
        MiningTaxInvoice invoice = new MiningTaxInvoice();
        invoice.setMainCharacterId(accountId);
        invoice.setMonth(month);
        invoice.setTotalTax(new BigDecimal(totalTax));
        invoice.setDetailsJson("[]");
        return invoice;
    }

    @Nested
    @DisplayName("Berechnung eines Monats")
    class MonthlyCalculation {

        @Test
        @DisplayName("liefert eine leere Bilanz, wenn nichts abgebaut und nichts gezahlt wurde")
        void emptyLedger() {
            MiningDtos.UserLedgerResponse ledger = service.ledgerOf(MAIN_ID);

            assertThat(ledger.months()).isEmpty();
            assertThat(ledger.totalDebt()).isEqualByComparingTo("0");
            assertThat(ledger.totalPaid()).isEqualByComparingTo("0");
            assertThat(ledger.currentBalance()).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("rechnet Menge mal Jita-Preis mal Steuersatz")
        void appliesTaxFormula() {
            // 1.000 Einheiten zu 10 ISK bei 10 % ergeben 1.000 ISK Steuer.
            when(miningRepo.findByCharacterIdIn(anyList()))
                    .thenReturn(List.of(mined(MAIN_ID, PAST_MONTH + "-15", VELDSPAR, 1_000)));
            when(taxRateService.findAllByTypeId()).thenReturn(Map.of(VELDSPAR, rate(VELDSPAR, "10.00", "10.000")));

            MiningDtos.UserLedgerResponse ledger = service.ledgerOf(MAIN_ID);

            assertThat(ledger.months()).hasSize(1);
            assertThat(ledger.months().getFirst().totalTax()).isEqualByComparingTo("1000.00");
            assertThat(ledger.totalDebt()).isEqualByComparingTo("1000.00");
        }

        @Test
        @DisplayName("haelt einen Betrag in Milliardenhoehe auf die ISK genau")
        void billionsStayExactToTheIsk() {
            // DER WICHTIGSTE TEST DIESER KLASSE.
            //
            // 87.654 Einheiten Mondgestein zu 210.200,55 ISK bei 10 Prozent sind
            // auf den Cent genau 1.842.491.900,97 ISK. Das ist die Zahl, die ein
            // Mitglied ueberweisen soll.
            //
            // OHNE DIE UMSTELLUNG AUF BigDecimal steht hier 1842491900.9700003 -
            // nachgerechnet, nicht vermutet. Die Steuerformel multipliziert einen
            // Preis mit Nachkommastellen erst mit einer fuenfstelligen Menge und
            // dann mit einem Satz, den ein double gar nicht darstellen kann
            // (10.0/100 ist binaer periodisch). Jeder Schritt haengt ein paar
            // Stellen an, die niemand gemeint hat, und sie stehen anschliessend in
            // einem Beleg, der ausdruecklich unveraenderlich sein soll. Genau so
            // ist "volume": 261.59999999999997 in eine eingefrorene Rechnung im
            // Bestand geraten.
            //
            // Der Betrag waechst mit der Corporation. Die ISK-Stelle selbst haelt
            // ein double bis rund 9*10^15 - das Argument ist nicht die Obergrenze,
            // sondern dass sich der Fehler ueber Monate und Posten aufsummiert.
            when(miningRepo.findByCharacterIdIn(anyList()))
                    .thenReturn(List.of(mined(MAIN_ID, PAST_MONTH + "-15", VELDSPAR, 87_654)));
            when(taxRateService.findAllByTypeId())
                    .thenReturn(Map.of(VELDSPAR, rate(VELDSPAR, "210200.55", "10.000")));

            MiningDtos.UserLedgerResponse ledger = service.ledgerOf(MAIN_ID);

            assertThat(ledger.totalDebt()).isEqualByComparingTo("1842491900.97");
            // Und zwar auf genau zwei Nachkommastellen, ohne Rest dahinter: ISK
            // hat ingame keine dritte Stelle, also darf auch keine entstehen.
            assertThat(ledger.months().getFirst().totalTax().scale()).isEqualTo(2);
        }

        @Test
        @DisplayName("rechnet ein Erz ohne Preis oder Satz mit null")
        void treatsMissingValuesAsZero() {
            when(miningRepo.findByCharacterIdIn(anyList()))
                    .thenReturn(List.of(mined(MAIN_ID, PAST_MONTH + "-15", VELDSPAR, 1_000)));
            MiningTaxRate incomplete = rate(VELDSPAR, "10.00", "10.000");
            incomplete.setCurrentJitaBuy(null);
            incomplete.setTaxPercentage(null);
            when(taxRateService.findAllByTypeId()).thenReturn(Map.of(VELDSPAR, incomplete));

            MiningDtos.UserLedgerResponse ledger = service.ledgerOf(MAIN_ID);

            assertThat(ledger.months().getFirst().totalTax()).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("zeigt ein Erz ohne hinterlegten Steuersatz steuerfrei an, statt einen anzulegen")
        void showsUnknownOreWithoutCreatingARate() {
            // OHNE DIESE REGEL schriebe ein GET eine Zeile in mining_tax_rates -
            // mitten im Lesepfad. Genau daran ist das @Transactional(readOnly)
            // gescheitert, das den viel schlimmeren Schreibzugriff verhindern
            // soll: das Einfrieren. Ein Typ ohne Satz kostet nichts, und
            // MiningTaxRateService.synchronizeWithSde traegt ihn beim naechsten
            // Start nach.
            when(miningRepo.findByCharacterIdIn(anyList()))
                    .thenReturn(List.of(mined(MAIN_ID, PAST_MONTH + "-15", 9999L, 10)));
            when(taxRateService.findAllByTypeId()).thenReturn(Map.of());

            MiningDtos.UserLedgerResponse ledger = service.ledgerOf(MAIN_ID);

            MiningDtos.LedgerItemDto posten = ledger.months().getFirst().details().getFirst();
            assertThat(posten.typeName()).isEqualTo("Unknown Ore (9999)");
            assertThat(posten.category()).isEqualTo("ORE");
            assertThat(posten.taxToPay()).isEqualByComparingTo("0");
            verify(taxRateService, never()).save(any());
        }

        @Test
        @DisplayName("kommt mit einem halb gepflegten Satz und einer Zahlung ohne Betrag zurecht")
        void toleratesIncompleteRows() {
            // Beides steht so im Bestand: ein Steuersatz, dessen Name und Klasse
            // nie nachgetragen wurden, und Aktivitaetszeilen ohne Wert. Ein NPE
            // mitten in der Steuerbilanz saehe fuer das Mitglied aus wie eine
            // kaputte Seite - und zwar auf der einen Seite, die ihm sagt, was es
            // schuldet.
            MiningTaxRate halbFertig = rate(VELDSPAR, "10.00", "10.000");
            halbFertig.setTypeName(null);
            halbFertig.setCategory(null);
            when(taxRateService.findAllByTypeId()).thenReturn(Map.of(VELDSPAR, halbFertig));
            when(miningRepo.findByCharacterIdIn(anyList()))
                    .thenReturn(List.of(mined(MAIN_ID, PAST_MONTH + "-15", VELDSPAR, 1_000)));

            CharacterActivity ohneBetrag = new CharacterActivity();
            ohneBetrag.setCharacterId(MAIN_ID);
            ohneBetrag.setType(ActivityType.TAX_PAYMENT);
            when(activityRepo.findByCharacterIdIn(anyList()))
                    .thenReturn(List.of(ohneBetrag, payment(MAIN_ID, "400")));

            MiningDtos.UserLedgerResponse ledger = service.ledgerOf(MAIN_ID);

            MiningDtos.LedgerItemDto posten = ledger.months().getFirst().details().getFirst();
            assertThat(posten.typeName()).isEqualTo("Unknown Ore (" + VELDSPAR + ")");
            assertThat(posten.category()).isEqualTo("ORE");
            assertThat(ledger.totalPaid()).isEqualByComparingTo("400.00");
        }

        @Test
        @DisplayName("weist das Volumen anhand des SDE-Stueckvolumens aus")
        void reportsVolumeFromSde() {
            when(miningRepo.findByCharacterIdIn(anyList()))
                    .thenReturn(List.of(mined(MAIN_ID, PAST_MONTH + "-15", VELDSPAR, 1_000)));
            when(taxRateService.findAllByTypeId()).thenReturn(Map.of(VELDSPAR, rate(VELDSPAR, "10.00", "10.000")));
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
                    1L, rate(1L, "10.00", "10.000"),
                    2L, rate(2L, "10.00", "10.000")));

            MiningDtos.UserLedgerResponse ledger = service.ledgerOf(MAIN_ID);

            List<MiningDtos.LedgerItemDto> details = ledger.months().getFirst().details();
            assertThat(details).hasSize(2);
            assertThat(details.getFirst().taxToPay()).isGreaterThan(details.get(1).taxToPay());
        }

        @Test
        @DisplayName("summiert die Mengen aller Charaktere eines Accounts")
        void sumsAcrossAccountCharacters() {
            when(miningRepo.findByCharacterIdIn(anyList())).thenReturn(List.of(
                    mined(MAIN_ID, PAST_MONTH + "-15", VELDSPAR, 600),
                    mined(ALT_ID, PAST_MONTH + "-16", VELDSPAR, 400)));
            when(taxRateService.findAllByTypeId()).thenReturn(Map.of(VELDSPAR, rate(VELDSPAR, "10.00", "10.000")));

            MiningDtos.UserLedgerResponse ledger = service.ledgerOf(MAIN_ID);

            assertThat(ledger.months().getFirst().details()).hasSize(1);
            assertThat(ledger.months().getFirst().details().getFirst().quantity()).isEqualTo(1_000);
        }

        @Test
        @DisplayName("ueberspringt Ledger-Zeilen ohne brauchbares Datum")
        void skipsEntriesWithoutDate() {
            when(miningRepo.findByCharacterIdIn(anyList())).thenReturn(List.of(
                    mined(MAIN_ID, null, VELDSPAR, 1_000),
                    mined(MAIN_ID, "2020", VELDSPAR, 1_000)));
            when(taxRateService.findAllByTypeId()).thenReturn(Map.of(VELDSPAR, rate(VELDSPAR, "10.00", "10.000")));

            assertThat(service.ledgerOf(MAIN_ID).months()).isEmpty();
        }
    }

    @Nested
    @DisplayName("Ein Lesezugriff schreibt nichts")
    class ReadsDoNotWrite {

        @Test
        @DisplayName("legt beim Abruf der eigenen Bilanz keine Rechnung an")
        void ownLedgerWritesNothing() {
            // OHNE DIESE REGEL fror MiningLedgerService.ledgerOf mitten im
            // Lesepfad eine Rechnung ein - ein GET schrieb. Zwei Folgen, beide
            // eingetreten: zwei gleichzeitige Abrufe liefen in
            // UNIQUE(main_character_id, month), und der erste Seitenaufruf nach
            // einem Monatswechsel schloss den Vormonat ab, bevor der ESI-Ledger
            // seine letzten Zeilen nachgeliefert hatte.
            //
            // Der Monat hier ist laengst abgeschlossen - genau der Fall, in dem
            // frueher geschrieben wurde.
            when(miningRepo.findByCharacterIdIn(anyList()))
                    .thenReturn(List.of(mined(MAIN_ID, PAST_MONTH + "-15", VELDSPAR, 1_000)));
            when(taxRateService.findAllByTypeId()).thenReturn(Map.of(VELDSPAR, rate(VELDSPAR, "10.00", "10.000")));

            service.ledgerOf(MAIN_ID);

            verify(invoiceRepo, never()).save(any());
            verify(invoiceRepo, never()).insertIfAbsent(anyLong(), anyString(), any(), any(), any());
            verify(taxRateService, never()).save(any());
        }

        @Test
        @DisplayName("legt auch beim Abruf der Admin-Akte keine Rechnung an")
        void memberLedgerWritesNothing() {
            when(miningRepo.findByCharacterIdIn(anyList()))
                    .thenReturn(List.of(mined(MAIN_ID, PAST_MONTH + "-15", VELDSPAR, 1_000)));
            when(taxRateService.findAllByTypeId()).thenReturn(Map.of(VELDSPAR, rate(VELDSPAR, "10.00", "10.000")));

            service.memberLedger(DIREKTORIN, MAIN_ID);

            verify(invoiceRepo, never()).save(any());
            verify(invoiceRepo, never()).insertIfAbsent(anyLong(), anyString(), any(), any(), any());
        }

        @Test
        @DisplayName("zwei gleichzeitige Abrufe desselben Nutzers kollidieren nicht")
        void twoConcurrentReadsDoNotCollide() throws Exception {
            // OHNE DIESE REGEL war das der wahrscheinlichste Weg in einen
            // HTTP 500: beide Transaktionen lesen die Rechnungen des Accounts und
            // finden den Monat nicht, beide rechnen, beide fuegen ein. Die zweite
            // blockiert bis zum Commit der ersten und scheitert dann an
            // UNIQUE(main_character_id, month) - mit einer
            // DataIntegrityViolationException auf einen reinen Lesevorgang.
            //
            // Es braucht dafuer nicht einmal einen Doppelklick: ein Director
            // oeffnet die Akte eines Mitglieds, waehrend das Mitglied seine
            // eigene Seite laedt. Zwei Menschen, zwei Endpunkte, derselbe Account,
            // derselbe Monat.
            when(miningRepo.findByCharacterIdIn(anyList()))
                    .thenReturn(List.of(mined(MAIN_ID, PAST_MONTH + "-15", VELDSPAR, 1_000)));
            when(taxRateService.findAllByTypeId()).thenReturn(Map.of(VELDSPAR, rate(VELDSPAR, "10.00", "10.000")));

            CyclicBarrier gleichzeitig = new CyclicBarrier(2);
            Callable<BigDecimal> abruf = () -> {
                gleichzeitig.await();
                return service.ledgerOf(MAIN_ID).totalDebt();
            };

            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Future<BigDecimal> ersterLeser = pool.submit(abruf);
                Future<BigDecimal> zweiterLeser = pool.submit(abruf);

                // Beide kommen durch, und beide sehen dieselbe Rechnung.
                assertThat(ersterLeser.get()).isEqualByComparingTo(zweiterLeser.get());
                assertThat(ersterLeser.get()).isEqualByComparingTo("1000.00");
            } finally {
                pool.shutdownNow();
            }

            // Und der eigentliche Beweis: nicht einmal ein Schreibversuch.
            verify(invoiceRepo, never()).save(any());
            verify(invoiceRepo, never()).insertIfAbsent(anyLong(), anyString(), any(), any(), any());
        }
    }

    @Nested
    @DisplayName("Einfrieren abgeschlossener Monate")
    class Freezing {

        @BeforeEach
        void oneAccount() {
            when(characterRepo.findAll()).thenReturn(new ArrayList<>(
                    List.of(character(MAIN_ID, MAIN_ID), character(ALT_ID, MAIN_ID))));
            when(taxRateService.findAllByTypeId()).thenReturn(Map.of(VELDSPAR, rate(VELDSPAR, "10.00", "10.000")));
        }

        @Test
        @DisplayName("friert einen Monat ein, der aus dem ESI-Fenster gefallen ist")
        void freezesSettledMonth() {
            when(miningRepo.findByCharacterIdIn(anyList()))
                    .thenReturn(List.of(mined(MAIN_ID, SETTLED_MONTH + "-15", VELDSPAR, 1_000)));

            assertThat(service.freezeDueMonths()).isEqualTo(1);

            ArgumentCaptor<BigDecimal> betrag = ArgumentCaptor.forClass(BigDecimal.class);
            ArgumentCaptor<String> details = ArgumentCaptor.forClass(String.class);
            verify(invoiceRepo).insertIfAbsent(eq(MAIN_ID), eq(SETTLED_MONTH), betrag.capture(),
                    details.capture(), any(Instant.class));
            assertThat(betrag.getValue()).isEqualByComparingTo("1000.00");
            assertThat(details.getValue()).contains("Veldspar");
        }

        @Test
        @DisplayName("laesst einen Monat in der Karenzzeit offen")
        void leavesMonthInGracePeriodOpen() {
            // OHNE DIESE REGEL waere der Vormonat abgeschlossen, bevor der
            // ESI-Mining-Ledger seine letzten Zeilen geliefert hat - er reicht
            // rund 30 Tage zurueck. Genau so fehlen einer Rechnung im Bestand
            // 482 Einheiten Erz, die am 18., 24. und 25. gefoerdert wurden.
            when(miningRepo.findByCharacterIdIn(anyList()))
                    .thenReturn(List.of(mined(MAIN_ID, GRACE_MONTH + "-15", VELDSPAR, 1_000)));

            assertThat(service.freezeDueMonths()).isZero();
            verify(invoiceRepo, never()).insertIfAbsent(anyLong(), anyString(), any(), any(), any());
        }

        @Test
        @DisplayName("laesst den laufenden Monat offen")
        void leavesCurrentMonthOpen() {
            when(miningRepo.findByCharacterIdIn(anyList()))
                    .thenReturn(List.of(mined(MAIN_ID, CURRENT_MONTH + "-01", VELDSPAR, 1_000)));

            assertThat(service.freezeDueMonths()).isZero();
        }

        @Test
        @DisplayName("schiebt einen Account mit ungueltigem Token auf")
        void postponesAccountWithInvalidToken() {
            // OHNE DIESE REGEL bekaeme ein Mitglied, dessen Anmeldung sechs
            // Wochen abgelaufen war, eine eingefrorene Rechnung ueber die Daten,
            // die nie abgeglichen wurden - und die wird danach nie wieder
            // angefasst.
            Character alt = character(ALT_ID, MAIN_ID);
            alt.setTokenInvalidSince(Instant.now());
            when(characterRepo.findAll())
                    .thenReturn(new ArrayList<>(List.of(character(MAIN_ID, MAIN_ID), alt)));
            when(miningRepo.findByCharacterIdIn(anyList()))
                    .thenReturn(List.of(mined(MAIN_ID, SETTLED_MONTH + "-15", VELDSPAR, 1_000)));

            assertThat(service.freezeDueMonths()).isZero();
            verify(invoiceRepo, never()).insertIfAbsent(anyLong(), anyString(), any(), any(), any());
        }

        @Test
        @DisplayName("uebergeht einen Monat, der bereits eine Rechnung hat")
        void skipsAlreadyFrozenMonth() {
            when(miningRepo.findByCharacterIdIn(anyList()))
                    .thenReturn(List.of(mined(MAIN_ID, SETTLED_MONTH + "-15", VELDSPAR, 1_000)));
            when(invoiceRepo.findByMainCharacterId(MAIN_ID))
                    .thenReturn(List.of(invoice(MAIN_ID, SETTLED_MONTH, "777.00")));

            assertThat(service.freezeDueMonths()).isZero();
            verify(invoiceRepo, never()).insertIfAbsent(anyLong(), anyString(), any(), any(), any());
        }

        @Test
        @DisplayName("bleibt harmlos, wenn die Rechnung im selben Augenblick von woanders entsteht")
        void toleratesConcurrentInsert() {
            // Das ist die Zusage des ON CONFLICT ... DO NOTHING: der Einschub
            // meldet 0 geschriebene Zeilen statt eine Constraint-Verletzung zu
            // werfen. Damit ist auch ein doppelt gestarteter Lauf harmlos - und
            // ein Fehlschlag hier wuerde den ganzen Lauf abbrechen, also auch
            // alle Accounts danach.
            when(miningRepo.findByCharacterIdIn(anyList()))
                    .thenReturn(List.of(mined(MAIN_ID, SETTLED_MONTH + "-15", VELDSPAR, 1_000)));
            when(invoiceRepo.insertIfAbsent(anyLong(), anyString(), any(), any(), any())).thenReturn(0);

            assertThatCode(() -> assertThat(service.freezeDueMonths()).isZero())
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("kommt ohne Rohdaten und mit unlesbarem Monat zurecht")
        void survivesEmptyAndUnreadableInput() {
            // Der leere Fall zuerst: kein Erz, nichts einzufrieren.
            assertThat(service.freezeDueMonths()).isZero();

            // Und ein Monatsschluessel, den YearMonth nicht deuten kann. Lieber
            // eine Rechnung zu spaet als eine ueber Daten, die niemand versteht.
            when(miningRepo.findByCharacterIdIn(anyList()))
                    .thenReturn(List.of(mined(MAIN_ID, "0000-99-15", VELDSPAR, 1_000)));

            assertThat(service.freezeDueMonths()).isZero();
        }

        @Test
        @DisplayName("nimmt einen vorhandenen Snapshot unveraendert und rechnet nicht neu")
        void usesExistingSnapshot() {
            when(invoiceRepo.findByMainCharacterId(MAIN_ID))
                    .thenReturn(List.of(invoice(MAIN_ID, PAST_MONTH, "777.00")));
            // Rohdaten mit einem voellig anderen Betrag - der Snapshot gewinnt.
            when(miningRepo.findByCharacterIdIn(anyList()))
                    .thenReturn(List.of(mined(MAIN_ID, PAST_MONTH + "-15", VELDSPAR, 1_000_000)));

            MiningDtos.UserLedgerResponse ledger = service.ledgerOf(MAIN_ID);

            assertThat(ledger.months().getFirst().totalTax()).isEqualByComparingTo("777.00");
        }

        @Test
        @DisplayName("zeigt einen Monat auch dann, wenn nur der Snapshot existiert")
        void snapshotWithoutRawData() {
            when(invoiceRepo.findByMainCharacterId(MAIN_ID))
                    .thenReturn(List.of(invoice(MAIN_ID, "2019-05", "500.00")));

            MiningDtos.UserLedgerResponse ledger = service.ledgerOf(MAIN_ID);

            assertThat(ledger.months()).hasSize(1);
            assertThat(ledger.totalDebt()).isEqualByComparingTo("500.00");
        }

        @Test
        @DisplayName("friert den Betrag auch dann ein, wenn die Aufschluesselung nicht schreibbar ist")
        void freezesTotalEvenWithoutDetails() {
            // Die Summe ist die Rechnung; die Aufschluesselung ist die Erklaerung
            // dazu. Geht das Schreiben der Erklaerung schief, waere es der
            // schlechteste aller Ausgaenge, den Monat deswegen gar nicht
            // einzufrieren - er bliebe dann fuer immer offen und wanderte mit
            // jedem Preisabgleich.
            tools.jackson.databind.ObjectMapper kaputt =
                    org.mockito.Mockito.mock(tools.jackson.databind.ObjectMapper.class);
            org.mockito.Mockito.when(kaputt.writeValueAsString(any()))
                    .thenThrow(new IllegalStateException("kein JSON"));
            service = new MiningLedgerService(characterRepo, miningRepo, activityRepo, invoiceRepo,
                    taxRateService, invTypeRepo, kaputt, creditService, guard);
            when(miningRepo.findByCharacterIdIn(anyList()))
                    .thenReturn(List.of(mined(MAIN_ID, SETTLED_MONTH + "-15", VELDSPAR, 1_000)));

            assertThat(service.freezeDueMonths()).isEqualTo(1);

            verify(invoiceRepo).insertIfAbsent(eq(MAIN_ID), eq(SETTLED_MONTH),
                    eq(new BigDecimal("1000.00")), eq("[]"), any(Instant.class));
        }

        @Test
        @DisplayName("zeigt den Monat auch, wenn die Aufschluesselung unlesbar ist")
        void survivesBrokenSnapshotDetails() {
            MiningTaxInvoice broken = invoice(MAIN_ID, PAST_MONTH, "500.00");
            broken.setDetailsJson("das ist kein JSON");
            when(invoiceRepo.findByMainCharacterId(MAIN_ID)).thenReturn(List.of(broken));

            MiningDtos.UserLedgerResponse ledger = service.ledgerOf(MAIN_ID);

            assertThat(ledger.months().getFirst().totalTax()).isEqualByComparingTo("500.00");
            assertThat(ledger.months().getFirst().details()).isEmpty();
        }
    }

    @Nested
    @DisplayName("Nachgelieferte Ledger-Zeilen")
    class LateArrivals {

        @Test
        @DisplayName("zaehlen in einem noch nicht eingefrorenen Monat weiterhin mit")
        void countTowardsAnUnfrozenMonth() {
            // DAS IST DER GANZE ZWECK DER KARENZZEIT. Der ESI-Mining-Ledger
            // reicht rund 30 Tage zurueck; ein Abgleich am 2. eines Monats bringt
            // regelmaessig noch Zeilen des Vormonats, und AssetSyncService
            // ergaenzt sie (er loescht nie). Solange der Monat nicht eingefroren
            // ist, wird er bei jedem Abruf neu aus den Rohdaten gerechnet - die
            // Nachzueglerin zaehlt also mit.
            //
            // OHNE DIESE REGEL - also mit einer Rechnung, die schon am 1. steht -
            // gewinnt der Snapshot fuer immer, und die Zeilen sind unwiderruflich
            // verloren. Im Bestand fehlen einer Rechnung dadurch 482 Einheiten
            // White Glaze, rund 10 Millionen ISK gegen einen ausgewiesenen
            // Monatsbetrag von 6,1 Millionen.
            when(taxRateService.findAllByTypeId())
                    .thenReturn(Map.of(VELDSPAR, rate(VELDSPAR, "10.00", "10.000")));
            when(miningRepo.findByCharacterIdIn(anyList()))
                    .thenReturn(List.of(mined(MAIN_ID, GRACE_MONTH + "-15", VELDSPAR, 1_000)));

            assertThat(service.ledgerOf(MAIN_ID).totalDebt()).isEqualByComparingTo("1000.00");

            // Der naechste Abgleich liefert eine Zeile vom 25. des Vormonats nach.
            when(miningRepo.findByCharacterIdIn(anyList())).thenReturn(List.of(
                    mined(MAIN_ID, GRACE_MONTH + "-15", VELDSPAR, 1_000),
                    mined(ALT_ID, GRACE_MONTH + "-25", VELDSPAR, 482)));

            MiningDtos.UserLedgerResponse danach = service.ledgerOf(MAIN_ID);

            assertThat(danach.totalDebt()).isEqualByComparingTo("1482.00");
            assertThat(danach.months().getFirst().details().getFirst().quantity()).isEqualTo(1_482);
        }
    }

    @Nested
    @DisplayName("Wasserfall-Verrechnung der Zahlungen")
    class Waterfall {

        private void twoPastMonthsOf1000Each() {
            when(miningRepo.findByCharacterIdIn(anyList())).thenReturn(List.of(
                    mined(MAIN_ID, "2020-01-15", VELDSPAR, 1_000),
                    mined(MAIN_ID, "2020-02-15", VELDSPAR, 1_000)));
            when(taxRateService.findAllByTypeId()).thenReturn(Map.of(VELDSPAR, rate(VELDSPAR, "10.00", "10.000")));
        }

        @Test
        @DisplayName("verrechnet vom aeltesten Monat an")
        void allocatesOldestFirst() {
            twoPastMonthsOf1000Each();
            when(activityRepo.findByCharacterIdIn(anyList())).thenReturn(List.of(payment(MAIN_ID, "1000")));

            MiningDtos.UserLedgerResponse ledger = service.ledgerOf(MAIN_ID);

            // Die Ausgabe steht neueste zuerst.
            MiningDtos.MonthlyLedgerDto january = ledger.months().get(1);
            MiningDtos.MonthlyLedgerDto february = ledger.months().getFirst();

            assertThat(january.month()).isEqualTo("2020-01");
            assertThat(january.taxPaid()).isEqualByComparingTo("1000.00");
            assertThat(january.isPaid()).isTrue();

            assertThat(february.month()).isEqualTo("2020-02");
            assertThat(february.taxPaid()).isEqualByComparingTo("0");
            assertThat(february.isPaid()).isFalse();
        }

        @Test
        @DisplayName("deckt mit einer Vorauszahlung auch spaetere Monate")
        void advancePaymentCoversLaterMonths() {
            twoPastMonthsOf1000Each();
            when(activityRepo.findByCharacterIdIn(anyList())).thenReturn(List.of(payment(MAIN_ID, "5000")));

            MiningDtos.UserLedgerResponse ledger = service.ledgerOf(MAIN_ID);

            assertThat(ledger.months()).allSatisfy(month -> assertThat(month.isPaid()).isTrue());
            assertThat(ledger.currentBalance()).isEqualByComparingTo("3000.00");
        }

        @Test
        @DisplayName("teilt eine zu kleine Zahlung anteilig zu")
        void partialPayment() {
            twoPastMonthsOf1000Each();
            when(activityRepo.findByCharacterIdIn(anyList())).thenReturn(List.of(payment(MAIN_ID, "1200")));

            MiningDtos.UserLedgerResponse ledger = service.ledgerOf(MAIN_ID);

            assertThat(ledger.months().get(1).taxPaid()).isEqualByComparingTo("1000.00");
            assertThat(ledger.months().getFirst().taxPaid()).isEqualByComparingTo("200.00");
            assertThat(ledger.months().getFirst().isPaid()).isFalse();
        }

        @Test
        @DisplayName("wertet einen Monat ab 95 Prozent Deckung als bezahlt")
        void toleratesRoundingBelowFullAmount() {
            when(miningRepo.findByCharacterIdIn(anyList()))
                    .thenReturn(List.of(mined(MAIN_ID, PAST_MONTH + "-15", VELDSPAR, 1_000)));
            when(taxRateService.findAllByTypeId()).thenReturn(Map.of(VELDSPAR, rate(VELDSPAR, "10.00", "10.000")));
            when(activityRepo.findByCharacterIdIn(anyList())).thenReturn(List.of(payment(MAIN_ID, "950")));

            assertThat(service.ledgerOf(MAIN_ID).months().getFirst().isPaid()).isTrue();
        }

        @Test
        @DisplayName("wertet knapp unter 95 Prozent noch als offen")
        void staysOpenJustBelowThreshold() {
            when(miningRepo.findByCharacterIdIn(anyList()))
                    .thenReturn(List.of(mined(MAIN_ID, PAST_MONTH + "-15", VELDSPAR, 1_000)));
            when(taxRateService.findAllByTypeId()).thenReturn(Map.of(VELDSPAR, rate(VELDSPAR, "10.00", "10.000")));
            when(activityRepo.findByCharacterIdIn(anyList())).thenReturn(List.of(payment(MAIN_ID, "949")));

            assertThat(service.ledgerOf(MAIN_ID).months().getFirst().isPaid()).isFalse();
        }

        @Test
        @DisplayName("zaehlt nur Steuerzahlungen, keine anderen Kennzahlen")
        void countsOnlyTaxPayments() {
            twoPastMonthsOf1000Each();
            when(activityRepo.findByCharacterIdIn(anyList())).thenReturn(List.of(
                    payment(MAIN_ID, "1000"),
                    CharacterActivity.of(MAIN_ID, ActivityType.PVE_ISK, 999_999, Instant.now())));

            assertThat(service.ledgerOf(MAIN_ID).totalPaid()).isEqualByComparingTo("1000.00");
        }

        @Test
        @DisplayName("summiert die Zahlungen aller Charaktere des Accounts")
        void sumsPaymentsAcrossAccount() {
            twoPastMonthsOf1000Each();
            when(activityRepo.findByCharacterIdIn(anyList()))
                    .thenReturn(List.of(payment(MAIN_ID, "600"), payment(ALT_ID, "400")));

            assertThat(service.ledgerOf(MAIN_ID).totalPaid()).isEqualByComparingTo("1000.00");
        }

        @Test
        @DisplayName("summiert Zahlungen mit Nachkommastellen ohne Drift")
        void sumsCentsWithoutDrift() {
            // OHNE DIESE REGEL sammelt sich der Fehler in der Summe: 0.1 + 0.2
            // ergibt in einem double 0.30000000000000004, und der Wasserfall
            // verteilt diese Zahl anschliessend auf die Monate. Im Bestand steht
            // deshalb eine PVE-Summe von 1319981075.6900005 - Nachkommastellen,
            // die keine Zahlung je hatte.
            twoPastMonthsOf1000Each();
            when(activityRepo.findByCharacterIdIn(anyList())).thenReturn(List.of(
                    payment(MAIN_ID, "0.10"), payment(MAIN_ID, "0.20"), payment(ALT_ID, "0.30")));

            assertThat(service.ledgerOf(MAIN_ID).totalPaid()).isEqualByComparingTo("0.60");
        }
    }

    /**
     * Eine Gutschrift ist ein NACHTRAG, keine Zuwendung.
     *
     * <p>Diese Klasse hat einmal das Gegenteil gesichert: Gutschriften liefen
     * nicht durch den Wasserfall, ein gedeckter Monat blieb OFFEN, und nur die
     * Aufforderung zur Ueberweisung verschwand. Die Begruendung dafuer war, eine
     * heute vergebene Gutschrift duerfe den Januar nicht rueckwirkend als bezahlt
     * ausweisen, weil fuer den Januar niemand etwas ueberwiesen habe - sie setzte
     * also geschenktes Geld voraus.</p>
     *
     * <p>So wird die Gutschrift hier nicht benutzt. Sie korrigiert: das Mitglied
     * HAT bezahlt, die Erkennung hat es nicht mitbekommen, jemand traegt es nach.
     * Damit dreht sich die Folgerung um, und die Zusicherung dieser Tests mit
     * ihr - ein gedeckter Monat ist bezahlt. Erhalten bleibt allein die
     * Sichtbarkeit der HERKUNFT: erkannte Zahlung und Nachtrag stehen getrennt in
     * der Monatszeile, mit Verweis auf die Buchung.</p>
     */
    @Nested
    @DisplayName("Nachgetragene Gutschriften")
    class BackfilledCredits {

        /** Ein einzelner offener Monat ueber 1.000 ISK Steuer, nichts gezahlt. */
        private void oneOpenMonthOf1000() {
            when(miningRepo.findByCharacterIdIn(anyList()))
                    .thenReturn(List.of(mined(MAIN_ID, PAST_MONTH + "-15", VELDSPAR, 1_000)));
            when(taxRateService.findAllByTypeId())
                    .thenReturn(Map.of(VELDSPAR, rate(VELDSPAR, "10.00", "10.000")));
        }

        private void twoMonths() {
            when(miningRepo.findByCharacterIdIn(anyList())).thenReturn(List.of(
                    mined(MAIN_ID, "2020-01-15", VELDSPAR, 1_000),
                    mined(MAIN_ID, "2020-02-15", VELDSPAR, 1_000)));
            when(taxRateService.findAllByTypeId())
                    .thenReturn(Map.of(VELDSPAR, rate(VELDSPAR, "10.00", "10.000")));
        }

        private void threeMonthsOf1000Each() {
            when(miningRepo.findByCharacterIdIn(anyList())).thenReturn(List.of(
                    mined(MAIN_ID, "2020-01-15", VELDSPAR, 1_000),
                    mined(MAIN_ID, "2020-02-15", VELDSPAR, 1_000),
                    mined(MAIN_ID, "2020-03-15", VELDSPAR, 1_000)));
            when(taxRateService.findAllByTypeId())
                    .thenReturn(Map.of(VELDSPAR, rate(VELDSPAR, "10.00", "10.000")));
        }

        /** Eine einzelne nachgetragene Buchung ueber diesen Betrag. */
        private void credited(String amount) {
            when(creditService.applicableFor(MAIN_ID)).thenReturn(
                    List.of(gutschrift(1L, amount, "hat per Contract bezahlt, nicht erkannt")));
        }

        @Test
        @DisplayName("macht einen Monat bezahlt, den sie ganz deckt")
        void creditMakesTheMonthPaid() {
            // OHNE DIESE REGEL bliebe der Monat auf OFFEN stehen, obwohl nichts
            // mehr zu tun ist - genau der Zustand, den die Oberflaeche mit
            // "Nichts zu tun, aber Status bleibt OFFEN" erklaeren musste. Ein
            // Satz, den niemand braucht: die Gutschrift traegt eine Zahlung nach,
            // die stattgefunden hat. Sie weiter als Schuld zu fuehren hiesse, an
            // einer Forderung festzuhalten, die die Fuehrung selbst fuer
            // beglichen erklaert hat - und das Mitglied haette keinen Weg mehr,
            // den Monat je zu schliessen.
            //
            // FRUEHER STAND HIER DAS GEGENTEIL: isPaid war ausdruecklich false,
            // weil "bezahlt" gleich "ueberwiesen" hiess. Diese Zusicherung galt
            // einer Zuwendung, nicht einer Korrektur.
            oneOpenMonthOf1000();
            credited("5000.00");

            MiningDtos.UserLedgerResponse ledger = service.ledgerOf(MAIN_ID);
            MiningDtos.MonthlyLedgerDto monat = ledger.months().getFirst();

            assertThat(monat.isPaid()).isTrue();
            assertThat(monat.amountDue()).isEqualByComparingTo("0");
            assertThat(monat.creditApplied()).isEqualByComparingTo("1000.00");

            // Und trotzdem NICHT ueberwiesen: das ist der Unterschied, der
            // sichtbar bleiben muss.
            assertThat(monat.taxPaid()).isEqualByComparingTo("0");
            assertThat(ledger.currentBalance()).isEqualByComparingTo("4000.00");
        }

        @Test
        @DisplayName("haelt erkannte Zahlung und Nachtrag in der Aufschluesselung auseinander")
        void recognisedPaymentAndBackfillStayDistinguishable() {
            // DAS IST DIE EINZIGE ZUSICHERUNG, DIE AUS DER ALTEN ENTSCHEIDUNG
            // BLEIBT. Beide Monate stehen auf "bezahlt" - im Status ist kein
            // Unterschied mehr, und es soll auch keiner sein.
            //
            // OHNE DIESE REGEL waere danach nicht mehr feststellbar, ob fuer einen
            // Monat wirklich Geld geflossen ist oder ob jemand ihn per Eintrag
            // geschlossen hat. Bei einer Rueckfrage - "wer hat den Februar
            // abgehakt und warum?" - bliebe nur eine Zahl ohne Herkunft. Der
            // Nachweis liegt in mining_tax_credits; er muss von der Monatszeile
            // aus erreichbar sein, sonst findet ihn niemand.
            twoMonths();
            when(activityRepo.findByCharacterIdIn(anyList()))
                    .thenReturn(List.of(payment(MAIN_ID, "1000")));
            credited("1000.00");

            MiningDtos.UserLedgerResponse ledger = service.ledgerOf(MAIN_ID);
            MiningDtos.MonthlyLedgerDto januar = ledger.months().get(1);
            MiningDtos.MonthlyLedgerDto februar = ledger.months().getFirst();

            assertThat(januar.isPaid()).isTrue();
            assertThat(februar.isPaid()).isTrue();

            // Der Januar: erkannt ueberwiesen, kein Nachtrag, kein Nachweis noetig.
            assertThat(januar.taxPaid()).isEqualByComparingTo("1000.00");
            assertThat(januar.creditApplied()).isEqualByComparingTo("0");
            assertThat(januar.appliedCredits()).isEmpty();

            // Der Februar: nachgetragen, und zwar von wem, wann und warum.
            assertThat(februar.taxPaid()).isEqualByComparingTo("0");
            assertThat(februar.creditApplied()).isEqualByComparingTo("1000.00");
            assertThat(februar.appliedCredits()).hasSize(1);

            MiningDtos.AppliedCreditDto nachweis = februar.appliedCredits().getFirst();
            assertThat(nachweis.creditId()).isEqualTo(1L);
            assertThat(nachweis.applied()).isEqualByComparingTo("1000.00");
            assertThat(nachweis.actorCharacterId()).isEqualTo(DIREKTORIN);
            assertThat(nachweis.actorName()).isEqualTo("Pilot 100");
            assertThat(nachweis.reason()).isEqualTo("hat per Contract bezahlt, nicht erkannt");
            assertThat(nachweis.occurredAt()).isNotNull();
        }

        @Test
        @DisplayName("nennt bei teilweiser Deckung den Restbetrag auf die ISK genau")
        void partialCreditLeavesTheRest() {
            // OHNE DIESE REGEL blieben nur zwei falsche Antworten uebrig: den
            // vollen Betrag fordern, obwohl eine Milliarde nachgetragen ist -
            // oder gar nichts fordern, obwohl 842 Mio wirklich fehlen. Der
            // Restbetrag ist keine Auslegungssache, er ist eine Subtraktion.
            //
            // 87.654 Einheiten zu 210.200,55 ISK bei 10 Prozent sind
            // 1.842.491.900,97 ISK. Abzueglich einer Gutschrift von genau einer
            // Milliarde bleiben 842.491.900,97 ISK - auf den Cent, denn in einem
            // double waere schon die Steuer 1842491900.9700003.
            //
            // Und der Monat bleibt OFFEN: 54 Prozent Deckung sind keine
            // beglichene Rechnung. Die Schwelle gilt fuer beide Quellen gleich.
            when(miningRepo.findByCharacterIdIn(anyList()))
                    .thenReturn(List.of(mined(MAIN_ID, PAST_MONTH + "-15", VELDSPAR, 87_654)));
            when(taxRateService.findAllByTypeId())
                    .thenReturn(Map.of(VELDSPAR, rate(VELDSPAR, "210200.55", "10.000")));
            credited("1000000000.00");

            MiningDtos.MonthlyLedgerDto monat = service.ledgerOf(MAIN_ID).months().getFirst();

            assertThat(monat.creditApplied()).isEqualByComparingTo("1000000000.00");
            assertThat(monat.amountDue()).isEqualByComparingTo("842491900.97");
            assertThat(monat.isPaid()).isFalse();
        }

        @Test
        @DisplayName("schliesst einen Monat in Milliardenhoehe auf den Cent genau")
        void billionCreditClosesTheMonthExactly() {
            // DER TEUERSTE FALL: ein Nachtrag ueber 1.842.491.900,97 ISK deckt
            // exakt die Monatssteuer. Der Rest muss NULL sein, nicht 0,000001.
            //
            // OHNE BigDecimal auf beiden Seiten stuende die Steuer als
            // 1842491900.9700003 da, der Nachtrag als 1842491900.97 - und der
            // Monat forderte einen Bruchteil eines Cents nach, waere also weiter
            // "es fehlt noch etwas". Ein Betrag, den niemand ueberweisen kann,
            // haelt einen Monat fuer immer offen.
            when(miningRepo.findByCharacterIdIn(anyList()))
                    .thenReturn(List.of(mined(MAIN_ID, PAST_MONTH + "-15", VELDSPAR, 87_654)));
            when(taxRateService.findAllByTypeId())
                    .thenReturn(Map.of(VELDSPAR, rate(VELDSPAR, "210200.55", "10.000")));
            credited("1842491900.97");

            MiningDtos.UserLedgerResponse ledger = service.ledgerOf(MAIN_ID);
            MiningDtos.MonthlyLedgerDto monat = ledger.months().getFirst();

            assertThat(monat.creditApplied()).isEqualByComparingTo("1842491900.97");
            assertThat(monat.amountDue()).isEqualByComparingTo("0");
            assertThat(monat.isPaid()).isTrue();
            assertThat(ledger.currentBalance()).isEqualByComparingTo("0");
            assertThat(monat.appliedCredits().getFirst().applied())
                    .isEqualByComparingTo("1842491900.97");
        }

        @Test
        @DisplayName("laesst ein Konto im Minus unveraendert zur Ueberweisung auffordern")
        void withoutCreditNothingChanges() {
            // Der Regelfall, und er darf sich nicht verschoben haben: ohne
            // Nachtrag ist der faellige Betrag genau das, was nach den
            // Ueberweisungen offen steht. Waere hier etwas anderes zu lesen,
            // haette die Zusammenlegung den Zahlungs-Wasserfall angefasst.
            twoMonths();
            when(activityRepo.findByCharacterIdIn(anyList()))
                    .thenReturn(List.of(payment(MAIN_ID, "1200")));

            MiningDtos.UserLedgerResponse ledger = service.ledgerOf(MAIN_ID);
            MiningDtos.MonthlyLedgerDto januar = ledger.months().get(1);
            MiningDtos.MonthlyLedgerDto februar = ledger.months().getFirst();

            assertThat(januar.amountDue()).isEqualByComparingTo("0");
            assertThat(februar.amountDue()).isEqualByComparingTo("800.00");
            assertThat(ledger.months()).allSatisfy(monat -> {
                assertThat(monat.creditApplied()).isEqualByComparingTo("0");
                assertThat(monat.appliedCredits()).isEmpty();
            });

            // Die Probe: was insgesamt zu ueberweisen ist, ist das Minus des
            // Saldos - sonst zeigen Kopfzeile und Monatsliste verschiedene
            // Schulden an.
            assertThat(sumOfDue(ledger)).isEqualByComparingTo(ledger.currentBalance().negate());
        }

        @Test
        @DisplayName("traegt bei mehreren offenen Monaten von alt nach neu nach")
        void scarceCreditGoesToTheOldestMonthsFirst() {
            // OHNE EINE FESTE REIHENFOLGE waere die Verteilung von der
            // Reihenfolge einer HashMap abhaengig, und derselbe Account saehe bei
            // zwei Abrufen zwei verschiedene Monate als bezahlt - der Status
            // haengt jetzt daran, nicht mehr nur die Aufforderung. Von alt nach
            // neu ist die einzige Reihenfolge, die sich erklaeren laesst: die
            // aelteste Schuld zuerst, dieselbe Richtung wie beim Wasserfall der
            // Zahlungen. "Neueste zuerst" liesse ausgerechnet den aeltesten
            // Rueckstand stehen.
            threeMonthsOf1000Each();
            credited("2500.00");

            MiningDtos.UserLedgerResponse ledger = service.ledgerOf(MAIN_ID);
            MiningDtos.MonthlyLedgerDto januar = ledger.months().get(2);
            MiningDtos.MonthlyLedgerDto februar = ledger.months().get(1);
            MiningDtos.MonthlyLedgerDto maerz = ledger.months().getFirst();

            assertThat(januar.month()).isEqualTo("2020-01");
            assertThat(januar.creditApplied()).isEqualByComparingTo("1000.00");
            assertThat(januar.isPaid()).isTrue();
            assertThat(januar.amountDue()).isEqualByComparingTo("0");

            assertThat(februar.creditApplied()).isEqualByComparingTo("1000.00");
            assertThat(februar.isPaid()).isTrue();
            assertThat(februar.amountDue()).isEqualByComparingTo("0");

            // Der Rest des Topfes - zu wenig fuer den Maerz, also bleibt er offen
            // und fordert genau die Luecke ein.
            assertThat(maerz.month()).isEqualTo("2020-03");
            assertThat(maerz.creditApplied()).isEqualByComparingTo("500.00");
            assertThat(maerz.isPaid()).isFalse();
            assertThat(maerz.amountDue()).isEqualByComparingTo("500.00");

            // Und die Summe stimmt gegen den Saldo: 2.500 Nachtrag auf 3.000
            // Steuer sind 500 im Minus.
            assertThat(ledger.currentBalance()).isEqualByComparingTo("-500.00");
            assertThat(sumOfDue(ledger)).isEqualByComparingTo("500.00");
        }

        @Test
        @DisplayName("bleibt nachweisbar, auch wenn eine Buchung zwei Monate deckt")
        void oneBookingCanCoverTwoMonths() {
            // Eine Gutschrift ist ein Topf und kein Monatsbetrag - 1.500 ISK
            // decken den Januar ganz und den Februar zur Haelfte.
            //
            // OHNE DEN RESTBETRAG JE BUCHUNG blieben zwei falsche Wege: dieselbe
            // Buchung in beiden Monaten voll anrechnen - dann waere das Geld
            // zweimal ausgegeben - oder sie ganz dem Januar zuschlagen, dann
            // fehlten dem Februar 500. Deshalb steht in der Zeile, was von der
            // Buchung HIER gilt (applied), und daneben, wie gross sie insgesamt
            // war (amount); sonst liest sich der Februar wie ein zweiter
            // Nachtrag ueber 1.500 ISK.
            twoMonths();
            credited("1500.00");

            MiningDtos.UserLedgerResponse ledger = service.ledgerOf(MAIN_ID);
            MiningDtos.AppliedCreditDto imJanuar = ledger.months().get(1).appliedCredits().getFirst();
            MiningDtos.AppliedCreditDto imFebruar =
                    ledger.months().getFirst().appliedCredits().getFirst();

            assertThat(imJanuar.creditId()).isEqualTo(imFebruar.creditId());
            assertThat(imJanuar.applied()).isEqualByComparingTo("1000.00");
            assertThat(imFebruar.applied()).isEqualByComparingTo("500.00");
            assertThat(imFebruar.amount()).isEqualByComparingTo("1500.00");

            assertThat(ledger.months().getFirst().isPaid()).isFalse();
            assertThat(sumOfDue(ledger)).isEqualByComparingTo("500.00");
        }

        @Test
        @DisplayName("ruehrt einen laengst ueberwiesenen Monat auch bei neuer Gutschrift nicht an")
        void aSettledMonthIsNotTouchedByANewCredit() {
            // Innerhalb eines Monats deckt ERST die erkannte Zahlung, DANN der
            // Nachtrag. Der Januar ist ueberwiesen, es steht nichts mehr offen -
            // also verbraucht er keine Buchung, und der ganze Nachtrag geht an
            // den Februar, der wirklich offen ist.
            //
            // OHNE DIESE REIHENFOLGE stuende die Gutschrift vor der Zahlung: der
            // Januar erschiene als nachgetragen, obwohl fuer ihn nachweislich
            // Geld geflossen ist, und der Februar bekaeme nur den Rest und
            // forderte Geld ein, das da ist. Der Status waere in beiden Faellen
            // derselbe - falsch waere die Herkunft, und die ist der Grund, warum
            // die Aufschluesselung ueberhaupt existiert.
            twoMonths();
            when(activityRepo.findByCharacterIdIn(anyList()))
                    .thenReturn(List.of(payment(MAIN_ID, "1000")));
            credited("5000.00");

            MiningDtos.UserLedgerResponse ledger = service.ledgerOf(MAIN_ID);
            MiningDtos.MonthlyLedgerDto januar = ledger.months().get(1);
            MiningDtos.MonthlyLedgerDto februar = ledger.months().getFirst();

            assertThat(januar.isPaid()).isTrue();
            assertThat(januar.taxPaid()).isEqualByComparingTo("1000.00");
            assertThat(januar.creditApplied()).isEqualByComparingTo("0");
            assertThat(januar.appliedCredits()).isEmpty();
            assertThat(januar.amountDue()).isEqualByComparingTo("0");

            assertThat(februar.isPaid()).isTrue();
            assertThat(februar.creditApplied()).isEqualByComparingTo("1000.00");
            assertThat(februar.amountDue()).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("traegt denselben Nachtrag auch in die Akte der Fuehrung")
        void leadershipSeesTheSameNumbers() {
            // Zwei Bildschirme, dieselbe Rechnung. Waere der Nachtrag nur in der
            // Eigensicht gerechnet, mahnte ein Director einen Rueckstand an, den
            // das Mitglied bei sich als bezahlt sieht.
            oneOpenMonthOf1000();
            credited("5000.00");

            MiningDtos.MonthlyLedgerDto monat =
                    service.memberLedger(DIREKTORIN, MAIN_ID).months().getFirst();

            assertThat(monat.creditApplied()).isEqualByComparingTo("1000.00");
            assertThat(monat.amountDue()).isEqualByComparingTo("0");
            assertThat(monat.isPaid()).isTrue();
            assertThat(monat.appliedCredits()).hasSize(1);
        }

        private BigDecimal sumOfDue(MiningDtos.UserLedgerResponse ledger) {
            return ledger.months().stream()
                    .map(MiningDtos.MonthlyLedgerDto::amountDue)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
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
        }

        @Test
        @DisplayName("fasst Main und Alts zu einer Zeile zusammen")
        void groupsByAccount() {
            List<MiningDtos.AdminLedgerSummaryDto> summaries = service.allAccountSummaries(DIREKTORIN);

            assertThat(summaries).hasSize(1);
            assertThat(summaries.getFirst().mainId()).isEqualTo(MAIN_ID);
            assertThat(summaries.getFirst().mainName()).isEqualTo("Pilot 1000");
            assertThat(summaries.getFirst().portraitUrl()).contains("/characters/1000/portrait");
        }

        @Test
        @DisplayName("addiert eingefrorene Monate und die noch offenen")
        void addsFrozenAndUnfrozenTax() {
            // OHNE DIESE REGEL zeigte die Uebersicht systematisch zu wenig. Sie
            // summierte die eingefrorenen Rechnungen und rechnete nur den
            // LAUFENDEN Monat live dazu. Seit das Einfrieren eine Karenzzeit hat,
            // bleibt ein vergangener Monat regulaer bis zu rund 32 Tage ohne
            // Rechnung - er stuende hier mit null Steuer, waehrend die Akte
            // desselben Accounts den richtigen Betrag zeigt. Dann streiten zwei
            // Bildschirme ueber dieselbe Zahl.
            when(invoiceRepo.findByMainCharacterId(MAIN_ID))
                    .thenReturn(List.of(invoice(MAIN_ID, PAST_MONTH, "500.00")));
            when(miningRepo.findByCharacterIdIn(anyList()))
                    .thenReturn(List.of(mined(ALT_ID, GRACE_MONTH + "-01", VELDSPAR, 1_000)));
            when(taxRateService.findAllByTypeId()).thenReturn(Map.of(VELDSPAR, rate(VELDSPAR, "10.00", "10.000")));

            MiningDtos.AdminLedgerSummaryDto summary = service.allAccountSummaries(DIREKTORIN).getFirst();

            assertThat(summary.totalTax()).isEqualByComparingTo("1500.00");
        }

        @Test
        @DisplayName("laesst ein Erz ohne Steuersatz aussen vor")
        void ignoresMiningWithoutRate() {
            when(miningRepo.findByCharacterIdIn(anyList()))
                    .thenReturn(List.of(mined(MAIN_ID, CURRENT_MONTH + "-01", 9999L, 1_000)));

            assertThat(service.allAccountSummaries(DIREKTORIN).getFirst().totalTax())
                    .isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("zieht die Zahlungen vom Soll ab")
        void computesBalance() {
            when(invoiceRepo.findByMainCharacterId(MAIN_ID))
                    .thenReturn(List.of(invoice(MAIN_ID, PAST_MONTH, "1000.00")));
            when(activityRepo.findByCharacterIdIn(anyList())).thenReturn(List.of(payment(ALT_ID, "400")));

            MiningDtos.AdminLedgerSummaryDto summary = service.allAccountSummaries(DIREKTORIN).getFirst();

            assertThat(summary.totalPaid()).isEqualByComparingTo("400.00");
            assertThat(summary.currentBalance()).isEqualByComparingTo("-600.00");
        }

        @Test
        @DisplayName("stellt das groesste Minus nach oben")
        void sortsByBalanceAscending() {
            when(characterRepo.findAll()).thenReturn(new ArrayList<>(
                    List.of(character(MAIN_ID, MAIN_ID), character(2000L, 2000L))));
            when(invoiceRepo.findByMainCharacterId(2000L))
                    .thenReturn(List.of(invoice(2000L, PAST_MONTH, "5000.00")));

            List<MiningDtos.AdminLedgerSummaryDto> summaries = service.allAccountSummaries(DIREKTORIN);

            assertThat(summaries).hasSize(2);
            assertThat(summaries.getFirst().mainId()).isEqualTo(2000L);
            assertThat(summaries.getFirst().currentBalance()).isEqualByComparingTo("-5000.00");
        }

        @Test
        @DisplayName("nimmt den Namen eines Alts, wenn der Main-Datensatz fehlt")
        void fallsBackToFirstCharacterName() {
            when(characterRepo.findAll())
                    .thenReturn(new ArrayList<>(List.of(character(ALT_ID, MAIN_ID))));

            assertThat(service.allAccountSummaries(DIREKTORIN).getFirst().mainName()).isEqualTo("Pilot 1001");
        }

        @Test
        @DisplayName("weist die Bilanz einem gewoehnlichen Mitglied ab")
        void deniesBalanceToPlainMember() {
            // OHNE DIESE REGEL saehe jedes Mitglied, was jedes andere schuldet -
            // die Schulden der ganzen Corporation stehen in dieser einen Liste.
            // Die Annotation am Endpunkt allein reicht dafuer nicht: sie faellt
            // bei einem Umbau lautlos weg und schuetzt einen zweiten Aufrufer gar
            // nicht.
            assertThatThrownBy(() -> service.allAccountSummaries(MITGLIED_OHNE_AMT))
                    .isInstanceOf(AccessDeniedException.class);

            verify(characterRepo, never()).findAll();
        }

        @Test
        @DisplayName("rechnet die Gutschriften in den Saldo ein")
        void foldsCreditsIntoBalance() {
            // OHNE DIESE REGEL zeigte die Bilanz weiter ein Minus, das laengst
            // ausgeglichen ist, und jemand liefe einem Mitglied wegen Geld
            // hinterher, das er ihm selbst zugesprochen hat.
            when(invoiceRepo.findByMainCharacterId(MAIN_ID))
                    .thenReturn(List.of(invoice(MAIN_ID, PAST_MONTH, "1000.00")));
            when(creditService.applicableByAccount())
                    .thenReturn(Map.of(MAIN_ID, List.of(gutschrift(1L, "400.00", "Nachtrag"))));

            MiningDtos.AdminLedgerSummaryDto summary = service.allAccountSummaries(DIREKTORIN).getFirst();

            assertThat(summary.totalCredited()).isEqualByComparingTo("400.00");
            assertThat(summary.currentBalance()).isEqualByComparingTo("-600.00");
        }
    }

    @Nested
    @DisplayName("Steuerakte eines Members")
    class MemberLedger {

        @Test
        @DisplayName("weist ein gewoehnliches Mitglied ab")
        void deniesPlainMember() {
            // OHNE DIESE REGEL koennte jedes Mitglied die Steuerakte jedes
            // anderen abrufen - wer wieviel geschuerft hat und was er schuldet.
            // Die Pruefung sitzt im Dienst, damit sie auch dann greift, wenn der
            // Endpunkt einmal anders zugeschnitten wird.
            assertThatThrownBy(() -> service.memberLedger(MITGLIED_OHNE_AMT, MAIN_ID))
                    .isInstanceOf(AccessDeniedException.class);

            verify(miningRepo, never()).findByCharacterIdIn(anyList());
        }

        @Test
        @DisplayName("schluesselt die Steuer nach Erz auf, und die Anteile ergeben die Summe")
        void compositionAddsUpToTotal() {
            // DAS IST DIE ZUSAGE DER GANZEN ANSICHT: was die Fuehrung an
            // Einzelposten sieht, muss die Rechnung ergeben, die das Mitglied
            // bezahlt - auf den Cent, nicht ungefaehr.
            //
            // Alle drei Posten haben eine exakte Steuer mit mehr als zwei
            // Nachkommastellen (35,36127 / 154,3788 / 244,34541 ISK), werden also
            // einzeln gerundet. Die Monatssumme ist die Summe dieser gerundeten
            // Posten und nicht die gerundete Summe der exakten - sonst traegt der
            // letzte Posten die Differenz, und wer nachrechnet, kommt auf eine
            // andere Zahl als die Rechnung.
            //
            // OHNE BigDecimal kam die Abweichung von woanders: die Summe lief
            // waehrend des Aufbaus mit, also in der Reihenfolge der HashMap, und
            // Gleitkommaaddition ist nicht assoziativ.
            when(miningRepo.findByCharacterIdIn(anyList())).thenReturn(List.of(
                    mined(MAIN_ID, PAST_MONTH + "-15", 1L, 777),
                    mined(ALT_ID, PAST_MONTH + "-16", 2L, 555),
                    mined(ALT_ID, PAST_MONTH + "-17", 3L, 333)));
            when(taxRateService.findAllByTypeId()).thenReturn(Map.of(
                    1L, rate(1L, "1.23", "3.700"),
                    2L, rate(2L, "4.56", "6.100"),
                    3L, rate(3L, "7.89", "9.300")));

            MiningDtos.AdminMemberLedgerDto akte = service.memberLedger(DIREKTORIN, MAIN_ID);

            MiningDtos.MonthlyLedgerDto monat = akte.months().getFirst();
            assertThat(monat.details()).hasSize(3);
            assertThat(monat.details()).extracting(MiningDtos.LedgerItemDto::taxToPay)
                    .containsExactly(new BigDecimal("244.35"), new BigDecimal("154.38"),
                            new BigDecimal("35.36"));

            BigDecimal summeDerAnteile = monat.details().stream()
                    .map(MiningDtos.LedgerItemDto::taxToPay)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            assertThat(summeDerAnteile).isEqualByComparingTo(monat.totalTax());
            assertThat(monat.totalTax()).isEqualByComparingTo("434.09");
            assertThat(akte.totalTax()).isEqualByComparingTo(monat.totalTax());
        }

        @Test
        @DisplayName("nennt je Erz Menge und Steueranteil")
        void reportsQuantityAndShare() {
            when(miningRepo.findByCharacterIdIn(anyList()))
                    .thenReturn(List.of(mined(MAIN_ID, PAST_MONTH + "-15", VELDSPAR, 1_000)));
            when(taxRateService.findAllByTypeId()).thenReturn(Map.of(VELDSPAR, rate(VELDSPAR, "10.00", "10.000")));

            MiningDtos.LedgerItemDto posten =
                    service.memberLedger(DIREKTORIN, MAIN_ID).months().getFirst().details().getFirst();

            assertThat(posten.typeName()).isEqualTo("Veldspar");
            assertThat(posten.quantity()).isEqualTo(1_000);
            assertThat(posten.jitaPrice()).isEqualByComparingTo("10.00");
            assertThat(posten.taxToPay()).isEqualByComparingTo("1000.00");
        }

        @Test
        @DisplayName("legt den Gutschriftenverlauf des Members bei")
        void includesCreditHistory() {
            // Die Fuehrung soll beim Klick auf ein Mitglied nicht nur die Schuld
            // sehen, sondern auch, was ihm schon zugesprochen wurde - sonst wird
            // derselbe Betrag ein zweites Mal vergeben.
            when(creditService.applicableFor(MAIN_ID))
                    .thenReturn(List.of(gutschrift(1L, "250.00", "Nachtrag")));

            MiningDtos.AdminMemberLedgerDto akte = service.memberLedger(DIREKTORIN, MAIN_ID);

            verify(creditService).historyForChecked(MAIN_ID);
            assertThat(akte.totalCredited()).isEqualByComparingTo("250.00");
            assertThat(akte.currentBalance()).isEqualByComparingTo("250.00");
        }

        @Test
        @DisplayName("loest die ID eines Alts auf den Account auf")
        void resolvesAltToAccount() {
            // Wer in der Uebersicht klickt, schickt die Account-ID. Kommt
            // trotzdem die ID eines Alts an, muss die Akte des Verbunds
            // herauskommen: eine getrennte Akte je Alt gaebe es nicht, die
            // Steuer wird ueber den Verbund gefuehrt.
            when(characterRepo.findById(ALT_ID)).thenReturn(Optional.of(character(ALT_ID, MAIN_ID)));

            MiningDtos.AdminMemberLedgerDto akte = service.memberLedger(DIREKTORIN, ALT_ID);

            assertThat(akte.accountId()).isEqualTo(MAIN_ID);
            assertThat(akte.accountName()).isEqualTo("Pilot 1000");
            assertThat(akte.portraitUrl()).contains("/characters/1000/portrait");
        }

        @Test
        @DisplayName("nimmt einen Main mit, dessen main_character_id leer ist")
        void includesMainWithoutOwnMainId() {
            // Der Datenbestand kennt beide Schreibweisen (siehe
            // Character.getAccountId). OHNE DIESE REGEL fiele ein Main der
            // zweiten Sorte aus seiner eigenen Akte heraus: findByMainCharacterId
            // findet ihn nicht, seine Erze zaehlten nicht, und die Uebersicht -
            // die ueber getAccountId gruppiert - zeigte fuer denselben Account
            // eine andere Zahl als die Akte.
            Character einzelgaenger = character(3000L, null);
            when(characterRepo.findById(3000L)).thenReturn(Optional.of(einzelgaenger));
            when(characterRepo.findByMainCharacterId(3000L)).thenReturn(List.of());

            service.memberLedger(DIREKTORIN, 3000L);

            ArgumentCaptor<List<Long>> ids = ArgumentCaptor.forClass(List.class);
            verify(miningRepo).findByCharacterIdIn(ids.capture());
            assertThat(ids.getValue()).containsExactly(3000L);
        }
    }
}
