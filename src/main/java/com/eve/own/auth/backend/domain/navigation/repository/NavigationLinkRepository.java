package com.eve.own.auth.backend.domain.navigation.repository;

import com.eve.own.auth.backend.domain.navigation.entity.NavigationLink;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface NavigationLinkRepository extends JpaRepository<NavigationLink, Long> {

    List<NavigationLink> findByCategoryId(Long categoryId);

    boolean existsByUrl(String url);
}
