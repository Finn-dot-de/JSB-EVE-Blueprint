package com.eve.own.auth.backend.domain.fleet.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "fleet_doctrines")
@Getter
@Setter
public class FleetDoctrine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String doctrineName;

    private String shipType;
    private Long shipTypeId;
    private String name;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String eftString;

    private String createdBy;
    private Instant createdAt;
}