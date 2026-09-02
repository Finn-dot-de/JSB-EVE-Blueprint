package com.eve.own.auth.backend.domain.character.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.eve.own.auth.backend.domain.character.entity.Character;
import com.eve.own.auth.backend.domain.character.entity.CharacterMailCount;
import com.eve.own.auth.backend.domain.character.repository.CharacterMailCountRepository;
import com.eve.own.auth.backend.esi.EsiResponse;
import com.eve.own.auth.backend.esi.EsiService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Gezaehlt wird - und nur gezaehlt. Dass dabei kein Betreff anfallen
 * <em>kann</em>, prueft {@code MailPrivacyTest}; hier steht, was gezaehlt wird
 * und was nicht.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Zaehlung der Nachrichten je Charakterpaar")
class MailCountSyncServiceTest {

    private static final Long CHARACTER_ID = 2_112_000_001L;
    private static final Long ALT_CHARACTER = 2_112_000_002L;
    private static final Long DRITTER = 2_112_000_003L;
    private static final String TOKEN = "token";
    private static final Instant GESTERN = Instant.parse("2026-08-29T20:00:00Z");
    private static final Instant HEUTE = Instant.parse("2026-08-30T20:00:00Z");

    @Mock private EsiService esiService;
    @Mock private AltSourceStore store;
    @Mock private CharacterMailCountRepository mailCountRepo;

    private AltSourceProperties properties;
    private MailCountSyncService service;
    private Character character;

    @BeforeEach
    void setUp() {
        properties = new AltSourceProperties();
        service = new MailCountSyncService(esiService, properties, store, mailCountRepo);

        character = new Character();
        character.setId(CHARACTER_ID);
        character.setName("Pilot Eins");

        when(esiService.getMailHeaders(anyLong(), anyString())).thenReturn(EsiResponse.empty());
    }

    private static EsiService.EsiMailRecipient an(Long id) {
        return new EsiService.EsiMailRecipient(id, "character");
    }

    private static EsiService.EsiMailRecipient anCorp(Long id) {
        return new EsiService.EsiMailRecipient(id, "corporation");
    }

    private static EsiService.EsiMailHeaderResponse mail(Long from, Instant timestamp,
                                                         EsiService.EsiMailRecipient... to) {
        return new EsiService.EsiMailHeaderResponse(from, to, timestamp);
    }

    private void esiLiefert(EsiService.EsiMailHeaderResponse... headers) {
        when(esiService.getMailHeaders(anyLong(), anyString()))
                .thenReturn(EsiResponse.changed(headers, null, null));
    }

