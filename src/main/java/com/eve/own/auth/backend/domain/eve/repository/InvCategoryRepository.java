package com.eve.own.auth.backend.domain.eve.repository;

import com.eve.own.auth.backend.domain.eve.entity.InvCategory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface InvCategoryRepository extends JpaRepository<InvCategory, Long> {
    Optional<InvCategory> findByCategoryNameIgnoreCase(String categoryName);
}