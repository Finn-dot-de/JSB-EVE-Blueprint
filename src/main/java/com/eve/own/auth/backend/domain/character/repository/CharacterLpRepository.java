package com.eve.own.auth.backend.domain.character.repository;

import com.eve.own.auth.backend.domain.character.entity.CharacterLp;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CharacterLpRepository extends JpaRepository<CharacterLp, Long> {

    @Modifying
    @Query("DELETE FROM CharacterLp lp WHERE lp.characterId = :characterId")
    void deleteByCharacterId(Long characterId);

    // NEU: Rechnet die LP von allen Alts pro Fraktion zusammen
    @Query("SELECT lp.corporationId, SUM(lp.loyaltyPoints) FROM CharacterLp lp WHERE lp.characterId IN :characterIds GROUP BY lp.corporationId ORDER BY SUM(lp.loyaltyPoints) DESC")
    List<Object[]> aggregateLp(List<Long> characterIds);
}