package com.eve.own.auth.backend.database.repository;

import com.eve.own.auth.backend.database.entity.Character;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CharacterRepository extends JpaRepository<Character, Long> {
}