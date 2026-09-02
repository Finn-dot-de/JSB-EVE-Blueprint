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
import com.eve.own.auth.backend.domain.character.entity.CharacterContact;
import com.eve.own.auth.backend.domain.character.repository.CharacterContactRepository;
import com.eve.own.auth.backend.esi.EsiResponse;
import com.eve.own.auth.backend.esi.EsiService;
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
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClientResponseException;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Erfassung der Kontaktlisten")
class ContactSyncServiceTest {

    private static final Long CHARACTER_ID = 2_112_000_001L;
    private static final Long ALT_CHARACTER = 2_112_000_002L;
    private static final String TOKEN = "token";

    @Mock private EsiService esiService;
    @Mock private AltSourceStore store;
    @Mock private CharacterContactRepository contactRepo;

    private AltSourceProperties properties;
    private ContactSyncService service;
    private Character character;

    @BeforeEach
    void setUp() {
        properties = new AltSourceProperties();
        service = new ContactSyncService(esiService, properties, store, contactRepo);

        character = new Character();
        character.setId(CHARACTER_ID);
        character.setName("Pilot Eins");

        when(esiService.getContacts(anyLong(), anyString())).thenReturn(EsiResponse.empty());
    }

    private static EsiService.EsiContactResponse contact(Long id, String type, Float standing) {
        return new EsiService.EsiContactResponse(id, type, standing, false, true);
    }

    private void esiLiefert(EsiService.EsiContactResponse... contacts) {
        when(esiService.getContacts(anyLong(), anyString()))
                .thenReturn(EsiResponse.changed(List.of(contacts), null, null));
    }

