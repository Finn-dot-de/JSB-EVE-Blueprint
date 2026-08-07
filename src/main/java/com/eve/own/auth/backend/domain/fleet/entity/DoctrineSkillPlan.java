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
 * Verknuepft ein Fitting mit einem Skillplan.
 *
 * <p>Beides in beide Richtungen mehrfach: ein Fitting kann mehrere Plaene
 * tragen (etwa "Magic 14" und "Raketen fortgeschritten"), und derselbe Plan
 * haengt an vielen Fittings.</p>
 *
 * <p>Bewusst als eigene Zeile mit blanken IDs statt als JPA-Beziehung - so
 * wie es die uebrigen Zuordnungen dieser Anwendung auch halten.</p>
 */
@Entity
@Table(name = "doctrine_skill_plans", indexes = {
        @Index(name = "idx_doctrine_plan_doctrine", columnList = "doctrine_id"),
        @Index(name = "idx_doctrine_plan_plan", columnList = "plan_id")
})
@Getter
@Setter
public class DoctrineSkillPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "doctrine_id", nullable = false)
    private Long doctrineId;

    @Column(name = "plan_id", nullable = false)
    private Long planId;
}
