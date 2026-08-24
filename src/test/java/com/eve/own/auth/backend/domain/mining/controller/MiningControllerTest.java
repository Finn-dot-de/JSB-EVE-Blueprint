package com.eve.own.auth.backend.domain.mining.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.eve.own.auth.backend.domain.mining.dto.MiningDtos;
import com.eve.own.auth.backend.domain.mining.entity.MiningTaxCredit;
import com.eve.own.auth.backend.domain.mining.entity.MiningTaxRate;
import com.eve.own.auth.backend.domain.mining.service.MiningLeaderboardService;
import com.eve.own.auth.backend.domain.mining.service.MiningLedgerService;
import com.eve.own.auth.backend.domain.mining.service.MiningTaxCreditService;
import com.eve.own.auth.backend.domain.mining.service.MiningTaxRateService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Die Mining-Endpunkte.
 *
 * <p>Bis hierher gab es fuer diesen Controller keinen Test - deshalb konnte ein
 * {@code /admin/member/mining/ore/composition} monatelang im Arbeitsverzeichnis
 * stehen, das sein eigenes {@code ResponseEntity} auf eine {@code List} umbog
 * und bei jedem Aufruf eine {@code ClassCastException} geworfen haette. Der
 * Uebersetzer laesst so einen Cast durch; nur ein Aufruf faellt darauf herein.
 * Dieser Test ruft jeden Endpunkt mindestens einmal auf.</p>
 *
 * <p>Der zweite Zweck ist die Frage, wer der Handelnde ist. Bei den Gutschriften
 * entscheidet sich daran, wessen Name im Nachweis steht - deshalb wird hier
 * ausdruecklich geprueft, dass er aus dem Sicherheitskontext kommt und nicht aus
 * dem Rumpf der Anfrage.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Mining-Endpunkte")
class MiningControllerTest {

    private static final Long ANGEMELDET = 100L;
    private static final Long MEMBER = 1000L;

    @Mock private MiningLedgerService ledgerService;
    @Mock private MiningLeaderboardService leaderboardService;
    @Mock private MiningTaxRateService taxRateService;
    @Mock private MiningTaxCreditService creditService;

    private MiningController controller;

