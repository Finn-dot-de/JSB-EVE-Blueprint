package com.eve.buy.bot.backend.domain.buybot.repository;

import com.eve.buy.bot.backend.domain.buybot.entity.BuybackConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Persistenz der Buybot-Konfiguration. */
@Repository
public interface BuybackConfigRepository extends JpaRepository<BuybackConfig, Long> {
}