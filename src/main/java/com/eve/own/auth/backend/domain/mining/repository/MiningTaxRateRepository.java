package com.eve.own.auth.backend.domain.mining.repository;

import com.eve.own.auth.backend.domain.mining.entity.MiningTaxRate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface MiningTaxRateRepository extends JpaRepository<MiningTaxRate, Long> {
}