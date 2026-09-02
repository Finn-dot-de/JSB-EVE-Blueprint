package com.eve.own.auth.backend.domain.character.service;

import com.eve.own.auth.backend.domain.character.repository.CharacterContactRepository;
import com.eve.own.auth.backend.domain.character.repository.CharacterIskTransferRepository;
import com.eve.own.auth.backend.domain.character.repository.CharacterMailCountRepository;
import com.eve.own.auth.backend.domain.character.repository.CorporationMemberPresenceRepository;
import java.time.Duration;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loescht Bewegungsdaten, deren Aufbewahrungsfrist abgelaufen ist.
 *
 * <h2>Warum es diese Klasse gibt</h2>
 * <p>Eine Aufbewahrungsfrist, die nur im Javadoc steht, ist keine. Der Nutzer
 * hat 90 Tage fuer die Anwesenheitsaufzeichnung festgelegt; damit das eine
 * Zusage ist und keine Absicht, braucht es einen Lauf, der wirklich laeuft, und
 * einen Test, der beweist, dass er loescht und dabei die juengeren Zeilen stehen
 * laesst. Beides gibt es: {@code AltSourceRetentionScheduler} und
 * {@code AltSourceRetentionServiceTest}.</p>
 *
 * <h2>Warum die ISK-Ueberweisungen mitlaufen</h2>
 * <p>Sie sind die einzige der vier Tabellen, die sonst unbegrenzt waechst.
 * Kontakte und Mail-Zaehlung sind Momentaufnahmen und werden bei jedem Lauf
 * ersetzt; die Anwesenheit hat ihre Frist vom Nutzer. Die Ueberweisungen dagegen
 * werden angehaengt - ohne Frist saehe man in fuenf Jahren noch, wer wem 2026
 * Geld geschickt hat. Die Frist ist dieselbe und ebenso einstellbar.</p>
 *
 * <h2>Warum eine Frist von null nicht loescht</h2>
 * <p>Die naheliegende Lesart - "null Tage Aufbewahrung, also alles weg" - macht
 * aus einem Tippfehler in der Konfiguration einen vollstaendigen Datenverlust,
 * und zwar beim naechsten naechtlichen Lauf, wenn niemand hinsieht. Eine
 * fehlende oder unsinnige Frist heisst hier deshalb <b>nicht loeschen</b>, und
 * das Protokoll sagt es laut.</p>
 */
@Slf4j
@Service
public class AltSourceRetentionService {

    private final AltSourceProperties properties;
    private final CorporationMemberPresenceRepository presenceRepo;
    private final CharacterIskTransferRepository iskTransferRepo;
    private final CharacterContactRepository contactRepo;
    private final CharacterMailCountRepository mailCountRepo;

    public AltSourceRetentionService(AltSourceProperties properties,
                                     CorporationMemberPresenceRepository presenceRepo,
                                     CharacterIskTransferRepository iskTransferRepo,
                                     CharacterContactRepository contactRepo,
                                     CharacterMailCountRepository mailCountRepo) {
        this.properties = properties;
        this.presenceRepo = presenceRepo;
        this.iskTransferRepo = iskTransferRepo;
        this.contactRepo = contactRepo;
        this.mailCountRepo = mailCountRepo;
    }

    /**
     * Entfernt Anwesenheitszeilen, die aelter als die Frist sind.
     *
     * @return die Anzahl geloeschter Zeilen
     */
    @Transactional
    public int purgePresence() {
        Instant threshold = thresholdFor(properties.getPresenceRetention(), "Anwesenheit");
        if (threshold == null) {
            return 0;
        }
        int removed = presenceRepo.deleteOlderThan(threshold);
        log.info("Anwesenheit: {} Zeilen vor {} geloescht.", removed, threshold);
        return removed;
    }

    /**
     * Entfernt ISK-Ueberweisungen, die aelter als die Frist sind.
     *
     * @return die Anzahl geloeschter Zeilen
     */
    @Transactional
    public int purgeIskTransfers() {
        Instant threshold = thresholdFor(properties.getIskTransferRetention(), "ISK-Ueberweisungen");
        if (threshold == null) {
            return 0;
        }
        int removed = iskTransferRepo.deleteOlderThan(threshold);
        log.info("ISK-Ueberweisungen: {} Zeilen vor {} geloescht.", removed, threshold);
        return removed;
    }

    /**
     * Entfernt Kontakt- und Mailzeilen, die seit der Frist nicht mehr
     * aufgefrischt wurden.
     *
     * <p>Die Momentaufnahmen werden je Lauf ersetzt - aber nur fuer Charaktere,
     * die im Lauf vorkommen. Ein entzogenes Token, ein ungueltig gewordenes
     * Token oder eine abgeschaltete Quelle nehmen einen Charakter dauerhaft aus
     * dem Lauf, und seine Zeilen blieben dann fuer immer liegen. Ohne diesen
     * Lauf waere die Zusage auf der Oberflaeche - dass sich diese Daten selbst
     * begrenzen - schlicht unwahr.</p>
     *
     * @return die Anzahl geloeschter Zeilen
     */
    @Transactional
    public int purgeSnapshots() {
        Instant threshold = thresholdFor(properties.getSnapshotRetention(),
                "Kontakte und Nachrichtenanzahlen");
        if (threshold == null) {
            return 0;
        }
        int kontakte = contactRepo.deleteOlderThan(threshold);
        int mails = mailCountRepo.deleteOlderThan(threshold);
        log.info("Momentaufnahmen: {} Kontakt- und {} Mailzeilen vor {} geloescht.",
                kontakte, mails, threshold);
        return kontakte + mails;
    }

    /**
     * Der Zeitpunkt, vor dem geloescht wird.
     *
     * @return {@code null}, wenn keine brauchbare Frist eingestellt ist - dann
     *     wird nicht geloescht
     */
    private static Instant thresholdFor(Duration retention, String was) {
        if (retention == null || retention.isZero() || retention.isNegative()) {
            log.warn("Keine gueltige Aufbewahrungsfrist fuer {} eingestellt ({}). "
                    + "Es wird NICHTS geloescht.", was, retention);
            return null;
        }
        return Instant.now().minus(retention);
    }
}
