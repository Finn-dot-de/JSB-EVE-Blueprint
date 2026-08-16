package com.eve.own.auth.backend.domain.fleet.repository;

import com.eve.own.auth.backend.domain.fleet.entity.DoctrineSkillPlan;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface DoctrineSkillPlanRepository extends JpaRepository<DoctrineSkillPlan, Long> {

    List<DoctrineSkillPlan> findByDoctrineId(Long doctrineId);

    List<DoctrineSkillPlan> findByDoctrineIdIn(Collection<Long> doctrineIds);

    void deleteByDoctrineId(Long doctrineId);

    void deleteByPlanId(Long planId);
}
