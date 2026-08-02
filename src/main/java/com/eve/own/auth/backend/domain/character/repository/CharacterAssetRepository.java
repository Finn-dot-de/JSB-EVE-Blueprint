package com.eve.own.auth.backend.domain.character.repository;

import com.eve.own.auth.backend.domain.character.entity.CharacterAsset;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CharacterAssetRepository extends JpaRepository<CharacterAsset, Long> {

    @Modifying
    @Query("DELETE FROM CharacterAsset a WHERE a.characterId = :characterId")
    void deleteByCharacterId(Long characterId);

    // nativeQuery, um das evesde-Schema direkt mit maximaler SQL-Performance zu joinen!
    @Query(value = """
        SELECT 
            g."groupName" as groupName, 
            SUM(a.quantity) as quantity,
            'https://images.evetech.net/types/' || t."typeID" || '/icon?size=64' as imageUrl
        FROM character_assets a
        JOIN evesde."invTypes" t ON a.type_id = t."typeID"
        JOIN evesde."invGroups" g ON t."groupID" = g."groupID"
        WHERE a.character_id IN (:characterIds) 
          AND g."categoryID" = 6
        GROUP BY g."groupName", t."typeID"
        ORDER BY quantity DESC
        """, nativeQuery = true)
    List<Object[]> aggregateAssetsByGroup(List<Long> characterIds);

    /**
     * Alle Typen, die irgendwo in den Assets vorkommen.
     * Grundlage fuer das stuendliche Jita-Preis-Update.
     */
    @Query(value = "SELECT DISTINCT a.type_id FROM character_assets a", nativeQuery = true)
    List<Long> findDistinctAssetTypeIds();
}
