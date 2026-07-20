package com.eve.own.auth.backend.domain.auth.repository;

import com.eve.own.auth.backend.domain.auth.entity.TitleRoleMapping;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface TitleRoleMappingRepository extends JpaRepository<TitleRoleMapping, Long> {
    List<TitleRoleMapping> findByCorporationIdAndTitleIdIn(Long corporationId, List<Long> titleIds);


    List<TitleRoleMapping> findByCorporationId(Long corporationId);



}