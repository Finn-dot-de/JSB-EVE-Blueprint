package com.eve.own.auth.backend.domain.mining.repository;

import com.eve.own.auth.backend.domain.mining.entity.MiningTaxInvoice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MiningTaxInvoiceRepository extends JpaRepository<MiningTaxInvoice, Long> {
    List<MiningTaxInvoice> findByMainCharacterId(Long mainCharacterId);
}