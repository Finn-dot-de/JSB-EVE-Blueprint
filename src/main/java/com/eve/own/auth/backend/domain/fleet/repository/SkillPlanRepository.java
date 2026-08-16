package com.eve.own.auth.backend.domain.fleet.repository;

import com.eve.own.auth.backend.domain.fleet.entity.SkillPlan;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SkillPlanRepository extends JpaRepository<SkillPlan, Long> {

    Optional<SkillPlan> findByNameIgnoreCase(String name);
}
