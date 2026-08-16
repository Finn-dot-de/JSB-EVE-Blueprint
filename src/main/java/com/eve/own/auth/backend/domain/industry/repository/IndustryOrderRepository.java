package com.eve.own.auth.backend.domain.industry.repository;

import com.eve.own.auth.backend.domain.industry.entity.IndustryOrder;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Die Bauauftraege.
 *
 * <p>Eigene Datei und nicht als verschachtelte Schnittstelle in einem Sammler:
 * Spring Data uebergeht verschachtelte Repository-Schnittstellen. Der Scanner
 * legt fuer sie <em>keine</em> Bohne an, und der Fehler faellt erst beim Start
 * auf - der Uebersetzer sieht ihn nicht, und die Tests mit Mocks auch nicht.</p>
 */
public interface IndustryOrderRepository extends JpaRepository<IndustryOrder, Long> {

    /**
     * Die Auftraege eines Kontos, neueste zuerst.
     *
     * <p>Immer ueber die Konto-Nummer und nie ueber die des Charakters: ein Alt
     * soll denselben Auftrag sehen wie der Hauptcharakter.</p>
     */
    List<IndustryOrder> findByAccountIdOrderByCreatedAtDesc(Long accountId);

    /** Ein einzelner Auftrag, aber nur wenn er dem Konto gehoert. */
    Optional<IndustryOrder> findByIdAndAccountId(Long id, Long accountId);

    /** Die laufenden Auftraege eines Kontos - Grundlage der Job-Zuordnung. */
    List<IndustryOrder> findByAccountIdAndStatus(Long accountId, String status);
}
