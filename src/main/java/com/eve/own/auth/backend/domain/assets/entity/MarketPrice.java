package com.eve.own.auth.backend.domain.assets.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * Jita-Preis-Cache fuer ALLE Typen, die irgendwo in den Assets auftauchen.
 * Wird stuendlich vom {@link com.eve.own.auth.backend.domain.assets.scheduler.AssetPriceScheduler}
 * ueber die Fuzzwork-Aggregates befuellt.
 */
@Entity
@Table(name = "market_prices")
@Getter
@Setter
public class MarketPrice {

    @Id
    @Column(name = "type_id")
    private Long typeId;

    @Column(name = "jita_buy")
    private Double jitaBuy;

    @Column(name = "jita_sell")
    private Double jitaSell;

    @Column(name = "updated_at")
    private Instant updatedAt;
}
