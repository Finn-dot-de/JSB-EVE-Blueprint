package com.eve.own.auth.backend.domain.navigation.repository;

import com.eve.own.auth.backend.domain.navigation.entity.NavigationLink;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NavigationLinkRepository extends JpaRepository<NavigationLink, Long> {

}