package com.eve.buy.bot.backend.domain.buybot.repository;

import com.eve.buy.bot.backend.domain.buybot.entity.BuybackCategoryRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Persistenz der Kategorie-Whitelist. */
@Repository
public interface BuybackCategoryRuleRepository extends JpaRepository<BuybackCategoryRule, Long> {
}