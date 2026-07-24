package com.eve.own.auth.backend.domain.character.repository;

import com.eve.own.auth.backend.domain.character.entity.CharacterMining;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CharacterMiningRepository extends JpaRepository<CharacterMining, Long> {
    @Modifying
    @Query("DELETE FROM CharacterMining m WHERE m.characterId = :characterId")
    void deleteByCharacterId(Long characterId);

    List<CharacterMining> findByCharacterIdIn(List<Long> characterIds);
}