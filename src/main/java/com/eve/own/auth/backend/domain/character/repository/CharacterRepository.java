package com.eve.own.auth.backend.domain.character.repository;

import com.eve.own.auth.backend.domain.character.entity.Character;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface CharacterRepository extends JpaRepository<com.eve.own.auth.backend.domain.character.entity.Character, Long> {

    @Query("SELECT c FROM Character c WHERE c.tokenExpiry < :threshold")
    List<Character> findCharactersWithExpiringTokens(Instant threshold);

    List<Character> findByMainCharacterId(Long mainCharacterId);

    @Query("SELECT c FROM Character c WHERE c.refreshToken IS NULL AND c.mainCharacterId = :mainId")
    List<Character> findAltsWithoutTokens(Long mainId);

    @EntityGraph(attributePaths = {"corporation", "roles"})
    @Query("SELECT c FROM Character c")
    List<Character> findAllWithCorporation();
}