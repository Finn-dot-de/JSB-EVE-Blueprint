package com.eve.own.auth.backend.domain.navigation.repository;

import com.eve.own.auth.backend.domain.navigation.entity.NavigationCategory;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface NavigationCategoryRepository extends JpaRepository<NavigationCategory, Long> {

    Optional<NavigationCategory> findByNameIgnoreCase(String name);
}