    private List<CharacterContact> captured() {
        ArgumentCaptor<List<CharacterContact>> captor = ArgumentCaptor.captor();
        verify(store).replaceContacts(anyLong(), captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("haelt einen Charakter-Kontakt samt Standing fest")
    void keepsCharacterContacts() {
        esiLiefert(contact(ALT_CHARACTER, "character", 10.0f));

        service.sync(character, TOKEN);

        assertThat(captured()).singleElement().satisfies(entry -> {
            assertThat(entry.getContactId()).isEqualTo(ALT_CHARACTER);
            assertThat(entry.getStanding()).isEqualTo(10.0);
            assertThat(entry.getWatched()).isTrue();
        });
    }

    @Test
    @DisplayName("laesst eine fehlende Standing null und macht daraus keine Null")
    void missingStandingStaysNull() {
        esiLiefert(contact(ALT_CHARACTER, "character", null));

        service.sync(character, TOKEN);

        // Ohne diese Zeile waere "ESI nennt keine Standing" von "der Spieler hat
        // bewusst neutral gesetzt" nicht mehr zu unterscheiden - genau die
        // Verwechslung, die die tragende Regel der Alt-Erkennung verbietet.
        assertThat(captured()).singleElement()
                .satisfies(entry -> assertThat(entry.getStanding()).isNull());
    }

    @Test
    @DisplayName("verwirft Corporations, Allianzen und Fraktionen aus der Kontaktliste")
    void dropsNonCharacterContacts() {
        esiLiefert(contact(98_000_001L, "corporation", 10.0f),
                contact(99_005_443L, "alliance", 5.0f),
                contact(500_001L, "faction", 0.0f),
                contact(ALT_CHARACTER, "character", 10.0f));

        service.sync(character, TOKEN);

        // Ohne diese Zeile stuende die eigene Corporation in der Liste jedes
        // zweiten Mitglieds - ein Merkmal, das alle teilen, ist kein
        // Fingerabdruck. Derselbe Fehler wie beim gemeinsamen Mining-Tag.
        assertThat(captured()).singleElement()
                .satisfies(entry -> assertThat(entry.getContactId()).isEqualTo(ALT_CHARACTER));
    }

    @Test
    @DisplayName("verwirft den eigenen Eintrag und doppelte Kontakte")
    void dropsSelfAndDuplicates() {
        esiLiefert(contact(CHARACTER_ID, "character", 10.0f),
                contact(ALT_CHARACTER, "character", 10.0f),
                contact(ALT_CHARACTER, "character", 5.0f));

        service.sync(character, TOKEN);

        // Ohne die Doppelten-Bremse braeche der eindeutige Schluessel
        // uk_contact_char_contact - und zwar fuer den ganzen Charakter, nicht
        // nur fuer die eine Zeile.
        assertThat(captured()).hasSize(1);
    }

    @Test
    @DisplayName("uebernimmt eine leere, aber vorhandene Antwort als geleerte Liste")
    void emptyAnswerEmptiesTheList() {
        when(esiService.getContacts(anyLong(), anyString()))
                .thenReturn(EsiResponse.changed(List.of(), null, null));

        service.sync(character, TOKEN);

        // Ein entfernter Kontakt ist eine Aussage. Ohne diese Zeile bliebe er
        // fuer immer stehen, weil "leer" mit "ausgefallen" verwechselt wuerde.
        assertThat(captured()).isEmpty();
    }

    @Test
    @DisplayName("schreibt bei ausgefallener Quelle nichts und laesst den bisherigen Stand stehen")
    void writesNothingWhenTheSourceFailed() {
        when(esiService.getContacts(anyLong(), anyString())).thenReturn(EsiResponse.empty());

        service.sync(character, TOKEN);

        // Ohne diese Zeile wuerde ein Ausfall die Kontaktliste loeschen und
        // danach aussaehe, als haette der Spieler alle Kontakte entfernt.
        verifyNoInteractions(store);
    }

    @Test
    @DisplayName("faengt einen ESI-Fehler nicht ab, schreibt aber auch nichts")
    void letsEsiErrorsThroughWithoutWriting() {
        when(esiService.getContacts(anyLong(), anyString()))
                .thenThrow(new RestClientResponseException("Fehler", 500, "", HttpHeaders.EMPTY, null, null));

        try {
            service.sync(character, TOKEN);
        } catch (RestClientResponseException expected) {
            // Der Zeitgeber muss den Fehler sehen: nur er kann auf ein
            // erschoepftes Fehler-Budget mit einer Pause reagieren.
        }
        verifyNoInteractions(store);
    }

    @Test
    @DisplayName("ueberspringt einen unveraenderten Stand nur, wenn schon Zeilen vorliegen")
    void skipsUnchangedOnlyWhenRowsExist() {
        when(esiService.getContacts(anyLong(), anyString()))
                .thenReturn(EsiResponse.unchanged(List.of(contact(ALT_CHARACTER, "character", 10.0f)),
                        null, null));
        when(contactRepo.existsByCharacterId(CHARACTER_ID)).thenReturn(false);

        service.sync(character, TOKEN);

        // Nach einem Deployment ist der ETag-Cache gefuellt und die Tabelle
        // leer. Ohne die zweite Bedingung bliebe der Charakter dauerhaft ohne
        // Kontakte - dieselbe Falle, die bei den Skills schon zuschlug.
        verify(store).replaceContacts(anyLong(), any());
    }

    @Test
    @DisplayName("ueberspringt einen unveraenderten Stand, wenn bereits Zeilen vorliegen")
    void skipsUnchangedWhenRowsExist() {
        when(esiService.getContacts(anyLong(), anyString()))
                .thenReturn(EsiResponse.unchanged(List.of(contact(ALT_CHARACTER, "character", 10.0f)),
                        null, null));
        when(contactRepo.existsByCharacterId(CHARACTER_ID)).thenReturn(true);

        service.sync(character, TOKEN);

        verify(store, never()).replaceContacts(anyLong(), any());
    }

    @Test
    @DisplayName("tut nichts, wenn die Erfassung abgeschaltet ist")
    void doesNothingWhenDisabled() {
        properties.setContactsEnabled(false);
        esiLiefert(contact(ALT_CHARACTER, "character", 10.0f));

        service.sync(character, TOKEN);

        // Der Schalter greift VOR dem ESI-Aufruf. Sonst kaemen die Daten
        // trotzdem herein und der Schalter spare nur das Speichern.
        verify(esiService, never()).getContacts(anyLong(), anyString());
        verifyNoInteractions(store);
    }
}
