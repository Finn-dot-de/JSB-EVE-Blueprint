package com.eve.own.auth.backend.domain.character.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.eve.own.auth.backend.domain.character.entity.CharacterContact;
import com.eve.own.auth.backend.domain.character.entity.CharacterIskTransfer;
import com.eve.own.auth.backend.domain.character.entity.IskTransferDirection;
import com.eve.own.auth.backend.domain.character.repository.CharacterContactRepository;
import com.eve.own.auth.backend.domain.character.repository.CharacterIskTransferRepository;
import com.eve.own.auth.backend.domain.character.repository.CharacterMailCountRepository;
import com.eve.own.auth.backend.domain.character.repository.CorporationMemberPresenceRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Die schreibende Seite der vier Datenquellen")
class AltSourceStoreTest {

    private static final Long CHARACTER_ID = 2_112_000_001L;
    private static final Long GEGENPARTEI = 2_112_000_002L;

    @Mock private CharacterIskTransferRepository iskTransferRepo;
    @Mock private CharacterContactRepository contactRepo;
    @Mock private CharacterMailCountRepository mailCountRepo;
    @Mock private CorporationMemberPresenceRepository presenceRepo;

    private AltSourceStore store;

    @BeforeEach
    void setUp() {
        store = new AltSourceStore(iskTransferRepo, contactRepo, mailCountRepo, presenceRepo);
        when(iskTransferRepo.findJournalRefIdsByCharacterId(anyLong())).thenReturn(List.of());
    }

    private static CharacterIskTransfer transfer(Long journalRefId) {
        CharacterIskTransfer transfer = new CharacterIskTransfer();
        transfer.setCharacterId(CHARACTER_ID);
        transfer.setCounterpartyId(GEGENPARTEI);
        transfer.setDirection(IskTransferDirection.OUTGOING);
        transfer.setAmount(new BigDecimal("1000.00"));
        transfer.setOccurredAt(Instant.parse("2026-08-05T12:00:00Z"));
        transfer.setJournalRefId(journalRefId);
        return transfer;
    }

    @SuppressWarnings("unchecked")
    private List<CharacterIskTransfer> gespeicherteUeberweisungen() {
        ArgumentCaptor<List<CharacterIskTransfer>> captor = ArgumentCaptor.captor();
        verify(iskTransferRepo).saveAll(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("speichert eine bereits bekannte Ueberweisung kein zweites Mal")
    void doesNotStoreAKnownTransferTwice() {
        when(iskTransferRepo.findJournalRefIdsByCharacterId(CHARACTER_ID)).thenReturn(List.of(7L));

        assertThat(store.appendIskTransfers(CHARACTER_ID, List.of(transfer(7L), transfer(8L))))
                .isEqualTo(1);

        // ESI liefert bei JEDEM Lauf dieselben rund dreissig Tage Journal. Ohne
        // den Abgleich ueber die Journal-ID waere die Haeufigkeit - das
        // eigentliche Signal - nach einer Woche nur noch die Anzahl der Laeufe.
        assertThat(gespeicherteUeberweisungen()).singleElement()
                .satisfies(t -> assertThat(t.getJournalRefId()).isEqualTo(8L));
    }

    @Test
    @DisplayName("speichert eine doppelt gelieferte Ueberweisung nur einmal")
    void collapsesDuplicatesWithinOneBatch() {
        assertThat(store.appendIskTransfers(CHARACTER_ID, List.of(transfer(9L), transfer(9L))))
                .isEqualTo(1);

        // Sonst braeche der eindeutige Schluessel uk_isk_transfer_journal - und
        // zwar fuer den ganzen Stapel, nicht nur fuer die doppelte Zeile.
        assertThat(gespeicherteUeberweisungen()).hasSize(1);
    }

    @Test
    @DisplayName("ruehrt die Datenbank gar nicht an, wenn alles schon bekannt ist")
    void writesNothingWhenEverythingIsKnown() {
        when(iskTransferRepo.findJournalRefIdsByCharacterId(CHARACTER_ID)).thenReturn(List.of(7L));

        assertThat(store.appendIskTransfers(CHARACTER_ID, List.of(transfer(7L)))).isZero();

        verify(iskTransferRepo, never()).saveAll(any());
    }

    @Test
    @DisplayName("loescht beim Ersetzen zuerst und legt erst dann neu an")
    void deletesBeforeInserting() {
        CharacterContact contact = new CharacterContact();
        contact.setCharacterId(CHARACTER_ID);
        contact.setContactId(GEGENPARTEI);
        contact.setRecordedAt(Instant.now());

        store.replaceContacts(CHARACTER_ID, List.of(contact));

        // Die Reihenfolge ist nicht Geschmack: der eindeutige Schluessel
        // uk_contact_char_contact laesst dieselbe Paarung kein zweites Mal zu.
        InOrder reihenfolge = Mockito.inOrder(contactRepo);
        reihenfolge.verify(contactRepo).deleteByCharacterId(CHARACTER_ID);
        reihenfolge.verify(contactRepo).saveAll(any());
    }

    @Test
    @DisplayName("leert die Kontaktliste, wenn der Spieler alle Kontakte entfernt hat")
    void emptyListClearsTheSnapshot() {
        store.replaceContacts(CHARACTER_ID, List.of());

        // Ein entfernter Kontakt ist eine Aussage. Ohne das Loeschen bliebe er
        // fuer immer stehen. Dass hier ueberhaupt eine leere Liste ankommt, hat
        // der ContactSyncService bereits von einem Ausfall unterschieden.
        verify(contactRepo).deleteByCharacterId(CHARACTER_ID);
        verify(contactRepo, never()).saveAll(any());
    }

    @Test
    @DisplayName("schreibt keine leere Anwesenheitsliste in die Datenbank")
    void doesNotWriteEmptyPresence() {
        store.appendPresence(List.of());

        verify(presenceRepo, never()).saveAll(any());
    }
}
