package com.eve.own.auth.backend.domain.groups.repository;

import com.eve.own.auth.backend.domain.groups.entity.AuthGroupRequest;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Die Beitrittsanfragen.
 *
 * <p>Alle Abfragen gehen ueber den Status, weil entschiedene Anfragen als
 * Nachweis stehen bleiben (siehe {@link AuthGroupRequest}). Ohne die
 * Einschraenkung waechst der Posteingang der Verwaltung mit jeder je
 * getroffenen Entscheidung weiter an.</p>
 */
@Repository
public interface AuthGroupRequestRepository extends JpaRepository<AuthGroupRequest, Long> {

    /** Alle Anfragen mit diesem Status - fuer Admins, die jede Gruppe sehen. */
    List<AuthGroupRequest> findByStatus(String status);

    /**
     * Die Anfragen mit diesem Status zu einer Menge von Gruppen.
     *
     * <p>Der Weg des Leiters: er bekommt die IDs seiner eigenen Gruppen
     * herein und damit ausschliesslich Anfragen, ueber die er auch entscheiden
     * darf. Achtung beim Aufruf - eine leere Menge liefert eine leere Liste,
     * das ist hier genau das gewuenschte Verhalten.</p>
     */
    List<AuthGroupRequest> findByStatusAndGroupIdIn(String status, Collection<Long> groupIds);

    /** Die Anfragen eines Charakters mit diesem Status - fuer das Kennzeichen "Anfrage ausstehend". */
    List<AuthGroupRequest> findByCharacterIdAndStatus(Long characterId, String status);

    /**
     * Ob dieser Charakter zu dieser Gruppe bereits eine Anfrage in diesem
     * Status hat.
     *
     * <p>Der Riegel gegen die doppelte Bewerbung: der Knopf ist im Browser zwar
     * abgeschaltet, sobald eine offene Anfrage vorliegt, doch der Endpunkt
     * steht jedem Angemeldeten offen und muss selbst pruefen.</p>
     */
    boolean existsByGroupIdAndCharacterIdAndStatus(Long groupId, Long characterId, String status);

    /**
     * Raeumt die Anfragen einer geloeschten Gruppe ab.
     *
     * <p>Die Anfragen zeigen ueber eine blanke ID auf die Gruppe, es gibt also
     * keinen Fremdschluessel, der sie mitnehmen wuerde. Bleiben sie stehen,
     * taucht die Verwaltung Anfragen zu einer Gruppe auf, die es nicht mehr
     * gibt. Der Aufrufer muss eine Transaktion halten.</p>
     */
    void deleteByGroupId(Long groupId);
}
