package com.eve.own.auth.backend.domain.fleet.repository;

import com.eve.own.auth.backend.domain.fleet.entity.FleetDoctrine;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface FleetDoctrineRepository extends JpaRepository<FleetDoctrine, Long> {
}