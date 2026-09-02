package com.eve.own.auth.backend.domain.character.repository;

import com.eve.own.auth.backend.domain.character.entity.CharacterIskTransfer;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
public interface CharacterIskTransferRepository extends JpaRepository<CharacterIskTransfer, Long> {

    List<CharacterIskTransfer> findByCharacterId(Long characterId);

    /**
     * Alle Journalzeilen dieser Charaktere in einem Zug.
     *
     * <p>Fuer die Bewertung, die je Corporation ein Kreuzprodukt rechnet. Eine
     * Abfrage je Paar waeren bei 273 unregistrierten Mitgliedern gegen 11 Konten
     * rund 3.000 Abfragen an einem einzigen Seitenaufruf.</p>
     *
     * <p><b>Nur die Journal-Eigner werden gesucht, nicht die Gegenparteien.</b>
     * Das genuegt: eine Zeile entsteht ausschliesslich auf der Seite eines
     * registrierten Charakters, und der ist bei jedem hier bewerteten Paar
     * entweder selbst gesucht oder Teil des Kontos. Zusaetzlich nach
     * {@code counterparty_id} zu suchen brachte dieselben Zeilen ein zweites
     * Mal.</p>
     */
    List<CharacterIskTransfer> findByCharacterIdIn(Collection<Long> characterIds);

    /**
     * Nur die Journal-IDs, nicht die ganzen Zeilen.
     *
     * <p>Der Abgleich braucht ausschliesslich die Schluessel. Volle Entitaeten
     * zu laden hiesse, bei jedem Charakter-Sync - alle zehn Minuten - saemtliche
     * je gespeicherten Ueberweisungen durch den Persistenzkontext zu ziehen,
     * nur um sie sofort wieder zu verwerfen.</p>
     */
    @Query("SELECT t.journalRefId FROM CharacterIskTransfer t WHERE t.characterId = :characterId")
    List<Long> findJournalRefIdsByCharacterId(Long characterId);

    /**
     * Loescht alles, was vor dem Zeitpunkt liegt.
     *
     * <p>Verglichen wird der Zeitpunkt der Ueberweisung, nicht der des Abrufs:
     * die Frist gilt dem Ereignis, nicht dem Zufall, wann wir es gesehen haben.</p>
     */
    @Modifying
    @Query("DELETE FROM CharacterIskTransfer t WHERE t.occurredAt < :threshold")
    int deleteOlderThan(Instant threshold);

    @Modifying
    @Query("DELETE FROM CharacterIskTransfer t WHERE t.characterId = :characterId")
    void deleteByCharacterId(Long characterId);
}
