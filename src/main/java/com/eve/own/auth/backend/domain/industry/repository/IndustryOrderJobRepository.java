package com.eve.own.auth.backend.domain.industry.repository;

import com.eve.own.auth.backend.domain.industry.entity.IndustryOrderJob;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Die Zuordnung von Jobs zu Auftraegen - was ESI nicht liefert. */
public interface IndustryOrderJobRepository extends JpaRepository<IndustryOrderJob, Long> {

    List<IndustryOrderJob> findByOrderId(Long orderId);

    /**
     * Die Summe der bereits gelieferten Stueck.
     *
     * <p>Der Fortschritt kommt aus dem Jobbuch und nicht aus den Hangars. Wer ein
     * fertiges Schiff verkauft, hat es trotzdem gebaut - ein an Bestaenden
     * gemessener Fortschritt liefe rueckwaerts.</p>
     */
    @Query("""
           SELECT COALESCE(SUM(oj.unitsProduced), 0) FROM IndustryOrderJob oj
           WHERE oj.orderId = :orderId
           """)
    long deliveredFor(@Param("orderId") Long orderId);

    void deleteByOrderId(Long orderId);
}
