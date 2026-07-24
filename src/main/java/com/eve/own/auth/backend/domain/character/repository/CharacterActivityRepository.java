package com.eve.own.auth.backend.domain.character.repository;

import com.eve.own.auth.backend.domain.character.entity.CharacterActivity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CharacterActivityRepository extends JpaRepository<CharacterActivity, Long> {
    List<CharacterActivity> findByCharacterId(Long characterId);

    List<CharacterActivity> findByCharacterIdIn(List<Long> characterIds);

    @Modifying
    @Query("DELETE FROM CharacterActivity a WHERE a.characterId = :characterId")
    void deleteByCharacterId(Long characterId);
}