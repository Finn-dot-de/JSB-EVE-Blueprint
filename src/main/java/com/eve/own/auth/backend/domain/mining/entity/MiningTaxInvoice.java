package com.eve.own.auth.backend.domain.mining.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "mining_tax_invoices", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"main_character_id", "month"})
})
@Getter
@Setter
public class MiningTaxInvoice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "main_character_id", nullable = false)
    private Long mainCharacterId;

    @Column(nullable = false)
    private String month;

    @Column(nullable = false)
    private Double totalTax;

    @Column(columnDefinition = "TEXT")
    private String detailsJson;
}