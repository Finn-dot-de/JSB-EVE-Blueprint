package com.eve.buy.bot.backend.domain.auth.repository;

import com.eve.buy.bot.backend.domain.auth.entity.SystemRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/** Persistenz der Systemrollen. */
@Repository
public interface SystemRoleRepository extends JpaRepository<SystemRole, String> {
    List<SystemRole> findByIsSpecialTrue();
}