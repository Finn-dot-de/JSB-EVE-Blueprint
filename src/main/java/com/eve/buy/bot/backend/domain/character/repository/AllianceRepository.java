package com.eve.buy.bot.backend.domain.character.repository;

import com.eve.buy.bot.backend.domain.character.entity.Alliance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Persistenz der Allianzen. */
@Repository
public interface AllianceRepository extends JpaRepository<Alliance, Long> {
}