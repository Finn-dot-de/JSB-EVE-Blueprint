package com.eve.own.auth.backend.domain.fleet.repository;

import com.eve.own.auth.backend.domain.fleet.entity.FleetAttendance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface FleetAttendanceRepository extends JpaRepository<FleetAttendance, Long> {
    List<FleetAttendance> findByFleetEventId(Long fleetEventId);
    boolean existsByFleetEventIdAndCharacterId(Long fleetEventId, Long characterId);

    // NEU: Holt uns exakt den einen Datensatz des Piloten in dieser Flotte
    Optional<FleetAttendance> findByFleetEventIdAndCharacterId(Long fleetEventId, Long characterId);
}