package com.eve.own.auth.backend.domain.mining.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "mining_tax_rates")
@Getter @Setter
public class MiningTaxRate {
    @Id
    private Long typeId;
    private String typeName;
    private String category; // "MOON", "ORE", "GAS", "ICE"
    private Double taxPercentage; // NEU: % vom Jita Buy (z.B. 10.0 für 10%)
    private Double currentJitaBuy; // NEU: Letzter bekannter Jita Buy Preis
}