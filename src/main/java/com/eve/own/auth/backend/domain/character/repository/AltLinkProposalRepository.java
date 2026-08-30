package com.eve.own.auth.backend.domain.character.repository;

import com.eve.own.auth.backend.domain.character.entity.AltLinkProposal;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Zugriff auf die bestaetigten Alt-Vormerkungen. */
@Repository
public interface AltLinkProposalRepository extends JpaRepository<AltLinkProposal, Long> {

    /**
     * Die Vormerkung zu einem nicht registrierten Charakter.
     *
     * <p>Hoechstens eine - die Eindeutigkeit steht als Bedingung an der Tabelle.
     * Diese Abfrage ist die Pruefung, die verhindert, dass eine bestehende
     * Vormerkung stillschweigend durch eine zweite ersetzt wird.</p>
     */
    Optional<AltLinkProposal> findByUnauthedCharacterId(Long unauthedCharacterId);

    /**
     * Alle Vormerkungen der genannten Charaktere in EINEM Zug.
     *
     * <p>Der Vorschlagslauf muss je Corporation mehrere hundert IDs pruefen;
     * einzeln waeren das mehrere hundert Abfragen fuer eine Liste, die
     * ueblicherweise leer ist.</p>
     */
    List<AltLinkProposal> findByUnauthedCharacterIdIn(List<Long> unauthedCharacterIds);

    /** Die Vormerkungen eines Kontos - fuer die spaetere Anzeige beim Main. */
    List<AltLinkProposal> findByMainCharacterId(Long mainCharacterId);
}
