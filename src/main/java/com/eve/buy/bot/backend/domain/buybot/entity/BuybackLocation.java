package com.eve.buy.bot.backend.domain.buybot.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * Ein Abgabeort mit seinen Gebuehren.
 *
 * <p>Die Transportgebuehr wird je Kubikmeter berechnet, die Sicherheitsgebuehr als Anteil
 * am Warenwert. Die Station-ID braucht die Vertragspruefung, weil ESI den Ort eines
 * Vertrags nur als ID liefert.
 */
@Entity
@Table(name = "buyback_locations")
@Getter
@Setter
public class BuybackLocation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(name = "transport_fee", nullable = false)
    private Double transportFee = 0.0;

    @Column(name = "security_fee", nullable = false)
    private Double securityFee = 0.0;

    @Column(name = "station_id")
    private Long stationId;
}