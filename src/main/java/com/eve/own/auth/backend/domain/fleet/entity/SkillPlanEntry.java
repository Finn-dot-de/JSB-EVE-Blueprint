package com.eve.own.auth.backend.domain.fleet.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * Eine Zeile eines Skillplans: dieser Skill auf mindestens dieser Stufe.
 *
 * <p>Der Name steht mit in der Zeile, obwohl er aus der typeID herleitbar
 * waere. Er wird beim Speichern einmal gegen die Stammdaten aufgeloest und
 * erspart der Anzeige den staendigen Rueckgriff auf die SDE - die Tabelle
 * {@code invTypes} liegt in einem eigenen Schema und wird zudem regelmaessig
 * neu eingespielt.</p>
 */
@Entity
@Table(name = "skill_plan_entries", indexes = {
        @Index(name = "idx_plan_entry_plan", columnList = "plan_id")
})
@Getter
@Setter
public class SkillPlanEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "plan_id", nullable = false)
    private Long planId;

    @Column(name = "skill_type_id", nullable = false)
    private Long skillTypeId;

    @Column(name = "skill_name", nullable = false)
    private String skillName;

    /** Geforderte Stufe, 1 bis 5. */
    @Column(name = "required_level", nullable = false)
    private Integer requiredLevel;
}
