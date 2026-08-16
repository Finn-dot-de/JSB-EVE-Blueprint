package com.eve.own.auth.backend.domain.industry.repository;

import com.eve.own.auth.backend.domain.industry.entity.IndustryOrderBaseline;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Die Nullmessung eines Auftrags - was bei Anlage schon im Hangar lag. */
public interface IndustryOrderBaselineRepository
        extends JpaRepository<IndustryOrderBaseline, Long> {

    List<IndustryOrderBaseline> findByOrderId(Long orderId);

    Optional<IndustryOrderBaseline> findByOrderIdAndTypeId(Long orderId, Long typeId);

    void deleteByOrderId(Long orderId);
}
