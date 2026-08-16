package com.eve.buy.bot.backend.domain.eve.repository;

import com.eve.buy.bot.backend.domain.buybot.dto.ReprocessMaterialProjection;
import com.eve.buy.bot.backend.domain.buybot.dto.TypeDetailsProjection;
import com.eve.buy.bot.backend.domain.eve.entity.InvType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Lesezugriff auf die Item-Typen der EVE-Statikdatenbank.
 *
 * <p>Die Abfragen sind bewusst nativ formuliert: die SDE-Tabellen tragen die
 * Original-Spaltennamen von CCP in gemischter Schreibweise.
 */
@Repository
public interface InvTypeRepository extends JpaRepository<InvType, Long> {

    Optional<InvType> findByTypeNameIgnoreCase(String typeName);

    @Query(value = """
        SELECT 
            t."typeID" as typeId, 
            t."typeName" as typeName, 
            t.volume as volume, 
            g."categoryID" as categoryId
        FROM evesde."invTypes" t
        JOIN evesde."invGroups" g ON t."groupID" = g."groupID"
        WHERE LOWER(t."typeName") = LOWER(:typeName)
        """, nativeQuery = true)
    TypeDetailsProjection findTypeDetailsByName(@Param("typeName") String typeName);

    /**
     * Gegenstück zur Namenssuche: Verträge liefern über ESI nur Type-IDs,
     * daher brauchen wir denselben Datensatz auch ID-basiert.
     */
    @Query(value = """
        SELECT
            t."typeID" as typeId,
            t."typeName" as typeName,
            t.volume as volume,
            g."categoryID" as categoryId
        FROM evesde."invTypes" t
        JOIN evesde."invGroups" g ON t."groupID" = g."groupID"
        WHERE t."typeID" IN (:typeIds)
        """, nativeQuery = true)
    List<TypeDetailsProjection> findTypeDetailsByIds(@Param("typeIds") Collection<Long> typeIds);

    /**
     * Reprocessing-Ausbeute für die angegebenen Typen (perfekte Ausbeute je portionSize).
     * Blueprints und andere Typen ohne Ausbeute liefern hier einfach keine Zeilen.
     */
    @Query(value = """
        SELECT
            m."typeID" as typeId,
            m."materialTypeID" as materialTypeId,
            m.quantity as quantity,
            t."portionSize" as portionSize
        FROM evesde."invTypeMaterials" m
        JOIN evesde."invTypes" t ON t."typeID" = m."typeID"
        WHERE m."typeID" IN (:typeIds)
          AND m.quantity > 0
        """, nativeQuery = true)
    List<ReprocessMaterialProjection> findReprocessMaterials(@Param("typeIds") Collection<Long> typeIds);
}