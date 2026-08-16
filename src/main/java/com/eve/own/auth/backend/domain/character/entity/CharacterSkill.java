package com.eve.own.auth.backend.domain.character.entity;

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
 * Ein trainierter Skill eines Charakters, gespiegelt aus /characters/{id}/skills/.
 *
 * <p>Grundlage fuer den Doktrin-Skillcheck: erst mit diesen Zeilen laesst sich
 * gegen die SDE-Anforderungen (dgmTypeAttributes) pruefen, wer eine Huelle
 * tatsaechlich fliegen kann.</p>
 */
@Entity
@Table(name = "character_skills", indexes = {
        @Index(name = "idx_skill_char_id", columnList = "character_id"),
        @Index(name = "idx_skill_type_id", columnList = "skill_type_id"),
        @Index(name = "idx_skill_type_char", columnList = "skill_type_id, character_id")
})
@Getter
@Setter
public class CharacterSkill {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "character_id", nullable = false)
    private Long characterId;

    /** typeID des Skills aus der SDE (Kategorie 16). */
    @Column(name = "skill_type_id", nullable = false)
    private Long skillTypeId;

    /**
     * Aktuell <em>wirksames</em> Level (0-5). Fuer den "Kann er fliegen?"-Check
     * ist ausschliesslich dieser Wert relevant - bei einem Omega-zu-Alpha-Wechsel
     * faellt er unter das trainierte Level zurueck.
     */
    @Column(name = "active_level", nullable = false)
    private Integer activeLevel;

    /** Trainiertes Level, unabhaengig vom Alpha/Omega-Status. */
    @Column(name = "trained_level")
    private Integer trainedLevel;

    @Column(name = "skillpoints")
    private Long skillpoints;
}
