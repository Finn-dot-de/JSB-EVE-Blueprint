package com.eve.own.auth.backend.domain.fleet.repository;

import com.eve.own.auth.backend.domain.fleet.entity.SkillPlanEntry;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SkillPlanEntryRepository extends JpaRepository<SkillPlanEntry, Long> {

    List<SkillPlanEntry> findByPlanId(Long planId);

    List<SkillPlanEntry> findByPlanIdIn(Collection<Long> planIds);

    void deleteByPlanId(Long planId);
}
