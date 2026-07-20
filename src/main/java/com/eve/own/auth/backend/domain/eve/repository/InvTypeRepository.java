package com.eve.own.auth.backend.domain.eve.repository;

import com.eve.own.auth.backend.domain.eve.entity.InvType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public interface InvTypeRepository extends JpaRepository<InvType, Long> {

    Optional<InvType> findByTypeNameIgnoreCase(String typeName);
}