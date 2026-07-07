package com.eveblueprint.backend.database.repository;

import com.eveblueprint.backend.database.entity.Corporation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CorporationRepository extends JpaRepository<Corporation, Long> {
}