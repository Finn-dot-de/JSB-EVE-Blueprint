package com.eve.own.auth.backend.domain.character.service;

import com.eve.own.auth.backend.domain.character.entity.Character;
import com.eve.own.auth.backend.domain.character.entity.CharacterContact;
import com.eve.own.auth.backend.domain.character.repository.CharacterContactRepository;
import com.eve.own.auth.backend.esi.EsiResponse;
import com.eve.own.auth.backend.esi.EsiService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Spiegelt die Kontaktliste eines registrierten Charakters.
 *
 * <p>Wer seinen eigenen Alt fuehrt, traegt ihn meist mit hoher Standing ein.
 * Das ist ein <em>bewusster Handgriff des Spielers</em> und damit etwas anderes
 * als ein Nebenprodukt des Gruppenalltags - dieselbe Eigenschaft, die die
 * ISK-Ueberweisung stark macht.</p>
 *
 * <p><b>ESI-Last:</b> ein konditionaler Aufruf je registriertem Charakter und
 * Lauf. Der Endpunkt ist zwar paginiert, legt aber 1024 Eintraege auf eine
 * Seite; mehr als eine Seite kommt praktisch nicht vor.</p>
 */
@Slf4j
@Service
public class ContactSyncService {

    /** Der Scope, den der Endpunkt verlangt - bereits in {@code EVE_SCOPES}. */
    static final String CONTACTS_SCOPE = "esi-characters.read_contacts.v1";

    /** Der einzige Kontakttyp, der etwas ueber eine Verbindung zweier Spieler aussagt. */
    private static final String CONTACT_TYPE_CHARACTER = "character";

    private final EsiService esiService;
    private final AltSourceProperties properties;
    private final AltSourceStore store;
    private final CharacterContactRepository contactRepo;

    public ContactSyncService(EsiService esiService,
                              AltSourceProperties properties,
                              AltSourceStore store,
                              CharacterContactRepository contactRepo) {
        this.esiService = esiService;
        this.properties = properties;
        this.store = store;
        this.contactRepo = contactRepo;
    }

    /**
     * Holt die Kontakte und ersetzt die Momentaufnahme des Charakters.
     *
     * <p><b>Ersetzt wird nur bei einer echten Antwort.</b> Liefert ESI nichts -
     * 304 ohne zwischengespeicherten Rumpf, ein Fehler, ein abgelaufener Token -
     * bleibt der bisherige Stand unangetastet. Die Alternative waere eine leere
     * Kontaktliste, die aussieht, als haette der Spieler alle Kontakte
     * geloescht. Das Projekt hat diesen Fehler bei den Fuzzwork-Nullpreisen und
     * beim halben Marktabzug schon zweimal gemacht.</p>
     *
     * <p>Eine <em>leere, aber vorhandene</em> Antwort ist etwas anderes: sie
     * heisst wirklich "keine Kontakte" und wird uebernommen.</p>
     *
     * <p>Ausnahmen werden hier nicht gefangen. Der Zeitgeber muss sie sehen, um
     * auf ein erschoepftes Fehler-Budget mit einer Pause reagieren zu koennen -
     * und weil vor dem Schreiben abgebrochen wird, kann keine Teilmenge
     * entstehen.</p>
     */
    public void sync(Character character, String token) {
        if (!properties.isContactsEnabled()) {
            return;
        }
        Long characterId = character.getId();

        EsiResponse<List<EsiService.EsiContactResponse>> response =
                esiService.getContacts(characterId, token);

        if (response == null || response.data() == null) {
            log.debug("Kontakte von {} nicht abrufbar - der bisherige Stand bleibt stehen.",
                    character.getName());
            return;
        }
        // Unveraendert heisst nur dann "nichts zu tun", wenn auch wirklich schon
        // etwas in der Tabelle liegt. Nach einem Deployment ist der ETag-Cache
        // gefuellt und die Tabelle leer - ohne diese zweite Bedingung bliebe der
        // Charakter dauerhaft ohne Kontakte. Dieselbe Falle wie bei den Skills.
        if (response.notModified() && contactRepo.existsByCharacterId(characterId)) {
            log.debug("Kontakte von {} unveraendert.", character.getName());
            return;
        }

        store.replaceContacts(characterId, toContacts(characterId, response.data()));
    }

    /**
     * Behaelt nur Charakter-Kontakte, jeden hoechstens einmal.
     *
     * <p>Corporations, Allianzen und Fraktionen fliegen raus: sie stehen bei
     * halb der Corporation in der Liste und sind damit derselbe Fehlschluss wie
     * der gemeinsame Mining-Tag. Der eigene Eintrag ebenfalls - er ist keine
     * Verbindung zu jemand anderem.</p>
     */
    private static List<CharacterContact> toContacts(Long characterId,
                                                     List<EsiService.EsiContactResponse> entries) {
        Instant recordedAt = Instant.now();
        Set<Long> seen = new HashSet<>();
        List<CharacterContact> contacts = new ArrayList<>();

        for (EsiService.EsiContactResponse entry : entries) {
            if (entry == null || entry.contact_id() == null) {
                continue;
            }
            if (!isCharacter(entry.contact_type()) || entry.contact_id().equals(characterId)) {
                continue;
            }
            // Ohne diese Zeile koennte eine doppelte Antwort den eindeutigen
            // Schluessel uk_contact_char_contact brechen und das Ersetzen
            // scheitern lassen - fuer den ganzen Charakter, nicht nur fuer die
            // eine Zeile.
            if (!seen.add(entry.contact_id())) {
                continue;
            }
            contacts.add(toContact(characterId, entry, recordedAt));
        }
        return contacts;
    }

    private static boolean isCharacter(String contactType) {
        return contactType != null
                && CONTACT_TYPE_CHARACTER.equals(contactType.toLowerCase(Locale.ROOT));
    }

    private static CharacterContact toContact(Long characterId,
                                              EsiService.EsiContactResponse entry,
                                              Instant recordedAt) {
        CharacterContact contact = new CharacterContact();
        contact.setCharacterId(characterId);
        contact.setContactId(entry.contact_id());
        // Fehlende Standing bleibt null. Als 0 waere sie von "bewusst neutral"
        // nicht mehr zu unterscheiden - genau die Verwechslung, die die
        // tragende Regel der Alt-Erkennung verbietet.
        contact.setStanding(entry.standing() == null ? null : entry.standing().doubleValue());
        contact.setWatched(entry.is_watched());
        contact.setRecordedAt(recordedAt);
        return contact;
    }
}
