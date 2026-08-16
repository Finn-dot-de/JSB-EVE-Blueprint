package com.eve.buy.bot.backend.domain.buybot.repository;

import com.eve.buy.bot.backend.domain.buybot.entity.BuybackLocation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Persistenz der Abgabeorte. */
@Repository
public interface BuybackLocationRepository extends JpaRepository<BuybackLocation, Long> {
}