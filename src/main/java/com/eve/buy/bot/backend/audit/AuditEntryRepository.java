package com.eve.buy.bot.backend.audit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;

/** Persistenz der Protokolleinträge. */
@Repository
public interface AuditEntryRepository extends JpaRepository<AuditEntry, Long> {

    /**
     * Liest Einträge, wahlweise gefiltert nach Kategorie und Mindestschwere.
     *
     * <p>Beide Filter sind optional: wird {@code null} übergeben, greift der jeweilige Filter
     * nicht. Die Schwere wird über ihre Ordinalzahl verglichen, INFO &lt; WARN &lt; ERROR.
     *
     * @param category    gesuchte Kategorie oder {@code null} für alle
     * @param minSeverity Mindestschwere oder {@code null} für alle
     * @param pageable    Seitenschnitt und Sortierung
     * @return die passenden Einträge
     */
    @Query("""
            SELECT a FROM AuditEntry a
            WHERE (:category IS NULL OR a.category = :category)
              AND (:minSeverity IS NULL OR a.severity >= :minSeverity)
            ORDER BY a.occurredAt DESC
            """)
    Page<AuditEntry> search(@Param("category") AuditCategory category,
                            @Param("minSeverity") AuditSeverity minSeverity,
                            Pageable pageable);

    /**
     * Löscht Einträge, die älter sind als der angegebene Zeitpunkt.
     *
     * @param threshold Zeitpunkt, vor dem gelöscht wird
     * @return Anzahl der gelöschten Einträge
     */
    @Modifying
    @Query("DELETE FROM AuditEntry a WHERE a.occurredAt < :threshold")
    int deleteOlderThan(@Param("threshold") Instant threshold);
}
