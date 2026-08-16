package com.eve.buy.bot.backend.domain.character.repository;

import com.eve.buy.bot.backend.domain.character.entity.Corporation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Persistenz der Corporations. */
@Repository
public interface CorporationRepository extends JpaRepository<Corporation, Long> {
}