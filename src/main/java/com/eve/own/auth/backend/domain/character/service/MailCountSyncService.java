package com.eve.own.auth.backend.domain.character.service;

import com.eve.own.auth.backend.domain.character.entity.Character;
import com.eve.own.auth.backend.domain.character.entity.CharacterMailCount;
import com.eve.own.auth.backend.domain.character.repository.CharacterMailCountRepository;
import com.eve.own.auth.backend.esi.EsiResponse;
import com.eve.own.auth.backend.esi.EsiService;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Zaehlt Nachrichten je Charakterpaar - und tut sonst nichts.
 *
 * <h2>Die Zusage, und wo sie durchgesetzt ist</h2>
 * <p>Betreff und Text werden nicht gespeichert, nicht protokolliert und nicht
 * durch diesen Dienst gereicht. Das haengt nicht an dieser Beschreibung: die
 * ESI-Antwort wird als {@link EsiService.EsiMailHeaderResponse} eingelesen, und
 * dieser Typ hat weder eine Betreffs- noch eine Mail-ID-Komponente. Der Betreff
 * faellt also schon beim Einlesen weg; es gibt in diesem Prozess keinen Wert,
 * den man versehentlich weiterreichen koennte. Die Entitaet
 * {@link CharacterMailCount} hat aus demselben Grund gar kein Textfeld.</p>
 *
 * <h2>Warum jeder Lauf neu zaehlt</h2>
 * <p>Weil es keine Mail-ID gibt, kann kein Lauf wissen, welche Nachricht er
 * schon gezaehlt hat. Fortschreiben waere also Raten. Stattdessen ersetzt jeder
 * Lauf die Zaehlung des Charakters vollstaendig, gebildet ueber die juengsten
 * Kopfzeilen, die ESI in einem Zug herausgibt. Der Wert heisst damit "so viele
 * der zuletzt sichtbaren Nachrichten liefen zwischen diesen beiden" - eine
 * ehrliche, wenn auch begrenzte Aussage.</p>
 *
 * <h2>Warum Rundschreiben nicht zaehlen</h2>
 * <p>Eine Mail an vierzig Corp-Mitglieder verbindet niemanden mit niemandem,
 * wuerde aber vierzig Paare erzeugen - und zwar genau die Paare, die ohnehin
 * dieselbe Corporation teilen. Das ist derselbe Fehler wie der rohe
 * Mining-Tag, der gemessen invertiert war. Deshalb zaehlt nur Post, die
 * ausschliesslich an Charaktere ging und an nicht mehr als
 * {@code eve.alt-sources.mail-max-recipients} davon.</p>
 *
 * <p><b>ESI-Last:</b> genau ein konditionaler Aufruf je registriertem Charakter
 * und Lauf. Mehr ist bauartbedingt nicht moeglich, siehe
 * {@code EsiService.getMailHeaders}.</p>
 */
@Slf4j
@Service
public class MailCountSyncService {

    /** Der Scope, den der Endpunkt verlangt - bereits in {@code EVE_SCOPES}. */
    static final String MAIL_SCOPE = "esi-mail.read_mail.v1";

    /** Der einzige Empfaengertyp, der ein Paar aus zwei Spielern beschreibt. */
    private static final String RECIPIENT_TYPE_CHARACTER = "character";

    private final EsiService esiService;
    private final AltSourceProperties properties;
    private final AltSourceStore store;
    private final CharacterMailCountRepository mailCountRepo;

    public MailCountSyncService(EsiService esiService,
                                AltSourceProperties properties,
                                AltSourceStore store,
                                CharacterMailCountRepository mailCountRepo) {
        this.esiService = esiService;
        this.properties = properties;
        this.store = store;
        this.mailCountRepo = mailCountRepo;
    }

