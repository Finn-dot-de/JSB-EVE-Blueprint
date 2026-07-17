package com.eve.own.auth.backend.domain.fleet.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

@Entity
@Table(name = "fleet_attendance")
@Getter
@Setter
public class FleetAttendance {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long fleetEventId;

    private Long characterId;
    private String characterName;

    private Long shipTypeId;
    private String shipName;

    private Instant joinTime;
}