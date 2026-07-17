package com.eve.own.auth.backend.domain.auth.repository;

import com.eve.own.auth.backend.domain.auth.entity.SystemRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SystemRoleRepository extends JpaRepository<SystemRole, String> {
    List<SystemRole> findByIsSpecialTrue();
}