    @BeforeEach
    void setUp() {
        controller = new MiningController(ledgerService, leaderboardService, taxRateService,
                creditService);

        // So setzt der JwtAuthenticationFilter das Principal: die Charakter-ID.
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(ANGEMELDET, null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static MiningDtos.TaxCreditDto creditDto(BigDecimal amount, String status) {
        return new MiningDtos.TaxCreditDto(1L, MEMBER, "Pilot 1000", "portrait", amount, status,
                null, ANGEMELDET, "Pilot 100", false, null, Instant.now());
    }

    @Nested
    @DisplayName("Eigene Sicht")
    class OwnViews {

        @Test
        @DisplayName("gibt die eigene Bilanz des angemeldeten Charakters zurueck")
        void ownLedger() {
            MiningDtos.UserLedgerResponse antwort = new MiningDtos.UserLedgerResponse(
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, List.of());
            when(ledgerService.ledgerOf(ANGEMELDET)).thenReturn(antwort);

            assertThat(controller.getMyLedger().getBody()).isSameAs(antwort);
            verify(ledgerService).ledgerOf(ANGEMELDET);
        }

        @Test
        @DisplayName("reicht den Monat an die Rangliste durch")
        void leaderboard() {
            MiningDtos.MiningLeaderboardDto antwort =
                    new MiningDtos.MiningLeaderboardDto("2026-08", List.of(), 0, 0, List.of());
            when(leaderboardService.leaderboard(anyString(), anyLong())).thenReturn(antwort);

            assertThat(controller.getLeaderboard("2026-08").getBody()).isSameAs(antwort);
            verify(leaderboardService).leaderboard("2026-08", ANGEMELDET);
        }
    }

    @Nested
    @DisplayName("Steuersaetze")
    class TaxRates {

        @Test
        @DisplayName("reicht Lesen, Speichern, Loeschen und Massenaenderung durch")
        void delegatesRateManagement() {
            MiningTaxRate rate = new MiningTaxRate();
            when(taxRateService.findAll()).thenReturn(List.of(rate));
            when(taxRateService.save(rate)).thenReturn(rate);

            assertThat(controller.getTaxRates().getBody()).containsExactly(rate);
            assertThat(controller.saveTaxRate(rate).getBody()).isSameAs(rate);

            controller.deleteTaxRate(1230L);
            verify(taxRateService).delete(1230L);

            controller.updateBulkTax("ORE", new BigDecimal("10.000"));
            verify(taxRateService).updateCategory("ORE", new BigDecimal("10.000"));
        }
    }

    @Nested
    @DisplayName("Einsicht der Fuehrung")
    class LeadershipViews {

        @Test
        @DisplayName("fragt die Bilanz mit dem angemeldeten Charakter als Handelndem ab")
        void summariesCarryTheActor() {
            // Der Dienst prueft die Berechtigung selbst und braucht dafuer, WER
            // fragt. Kaeme die ID aus dem Rumpf oder der Adresszeile, koennte sich
            // jeder als Director ausgeben.
            when(ledgerService.allAccountSummaries(anyLong())).thenReturn(List.of());

            controller.getAllLedgersSummary();

            verify(ledgerService).allAccountSummaries(ANGEMELDET);
        }

        @Test
        @DisplayName("liefert die Steuerakte eines Members samt Erzaufschluesselung")
        void memberLedger() {
            MiningDtos.LedgerItemDto posten = new MiningDtos.LedgerItemDto(
                    1230L, "Veldspar", "ORE", 1_000, 100.0,
                    new BigDecimal("10.00"), new BigDecimal("1000.00"));
            MiningDtos.MonthlyLedgerDto monat = new MiningDtos.MonthlyLedgerDto(
                    "2026-08", new BigDecimal("1000.00"), BigDecimal.ZERO, BigDecimal.ZERO,
                    false, new BigDecimal("1000.00"), List.of(), List.of(posten));
            MiningDtos.AdminMemberLedgerDto akte = new MiningDtos.AdminMemberLedgerDto(
                    MEMBER, "Pilot 1000", "portrait", new BigDecimal("1000.00"), BigDecimal.ZERO,
                    BigDecimal.ZERO, new BigDecimal("-1000.00"), List.of(monat), List.of());
            when(ledgerService.memberLedger(ANGEMELDET, MEMBER)).thenReturn(akte);

            MiningDtos.AdminMemberLedgerDto antwort = controller.getMemberLedger(MEMBER).getBody();

            assertThat(antwort).isSameAs(akte);
            assertThat(antwort.months().getFirst().details()).containsExactly(posten);
        }

        @Test
        @DisplayName("reicht die Abweisung des Dienstes durch, statt sie zu verschlucken")
        void deniedStaysDenied() {
            // Der Controller darf eine abgewiesene Anfrage nicht in eine leere
            // Antwort verwandeln - der ApiExceptionHandler macht daraus ein 403,
            // und ein stilles 200 mit leerer Liste saehe fuer die Oberflaeche aus
            // wie "es gibt nichts zu sehen".
            when(ledgerService.memberLedger(anyLong(), anyLong()))
                    .thenThrow(new AccessDeniedException("kein Amt"));

            assertThatThrownBy(() -> controller.getMemberLedger(MEMBER))
                    .isInstanceOf(AccessDeniedException.class);
        }
    }

    @Nested
    @DisplayName("Gutschriften")
    class Credits {

        @Test
        @DisplayName("nimmt den Handelnden aus dem Sicherheitskontext, nicht aus dem Rumpf")
        void actorComesFromSecurityContext() {
            // DAS IST DIE REGEL, ohne die der Nachweis wertlos waere: schriebe der
            // Aufrufer den Handelnden selbst in den Rumpf, koennte er jede Buchung
            // unter fremdem Namen anlegen. Der Rumpf traegt deshalb nur Betrag und
            // Grund - fuer eine Absender-ID ist in ihm gar kein Platz.
            when(creditService.grant(anyLong(), anyLong(), anyString(), any()))
                    .thenReturn(creditDto(new BigDecimal("1500.00"), MiningTaxCredit.STATUS_ACTIVE));

            controller.grantCredit(MEMBER, new MiningDtos.GrantCreditDto("1500", "Ausschuettung"));

            verify(creditService).grant(ANGEMELDET, MEMBER, "1500", "Ausschuettung");
        }

        @Test
        @DisplayName("reicht den Betrag unveraendert als Zeichenkette weiter")
        void amountPassesThroughUntouched() {
            // Der Controller rechnet nichts und wandelt nichts. Wuerde er den
            // Betrag unterwegs in eine Zahl fassen, waere die Zeichenkette im
            // Rumpf umsonst gewesen - genau dort geht die Genauigkeit sonst
            // verloren.
            when(creditService.grant(anyLong(), anyLong(), anyString(), any()))
                    .thenReturn(creditDto(new BigDecimal("12345678901.23"),
                            MiningTaxCredit.STATUS_ACTIVE));

            controller.grantCredit(MEMBER,
                    new MiningDtos.GrantCreditDto("12345678901.23", null));

            verify(creditService).grant(ANGEMELDET, MEMBER, "12345678901.23", null);
        }

        @Test
        @DisplayName("nimmt eine Gutschrift mit Grund zurueck")
        void reverseWithReason() {
            when(creditService.reverse(anyLong(), anyLong(), any()))
                    .thenReturn(creditDto(new BigDecimal("-1500.00"),
                            MiningTaxCredit.STATUS_REVERSAL));

            MiningDtos.TaxCreditDto antwort = controller
                    .reverseCredit(7L, new MiningDtos.ReverseCreditDto("doppelt gebucht")).getBody();

            verify(creditService).reverse(ANGEMELDET, 7L, "doppelt gebucht");
            assertThat(antwort.status()).isEqualTo(MiningTaxCredit.STATUS_REVERSAL);
            assertThat(antwort.amount()).isEqualByComparingTo("-1500.00");
        }

        @Test
        @DisplayName("nimmt eine Gutschrift auch ohne Rumpf zurueck")
        void reverseWithoutBody() {
            // Der Grund ist freiwillig, also darf auch der ganze Rumpf fehlen.
            // Ohne diese Abfrage flaege hier eine NullPointerException - und zwar
            // beim Zuruecknehmen, also genau dann, wenn jemand einen Fehler
            // korrigieren will.
            when(creditService.reverse(anyLong(), anyLong(), any()))
                    .thenReturn(creditDto(new BigDecimal("-1500.00"),
                            MiningTaxCredit.STATUS_REVERSAL));

            controller.reverseCredit(7L, null);

            verify(creditService).reverse(eq(ANGEMELDET), eq(7L), isNull());
        }

        @Test
        @DisplayName("liefert den Verlauf je Account und den Blick von oben")
        void historyEndpoints() {
            MiningDtos.TaxCreditDto zeile =
                    creditDto(new BigDecimal("1500.00"), MiningTaxCredit.STATUS_ACTIVE);
            when(creditService.historyFor(ANGEMELDET, MEMBER)).thenReturn(List.of(zeile));
            when(creditService.recentHistory(ANGEMELDET)).thenReturn(List.of(zeile));

            assertThat(controller.getCreditsFor(MEMBER).getBody()).containsExactly(zeile);
            assertThat(controller.getRecentCredits().getBody()).containsExactly(zeile);
        }
    }
}
