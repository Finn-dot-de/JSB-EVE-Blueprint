package com.eve.own.auth.backend.domain.assets.repository;

import com.eve.own.auth.backend.domain.assets.entity.AssetLocation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AssetLocationRepository extends JpaRepository<AssetLocation, Long> {

    /**
     * Alle Root-Locations aus den Assets, fuer die es noch keinen Namens-Eintrag gibt.
     */
    @Query(value = """
            SELECT DISTINCT a.root_location_id
            FROM character_assets a
            WHERE a.root_location_id IS NOT NULL
              AND NOT EXISTS (
                  SELECT 1 FROM asset_locations l
                  WHERE l.location_id = a.root_location_id
                    AND (l.resolve_failed IS NULL OR l.resolve_failed = false)
              )
            """, nativeQuery = true)
    List<Long> findUnresolvedLocationIds();
}
