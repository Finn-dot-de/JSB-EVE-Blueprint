package com.eve.own.auth.backend.domain.character.repository;

import com.eve.own.auth.backend.domain.character.entity.CharacterSkill;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
public interface CharacterSkillRepository extends JpaRepository<CharacterSkill, Long> {

    @Modifying
    @Query("DELETE FROM CharacterSkill s WHERE s.characterId = :characterId")
    void deleteByCharacterId(Long characterId);

    /**
     * Wird gebraucht, um ein ESI-304 richtig zu deuten: "unveraendert" heisst nur
     * dann "nichts zu tun", wenn zu dem Charakter ueberhaupt schon Skills in der
     * Datenbank liegen. Sonst bliebe ein Charakter, dessen ETag aus einem
     * frueheren Sync stammt, dauerhaft ohne Skill-Daten.
     */
    boolean existsByCharacterId(Long characterId);
}
