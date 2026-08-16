package com.eve.buy.bot.backend.domain.buybot.repository;

import com.eve.buy.bot.backend.domain.buybot.entity.BuybackTypeRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Persistenz der Einzelitem-Regeln. */
@Repository
public interface BuybackTypeRuleRepository extends JpaRepository<BuybackTypeRule, Long> {
}