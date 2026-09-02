package com.eve.own.auth.backend.domain.character.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.eve.own.auth.backend.domain.character.CorporationScope;
import com.eve.own.auth.backend.domain.character.entity.CharacterIskTransfer;
import com.eve.own.auth.backend.domain.character.entity.IskTransferDirection;
import com.eve.own.auth.backend.esi.EsiService;
import java.math.BigDecimal;
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
 * Die Regel, die dieses Signal traegt: eine Ueberweisung zwischen zwei
 * <em>bestimmten</em> Charakteren ist selten und gerichtet. Alles, was das nicht
 * ist - Steuern, Kopfgelder, Marktgeschaefte -, darf nicht mitgezaehlt werden,
 * sonst ist der Wert wieder ein Gruppenmerkmal wie der gemeinsame Mining-Tag.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Erfassung der Spieler-Ueberweisungen")
class IskTransferSyncServiceTest {

    private static final Long CHARACTER_ID = 2_112_000_001L;
    private static final Long ALT_CHARACTER = 2_112_000_002L;
    private static final Long MAIN_CORP = 98_000_001L;
    private static final Long OTHER_CORPORATION = 98_765_432L;
    private static final Long AN_ALLIANCE = 99_005_443L;
    private static final Long AN_NPC_CORPORATION = 1_000_167L;

    @Mock private AltSourceStore store;

    private AltSourceProperties properties;
    private IskTransferSyncService service;

    @BeforeEach
    void setUp() {
        properties = new AltSourceProperties();
        service = new IskTransferSyncService(properties, store, new CorporationScope(MAIN_CORP, ""));
    }

    private static EsiService.EsiJournalResponse journal(Long id, String refType, Double amount,
                                                         Long secondParty) {
        return new EsiService.EsiJournalResponse(id, "2026-08-05T12:00:00Z", refType, amount,
                secondParty, null);
    }

    private List<CharacterIskTransfer> captured() {
        ArgumentCaptor<List<CharacterIskTransfer>> captor = ArgumentCaptor.captor();
        verify(store).appendIskTransfers(anyLong(), captor.capture());
        return captor.getValue();
    }

    @Nested
    @DisplayName("Nur echte Spieler-Ueberweisungen")
    class OnlyRealPlayerTransfers {

        @Test
        @DisplayName("haelt eine Ueberweisung an einen anderen Charakter mit Richtung und Betrag fest")
        void keepsTransferToAnotherCharacter() {
            service.sync(CHARACTER_ID, new EsiService.EsiJournalResponse[]{
                    journal(7L, "player_donation", -250_000_000.0, ALT_CHARACTER)});

            assertThat(captured()).singleElement().satisfies(transfer -> {
                assertThat(transfer.getCounterpartyId()).isEqualTo(ALT_CHARACTER);
                assertThat(transfer.getDirection()).isEqualTo(IskTransferDirection.OUTGOING);
                // Der Betrag steht positiv da, das Vorzeichen steckt in der
                // Richtung. Ohne diese Zusicherung summierte der erste Leser,
                // der abs() vergisst, Ein- und Ausgaenge zu ungefaehr null.
                assertThat(transfer.getAmount()).isEqualByComparingTo("250000000.00");
                assertThat(transfer.getOccurredAt()).isEqualTo(Instant.parse("2026-08-05T12:00:00Z"));
                assertThat(transfer.getJournalRefId()).isEqualTo(7L);
            });
        }

        @Test
        @DisplayName("merkt sich bei einer eingehenden Zahlung die Gegenrichtung")
        void marksIncomingDirection() {
            service.sync(CHARACTER_ID, new EsiService.EsiJournalResponse[]{
                    journal(8L, "player_donation", 10_000_000.0, ALT_CHARACTER)});

            assertThat(captured()).singleElement()
                    .satisfies(t -> assertThat(t.getDirection()).isEqualTo(IskTransferDirection.INCOMING));
        }

        @Test
        @DisplayName("zaehlt eine Steuerzahlung an die eigene Corporation nicht mit")
        void ignoresTaxPaymentToOwnCorporation() {
            service.sync(CHARACTER_ID, new EsiService.EsiJournalResponse[]{
                    journal(9L, "player_donation", -5_000_000.0, MAIN_CORP)});

            // Ohne diese Zeile stuende die Steuerzahlung jedes Mitglieds als
            // "Beziehung zur Corporation" in der Tabelle - ein Merkmal, das
            // ALLE teilen, und damit das genaue Gegenteil eines Fingerabdrucks.
            verify(store, never()).appendIskTransfers(anyLong(), any());
        }

        @Test
        @DisplayName("zaehlt Zahlungen an eine fremde Corporation oder Allianz nicht mit")
        void ignoresCorporationsAndAlliances() {
            service.sync(CHARACTER_ID, new EsiService.EsiJournalResponse[]{
                    journal(10L, "player_donation", -1_000.0, OTHER_CORPORATION),
                    journal(11L, "player_donation", -1_000.0, AN_ALLIANCE)});

            verify(store, never()).appendIskTransfers(anyLong(), any());
        }

        @Test
        @DisplayName("zaehlt eine NPC-Gegenpartei nicht mit")
        void ignoresNpcCounterparties() {
            service.sync(CHARACTER_ID, new EsiService.EsiJournalResponse[]{
                    journal(12L, "player_donation", -1_000.0, AN_NPC_CORPORATION)});

            verify(store, never()).appendIskTransfers(anyLong(), any());
        }

