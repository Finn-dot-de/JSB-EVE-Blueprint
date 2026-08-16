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