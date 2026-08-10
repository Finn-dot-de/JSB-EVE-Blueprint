package com.eve.own.auth.backend.domain.industry.repository;

import com.eve.own.auth.backend.domain.industry.entity.IndustryOrderRequirement;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Die eingefrorene Bedarfstabelle eines Auftrags. */
public interface IndustryOrderRequirementRepository
        extends JpaRepository<IndustryOrderRequirement, Long> {

    List<IndustryOrderRequirement> findByOrderIdOrderByDepthAscQuantityNeededDesc(Long orderId);

    List<IndustryOrderRequirement> findByOrderIdAndDepth(Long orderId, Integer depth);

    Optional<IndustryOrderRequirement> findByOrderIdAndTypeId(Long orderId, Long typeId);

    void deleteByOrderId(Long orderId);
}
