package com.eve.own.auth.backend.domain.fleet.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

@Entity
@Table(name = "fleet_events")
@Getter
@Setter
public class FleetEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long fcCharacterId;
    private String fcCharacterName;
    private String fleetName;
    private String doctrine;
    private Instant startTime;
    private Instant endTime;
    private Instant linkExpiryTime;

    private String trackingType;

    @Column(unique = true)
    private String trackingCode;
}