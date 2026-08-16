package com.eve.buy.bot.backend.domain.auth.repository;

import com.eve.buy.bot.backend.domain.auth.entity.TitleRoleMapping;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

/** Persistenz der Zuordnung von Corp-Titeln zu Rollen. */
@Repository
public interface TitleRoleMappingRepository extends JpaRepository<TitleRoleMapping, Long> {
    List<TitleRoleMapping> findByCorporationIdAndTitleIdIn(Long corporationId, List<Long> titleIds);


    List<TitleRoleMapping> findByCorporationId(Long corporationId);



}