package com.eve.own.auth.backend.domain.auth.repository;

import com.eve.own.auth.backend.domain.auth.entity.RoleAssignmentAudit;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Der Zugriff auf den Nachweis der von Hand vergebenen Rollen.
 *
 * <p>Beide Abfragen liefern absteigend nach Zeitpunkt: gefragt ist immer "was
 * ist zuletzt passiert", nie "was war zuerst". Die Tabelle wird nie aufgeraeumt
 * (siehe {@link RoleAssignmentAudit}), deshalb ist die Sortierung nicht Kosmetik
 * - ohne sie stuende der aelteste Eintrag oben und die Liste waere nach einem
 * Jahr unbrauchbar.</p>
 */
@Repository
public interface RoleAssignmentAuditRepository extends JpaRepository<RoleAssignmentAudit, Long> {

    /** Der Verlauf eines Charakters - die Frage "wie kam er an diese Rolle?". */
    List<RoleAssignmentAudit> findByCharacterIdOrderByOccurredAtDesc(Long characterId);

    /**
     * Die juengsten Eintraege ueber alle Charaktere hinweg.
     *
     * <p>Begrenzt, weil die Tabelle unbegrenzt waechst: eine ungebremste Abfrage
     * laedt irgendwann jede je getroffene Aenderung in den Speicher, nur damit
     * die Oberflaeche die obersten zwanzig zeigt.</p>
     */
    List<RoleAssignmentAudit> findTop200ByOrderByOccurredAtDesc();
}
