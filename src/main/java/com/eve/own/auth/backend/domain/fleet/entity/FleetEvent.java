package com.eve.own.auth.backend.domain.fleet.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

/**
 * Eine erfasste Flotte.
 *
 * <p>Der Index auf {@code start_time} ist die einzige Bedingung, nach der
 * diese Tabelle je gefiltert wird - vom 10-Sekunden-Poll des FLEETS-Reiters
 * ebenso wie von der FAT-Statistik ueber ein Quartal. Ohne ihn ist beides ein
 * vollstaendiger Durchlauf.</p>
 */
@Entity
@Table(name = "fleet_events",
        indexes = @Index(name = "idx_fleet_event_start", columnList = "start_time"))
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