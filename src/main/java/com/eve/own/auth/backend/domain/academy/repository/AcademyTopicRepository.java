package com.eve.own.auth.backend.domain.academy.repository;

import com.eve.own.auth.backend.domain.academy.entity.AcademyTopic;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Die Schulungsthemen der Academy. */
@Repository
public interface AcademyTopicRepository extends JpaRepository<AcademyTopic, Long> {

    /**
     * Alle Themen, nach Titel sortiert - die Sicht der Verwaltung, inaktive
     * eingeschlossen.
     *
     * <p>Die Sortierung gehoert hierher und nicht in die Anzeige: ohne
     * {@code ORDER BY} bestimmt die Datenbank die Reihenfolge, und die Karten
     * springen dann nach jedem Speichern.</p>
     */
    List<AcademyTopic> findAllByOrderByTitleAsc();

    /** Die Themen, die im Reiter "Themen" erscheinen. */
    List<AcademyTopic> findAllByActiveTrueOrderByTitleAsc();

    /**
     * Ein Thema mit diesem Titel, ohne Ruecksicht auf Gross- und Kleinschreibung.
     *
     * <p>Fuer die lesbare Meldung beim Speichern. Der Unique-Constraint an der
     * Spalte faengt das Wettrennen ab, sagt dem Autor aber nur "constraint
     * violation" - und "EWar Grundlagen" gegen "ewar grundlagen" faenge er gar
     * nicht ab, obwohl es fuer jeden Leser dasselbe Thema ist.</p>
     */
    Optional<AcademyTopic> findByTitleIgnoreCase(String title);
}
