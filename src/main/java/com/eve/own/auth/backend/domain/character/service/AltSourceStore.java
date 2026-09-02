package com.eve.own.auth.backend.domain.character.service;

import com.eve.own.auth.backend.domain.character.entity.CharacterContact;
import com.eve.own.auth.backend.domain.character.entity.CharacterIskTransfer;
import com.eve.own.auth.backend.domain.character.entity.CharacterMailCount;
import com.eve.own.auth.backend.domain.character.entity.CorporationMemberPresence;
import com.eve.own.auth.backend.domain.character.repository.CharacterContactRepository;
import com.eve.own.auth.backend.domain.character.repository.CharacterIskTransferRepository;
import com.eve.own.auth.backend.domain.character.repository.CharacterMailCountRepository;
import com.eve.own.auth.backend.domain.character.repository.CorporationMemberPresenceRepository;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Die schreibende Seite der vier neuen Datenquellen - und nur sie.
 *
 * <h2>Warum das eine eigene Bohne ist</h2>
 * <p>Ein Ersetzen besteht aus Loeschen <em>und</em> Einfuegen. Bricht etwas
 * dazwischen ab, steht die Tabelle leer da und sieht aus wie "dieser Charakter
 * hat keine Kontakte" - eine Aussage, die niemand getroffen hat. Beides muss
 * also in einer Transaktion liegen. Ein {@code @Transactional} auf einer Methode
 * derselben Klasse, die sich selbst aufruft, wirkt bei Spring <b>nicht</b>: der
 * Aufruf geht am Proxy vorbei. Also liegen die schreibenden Methoden in einer
 * eigenen Bohne, genau wie beim {@code AssetSyncService}.</p>
 *
 * <p>Und die abholenden Dienste duerfen ihre HTTP-Aufrufe nicht in einer offenen
 * Transaktion machen. Hier ist die Trennung erzwungen: dieser Klasse ist ESI
 * unbekannt.</p>
 */
@Slf4j
@Service
public class AltSourceStore {

    private final CharacterIskTransferRepository iskTransferRepo;
    private final CharacterContactRepository contactRepo;
    private final CharacterMailCountRepository mailCountRepo;
    private final CorporationMemberPresenceRepository presenceRepo;

    public AltSourceStore(CharacterIskTransferRepository iskTransferRepo,
                          CharacterContactRepository contactRepo,
                          CharacterMailCountRepository mailCountRepo,
                          CorporationMemberPresenceRepository presenceRepo) {
        this.iskTransferRepo = iskTransferRepo;
        this.contactRepo = contactRepo;
        this.mailCountRepo = mailCountRepo;
        this.presenceRepo = presenceRepo;
    }

    /**
     * Haengt neue Ueberweisungen an; bereits bekannte werden uebergangen.
     *
     * <p>Angehaengt und nicht ersetzt: das Journal reicht nur rund dreissig Tage
     * zurueck, ein Ersetzen wuerde also alles Aeltere bei jedem Lauf wegwerfen.
     * Der Abgleich laeuft ueber die Journal-ID, weil zwei Ueberweisungen
     * derselben Summe am selben Tag durchaus vorkommen.</p>
     *
     * @return wieviele Zeilen tatsaechlich neu waren
     */
    @Transactional
    public int appendIskTransfers(Long characterId, List<CharacterIskTransfer> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return 0;
        }
        Set<Long> known = new HashSet<>(iskTransferRepo.findJournalRefIdsByCharacterId(characterId));

        List<CharacterIskTransfer> fresh = candidates.stream()
                .filter(transfer -> known.add(transfer.getJournalRefId()))
                .toList();

        if (fresh.isEmpty()) {
            return 0;
        }
        iskTransferRepo.saveAll(fresh);
        log.info("ISK-Ueberweisungen fuer Charakter {}: {} neue Zeilen.", characterId, fresh.size());
        return fresh.size();
    }

    /** Ersetzt die Kontakt-Momentaufnahme eines Charakters vollstaendig. */
    @Transactional
    public void replaceContacts(Long characterId, List<CharacterContact> contacts) {
        contactRepo.deleteByCharacterId(characterId);
        if (contacts != null && !contacts.isEmpty()) {
            contactRepo.saveAll(contacts);
        }
        log.info("Kontakte fuer Charakter {} aktualisiert: {} Eintraege.",
                characterId, contacts == null ? 0 : contacts.size());
    }

    /** Ersetzt die Mail-Zaehlung eines Charakters vollstaendig. */
    @Transactional
    public void replaceMailCounts(Long characterId, List<CharacterMailCount> counts) {
        mailCountRepo.deleteByCharacterId(characterId);
        if (counts != null && !counts.isEmpty()) {
            mailCountRepo.saveAll(counts);
        }
        log.info("Mail-Zaehlung fuer Charakter {} aktualisiert: {} Paare.",
                characterId, counts == null ? 0 : counts.size());
    }

    /** Haengt die veraenderten Anwesenheitszeilen eines Laufs an. */
    @Transactional
    public void appendPresence(List<CorporationMemberPresence> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        presenceRepo.saveAll(rows);
        log.info("Anwesenheit: {} veraenderte Mitglieder festgehalten.", rows.size());
    }
}
