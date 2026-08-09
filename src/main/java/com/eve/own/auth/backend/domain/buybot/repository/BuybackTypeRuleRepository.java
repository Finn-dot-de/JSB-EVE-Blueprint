package com.eve.own.auth.backend.domain.buybot.repository;

import com.eve.own.auth.backend.domain.buybot.entity.BuybackTypeRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface BuybackTypeRuleRepository extends JpaRepository<BuybackTypeRule, Long> {
}