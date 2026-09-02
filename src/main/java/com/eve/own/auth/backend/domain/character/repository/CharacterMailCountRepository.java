package com.eve.own.auth.backend.domain.character.repository;

import java.time.Instant;
import com.eve.own.auth.backend.domain.character.entity.CharacterMailCount;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
public interface CharacterMailCountRepository extends JpaRepository<CharacterMailCount, Long> {

    List<CharacterMailCount> findByCharacterId(Long characterId);

    List<CharacterMailCount> findByCounterpartyId(Long counterpartyId);

    /**
     * Die Zaehlstaende dieser Postfaecher in einem Zug - fuer die Bewertung, die
     * je Corporation Tausende Paare rechnet.
     *
     * <p>Auch hier vollstaendig und nicht auf die gesuchte Gegenpartei
     * eingeschraenkt: nur so laesst sich "das Postfach wurde gezaehlt, es gab
     * aber nichts mit diesem Charakter" von "dieses Postfach wurde nie
     * gezaehlt" unterscheiden. Das erste ist eine Messung, das zweite ist keine.</p>
     */
    List<CharacterMailCount> findByCharacterIdIn(Collection<Long> characterIds);

    boolean existsByCharacterId(Long characterId);

    /** Massenloeschung aus demselben Grund wie bei {@code CharacterContactRepository}. */
    @Modifying
    @Query("DELETE FROM CharacterMailCount m WHERE m.characterId = :characterId")
    void deleteByCharacterId(Long characterId);

    /**
     * Der Loeschlauf der Aufbewahrungsfrist.
     *
     * <p>Noetig, obwohl jeder Erfassungslauf die Zeilen eines Charakters ersetzt:
     * das Ersetzen geschieht <em>je Charakter</em> und nur, wenn dieser Charakter
     * im Lauf ueberhaupt vorkommt. Wer sein Token entzieht, wessen Token
     * ungueltig wird oder wessen Quelle abgeschaltet wird, faellt aus dem Lauf
     * heraus - und seine Zeilen blieben sonst fuer immer liegen. Genau das ist
     * die Luecke zwischen dem, was die Oberflaeche zusagt, und dem, was die
     * Bauform leistet.</p>
     */
    @Modifying
    @Query("DELETE FROM CharacterMailCount e WHERE e.countedAt < :threshold")
    int deleteOlderThan(Instant threshold);
}
