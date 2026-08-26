package com.eve.own.auth.backend.domain.academy.repository;

import com.eve.own.auth.backend.domain.academy.entity.AcademyInterest;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Die Interessensbekundungen.
 *
 * <p>Keine Abfrage geht ueber einen Status, denn es gibt keinen: eine Bekundung
 * ist da oder sie ist weg (siehe {@link AcademyInterest}).</p>
 */
@Repository
public interface AcademyInterestRepository extends JpaRepository<AcademyInterest, Long> {

    /** Die Bekundungen zu einem Thema - Grundlage von Zaehler und Namensliste. */
    List<AcademyInterest> findByTopicId(Long topicId);

    /**
     * Die Bekundungen zu einer Menge von Themen.
     *
     * <p>Der Weg der Themenliste: ein Ladevorgang fuer alle Karten statt einer
     * Abfrage je Thema. Gezaehlt wird anschliessend im Speicher, wie es
     * {@code AuthGroupService.memberCounts} auch tut. Achtung beim Aufruf: eine
     * leere Menge liefert eine leere Liste - der Aufrufer faengt den Fall
     * trotzdem vorher ab, damit gar keine Abfrage laeuft.</p>
     */
    List<AcademyInterest> findByTopicIdIn(Collection<Long> topicIds);

    /**
     * Die Bekundung dieses Accounts zu diesem Thema.
     *
     * <p>Der Riegel gegen die zweite Zeile: das {@code PUT} ist idempotent, ein
     * zweiter Aufruf desselben Accounts schreibt genau diese Zeile um und legt
     * keine neue an.</p>
     */
    Optional<AcademyInterest> findByTopicIdAndAccountId(Long topicId, Long accountId);

    /** Alle Bekundungen eines Accounts - fuer "Du bist dabei" in der Themenliste. */
    List<AcademyInterest> findByAccountId(Long accountId);

    /**
     * Raeumt die Bekundungen eines geloeschten Themas ab.
     *
     * <p>Die Bekundungen zeigen ueber eine blanke ID auf das Thema, es gibt also
     * keinen Fremdschluessel, der sie mitnaehme. Bleiben sie stehen, zaehlen sie
     * fuer immer auf ein Thema, das es nicht mehr gibt - unsichtbar, aber in der
     * Tabelle. Der Aufrufer muss eine Transaktion halten.</p>
     */
    void deleteByTopicId(Long topicId);
}
