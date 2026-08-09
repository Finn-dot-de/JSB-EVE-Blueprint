package com.eve.own.auth.backend.domain.buybot.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

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