package com.eve.buy.bot.backend.domain.buybot.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * Freigabe einer ganzen Item-Kategorie.
 *
 * <p>Der Buybot arbeitet im Whitelist-Modus: ohne Eintrag hier oder als Einzelitem wird
 * nichts angekauft.
 */
@Entity
@Table(name = "buyback_category_rules")
@Getter
@Setter
public class BuybackCategoryRule {

    @Id
    @Column(name = "category_id")
    private Long categoryId;

    @Column(name = "modifier")
    private Double modifier;

    /**
     * true = für diese Kategorie wird der Reprocessing-Wert angesetzt statt des Marktpreises.
     * Eine Einzelitem-Regel kann das überschreiben.
     */
    @Column(name = "use_reprocessed_value")
    private Boolean useReprocessedValue;
}