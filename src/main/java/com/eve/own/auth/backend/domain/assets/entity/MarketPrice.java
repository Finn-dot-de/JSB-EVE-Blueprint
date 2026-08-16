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

    /**
     * Der Referenzpreis von CCP, aus {@code /markets/prices/}.
     *
     * <p>Grundlage des geschaetzten Warenwerts und damit der gesamten Jobgebuehr.
     * Die Jita-Preise sind dafuer <em>kein</em> Ersatz: dieser Wert ist ein
     * eigener, ueber Wochen geglaetteter Referenzwert, den CCP selbst rechnet.
     * Er darf {@code null} sein - ESI liefert ihn nicht fuer jeden Typ, und eine
     * fehlende Bewertung muss sichtbar bleiben, statt als null ISK in eine
     * Summe einzugehen.</p>
     */
    @Column(name = "adjusted_price")
    private Double adjustedPrice;

    @Column(name = "updated_at")
    private Instant updatedAt;
}