    private List<CharacterMailCount> captured() {
        ArgumentCaptor<List<CharacterMailCount>> captor = ArgumentCaptor.captor();
        verify(store).replaceMailCounts(anyLong(), captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("zaehlt gesendete und empfangene Nachrichten getrennt")
    void countsSentAndReceivedSeparately() {
        esiLiefert(mail(CHARACTER_ID, HEUTE, an(ALT_CHARACTER)),
                mail(CHARACTER_ID, GESTERN, an(ALT_CHARACTER)),
                mail(ALT_CHARACTER, GESTERN, an(CHARACTER_ID)));

        service.sync(character, TOKEN);

        assertThat(captured()).singleElement().satisfies(row -> {
            assertThat(row.getCounterpartyId()).isEqualTo(ALT_CHARACTER);
            assertThat(row.getSentCount()).isEqualTo(2);
            assertThat(row.getReceivedCount()).isEqualTo(1);
            // Die Richtung getrennt zu halten kostet nichts und ist der halbe
            // Wert: "schreibt staendig hin" und "bekommt staendig Post" sind
            // zwei verschiedene Aussagen.
            assertThat(row.getLastMailAt()).isEqualTo(HEUTE);
        });
    }

    @Test
    @DisplayName("zaehlt eine Rundmail an zu viele Empfaenger gar nicht")
    void ignoresBroadcastsToManyRecipients() {
        properties.setMailMaxRecipients(2);
        esiLiefert(mail(CHARACTER_ID, HEUTE, an(ALT_CHARACTER), an(DRITTER), an(4L)));

        service.sync(character, TOKEN);

        // Ohne diese Zeile erzeugte jede Rundmail Paare zwischen genau den
        // Leuten, die ohnehin dieselbe Corporation teilen. Beim gemeinsamen
        // Mining-Tag hat derselbe Fehler die Werte gemessen INVERTIERT.
        assertThat(captured()).isEmpty();
    }

    @Test
    @DisplayName("zaehlt eine Mail an eine Corporation nicht, auch wenn sie nur einen Empfaenger hat")
    void ignoresCorporationMailsDespiteSingleRecipient() {
        esiLiefert(mail(CHARACTER_ID, HEUTE, anCorp(98_000_001L)));

        service.sync(character, TOKEN);

        // Hinter dem EINEN Empfaenger stehen vierhundert Leser. Eine Pruefung
        // allein auf die Anzahl der Eintraege ginge hier daneben.
        assertThat(captured()).isEmpty();
    }

    @Test
    @DisplayName("zaehlt eine Mail mit gemischten Empfaengern nicht mit")
    void ignoresMixedRecipientMails() {
        esiLiefert(mail(CHARACTER_ID, HEUTE, an(ALT_CHARACTER), anCorp(98_000_001L)));

        service.sync(character, TOKEN);

        assertThat(captured()).isEmpty();
    }

    @Test
    @DisplayName("zaehlt sich selbst nicht als Gegenpartei")
    void doesNotCountItself() {
        esiLiefert(mail(CHARACTER_ID, HEUTE, an(CHARACTER_ID), an(ALT_CHARACTER)));

        service.sync(character, TOKEN);

        assertThat(captured()).singleElement()
                .satisfies(row -> assertThat(row.getCounterpartyId()).isEqualTo(ALT_CHARACTER));
    }

    @Test
    @DisplayName("zaehlt eine Mail an zwei Empfaenger fuer beide Paare")
    void countsSmallGroupMailForEveryRecipient() {
        esiLiefert(mail(CHARACTER_ID, HEUTE, an(ALT_CHARACTER), an(DRITTER)));

        service.sync(character, TOKEN);

        assertThat(captured()).hasSize(2)
                .allSatisfy(row -> assertThat(row.getSentCount()).isEqualTo(1));
    }

    @Test
    @DisplayName("schreibt bei ausgefallener Quelle nichts und laesst die bisherige Zaehlung stehen")
    void writesNothingWhenTheSourceFailed() {
        when(esiService.getMailHeaders(anyLong(), anyString())).thenReturn(EsiResponse.empty());

        service.sync(character, TOKEN);

        // Ohne diese Zeile wuerde ein Ausfall die Zaehlung auf null setzen -
        // und eine Null sieht aus wie ein Ergebnis.
        verifyNoInteractions(store);
    }

    @Test
    @DisplayName("ueberspringt ein unveraendertes Postfach, wenn schon gezaehlt wurde")
    void skipsUnchangedMailbox() {
        when(esiService.getMailHeaders(anyLong(), anyString())).thenReturn(
                EsiResponse.unchanged(new EsiService.EsiMailHeaderResponse[]{
                        mail(CHARACTER_ID, HEUTE, an(ALT_CHARACTER))}, null, null));
        when(mailCountRepo.existsByCharacterId(CHARACTER_ID)).thenReturn(true);

        service.sync(character, TOKEN);

        verify(store, never()).replaceMailCounts(anyLong(), any());
    }

    @Test
    @DisplayName("tut nichts, wenn die Zaehlung abgeschaltet ist")
    void doesNothingWhenDisabled() {
        properties.setMailEnabled(false);
        esiLiefert(mail(CHARACTER_ID, HEUTE, an(ALT_CHARACTER)));

        service.sync(character, TOKEN);

        // Der Schalter greift VOR dem ESI-Aufruf: abgeschaltet heisst, dass das
        // Postfach gar nicht erst geoeffnet wird.
        verify(esiService, never()).getMailHeaders(anyLong(), anyString());
        verifyNoInteractions(store);
    }
}
