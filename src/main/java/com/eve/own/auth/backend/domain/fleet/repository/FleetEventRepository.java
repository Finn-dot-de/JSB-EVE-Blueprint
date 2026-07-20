package com.eve.own.auth.backend.domain.fleet.repository;

import com.eve.own.auth.backend.domain.fleet.entity.FleetEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface FleetEventRepository extends JpaRepository<FleetEvent, Long> {
    Optional<FleetEvent> findByTrackingCode(String trackingCode);

    List<FleetEvent> findByEndTimeIsNull(); // Wird vom Auto-Tracker genutzt (nur Live)

    // NEU: Wird vom Frontend genutzt (Live + Beendet der letzten 24h)
    List<FleetEvent> findByStartTimeAfterOrderByStartTimeDesc(Instant startTime);
}