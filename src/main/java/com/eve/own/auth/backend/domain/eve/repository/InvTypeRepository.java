package com.eve.own.auth.backend.domain.eve.repository;

import com.eve.own.auth.backend.domain.eve.entity.InvType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface InvTypeRepository extends JpaRepository<InvType, Long> {
    Optional<InvType> findByTypeNameIgnoreCase(String typeName);

    // Native Query: Sucht EXAKT nur in Kategorie 25 (Erze, Eis, Monde) und Gruppe 711 (Gas)
    // FIX: Filtert "Compressed", "Cosmetic" und "Blockout" (CCP Entwickler-Müll) raus!
    @Query(value = """
        SELECT t.* FROM evesde."invTypes" t
        JOIN evesde."invGroups" g ON t."groupID" = g."groupID"
        WHERE LOWER(t."typeName") LIKE LOWER(CONCAT('%', :typeName, '%'))
          AND (g."categoryID" = 25 OR g."groupID" = 711)
          AND LOWER(t."typeName") NOT LIKE '%compressed%'
          AND LOWER(t."typeName") NOT LIKE '%cosmetic%'
          AND LOWER(t."typeName") NOT LIKE '%blockout%'
        ORDER BY LENGTH(t."typeName") ASC, t."typeName" ASC
        LIMIT 20
        """, nativeQuery = true)
    List<InvType> searchMineables(@Param("typeName") String typeName);

    // Holt alle echten Abbaubaren Materialien für den Start-Initializer
    @Query(value = """
        SELECT t.* FROM evesde."invTypes" t
        JOIN evesde."invGroups" g ON t."groupID" = g."groupID"
        WHERE (g."categoryID" = 25 OR g."groupID" = 711)
          AND LOWER(t."typeName") NOT LIKE '%compressed%'
          AND LOWER(t."typeName") NOT LIKE '%cosmetic%'
          AND LOWER(t."typeName") NOT LIKE '%blockout%'
        """, nativeQuery = true)
    List<InvType> findAllMineables();
}