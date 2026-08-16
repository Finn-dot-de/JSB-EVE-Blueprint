package com.eve.buy.bot.backend.domain.eve.repository;

import com.eve.buy.bot.backend.domain.eve.entity.InvCategory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/** Lesezugriff auf die Item-Kategorien der EVE-Statikdatenbank. */
@Repository
public interface InvCategoryRepository extends JpaRepository<InvCategory, Long> {
    Optional<InvCategory> findByCategoryNameIgnoreCase(String categoryName);
}