package com.eve.own.auth.backend.domain.eve.repository;

import com.eve.own.auth.backend.domain.eve.entity.EveType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface EveTypeRepository extends JpaRepository<EveType, Long> {
}