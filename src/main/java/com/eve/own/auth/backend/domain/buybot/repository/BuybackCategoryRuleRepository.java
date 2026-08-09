package com.eve.own.auth.backend.domain.buybot.repository;

import com.eve.own.auth.backend.domain.buybot.entity.BuybackCategoryRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface BuybackCategoryRuleRepository extends JpaRepository<BuybackCategoryRule, Long> {
}