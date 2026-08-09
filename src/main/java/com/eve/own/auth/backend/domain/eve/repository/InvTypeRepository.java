package com.eve.own.auth.backend.domain.eve.repository;

import com.eve.own.auth.backend.domain.buybot.dto.TypeDetailsProjection;
import com.eve.own.auth.backend.domain.eve.entity.InvType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.Optional;

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
}