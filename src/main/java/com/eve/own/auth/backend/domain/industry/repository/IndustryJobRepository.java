package com.eve.own.auth.backend.domain.industry.repository;

import com.eve.own.auth.backend.domain.industry.entity.IndustryJob;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Der Spiegel der Industriejobs aus ESI. */
public interface IndustryJobRepository extends JpaRepository<IndustryJob, Long> {

    List<IndustryJob> findByOwnerCharacterIdIn(Collection<Long> characterIds);

    /**
     * Jobs eines Kontos, die ein bestimmtes Produkt herstellen.
     *
     * <p>Grundlage der automatischen Zuordnung: laeuft irgendwo ein Job auf eine
     * Raven und hat das Konto einen offenen Raven-Auftrag, gehoeren die beiden mit
     * hoher Wahrscheinlichkeit zusammen. Ob wirklich, entscheidet danach die
     * Zeitbedingung.</p>
     */
    @Query("""
           SELECT j FROM IndustryJob j
           WHERE j.ownerCharacterId IN :characterIds
             AND j.productTypeId = :productTypeId
           """)
    List<IndustryJob> findForProduct(@Param("characterIds") Collection<Long> characterIds,
                                     @Param("productTypeId") Long productTypeId);
}