        @Test
        @DisplayName("zaehlt Kopfgelder, Marktgeschaefte und Gebuehren nicht mit")
        void ignoresEverythingThatIsNotADonation() {
            service.sync(CHARACTER_ID, new EsiService.EsiJournalResponse[]{
                    journal(13L, "bounty_prizes", 100_000.0, ALT_CHARACTER),
                    journal(14L, "market_transaction", -100_000.0, ALT_CHARACTER),
                    journal(15L, "brokers_fee", -100.0, ALT_CHARACTER),
                    journal(16L, "corporation_account_withdrawal", -100.0, ALT_CHARACTER)});

            // Ohne den Filter auf player_donation waere jedes Marktgeschaeft eine
            // "Beziehung" - und der Marktplatz verbindet beliebige Fremde.
            verify(store, never()).appendIskTransfers(anyLong(), any());
        }

        @Test
        @DisplayName("zaehlt eine Ueberweisung an sich selbst nicht mit")
        void ignoresSelfTransfers() {
            service.sync(CHARACTER_ID, new EsiService.EsiJournalResponse[]{
                    journal(17L, "player_donation", -1_000.0, CHARACTER_ID)});

            verify(store, never()).appendIskTransfers(anyLong(), any());
        }

        @Test
        @DisplayName("ueberspringt Zeilen ohne Journal-ID, ohne Betrag oder mit Betrag null")
        void skipsUnusableRows() {
            service.sync(CHARACTER_ID, new EsiService.EsiJournalResponse[]{
                    journal(null, "player_donation", -1_000.0, ALT_CHARACTER),
                    journal(18L, "player_donation", null, ALT_CHARACTER),
                    journal(19L, "player_donation", 0.0, ALT_CHARACTER)});

            // Ohne Journal-ID gaebe es kein Wiedererkennen: ESI liefert dieselben
            // dreissig Tage bei jedem Lauf erneut, und die Haeufigkeit - das
            // eigentliche Signal - waere bald nur noch die Anzahl der Laeufe.
            verify(store, never()).appendIskTransfers(anyLong(), any());
        }

        @Test
        @DisplayName("laesst eine unlesbare Zeitangabe die uebrigen Zeilen nicht mitreissen")
        void oneBadTimestampDoesNotLoseTheRest() {
            service.sync(CHARACTER_ID, new EsiService.EsiJournalResponse[]{
                    new EsiService.EsiJournalResponse(20L, "gestern", "player_donation",
                            -1_000.0, ALT_CHARACTER, null),
                    journal(21L, "player_donation", -2_000.0, ALT_CHARACTER)});

            assertThat(captured()).singleElement()
                    .satisfies(t -> assertThat(t.getJournalRefId()).isEqualTo(21L));
        }
    }

    @Nested
    @DisplayName("Untergrenze fuer den Betrag")
    class MinimumAmount {

        @Test
        @DisplayName("haelt in der Vorgabe jeden Betrag fest, auch einen sehr kleinen")
        void keepsEveryAmountByDefault() {
            service.sync(CHARACTER_ID, new EsiService.EsiJournalResponse[]{
                    journal(22L, "player_donation", -1.0, ALT_CHARACTER)});

            assertThat(captured()).hasSize(1);
        }

        @Test
        @DisplayName("verwirft Betraege unterhalb einer eingestellten Grenze")
        void dropsAmountsBelowTheConfiguredLimit() {
            properties.setIskTransferMinAmount(new BigDecimal("1000000"));

            service.sync(CHARACTER_ID, new EsiService.EsiJournalResponse[]{
                    journal(23L, "player_donation", -999_999.0, ALT_CHARACTER),
                    journal(24L, "player_donation", -1_000_000.0, ALT_CHARACTER)});

            assertThat(captured()).singleElement()
                    .satisfies(t -> assertThat(t.getJournalRefId()).isEqualTo(24L));
        }
    }

    @Nested
    @DisplayName("Ausfall und Abschaltung")
    class FailureAndSwitch {

        @Test
        @DisplayName("schreibt bei ausgefallener Quelle keine Teildaten")
        void writesNothingWhenTheSourceFailed() {
            service.sync(CHARACTER_ID, null);

            // null heisst "die Quelle war nicht da" und nicht "es gab keine
            // Ueberweisungen". Ohne diese Unterscheidung sieht ein Ausfall aus
            // wie ein vollstaendiger, leerer Stand - derselbe Fehler wie bei den
            // Fuzzwork-Nullpreisen und beim halben Marktabzug.
            verifyNoInteractions(store);
        }

        @Test
        @DisplayName("tut nichts, wenn die Erfassung abgeschaltet ist")
        void doesNothingWhenDisabled() {
            properties.setIskTransfersEnabled(false);

            service.sync(CHARACTER_ID, new EsiService.EsiJournalResponse[]{
                    journal(25L, "player_donation", -250_000_000.0, ALT_CHARACTER)});

            // Der Schalter muss VOR dem Verarbeiten greifen. Wuerde er erst am
            // Speichern haengen, entstuenden die personenbezogenen Daten trotzdem
            // - nur eben im Arbeitsspeicher und im Protokoll.
            verifyNoInteractions(store);
        }
    }
}
