package com.eve.own.auth.backend.domain.character.repository;

import com.eve.own.auth.backend.domain.character.entity.CorporationAsset;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
public interface CorporationAssetRepository extends JpaRepository<CorporationAsset, Long> {

    @Modifying
    @Query("DELETE FROM CorporationAsset a WHERE a.corporationId = :corporationId")
    void deleteByCorporationId(Long corporationId);

    /**
     * Analog zu {@code CharacterAssetRepository.hasPendingCustomNames}: gibt es
     * benennbare Corp-Bestaende, deren Ingame-Name noch nie abgefragt wurde?
     * Der Kategorie-Filter muss deckungsgleich mit
     * {@code InvTypeRepository.findNameableTypeIds} bleiben.
     */
    @Query(value = """
            SELECT EXISTS (
                SELECT 1 FROM corporation_assets a
                JOIN evesde."invTypes" t ON t."typeID" = a.type_id
                JOIN evesde."invGroups" g ON g."groupID" = t."groupID"
                WHERE a.corporation_id = :corporationId
                  AND a.is_singleton IS TRUE
                  AND g."categoryID" IN (2, 6, 22, 65)
                  AND a.custom_name IS NULL
            )
            """, nativeQuery = true)
    boolean hasPendingCustomNames(Long corporationId);
}