    /**
     * Zaehlt die Kopfzeilen und ersetzt die Zaehlung des Charakters.
     *
     * <p>Ersetzt wird auch hier nur bei einer echten Antwort: ohne Daten bleibt
     * der bisherige Stand stehen, statt ihn durch eine Null zu ersetzen, die wie
     * ein Ergebnis aussieht.</p>
     */
    public void sync(Character character, String token) {
        if (!properties.isMailEnabled()) {
            return;
        }
        Long characterId = character.getId();

        EsiResponse<EsiService.EsiMailHeaderResponse[]> response =
                esiService.getMailHeaders(characterId, token);

        if (response == null || response.data() == null) {
            log.debug("Postfach von {} nicht abrufbar - die bisherige Zaehlung bleibt stehen.",
                    character.getName());
            return;
        }
        if (response.notModified() && mailCountRepo.existsByCharacterId(characterId)) {
            log.debug("Postfach von {} unveraendert.", character.getName());
            return;
        }

        store.replaceMailCounts(characterId, count(characterId, response.data()));
    }

    /** Verdichtet die Kopfzeilen zu einer Zeile je Gegenpartei. */
    private List<CharacterMailCount> count(Long characterId,
                                           EsiService.EsiMailHeaderResponse[] headers) {
        Instant countedAt = Instant.now();
        Map<Long, CharacterMailCount> byCounterparty = new LinkedHashMap<>();

        for (EsiService.EsiMailHeaderResponse header : headers) {
            if (!isPersonalMail(header)) {
                continue;
            }
            if (characterId.equals(header.from())) {
                countSent(characterId, header, byCounterparty, countedAt);
            } else {
                countReceived(characterId, header, byCounterparty, countedAt);
            }
        }
        return List.copyOf(byCounterparty.values());
    }

    /**
     * Ob die Nachricht an einen kleinen Kreis echter Charaktere ging.
     *
     * <p>Ein Empfaenger vom Typ {@code corporation}, {@code alliance} oder
     * {@code mailing_list} macht die Nachricht zum Rundschreiben, unabhaengig
     * davon, wieviele Eintraege die Liste hat: hinter dem einen Eintrag stehen
     * hunderte Leser. Deshalb reicht die blosse Anzahl als Pruefung nicht.</p>
     */
    private boolean isPersonalMail(EsiService.EsiMailHeaderResponse header) {
        if (header == null || header.from() == null || header.recipients() == null) {
            return false;
        }
        EsiService.EsiMailRecipient[] recipients = header.recipients();
        if (recipients.length == 0 || recipients.length > properties.getMailMaxRecipients()) {
            return false;
        }
        for (EsiService.EsiMailRecipient recipient : recipients) {
            if (recipient == null || recipient.recipient_id() == null
                    || !isCharacter(recipient.recipient_type())) {
                return false;
            }
        }
        return true;
    }

    private static boolean isCharacter(String recipientType) {
        return recipientType != null
                && RECIPIENT_TYPE_CHARACTER.equals(recipientType.toLowerCase(Locale.ROOT));
    }

    private static void countSent(Long characterId, EsiService.EsiMailHeaderResponse header,
                                  Map<Long, CharacterMailCount> byCounterparty, Instant countedAt) {
        for (EsiService.EsiMailRecipient recipient : header.recipients()) {
            if (recipient.recipient_id().equals(characterId)) {
                // Post an sich selbst ist keine Verbindung zu jemand anderem.
                continue;
            }
            CharacterMailCount row =
                    row(characterId, recipient.recipient_id(), byCounterparty, countedAt);
            row.addSent();
            row.noteMailAt(header.timestamp());
        }
    }

    private static void countReceived(Long characterId, EsiService.EsiMailHeaderResponse header,
                                      Map<Long, CharacterMailCount> byCounterparty,
                                      Instant countedAt) {
        CharacterMailCount row = row(characterId, header.from(), byCounterparty, countedAt);
        row.addReceived();
        row.noteMailAt(header.timestamp());
    }

    private static CharacterMailCount row(Long characterId, Long counterpartyId,
                                          Map<Long, CharacterMailCount> byCounterparty,
                                          Instant countedAt) {
        return byCounterparty.computeIfAbsent(counterpartyId, id -> {
            CharacterMailCount count = new CharacterMailCount();
            count.setCharacterId(characterId);
            count.setCounterpartyId(id);
            count.setCountedAt(countedAt);
            return count;
        });
    }
}
