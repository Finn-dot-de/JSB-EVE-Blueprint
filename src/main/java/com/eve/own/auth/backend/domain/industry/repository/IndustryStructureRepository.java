package com.eve.own.auth.backend.domain.industry.repository;

import com.eve.own.auth.backend.domain.industry.entity.IndustryStructure;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Die gesammelten Bauorte. */
public interface IndustryStructureRepository extends JpaRepository<IndustryStructure, Long> {

    /**
     * Sucht nach Struktur- oder Systemname.
     *
     * <p>Beides in einer Abfrage, weil der Nutzer beim Tippen nicht unterscheidet:
     * "Jita" ist ein System, "Azbel" ein Strukturtyp, und der eigene Bauort heisst
     * womoeglich "MA Fabrik". Sortiert wird nach Brauchbarkeit - eigene Strukturen
     * mit bekannten Diensten zuerst, denn dort weiss man sicher, ob man andocken
     * und bauen kann.</p>
     */
    @Query("""
           SELECT s FROM IndustryStructure s
           WHERE lower(COALESCE(s.name, '')) LIKE %:needle%
              OR lower(COALESCE(s.systemName, '')) LIKE %:needle%
              OR lower(COALESCE(s.typeName, '')) LIKE %:needle%
           ORDER BY CASE WHEN s.source = 'CORP' THEN 0
                         WHEN s.servicesKnown = true THEN 1
                         ELSE 2 END,
                    s.systemName, s.name
           """)
    List<IndustryStructure> search(@Param("needle") String needle, Limit limit);

    /** Alle Bauorte in einem System - Grundlage der Empfehlungen. */
    List<IndustryStructure> findBySolarSystemId(Long solarSystemId);

    List<IndustryStructure> findBySource(String source);
}
