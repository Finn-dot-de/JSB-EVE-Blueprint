package com.eve.own.auth.backend.domain.buybot.repository;

import com.eve.own.auth.backend.domain.buybot.entity.BuybackLocation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface BuybackLocationRepository extends JpaRepository<BuybackLocation, Long> {
}