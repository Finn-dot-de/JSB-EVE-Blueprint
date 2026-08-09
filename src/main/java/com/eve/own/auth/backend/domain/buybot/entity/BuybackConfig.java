package com.eve.own.auth.backend.domain.buybot.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "buyback_config")
@Getter
@Setter
public class BuybackConfig {
    @Id
    private Long id = 1L;

    @Column(name = "price_basis", nullable = false)
    private String priceBasis = "buy";

    @Column(name = "global_modifier", nullable = false)
    private Double globalModifier = 90.0;

    @Column(name = "volume_threshold")
    private Double volumeThreshold = 350000.0;

    @Column(name = "value_threshold")
    private Double valueThreshold = 1000000000.0;

    @Column(name = "item_value_threshold")
    private Double itemValueThreshold = 500000000.0;
    
    @Embedded
    private BotTexts botTexts;
}