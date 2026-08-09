package com.eve.own.auth.backend.domain.buybot.repository;

import com.eve.own.auth.backend.domain.buybot.entity.BuybackConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface BuybackConfigRepository extends JpaRepository<BuybackConfig, Long> {
}