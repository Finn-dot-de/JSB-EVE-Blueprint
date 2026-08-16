package com.eve.own.auth.backend.domain.fleet.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * Ein benannter Satz Skills, der ueber die reinen Voraussetzungen hinausgeht.
 *
 * <p>Die Stammdaten sagen nur, was noetig ist, um ein Modul ueberhaupt online
 * zu bekommen. Ob jemand mit dem Schiff auch etwas ausrichtet, haengt an den
 * Unterstuetzungs-Skills - dem, was in EVE gemeinhin "Magic 14" heisst. Die
 * stehen in keiner Voraussetzung und muessen deshalb von Hand hinterlegt
 * werden.</p>
 *
 * <p>Benannt und wiederverwendbar, weil derselbe Satz an vielen Fittings
 * haengt: einmal pflegen statt dreissigmal abtippen.</p>
 */
@Entity
@Table(name = "skill_plans")
@Getter
@Setter
public class SkillPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    private String description;

    private String createdBy;

    private Instant createdAt;
}
