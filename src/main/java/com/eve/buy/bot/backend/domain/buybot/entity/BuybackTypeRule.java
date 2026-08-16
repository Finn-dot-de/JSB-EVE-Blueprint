package com.eve.buy.bot.backend.domain.buybot.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * Regel fuer ein einzelnes Item.
 *
 * <p>Sie ueberlagert die Kategorie: ein gesperrtes Item bleibt gesperrt, auch wenn seine
 * Kategorie erlaubt ist, und ein eigener Modifikator schlaegt den der Kategorie.
 */
@Entity
@Table(name = "buyback_type_rules")
@Getter
@Setter
public class BuybackTypeRule {

    @Id
    @Column(name = "type_id")
    private Long typeId;

    @Column(name = "modifier")
    private Double modifier;

    @Column(name = "is_blacklisted", nullable = false)
    private Boolean isBlacklisted = false;

    /**
     * true = statt des Marktpreises wird der Reprocessing-Wert der Ausbeute angesetzt.
     * NULL = keine Angabe, dann greift die Kategorie-Einstellung.
     */
    @Column(name = "use_reprocessed_value")
    private Boolean useReprocessedValue;
}