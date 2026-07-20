package com.eve.own.auth.backend.domain.character.repository;

import com.eve.own.auth.backend.domain.character.entity.AssetSummary;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AssetSummaryRepository extends JpaRepository<AssetSummary, Long> {
    List<AssetSummary> findByCharacterId(Long characterId);
}