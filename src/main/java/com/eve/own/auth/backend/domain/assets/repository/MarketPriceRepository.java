package com.eve.own.auth.backend.domain.assets.repository;

import com.eve.own.auth.backend.domain.assets.entity.MarketPrice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface MarketPriceRepository extends JpaRepository<MarketPrice, Long> {
}
